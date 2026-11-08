package org.scottishtecharmy.soundscape.sim.jvm

import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.scottishtecharmy.soundscape.sim.sources.BudgetLedgerData
import org.scottishtecharmy.soundscape.sim.sources.BudgetLedgerStore
import java.io.File
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * JVM file-backed implementation of [BudgetLedgerStore] for `perception/docs/agent/metrics/budget-ledger.json`.
 */
class JvmFileBudgetLedgerStore(
    val file: File
) : BudgetLedgerStore {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    override fun load(): BudgetLedgerData? {
        if (!file.exists()) return null
        return json.decodeFromString(BudgetLedgerData.serializer(), file.readText())
    }

    override fun save(data: BudgetLedgerData) {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(BudgetLedgerData.serializer(), data))
    }
}

/**
 * Recorded HTTP fixture stored on disk for offline deterministic test replay.
 * Auth headers and signed CDN image URLs are always scrubbed before persistence.
 */
@Serializable
data class RecordedHttpFixture(
    val fixtureKey: String,
    val method: String,
    val url: String,
    val statusCode: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: String
)

/**
 * HTTP gateway with built-in retry/backoff on 429 & 5xx, auth-header scrubbing, signed-URL scrubbing,
 * and deterministic fixture recording & replay.
 */
class HttpGateway(
    val replayDir: File? = null,
    val recordDir: File? = null,
    val replayOnly: Boolean = false,
    val maxRetries: Int = 3,
    val initialBackoffMs: Long = 1000L
) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private val httpClient: HttpClient by lazy {
        val builder = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)

        val proxyUrl = System.getenv("HTTPS_PROXY")
            ?: System.getenv("https_proxy")
            ?: System.getenv("HTTP_PROXY")
            ?: System.getenv("http_proxy")
        if (!proxyUrl.isNullOrBlank()) {
            val uri = URI.create(proxyUrl)
            if (uri.host != null && uri.port > 0) {
                builder.proxy(ProxySelector.of(InetSocketAddress(uri.host, uri.port)))
            }
        }
        builder.build()
    }

    /**
     * Returns true if [fixtureKey] is present in [replayDir] and will be served offline without network I/O.
     */
    fun hasReplayFixture(fixtureKey: String?): Boolean {
        if (fixtureKey == null || replayDir == null) return false
        return File(replayDir, "$fixtureKey.json").exists()
    }

    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        fixtureKey: String? = null
    ): RecordedHttpFixture = execute(
        method = "GET",
        url = url,
        headers = headers,
        requestBody = null,
        fixtureKey = fixtureKey
    )

    suspend fun post(
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String,
        contentType: String = "application/x-www-form-urlencoded",
        fixtureKey: String? = null
    ): RecordedHttpFixture = execute(
        method = "POST",
        url = url,
        headers = headers + ("Content-Type" to contentType),
        requestBody = body,
        fixtureKey = fixtureKey
    )

    fun getBytes(url: String, headers: Map<String, String> = emptyMap()): ByteArray {
        check(!replayOnly) { "Network byte fetch forbidden in replayOnly mode: $url" }
        val reqBuilder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(45))
            .GET()
        for ((k, v) in headers) {
            reqBuilder.header(k, v)
        }
        val resp = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofByteArray())
        if (resp.statusCode() !in 200..299) {
            throw IllegalStateException("HTTP ${resp.statusCode()} fetching bytes from $url")
        }
        return resp.body()
    }

    private suspend fun execute(
        method: String,
        url: String,
        headers: Map<String, String>,
        requestBody: String?,
        fixtureKey: String?
    ): RecordedHttpFixture {
        // 1. Check replayDir first if fixtureKey is provided
        if (fixtureKey != null && replayDir != null) {
            val file = File(replayDir, "$fixtureKey.json")
            if (file.exists()) {
                return json.decodeFromString(RecordedHttpFixture.serializer(), file.readText())
            }
        }

        if (replayOnly) {
            throw IllegalStateException("Fixture '$fixtureKey' not found in ${replayDir?.absolutePath} and replayOnly=true (url=$url)")
        }

        // 2. Perform live HTTP call with retry + exponential backoff on 429 and 5xx
        var attempt = 0
        var backoff = initialBackoffMs
        var lastStatus = 0
        var lastBody = ""

        while (attempt <= maxRetries) {
            attempt++
            val reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))

            for ((k, v) in headers) {
                reqBuilder.header(k, v)
            }

            if (method == "POST") {
                reqBuilder.POST(HttpRequest.BodyPublishers.ofString(requestBody ?: ""))
            } else {
                reqBuilder.GET()
            }

            val resp = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString())
            lastStatus = resp.statusCode()
            lastBody = resp.body()

            if (lastStatus in 200..299) {
                val fixture = RecordedHttpFixture(
                    fixtureKey = fixtureKey ?: "live",
                    method = method,
                    url = scrubUrl(url),
                    statusCode = lastStatus,
                    headers = scrubHeaders(headers),
                    body = lastBody
                )
                if (fixtureKey != null && recordDir != null) {
                    recordDir.mkdirs()
                    val scrubbedFixture = fixture.copy(
                        body = scrubSignedUrlsInBody(lastBody)
                    )
                    File(recordDir, "$fixtureKey.json").writeText(
                        json.encodeToString(RecordedHttpFixture.serializer(), scrubbedFixture)
                    )
                }
                return fixture
            }

            if (lastStatus == 429 || lastStatus in 500..599) {
                if (attempt <= maxRetries) {
                    delay(backoff)
                    backoff *= 2
                    continue
                }
            }
            break
        }

        throw IllegalStateException("HTTP $lastStatus for $method $url after $attempt attempts: ${lastBody.take(300)}")
    }

    companion object {
        private val SENSITIVE_HEADER_KEYS = setOf("authorization", "x-goog-api-key", "proxy-authorization", "cookie")
        private val THUMB_URL_REGEX = Regex(""""(thumb_(?:256|512|1024|2048|original)_url)"\s*:\s*"[^"]+"""")
        private val SCONTENT_URL_REGEX = Regex("""https://scontent[^\s"']+""")

        /**
         * Removes sensitive authentication headers before recording fixtures.
         */
        fun scrubHeaders(headers: Map<String, String>): Map<String, String> =
            headers.filterKeys { it.lowercase() !in SENSITIVE_HEADER_KEYS }

        /**
         * Replaces Mapillary short-lived signed CDN thumbnail URLs with a safe deterministic stub URL.
         */
        fun scrubSignedUrlsInBody(body: String): String {
            val step1 = THUMB_URL_REGEX.replace(body) { matchResult ->
                val fieldName = matchResult.groupValues[1]
                """"$fieldName":"https://stub.mapillary.example/thumb.jpg""""
            }
            return SCONTENT_URL_REGEX.replace(step1, "https://stub.mapillary.example/thumb.jpg")
        }

        fun scrubUrl(url: String): String =
            url.replace(Regex("([?&](?:access_token|key|token)=)[^&]+"), "$1REDACTED")
    }
}
