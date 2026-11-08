# Agent Engineering Notes (`perception/`)

Non-obvious build, API, and testing lessons discovered while developing `perception/`:

1. **Standalone Gradle Project Isolation (`-p perception`)**:
   - The sandbox does not have an Android SDK installed, so running `./gradlew` against the root project fails on the Android Gradle Plugin.
   - `perception/settings.gradle.kts` defines `rootProject.name = "perception"` as an independent Gradle build. Always run `./gradlew -p perception <task>` (e.g. `./gradlew -p perception test`).
   - Do **not** modify root `settings.gradle.kts`, `build.gradle.kts`, or `gradle/libs.versions.toml`.

2. **KMP Portability Boundary**:
   - Packages `sim/model`, `sim/geo`, `sim/eval`, `sim/builders`, and `sim/sources` must use **pure Kotlin** (`kotlin.math.*`, `kotlinx.serialization.*`, `kotlinx.coroutines.*`) and avoid `java.*` / `javax.*` imports so they can be moved directly into `shared/src/commonMain` later.
   - JVM-specific code (`HttpGateway`, Ktor CIO engine, `BufferedImage` / `ImageIO` panorama reprojection, file I/O, CLI) must stay under `sim/jvm`.

3. **Meta `sam-3.1` Response Grammar (`POST https://api.meta.ai/v1/responses`)**:
   - Send `Authorization: Bearer $META_API_KEY` header.
   - Each call accepts **one** short noun phrase text prompt (e.g., `"door"`, `"shop sign"`, `"steps"`).
   - Detections are returned in `output[0].content[0].text` using the token format:
     `<0f>0<|box;x1=145;y1=260;x2=158;y2=368;w=640;h=640;c=0.7037|><|mask;x=0;y=0;c=0.7037;data=109,14,!...base85...|>,1<|box;...|>`
   - Note that the mask dimensions `H_mask,W_mask` in `data=109,14,!...` correspond to the **bounding box dimensions** (`y2 - y1 + 1` by `x2 - x1 + 1`), not the full image dimensions (`w, h`).
   - When 0 objects are detected, `output_text` is empty (`""`).

4. **Gemini Flash Latency Optimization (`gemini-3.8-flash`)**:
   - Send `x-goog-api-key: $GEMINI_API_KEY` header to `https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent`.
   - Pass `"generationConfig": {"thinkingConfig": {"thinkingBudget": 0}}` (or minimal thinking) on crop verification (`Verifier`) and sign OCR (`TextReader`) calls to avoid spending ~90+ thinking tokens and ~5s latency on simple `YES`/`NO`/`INSUFFICIENT` checks.

5. **Overpass API Query Construction**:
   - Never include broad `relation["name"](around:...)` with `out body geom;` in urban areas — it pulls entire transit and boundary relations across the region and triggers gateway timeouts.
   - Restrict queries to `way["building"]`, `relation["building"]`, `node["entrance"]`, `node["name"]`, and `way["name"]["highway"!~".*"]["railway"!~".*"]`.

6. **Fixture Scrubbing & Privacy**:
   - Always strip `Authorization` and `x-goog-api-key` headers and replace Mapillary signed CDN URLs (`thumb_*_url` containing `scontent` / access tokens) with `"https://stub.mapillary.example/thumb.jpg"` before writing fixtures to `perception/src/test/resources/fixtures/`.
   - Never commit downloaded `.jpg`/`.png` Mapillary images.

7. **JDK 21 & Sandbox Egress Proxy Setup for Gradle/Java**:
   - Java's `HttpURLConnection` does not read `$HTTPS_PROXY` directly; configure `/root/.gradle/gradle.properties` with `systemProp.http.proxyHost=10.100.1.1`, `systemProp.http.proxyPort=8081`, `systemProp.https.proxyHost=10.100.1.1`, and `systemProp.https.proxyPort=8081`.
   - Import the sandbox egress proxy root CA certificate into `/etc/ssl/certs/java/cacerts` (`keytool -importcert -alias sandbox-proxy-ca ...`) so Gradle and Ktor CIO trust TLS connections through the proxy.
