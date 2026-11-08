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
import org.scottishtecharmy.soundscape.sim.sources.CategoryPrecheck
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
 * JVM implementation of [Verifier], [TextReader], [Embedder], and [CategoryPrecheck] backed by Google Gemini REST APIs:
 * - Crop verification, OCR & room category precheck: `gemini-3.8-flash` via
 *   `POST .../v1beta/models/gemini-3.8-flash:generateContent` with header `x-goog-api-key: $GEMINI_API_KEY`.
 * - Multimodal embeddings: `gemini-embedding-2-preview` via `:embedContent` (with deterministic local
 *   feature fallback when offline).
 */
class GeminiFlashClient(
    private val gateway: HttpGateway,
    private val budget: Budget = Budget(),
    private val modelId: String = "gemini-3.8-flash",
    private val embeddingModelId: String = "gemini-embedding-2-preview",
    private val apiKeyProvider: () -> String = { System.getenv("GEMINI_API_KEY") ?: "" }
) : Verifier, TextReader, Embedder, CategoryPrecheck {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun verifyCrop(
        cropJpegBytes: ByteArray,
        category: SimCategory,
        promptLabel: String,
        contextKey: String?
    ): VerifierVerdict {
        val fKey = if (!contextKey.isNullOrBlank()) {
            "gemini_verify_${category.name.lowercase()}_$contextKey"
        } else {
            "gemini_verify_${category.name.lowercase()}_${sha256Short(cropJpegBytes)}"
        }

        val isReplay = gateway.hasReplayFixture(fKey)
        budget.consumeGemini(1, isReplay = isReplay)

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
                put("maxOutputTokens", 128)
                put("thinkingConfig", buildJsonObject {
                    put("thinkingBudget", 0)
                })
            })
        }.toString()

        val key = apiKeyProvider()
        val headers = if (key.isNotBlank()) mapOf("x-goog-api-key" to key) else emptyMap()

        val resp = gateway.post(
            url = "https://generativelanguage.googleapis.com/v1beta/models/$modelId:generateContent",
            headers = headers,
            body = payload,
            contentType = "application/json",
            fixtureKey = fKey
        )

        return parseVerifierResponseJson(resp.body)
    }

    override suspend fun readText(
        cropJpegBytes: ByteArray,
        contextKey: String?
    ): OcrResult? {
        val fKey = if (!contextKey.isNullOrBlank()) {
            "gemini_ocr_$contextKey"
        } else {
            "gemini_ocr_${sha256Short(cropJpegBytes)}"
        }

        val isReplay = gateway.hasReplayFixture(fKey)
        budget.consumeGemini(1, isReplay = isReplay)

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
                put("maxOutputTokens", 128)
                put("responseMimeType", "application/json")
                put("thinkingConfig", buildJsonObject {
                    put("thinkingBudget", 0)
                })
            })
        }.toString()

        val key = apiKeyProvider()
        val headers = if (key.isNotBlank()) mapOf("x-goog-api-key" to key) else emptyMap()

        val resp = gateway.post(
            url = "https://generativelanguage.googleapis.com/v1beta/models/$modelId:generateContent",
            headers = headers,
            body = payload,
            contentType = "application/json",
            fixtureKey = fKey
        )

        return parseOcrResponseJson(resp.body)
    }

    override suspend fun embedImage(jpegBytes: ByteArray, contextKey: String?): List<Double> {
        val fKey = if (!contextKey.isNullOrBlank()) {
            "gemini_embed_$contextKey"
        } else {
            "gemini_embed_${sha256Short(jpegBytes)}"
        }
        val isReplay = gateway.hasReplayFixture(fKey)
        if (!isReplay && !budget.canConsumeGemini(1)) {
            return computeLocalFallbackEmbedding(jpegBytes)
        }
        return try {
            budget.consumeGemini(1, isReplay = isReplay)
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

            val resp = gateway.post(
                url = "https://generativelanguage.googleapis.com/v1beta/models/$embeddingModelId:embedContent",
                headers = headers,
                body = payload,
                contentType = "application/json",
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

    override suspend fun detectPresentCategories(keyframeJpegs: List<ByteArray>, contextKey: String?): Set<SimCategory> {
        if (keyframeJpegs.isEmpty()) return emptySet()
        val selected = keyframeJpegs.take(6)
        val fKey = if (!contextKey.isNullOrBlank()) {
            "gemini_precheck_$contextKey"
        } else {
            "gemini_precheck_${sha256Short(selected.first())}"
        }
        val isReplay = gateway.hasReplayFixture(fKey)
        budget.consumeGemini(1, isReplay = isReplay)

        val prompt = "Inspect these indoor room keyframes and list which of the following categories are actually visible: " +
            "door, desk, reception desk, counter, chair, table, sofa, sign, stairs, lift, window. " +
            "Return ONLY a comma-separated list of present categories from that exact list."

        val payload = buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("parts", buildJsonArray {
                        for (jpeg in selected) {
                            add(buildJsonObject {
                                put("inline_data", buildJsonObject {
                                    put("mime_type", "image/jpeg")
                                    put("data", Base64.getEncoder().encodeToString(jpeg))
                                })
                            })
                        }
                        add(buildJsonObject {
                            put("text", prompt)
                        })
                    })
                })
            })
            put("generationConfig", buildJsonObject {
                put("temperature", 0.0)
                put("maxOutputTokens", 128)
                put("thinkingConfig", buildJsonObject {
                    put("thinkingBudget", 0)
                })
            })
        }.toString()

        val key = apiKeyProvider()
        val headers = if (key.isNotBlank()) mapOf("x-goog-api-key" to key) else emptyMap()

        val resp = gateway.post(
            url = "https://generativelanguage.googleapis.com/v1beta/models/$modelId:generateContent",
            headers = headers,
            body = payload,
            contentType = "application/json",
            fixtureKey = fKey
        )

        val text = extractCandidateText(json.parseToJsonElement(resp.body).jsonObject).lowercase()
        val present = mutableSetOf<SimCategory>()
        if ("reception desk" in text) present.add(SimCategory.RECEPTION_DESK)
        if ("door" in text) present.add(SimCategory.DOOR)
        if ("desk" in text) present.add(SimCategory.DESK)
        if ("counter" in text) present.add(SimCategory.COUNTER)
        if ("chair" in text) present.add(SimCategory.CHAIR)
        if ("table" in text) present.add(SimCategory.TABLE)
        if ("sofa" in text) present.add(SimCategory.SOFA)
        if ("sign" in text) present.add(SimCategory.SIGN)
        if ("stairs" in text || "steps" in text) present.add(SimCategory.STAIRS)
        if ("lift" in text || "elevator" in text) present.add(SimCategory.LIFT)
        if ("window" in text) present.add(SimCategory.WINDOW)
        return present
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
                val stepX = maxOf(1, img.width / 64)
                val stepY = maxOf(1, img.height / 48)
                var globalSumR = 0.0
                var globalSumG = 0.0
                var globalSumB = 0.0
                var globalCount = 0
                for (y in 0 until img.height step stepY) {
                    for (x in 0 until img.width step stepX) {
                        val rgb = img.getRGB(x, y)
                        globalSumR += ((rgb shr 16) and 0xFF) / 255.0
                        globalSumG += ((rgb shr 8) and 0xFF) / 255.0
                        globalSumB += (rgb and 0xFF) / 255.0
                        globalCount++
                    }
                }
                val meanR = if (globalCount > 0) globalSumR / globalCount else 0.5
                val meanG = if (globalCount > 0) globalSumG / globalCount else 0.5
                val meanB = if (globalCount > 0) globalSumB / globalCount else 0.5
                val meanY = 0.299 * meanR + 0.587 * meanG + 0.114 * meanB
                val meanAll = (meanR + meanG + meanB) / 3.0

                var varR = 0.0
                var varG = 0.0
                var varB = 0.0
                var varRg = 0.0
                var varYb = 0.0
                var varY = 0.0
                val meanRg = meanR - meanG
                val meanYb = 0.5 * (meanR + meanG) - meanB
                for (y in 0 until img.height step stepY) {
                    for (x in 0 until img.width step stepX) {
                        val rgb = img.getRGB(x, y)
                        val r = ((rgb shr 16) and 0xFF) / 255.0
                        val g = ((rgb shr 8) and 0xFF) / 255.0
                        val b = (rgb and 0xFF) / 255.0
                        val lum = 0.299 * r + 0.587 * g + 0.114 * b
                        val rg = r - g
                        val yb = 0.5 * (r + g) - b
                        varR += (r - meanR) * (r - meanR)
                        varG += (g - meanG) * (g - meanG)
                        varB += (b - meanB) * (b - meanB)
                        varRg += (rg - meanRg) * (rg - meanRg)
                        varYb += (yb - meanYb) * (yb - meanYb)
                        varY += (lum - meanY) * (lum - meanY)
                    }
                }
                val denom = globalCount.coerceAtLeast(1).toDouble()
                vec[0] = meanR - meanY
                vec[1] = meanG - meanY
                vec[2] = meanB - meanY
                vec[3] = meanRg
                vec[4] = meanYb
                vec[5] = (meanY - 0.5) * 0.5
                vec[6] = sqrt(varR / denom)
                vec[7] = sqrt(varG / denom)
                vec[8] = sqrt(varB / denom)
                vec[9] = sqrt(varRg / denom)
                vec[10] = sqrt(varYb / denom)
                vec[11] = sqrt(varY / denom)

                // 4 vertical bands (ceiling, upper wall, lower wall, floor) x 5 chromatic features = 20 features
                val bands = 4
                for (by in 0 until bands) {
                    val yStart = (by * img.height) / bands
                    val yEnd = ((by + 1) * img.height) / bands
                    var bR = 0.0
                    var bG = 0.0
                    var bB = 0.0
                    var cnt = 0
                    for (y in yStart until yEnd step stepY) {
                        for (x in 0 until img.width step stepX) {
                            val rgb = img.getRGB(x, y)
                            bR += ((rgb shr 16) and 0xFF) / 255.0
                            bG += ((rgb shr 8) and 0xFF) / 255.0
                            bB += (rgb and 0xFF) / 255.0
                            cnt++
                        }
                    }
                    val base = 12 + by * 5
                    if (cnt > 0) {
                        val mr = bR / cnt
                        val mg = bG / cnt
                        val mb = bB / cnt
                        vec[base] = mr - meanAll
                        vec[base + 1] = mg - meanAll
                        vec[base + 2] = mb - meanAll
                        vec[base + 3] = mr - mg
                        vec[base + 4] = 0.5 * (mr + mg) - mb
                    }
                }
            } else {
                val digest = MessageDigest.getInstance("SHA-256").digest(jpegBytes)
                for (i in 0 until 32) {
                    vec[i] = ((digest[i].toInt() and 0xFF) / 255.0) - 0.5
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
