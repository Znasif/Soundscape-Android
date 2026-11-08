package org.scottishtecharmy.soundscape.sim.jvm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.scottishtecharmy.soundscape.sim.model.SimCategory
import org.scottishtecharmy.soundscape.sim.model.VerifierVerdict
import org.scottishtecharmy.soundscape.sim.sources.Budget
import org.scottishtecharmy.soundscape.sim.sources.Embedder
import org.scottishtecharmy.soundscape.sim.sources.OcrResult
import org.scottishtecharmy.soundscape.sim.sources.TextReader
import org.scottishtecharmy.soundscape.sim.sources.Verifier
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.math.sqrt

/**
 * JVM implementation of [Verifier], [TextReader], and [Embedder] backed by Google Gemini REST APIs:
 * - Crop verification & OCR: `gemini-3.8-flash` via `POST .../v1beta/models/gemini-3.8-flash:generateContent`
 *   with `thinkingBudget = 0` and header `x-goog-api-key: $GEMINI_API_KEY`.
 * - Multimodal embeddings: `gemini-embedding-2-preview` via `:embedContent` (with deterministic local
 *   feature fallback when offline).
 */
class GeminiFlashClient(
    private val gateway: HttpGateway,
    private val budget: Budget = Budget(),
    private val modelId: String = "gemini-3.8-flash",
    private val embeddingModelId: String = "gemini-embedding-2-preview",
    private val apiKeyProvider: () -> String = { System.getenv("GEMINI_API_KEY") ?: "" }
) : Verifier, TextReader, Embedder {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun verifyCrop(
        cropJpegBytes: ByteArray,
        category: SimCategory,
        promptLabel: String
    ): VerifierVerdict {
        budget.consumeGemini(1)

        val base64Jpeg = Base64.getEncoder().encodeToString(cropJpegBytes)
        val prompt = "You are verifying a candidate detection of '$promptLabel' (${category.name}) for a blind navigation map. " +
            "Is there a real $promptLabel visible in this image crop (not a window reflection, tree shadow, or false alarm)? " +
            "Answer with a single word: YES, NO, or INSUFFICIENT."

        val payload = buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("parts", buildJsonArray {
                        add(buildJsonObject {
                            put("inline_data", buildJsonObject {
                                put("mime_type", "image/jpeg")
                                put("data", base64Jpeg)
                            })
                        })
                        add(buildJsonObject {
                            put("text", prompt)
                        })
                    })
                })
            })
            put("generationConfig", buildJsonObject {
                put("temperature", 0.0)
                put("maxOutputTokens", 512)
            })
        }.toString()

        val key = apiKeyProvider()
        val headers = if (key.isNotBlank()) mapOf("x-goog-api-key" to key) else emptyMap()
        val fKey = "gemini_verify_${category.name.lowercase()}_${sha256Short(cropJpegBytes)}"

        val resp = gateway.post(
            url = "https://generativelanguage.googleapis.com/v1beta/models/$modelId:generateContent",
            headers = headers,
            body = payload,
            fixtureKey = fKey
        )

        return parseVerifierResponseJson(resp.body)
    }

    override suspend fun readText(cropJpegBytes: ByteArray): OcrResult? {
        budget.consumeGemini(1)

        val base64Jpeg = Base64.getEncoder().encodeToString(cropJpegBytes)
        val prompt = "Read the visible shop, building, room, or directional sign text in this image crop. " +
            "Return ONLY a JSON object {\"text\": \"<visible text>\", \"confidence\": 0.0..1.0}. " +
            "If no legible text is present, return {\"text\": \"\", \"confidence\": 0.0}."

        val payload = buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("parts", buildJsonArray {
                        add(buildJsonObject {
                            put("inline_data", buildJsonObject {
                                put("mime_type", "image/jpeg")
                                put("data", base64Jpeg)
                            })
                        })
                        add(buildJsonObject {
                            put("text", prompt)
                        })
                    })
                })
            })
            put("generationConfig", buildJsonObject {
                put("temperature", 0.0)
                put("maxOutputTokens", 512)
                put("responseMimeType", "application/json")
            })
        }.toString()

        val key = apiKeyProvider()
        val headers = if (key.isNotBlank()) mapOf("x-goog-api-key" to key) else emptyMap()
        val fKey = "gemini_ocr_${sha256Short(cropJpegBytes)}"

        val resp = gateway.post(
            url = "https://generativelanguage.googleapis.com/v1beta/models/$modelId:generateContent",
            headers = headers,
            body = payload,
            fixtureKey = fKey
        )

        return parseOcrResponseJson(resp.body)
    }

    override suspend fun embedImage(jpegBytes: ByteArray): List<Double> {
        if (!budget.canConsumeGemini(1)) {
            return computeLocalFallbackEmbedding(jpegBytes)
        }
        return try {
            budget.consumeGemini(1)
            val base64Jpeg = Base64.getEncoder().encodeToString(jpegBytes)
            val payload = buildJsonObject {
                put("model", "models/$embeddingModelId")
                put("content", buildJsonObject {
                    put("parts", buildJsonArray {
                        add(buildJsonObject {
                            put("inline_data", buildJsonObject {
                                put("mime_type", "image/jpeg")
                                put("data", base64Jpeg)
                            })
                        })
                    })
                })
            }.toString()

            val key = apiKeyProvider()
            val headers = if (key.isNotBlank()) mapOf("x-goog-api-key" to key) else emptyMap()
            val fKey = "gemini_embed_${sha256Short(jpegBytes)}"

            val resp = gateway.post(
                url = "https://generativelanguage.googleapis.com/v1beta/models/$embeddingModelId:embedContent",
                headers = headers,
                body = payload,
                fixtureKey = fKey
            )
            val root = json.parseToJsonElement(resp.body).jsonObject
            val values = root["embedding"]?.jsonObject?.get("values")?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.doubleOrNull }
                ?: emptyList()
            if (values.isNotEmpty()) values else computeLocalFallbackEmbedding(jpegBytes)
        } catch (_: Exception) {
            computeLocalFallbackEmbedding(jpegBytes)
        }
    }

    fun parseVerifierResponseJson(responseJson: String): VerifierVerdict {
        val text = extractCandidateText(json.parseToJsonElement(responseJson).jsonObject).trim().uppercase()
        return when {
            text.startsWith("YES") || text.contains("YES") -> VerifierVerdict.YES
            text.startsWith("NO") || text.contains("NO") -> VerifierVerdict.NO
            else -> VerifierVerdict.INSUFFICIENT
        }
    }

    fun parseOcrResponseJson(responseJson: String): OcrResult? {
        val rawText = extractCandidateText(json.parseToJsonElement(responseJson).jsonObject).trim()
        if (rawText.isBlank()) return null

        val cleaned = rawText
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        return try {
            val obj = json.parseToJsonElement(cleaned).jsonObject
            val txt = obj["text"]?.jsonPrimitive?.content?.trim() ?: ""
            val conf = obj["confidence"]?.jsonPrimitive?.doubleOrNull ?: if (txt.isNotEmpty()) 0.85 else 0.0
            if (txt.isBlank()) null else OcrResult(text = txt, confidence = conf.coerceIn(0.0, 1.0))
        } catch (_: Exception) {
            val singleLine = cleaned.lines().firstOrNull()?.trim()?.trim('"') ?: ""
            if (singleLine.isNotBlank() && !singleLine.equals("none", ignoreCase = true)) {
                OcrResult(text = singleLine, confidence = 0.75)
            } else {
                null
            }
        }
    }

    companion object {
        fun extractCandidateText(root: JsonObject): String {
            val candidates = root["candidates"]?.jsonArray ?: return ""
            val first = candidates.firstOrNull()?.jsonObject ?: return ""
            val parts = first["content"]?.jsonObject?.get("parts")?.jsonArray ?: return ""
            val sb = StringBuilder()
            for (p in parts) {
                val obj = p.jsonObject
                if (obj["thought"]?.jsonPrimitive?.content == "true") continue
                val t = obj["text"]?.jsonPrimitive?.content ?: continue
                sb.append(t)
            }
            return sb.toString()
        }

        /**
         * Deterministic L2-normalized local spatial-color & luminance descriptor (`32` dimensions)
         * used as an offline fallback when Gemini embedding API is unavailable.
         */
        fun computeLocalFallbackEmbedding(jpegBytes: ByteArray): List<Double> {
            val img = try {
                ImageIO.read(ByteArrayInputStream(jpegBytes))
            } catch (_: Exception) {
                null
            }
            val vec = DoubleArray(32)
            if (img != null && img.width > 0 && img.height > 0) {
                // 4x2 spatial grid x 4 channels (R, G, B, edge-energy) = 32 dims
                val gridX = 4
                val gridY = 2
                for (gy in 0 until gridY) {
                    for (gx in 0 until gridX) {
                        val xStart = (gx * img.width) / gridX
                        val xEnd = ((gx + 1) * img.width) / gridX
                        val yStart = (gy * img.height) / gridY
                        val yEnd = ((gy + 1) * img.height) / gridY
                        var rSum = 0.0
                        var gSum = 0.0
                        var bSum = 0.0
                        var count = 0
                        for (y in yStart until yEnd) {
                            for (x in xStart until xEnd) {
                                val rgb = img.getRGB(x, y)
                                rSum += ((rgb shr 16) and 0xFF) / 255.0
                                gSum += ((rgb shr 8) and 0xFF) / 255.0
                                bSum += (rgb and 0xFF) / 255.0
                                count++
                            }
                        }
                        val baseIdx = (gy * gridX + gx) * 4
                        if (count > 0) {
                            vec[baseIdx] = rSum / count
                            vec[baseIdx + 1] = gSum / count
                            vec[baseIdx + 2] = bSum / count
                            vec[baseIdx + 3] = (0.299 * vec[baseIdx] + 0.587 * vec[baseIdx + 1] + 0.114 * vec[baseIdx + 2])
                        }
                    }
                }
            } else {
                val digest = MessageDigest.getInstance("SHA-256").digest(jpegBytes)
                for (i in 0 until 32) {
                    vec[i] = ((digest[i].toInt() and 0xFF) / 255.0)
                }
            }
            val norm = sqrt(vec.sumOf { it * it }).coerceAtLeast(1e-9)
            return vec.map { kotlin.math.round((it / norm) * 10000.0) / 10000.0 }
        }

        private fun sha256Short(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return digest.take(6).joinToString("") { "%02x".format(it) }
        }
    }
}
