package org.scottishtecharmy.soundscape.sim.jvm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.scottishtecharmy.soundscape.sim.model.SimCategory
import org.scottishtecharmy.soundscape.sim.sources.Budget
import org.scottishtecharmy.soundscape.sim.sources.SegmentationClient
import org.scottishtecharmy.soundscape.sim.sources.SegmentedInstance
import java.security.MessageDigest
import java.util.Base64

/**
 * JVM implementation of [SegmentationClient] calling Meta Model API (`POST https://api.meta.ai/v1/responses`)
 * with model `sam-3.1` and header `Authorization: Bearer $META_API_KEY`.
 */
class MetaSam31Client(
    private val gateway: HttpGateway,
    private val budget: Budget = Budget(),
    private val apiKeyProvider: () -> String = { System.getenv("META_API_KEY") ?: "" },
    private val fixtureKeyOverride: String? = null
) : SegmentationClient {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun segment(
        imageJpegBytes: ByteArray,
        prompt: String,
        category: SimCategory,
        contextKey: String?
    ): List<SegmentedInstance> {
        val promptSlug = prompt.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
        val fKey = fixtureKeyOverride
            ?: if (!contextKey.isNullOrBlank()) {
                "sam31_${contextKey}_$promptSlug"
            } else {
                "sam31_${promptSlug}_${sha256Short(imageJpegBytes)}"
            }

        val isReplay = gateway.hasReplayFixture(fKey)
        budget.consumeSam(1, isReplay = isReplay)

        val base64Jpeg = Base64.getEncoder().encodeToString(imageJpegBytes)
        val payload = buildJsonObject {
            put("model", "sam-3.1")
            put("input", buildJsonArray {
                add(buildJsonObject {
                    put("type", "message")
                    put("role", "user")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "input_text")
                            put("text", prompt)
                        })
                        add(buildJsonObject {
                            put("type", "input_image")
                            put("image_url", "data:image/jpeg;base64,$base64Jpeg")
                        })
                    })
                })
            })
            put("stream", false)
            put("metadata", buildJsonObject {
                put("include_confidence", "true")
                put("mask_encoding", "one_bit")
            })
        }.toString()

        val key = apiKeyProvider()
        val headers = if (key.isNotBlank()) mapOf("Authorization" to "Bearer $key") else emptyMap()

        val resp = gateway.post(
            url = "https://api.meta.ai/v1/responses",
            headers = headers,
            body = payload,
            contentType = "application/json",
            fixtureKey = fKey
        )

        return parseSam31ResponseJson(resp.body, prompt, category)
    }

    fun parseSam31ResponseJson(
        responseJson: String,
        prompt: String,
        category: SimCategory,
        defaultWidth: Int = 640,
        defaultHeight: Int = 640
    ): List<SegmentedInstance> {
        val root = json.parseToJsonElement(responseJson).jsonObject
        val outputText = extractOutputText(root)
        return parseSam31OutputText(outputText, prompt, category, defaultWidth, defaultHeight)
    }

    companion object {
        private val INSTANCE_REGEX = Regex(
            """<\|box;x1=(\d+);y1=(\d+);x2=(\d+);y2=(\d+);w=(\d+);h=(\d+)(?:;c=([0-9.]+))?\|>(?:<\|mask;x=\d+;y=\d+(?:;c=[0-9.]+)?;data=(\d+),(\d+),([^|]+)\|>)?"""
        )

        private const val BASE85_ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz!#$%&()*+-;<=>?@^_`{|}~"

        fun extractOutputText(root: JsonObject): String {
            root["output_text"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.let { return it }
            val outputArr = root["output"]?.jsonArray ?: return ""
            val sb = StringBuilder()
            for (item in outputArr) {
                val contentArr = item.jsonObject["content"]?.jsonArray ?: continue
                for (c in contentArr) {
                    val txt = c.jsonObject["text"]?.jsonPrimitive?.content ?: continue
                    sb.append(txt)
                }
            }
            return sb.toString()
        }

        /**
         * Parses SAM 3.1's structured `<|box;...|><|mask;...|>` text output into [SegmentedInstance] records.
         */
        fun parseSam31OutputText(
            outputText: String,
            prompt: String,
            category: SimCategory,
            defaultWidth: Int = 640,
            defaultHeight: Int = 640
        ): List<SegmentedInstance> {
            if (outputText.isBlank()) return emptyList()
            val results = mutableListOf<SegmentedInstance>()

            for (match in INSTANCE_REGEX.findAll(outputText)) {
                val x1 = match.groupValues[1].toIntOrNull() ?: continue
                val y1 = match.groupValues[2].toIntOrNull() ?: continue
                val x2 = match.groupValues[3].toIntOrNull() ?: continue
                val y2 = match.groupValues[4].toIntOrNull() ?: continue
                val w = match.groupValues[5].toIntOrNull()?.takeIf { it > 0 } ?: defaultWidth
                val h = match.groupValues[6].toIntOrNull()?.takeIf { it > 0 } ?: defaultHeight
                val conf = match.groupValues[7].toDoubleOrNull() ?: 0.75
                val maskH = match.groupValues[8].toIntOrNull() ?: (y2 - y1 + 1).coerceAtLeast(0)
                val maskW = match.groupValues[9].toIntOrNull() ?: (x2 - x1 + 1).coerceAtLeast(0)
                val maskData = match.groupValues[10].takeIf { it.isNotBlank() }

                val normBox = listOf(
                    round4(x1.toDouble() / w),
                    round4(y1.toDouble() / h),
                    round4(x2.toDouble() / w),
                    round4(y2.toDouble() / h)
                )

                results.add(
                    SegmentedInstance(
                        prompt = prompt,
                        category = category,
                        box = listOf(x1, y1, x2, y2),
                        imageWidth = w,
                        imageHeight = h,
                        confidence = conf,
                        normalizedBox = normBox,
                        maskWidth = maskW,
                        maskHeight = maskH,
                        maskData = maskData
                    )
                )
            }
            return if (category == SimCategory.DOOR && results.size >= 2) {
                mergeDoubleDoorWings(results)
            } else {
                results
            }
        }

        /**
         * Pairs two open wings/leaves of a double door in the same crop when their vertical bounds
         * (`y1`, `y2`) align within `25 px` and their horizontal gap is `<= 140 px`.
         */
        fun mergeDoubleDoorWings(instances: List<SegmentedInstance>): List<SegmentedInstance> {
            val sorted = instances.sortedBy { it.box[0] }
            val used = BooleanArray(sorted.size)
            val merged = mutableListOf<SegmentedInstance>()

            for (i in sorted.indices) {
                if (used[i]) continue
                used[i] = true
                var current = sorted[i]

                for (j in i + 1 until sorted.size) {
                    if (used[j]) continue
                    val other = sorted[j]
                    val y1Diff = kotlin.math.abs(current.box[1] - other.box[1])
                    val y2Diff = kotlin.math.abs(current.box[3] - other.box[3])
                    val xGap = other.box[0] - current.box[2]
                    if (y1Diff <= 25 && y2Diff <= 25 && xGap in -20..140) {
                        used[j] = true
                        val nx1 = minOf(current.box[0], other.box[0])
                        val ny1 = minOf(current.box[1], other.box[1])
                        val nx2 = maxOf(current.box[2], other.box[2])
                        val ny2 = maxOf(current.box[3], other.box[3])
                        val w = current.imageWidth
                        val h = current.imageHeight
                        current = current.copy(
                            box = listOf(nx1, ny1, nx2, ny2),
                            confidence = maxOf(current.confidence, other.confidence),
                            normalizedBox = listOf(
                                round4(nx1.toDouble() / w),
                                round4(ny1.toDouble() / h),
                                round4(nx2.toDouble() / w),
                                round4(ny2.toDouble() / h)
                            ),
                            maskWidth = nx2 - nx1 + 1,
                            maskHeight = ny2 - ny1 + 1
                        )
                    }
                }
                merged.add(current)
            }
            return merged
        }

        /**
         * Decodes a SAM 3.1 `one_bit` base85 mask string (`!<base85>`) of dimensions `height x width`.
         */
        fun decodeOneBitMask(maskData: String, height: Int, width: Int): BooleanArray? {
            if (height <= 0 || width <= 0 || !maskData.startsWith("!")) return null
            val encoded = maskData.substring(1)
            val bytes = decodeRfc1924Base85(encoded) ?: return null
            val totalPixels = height * width
            val mask = BooleanArray(totalPixels)
            for (i in 0 until totalPixels) {
                val byteIdx = i / 8
                val bitIdx = i % 8
                if (byteIdx < bytes.size) {
                    val b = bytes[byteIdx].toInt() and 0xFF
                    mask[i] = ((b shr bitIdx) and 1) != 0
                }
            }
            return mask
        }

        private fun decodeRfc1924Base85(str: String): ByteArray? {
            if (str.isEmpty()) return ByteArray(0)
            val out = ArrayList<Byte>((str.length * 4) / 5 + 4)
            var idx = 0
            while (idx < str.length) {
                val chunkLen = minOf(5, str.length - idx)
                var acc = 0L
                for (k in 0 until 5) {
                    val ch = if (k < chunkLen) str[idx + k] else 'u'
                    if (ch.code !in 33..126) return null
                    val rfcIdx = BASE85_ALPHABET.indexOf(ch)
                    val v = if (rfcIdx >= 0) rfcIdx else (ch.code - 33) % 85
                    acc = acc * 85L + v
                }
                val bytesToEmit = maxOf(1, if (chunkLen == 5) 4 else chunkLen - 1)
                for (bIdx in 0 until bytesToEmit) {
                    val shift = 24 - (bIdx * 8)
                    out.add(((acc shr shift) and 0xFF).toByte())
                }
                idx += chunkLen
            }
            return out.toByteArray()
        }

        private fun round4(v: Double): Double = kotlin.math.round(v * 10000.0) / 10000.0

        private fun sha256Short(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return digest.take(6).joinToString("") { "%02x".format(it) }
        }
    }
}
