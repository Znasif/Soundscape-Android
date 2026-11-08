package org.scottishtecharmy.soundscape.sim.jvm

import org.scottishtecharmy.soundscape.sim.geo.PerspectiveCamera
import org.scottishtecharmy.soundscape.sim.sources.MapillarySource
import org.scottishtecharmy.soundscape.sim.sources.PanoramaImageSource
import org.scottishtecharmy.soundscape.sim.sources.PerspectiveCropImage
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * JVM implementation of [PanoramaImageSource].
 *
 * - Fetches a fresh signed panorama URL from [MapillarySource.getImageById].
 * - Downloads and caches the equirectangular [BufferedImage] in memory only (never committed to git).
 * - Reprojects equirectangular panoramas into rectilinear [PerspectiveCamera] crops using bilinear interpolation
 *   and downscales to [maxDimension] (default `1024`, with standard views at `640x640`).
 * - In offline fixture replay mode (when `signedUrl` is scrubbed to `https://stub.mapillary.example/thumb.jpg`
 *   or `gateway.replayOnly == true`), returns a deterministic stub crop in memory so downstream `SegmentationClient`,
 *   `Verifier`, and `TextReader` calls replay from recorded JSON fixtures via `contextKey` with zero stored images.
 */
class JvmPanoramaImageSource(
    private val mapillarySource: MapillarySource? = null,
    private val gateway: HttpGateway? = null,
    private val maxDimension: Int = 1024
) : PanoramaImageSource {

    private val panoCache = mutableMapOf<String, BufferedImage>()

    /**
     * Registers an in-memory equirectangular image for deterministic offline unit tests.
     */
    fun putPanoramaForTesting(stationId: String, image: BufferedImage) {
        panoCache[stationId] = image
    }

    override suspend fun extractPerspectiveCrop(
        stationId: String,
        compassAngleDegrees: Double,
        camera: PerspectiveCamera
    ): PerspectiveCropImage? {
        val effectiveCamera = downscaleCameraIfNeeded(camera, maxDimension)
        val cachedPano = panoCache[stationId]
        if (cachedPano != null) {
            val projected = reprojectEquirectangularToPerspective(
                pano = cachedPano,
                compassAngleDegrees = compassAngleDegrees,
                camera = effectiveCamera
            )
            return PerspectiveCropImage(
                stationId = stationId,
                camera = effectiveCamera,
                jpegBytes = encodeJpeg(projected)
            )
        }

        val source = mapillarySource ?: return null
        val gw = gateway ?: return null

        var meta = source.getImageById(stationId) ?: return null
        var signedUrl = meta.thumb2048Url ?: meta.thumb1024Url ?: return null

        if (gw.replayOnly) {
            // Offline replay mode: return lightweight stub JPEG so StreetBuilder replays recorded SAM/Gemini JSON fixtures
            return PerspectiveCropImage(
                stationId = stationId,
                camera = effectiveCamera,
                jpegBytes = STUB_REPLAY_JPEG
            )
        }

        if (signedUrl.startsWith("https://stub.mapillary.example")) {
            val liveSource = HttpMapillaryClient(gateway = HttpGateway(replayOnly = false))
            meta = liveSource.getImageById(stationId) ?: return null
            signedUrl = meta.thumb2048Url ?: meta.thumb1024Url ?: return null
            if (signedUrl.startsWith("https://stub.mapillary.example")) {
                return PerspectiveCropImage(
                    stationId = stationId,
                    camera = effectiveCamera,
                    jpegBytes = STUB_REPLAY_JPEG
                )
            }
        }

        val bytes = gw.getBytes(signedUrl)
        val decoded = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
        panoCache[stationId] = decoded
        val projected = reprojectEquirectangularToPerspective(
            pano = decoded,
            compassAngleDegrees = compassAngleDegrees,
            camera = effectiveCamera
        )
        return PerspectiveCropImage(
            stationId = stationId,
            camera = effectiveCamera,
            jpegBytes = encodeJpeg(projected)
        )
    }

    override fun cropSubImage(
        crop: PerspectiveCropImage,
        box: List<Int>,
        paddingFraction: Double
    ): ByteArray {
        if (crop.jpegBytes === STUB_REPLAY_JPEG) return STUB_REPLAY_JPEG
        val src = ImageIO.read(ByteArrayInputStream(crop.jpegBytes)) ?: return crop.jpegBytes
        if (box.size < 4) return crop.jpegBytes

        val x1 = box[0].coerceIn(0, src.width - 1)
        val y1 = box[1].coerceIn(0, src.height - 1)
        val x2 = box[2].coerceIn(x1, src.width - 1)
        val y2 = box[3].coerceIn(y1, src.height - 1)

        val boxW = max(1, x2 - x1 + 1)
        val boxH = max(1, y2 - y1 + 1)
        val padX = (boxW * paddingFraction).roundToInt().coerceAtLeast(80)
        val padY = (boxH * paddingFraction).roundToInt().coerceAtLeast(80)

        val cropX1 = (x1 - padX).coerceIn(0, src.width - 1)
        val cropY1 = (y1 - padY).coerceIn(0, src.height - 1)
        val cropX2 = (x2 + padX).coerceIn(cropX1 + 1, src.width)
        val cropY2 = (y2 + padY).coerceIn(cropY1 + 1, src.height)

        val sub = src.getSubimage(cropX1, cropY1, cropX2 - cropX1, cropY2 - cropY1)
        val copy = BufferedImage(sub.width, sub.height, BufferedImage.TYPE_INT_RGB)
        val g = copy.createGraphics()
        g.drawImage(sub, 0, 0, null)
        g.dispose()
        return encodeJpeg(copy)
    }

    companion object {
        private val STUB_REPLAY_JPEG: ByteArray by lazy {
            encodeJpeg(BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB))
        }

        fun downscaleCameraIfNeeded(camera: PerspectiveCamera, maxDimension: Int): PerspectiveCamera {
            val maxSide = max(camera.width, camera.height)
            if (maxSide <= maxDimension) return camera
            val scale = maxDimension.toDouble() / maxSide.toDouble()
            return camera.copy(
                width = (camera.width * scale).roundToInt().coerceAtLeast(16),
                height = (camera.height * scale).roundToInt().coerceAtLeast(16)
            )
        }

        /**
         * Reprojects an equirectangular 360x180 [BufferedImage] with heading [compassAngleDegrees]
         * into a rectilinear [PerspectiveCamera] view using bilinear pixel interpolation.
         */
        fun reprojectEquirectangularToPerspective(
            pano: BufferedImage,
            compassAngleDegrees: Double,
            camera: PerspectiveCamera
        ): BufferedImage {
            val out = BufferedImage(camera.width, camera.height, BufferedImage.TYPE_INT_RGB)
            val panoW = pano.width
            val panoH = pano.height

            for (v in 0 until camera.height) {
                for (u in 0 until camera.width) {
                    val srcCoord = camera.pixelToEquirectangular(
                        u = u + 0.5,
                        v = v + 0.5,
                        panoWidth = panoW,
                        panoHeight = panoH,
                        panoCompassAngleDegrees = compassAngleDegrees
                    )
                    val rgb = sampleBilinearRgb(pano, srcCoord.u, srcCoord.v)
                    out.setRGB(u, v, rgb)
                }
            }
            return out
        }

        private fun sampleBilinearRgb(img: BufferedImage, u: Double, v: Double): Int {
            val w = img.width
            val h = img.height
            val x = ((u % w) + w) % w
            val y = v.coerceIn(0.0, (h - 1).toDouble())

            val x0 = floor(x).toInt().coerceIn(0, w - 1)
            val x1 = (x0 + 1) % w
            val y0 = floor(y).toInt().coerceIn(0, h - 1)
            val y1 = min(h - 1, y0 + 1)

            val fx = x - floor(x)
            val fy = y - y0

            val c00 = img.getRGB(x0, y0)
            val c10 = img.getRGB(x1, y0)
            val c01 = img.getRGB(x0, y1)
            val c11 = img.getRGB(x1, y1)

            val r = bilerpChannel((c00 shr 16) and 0xFF, (c10 shr 16) and 0xFF, (c01 shr 16) and 0xFF, (c11 shr 16) and 0xFF, fx, fy)
            val g = bilerpChannel((c00 shr 8) and 0xFF, (c10 shr 8) and 0xFF, (c01 shr 8) and 0xFF, (c11 shr 8) and 0xFF, fx, fy)
            val b = bilerpChannel(c00 and 0xFF, c10 and 0xFF, c01 and 0xFF, c11 and 0xFF, fx, fy)

            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        private fun bilerpChannel(c00: Int, c10: Int, c01: Int, c11: Int, fx: Double, fy: Double): Int {
            val top = c00 * (1.0 - fx) + c10 * fx
            val bot = c01 * (1.0 - fx) + c11 * fx
            return (top * (1.0 - fy) + bot * fy).roundToInt().coerceIn(0, 255)
        }

        fun encodeJpeg(image: BufferedImage): ByteArray {
            val baos = ByteArrayOutputStream()
            ImageIO.write(image, "jpg", baos)
            return baos.toByteArray()
        }
    }
}
