package org.scottishtecharmy.soundscape.sim.builders

import org.scottishtecharmy.soundscape.sim.geo.EnuPoint
import org.scottishtecharmy.soundscape.sim.geo.LatLon
import org.scottishtecharmy.soundscape.sim.geo.LocalEnu
import org.scottishtecharmy.soundscape.sim.model.SimCategory
import org.scottishtecharmy.soundscape.sim.model.SimEnvironment
import org.scottishtecharmy.soundscape.sim.model.SimObject
import kotlin.math.round

/**
 * Filter mode for `export-gpx --include osm|perceived|all`.
 */
enum class GpxIncludeMode {
    OSM,
    PERCEIVED,
    ALL;

    companion object {
        fun fromArg(arg: String): GpxIncludeMode = when (arg.trim().lowercase()) {
            "osm" -> OSM
            "perceived" -> PERCEIVED
            "all" -> ALL
            else -> throw IllegalArgumentException("Unknown --include mode '$arg' (expected osm|perceived|all)")
        }
    }
}

/**
 * Single waypoint entry for GPX export.
 */
data class GpxExportWaypoint(
    val latitude: Double,
    val longitude: Double,
    val name: String,
    val description: String,
    val eastMeters: Double = 0.0,
    val northMeters: Double = 0.0
)

/**
 * Exports a [SimEnvironment] or a custom walking route to a GPX 1.1 XML document compatible with
 * Soundscape-Android's GPX importer (`GpxParser.kt` and `GpxUtils.kt` / `IntentParser.parseGpx`).
 *
 * Pure Kotlin (`commonMain`-ready; zero `java.*` imports).
 */
object GpxExporter {

    private val EXPORTABLE_CATEGORIES = setOf(
        SimCategory.DOOR,
        SimCategory.SIGN,
        SimCategory.STEPS,
        SimCategory.STAIRS,
        SimCategory.BUS_STOP,
        SimCategory.CURB_CUT
    )

    /**
     * Exports [env] landmarks matching [includeMode] to GPX XML, ordered continuously along the facade.
     */
    fun exportEnvironmentToGpx(
        env: SimEnvironment,
        includeMode: GpxIncludeMode = GpxIncludeMode.PERCEIVED,
        routeNameOverride: String? = null
    ): String {
        val anchor = env.geoAnchor
        val localEnu = if (anchor != null) LocalEnu(anchor.latitude, anchor.longitude, anchor.yawDegrees) else null
        val attribution = env.attribution ?: SimEnvironment.DEFAULT_ATTRIBUTION

        val rawObjects = env.objects.orEmpty().filter { obj ->
            if (obj.category !in EXPORTABLE_CATEGORIES) return@filter false
            val isPerceived = "SAM_3_1" in obj.sources || obj.sources.any { it.equals("sam-3.1", ignoreCase = true) }
            val isOsm = "OSM" in obj.sources
            when (includeMode) {
                GpxIncludeMode.PERCEIVED -> isPerceived
                GpxIncludeMode.OSM -> isOsm && !isPerceived
                GpxIncludeMode.ALL -> isPerceived || isOsm
            }
        }

        val candidateWaypoints = rawObjects.mapNotNull { obj ->
            val enu = EnuPoint.fromPositionList(obj.position)
            val latLon = if (obj.latitude != null && obj.longitude != null) {
                LatLon(obj.latitude, obj.longitude)
            } else if (localEnu != null) {
                localEnu.enuToLatLon(enu)
            } else {
                return@mapNotNull null
            }

            GpxExportWaypoint(
                latitude = round6(latLon.latitude),
                longitude = round6(latLon.longitude),
                name = buildListenerName(obj),
                description = buildWaypointDescription(obj, attribution),
                eastMeters = enu.east,
                northMeters = enu.north
            )
        }

        val orderedWaypoints = orderWaypointsAlongFacade(candidateWaypoints)
        val cleanAreaName = env.name
            .replace("Street Preview", "")
            .trim()
            .ifEmpty { env.name }

        val modeSuffix = when (includeMode) {
            GpxIncludeMode.PERCEIVED -> "perceived entrances"
            GpxIncludeMode.OSM -> "OSM entrances"
            GpxIncludeMode.ALL -> "all entrances and signs"
        }
        val routeName = routeNameOverride ?: "Prototype: $cleanAreaName $modeSuffix"
        val routeDesc = "Soundscape Street Preview prototype GPX ($modeSuffix, ${orderedWaypoints.size} waypoints). $attribution"

        return renderGpxXml(
            routeName = routeName,
            routeDescription = routeDesc,
            waypoints = orderedWaypoints
        )
    }

    /**
     * Exports a custom ordered list of [waypoints] (such as a Muni bus stop -> sidewalk -> crossing -> entrance route)
     * to a Soundscape-compatible GPX 1.1 string.
     */
    fun exportCustomRouteToGpx(
        routeName: String,
        routeDescription: String,
        waypoints: List<GpxExportWaypoint>
    ): String = renderGpxXml(
        routeName = routeName,
        routeDescription = routeDescription,
        waypoints = waypoints
    )

    /**
     * Orders waypoints along the building facade / street corridor using a greedy nearest-neighbour walk
     * starting from the southern/western extremity (`min(northMeters + eastMeters)`), avoiding back-and-forth zig-zags.
     */
    fun orderWaypointsAlongFacade(waypoints: List<GpxExportWaypoint>): List<GpxExportWaypoint> {
        if (waypoints.size <= 2) return waypoints
        val remaining = waypoints.toMutableList()
        val startIdx = remaining.indices.minByOrNull { idx ->
            remaining[idx].northMeters + remaining[idx].eastMeters
        } ?: 0
        val ordered = mutableListOf(remaining.removeAt(startIdx))

        while (remaining.isNotEmpty()) {
            val current = ordered.last()
            val nextIdx = remaining.indices.minByOrNull { idx ->
                val cand = remaining[idx]
                val de = cand.eastMeters - current.eastMeters
                val dn = cand.northMeters - current.northMeters
                de * de + dn * dn
            } ?: 0
            ordered.add(remaining.removeAt(nextIdx))
        }
        return ordered
    }

    private fun buildListenerName(obj: SimObject): String {
        val isPerceived = "SAM_3_1" in obj.sources || obj.sources.any { it.equals("sam-3.1", ignoreCase = true) }
        return when (obj.category) {
            SimCategory.DOOR -> {
                val venueName = obj.linkedVenue?.name?.takeIf { it.isNotBlank() }
                    ?: obj.text?.takeIf { it.isNotBlank() }
                if (isPerceived) {
                    if (venueName != null) "Door, perceived: $venueName" else "Door, perceived"
                } else {
                    if (venueName != null) "Entrance, OSM: $venueName" else "Entrance, OSM (${obj.subCategory ?: "main"})"
                }
            }
            SimCategory.SIGN -> {
                val signText = obj.text?.takeIf { it.isNotBlank() }
                    ?: obj.name.removePrefix("Sign:").trim().takeIf { it.isNotBlank() }
                if (signText != null) "Shop sign: $signText" else "Shop sign, perceived"
            }
            SimCategory.STEPS, SimCategory.STAIRS -> {
                if (isPerceived) "Steps, perceived" else "Steps, OSM"
            }
            SimCategory.BUS_STOP -> obj.name
            SimCategory.CURB_CUT -> "Curb cut, perceived"
            else -> obj.name
        }
    }

    private fun buildWaypointDescription(obj: SimObject, attribution: String): String {
        val ev = obj.evidence.firstOrNull()
        val stationId = ev?.stationId ?: "unknown"
        val conf = round2(ev?.detectorConfidence ?: obj.confidence)
        val verifierStr = ev?.verifierResult?.name ?: "N/A"
        val methodStr = ev?.localisationMethod ?: "UNKNOWN"
        val sourcesStr = obj.sources.joinToString("+")
        return "Source: $sourcesStr (Mapillary imageId=$stationId, sam-3.1 confidence=$conf, verifier=$verifierStr, method=$methodStr). Attribution: $attribution"
    }

    private fun renderGpxXml(
        routeName: String,
        routeDescription: String,
        waypoints: List<GpxExportWaypoint>
    ): String {
        val sb = StringBuilder()
        sb.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.appendLine("""<gpx version="1.1" creator="Soundscape-Android Perception Pipeline" xmlns="http://www.topografix.com/GPX/1/1">""")
        sb.appendLine("  <metadata>")
        sb.appendLine("    <name>${escapeXml(routeName)}</name>")
        sb.appendLine("    <desc>${escapeXml(routeDescription)}</desc>")
        sb.appendLine("  </metadata>")
        for (wpt in waypoints) {
            sb.appendLine("""  <wpt lat="${wpt.latitude}" lon="${wpt.longitude}">""")
            sb.appendLine("    <name>${escapeXml(wpt.name)}</name>")
            sb.appendLine("    <desc>${escapeXml(wpt.description)}</desc>")
            sb.appendLine("  </wpt>")
        }
        sb.appendLine("  <rte>")
        sb.appendLine("    <name>${escapeXml(routeName)}</name>")
        for (wpt in waypoints) {
            sb.appendLine("""    <rtept lat="${wpt.latitude}" lon="${wpt.longitude}">""")
            sb.appendLine("      <name>${escapeXml(wpt.name)}</name>")
            sb.appendLine("      <desc>${escapeXml(wpt.description)}</desc>")
            sb.appendLine("    </rtept>")
        }
        sb.appendLine("  </rte>")
        sb.appendLine("</gpx>")
        return sb.toString()
    }

    fun escapeXml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun round2(v: Double): Double = round(v * 100.0) / 100.0
    private fun round6(v: Double): Double = round(v * 1_000_000.0) / 1_000_000.0
}

/**
 * Mirrored GPX data model and parser for unit-testing GPX files exported by [GpxExporter] without modifying
 * or depending on the Android `shared/` Gradle module.
 *
 * Exact lines mirrored from Soundscape-Android:
 * - `shared/src/commonMain/kotlin/org/scottishtecharmy/soundscape/geoengine/utils/gpx/GpxParser.kt` lines 14–229:
 *   - `tokenize(xml)` (lines 14–49), `stripNamespace` (lines 51–54), `splitTagContent` (lines 56–80), `decodeEntities` (lines 82–88)
 *   - `TokenReader` (`skipToEndTag`, `readTextContent`) (lines 90–130)
 *   - `buildGpx` (lines 132–164), `readMetadata` (lines 166–182), `readWaypoint` (lines 184–211), `readRoute` (lines 213–229)
 * - `shared/src/commonMain/kotlin/org/scottishtecharmy/soundscape/utils/GpxUtils.kt` lines 11–61:
 *   - `parseGpxFile(input)` route-points first (`parsedGpx.routes.forEach`, lines 22–34), falling back to
 *     `parsedGpx.waypoints` when `waypoints.isEmpty()` (lines 35–47), and returning `metadata.name` and `metadata.desc` (lines 48–54).
 */
object SimGpxMirrorParser {

    data class MirroredWaypoint(
        val latitude: Double,
        val longitude: Double,
        val name: String,
        val desc: String
    )

    data class MirroredRoute(
        val name: String,
        val description: String,
        val markers: List<MirroredWaypoint>,
        val rawWaypoints: List<MirroredWaypoint>,
        val rawRoutePoints: List<MirroredWaypoint>
    )

    private sealed class XmlToken {
        data class StartTag(val localName: String, val attributes: Map<String, String>) : XmlToken()
        data class EndTag(val localName: String) : XmlToken()
        data class Text(val content: String) : XmlToken()
    }

    /**
     * Mirrors `parseGpxFile` (`GpxUtils.kt` lines 11–61) and `parseGpx` (`GpxParser.kt` lines 3–229).
     */
    fun parseGpxFile(input: String): MirroredRoute {
        val tokens = tokenize(input)
        val reader = TokenReader(tokens)
        var metaName = ""
        var metaDesc = ""
        val wpts = mutableListOf<MirroredWaypoint>()
        val rtepts = mutableListOf<MirroredWaypoint>()

        while (reader.hasNext()) {
            when (val t = reader.next()) {
                is XmlToken.StartTag -> when (t.localName) {
                    "metadata" -> {
                        val (n, d) = readMetadata(reader)
                        metaName = n
                        metaDesc = d
                    }
                    "wpt" -> wpts.add(readWaypoint(reader, "wpt", t.attributes))
                    "rte" -> rtepts.addAll(readRoute(reader))
                    else -> {}
                }
                else -> {}
            }
        }

        // Mirrors GpxUtils.kt lines 22-47: use routePoints if present, otherwise fallback to waypoints
        val markers = if (rtepts.isNotEmpty()) rtepts else wpts
        return MirroredRoute(
            name = metaName,
            description = metaDesc,
            markers = markers,
            rawWaypoints = wpts,
            rawRoutePoints = rtepts
        )
    }

    private fun tokenize(xml: String): List<XmlToken> {
        val tokens = mutableListOf<XmlToken>()
        var i = 0
        while (i < xml.length) {
            if (xml[i] == '<') {
                val end = xml.indexOf('>', i)
                if (end == -1) break
                val tag = xml.substring(i + 1, end).trim()
                i = end + 1

                if (tag.startsWith("?") || tag.startsWith("!")) continue

                if (tag.startsWith("/")) {
                    tokens.add(XmlToken.EndTag(stripNamespace(tag.substring(1).trim())))
                } else {
                    val selfClosing = tag.endsWith("/")
                    val content = if (selfClosing) tag.dropLast(1).trim() else tag
                    val parts = splitTagContent(content)
                    val localName = stripNamespace(parts.first)
                    tokens.add(XmlToken.StartTag(localName, parts.second))
                    if (selfClosing) {
                        tokens.add(XmlToken.EndTag(localName))
                    }
                }
            } else {
                val nextTag = xml.indexOf('<', i)
                val textEnd = if (nextTag == -1) xml.length else nextTag
                val text = xml.substring(i, textEnd)
                if (text.isNotBlank()) {
                    tokens.add(XmlToken.Text(decodeEntities(text.trim())))
                }
                i = textEnd
            }
        }
        return tokens
    }

    private fun stripNamespace(name: String): String {
        val colon = name.indexOf(':')
        return if (colon >= 0) name.substring(colon + 1) else name
    }

    private fun splitTagContent(tag: String): Pair<String, Map<String, String>> {
        val attrs = mutableMapOf<String, String>()
        val nameEnd = tag.indexOfFirst { it.isWhitespace() }
        if (nameEnd == -1) return Pair(tag, attrs)

        val name = tag.substring(0, nameEnd)
        var rest = tag.substring(nameEnd).trim()

        while (rest.isNotEmpty()) {
            val eq = rest.indexOf('=')
            if (eq == -1) break
            val key = rest.substring(0, eq).trim()
            rest = rest.substring(eq + 1).trim()
            if (rest.isEmpty()) break

            val quote = rest[0]
            if (quote != '"' && quote != '\'') break
            val closeQuote = rest.indexOf(quote, 1)
            if (closeQuote == -1) break

            attrs[stripNamespace(key)] = decodeEntities(rest.substring(1, closeQuote))
            rest = rest.substring(closeQuote + 1).trim()
        }
        return Pair(name, attrs)
    }

    private fun decodeEntities(s: String): String = s
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")

    private class TokenReader(private val tokens: List<XmlToken>) {
        var pos = 0
        fun hasNext() = pos < tokens.size
        fun peek(): XmlToken? = if (hasNext()) tokens[pos] else null
        fun next(): XmlToken = tokens[pos++]

        fun skipToEndTag(name: String) {
            var depth = 1
            while (hasNext() && depth > 0) {
                when (val t = next()) {
                    is XmlToken.StartTag -> if (t.localName == name) depth++
                    is XmlToken.EndTag -> if (t.localName == name) depth--
                    is XmlToken.Text -> {}
                }
            }
        }

        fun readTextContent(tagName: String): String {
            val sb = StringBuilder()
            while (true) {
                val t = peek() ?: break
                when (t) {
                    is XmlToken.EndTag -> {
                        if (t.localName == tagName) {
                            next()
                            return sb.toString()
                        }
                        break
                    }
                    is XmlToken.Text -> {
                        sb.append(t.content)
                        next()
                    }
                    is XmlToken.StartTag -> {
                        next()
                        skipToEndTag(t.localName)
                    }
                }
            }
            return sb.toString()
        }
    }

    private fun readMetadata(reader: TokenReader): Pair<String, String> {
        var name = ""
        var desc = ""
        while (reader.hasNext()) {
            when (val t = reader.next()) {
                is XmlToken.StartTag -> when (t.localName) {
                    "name" -> name = reader.readTextContent("name")
                    "desc" -> desc = reader.readTextContent("desc")
                    else -> reader.skipToEndTag(t.localName)
                }
                is XmlToken.EndTag -> if (t.localName == "metadata") break
                is XmlToken.Text -> {}
            }
        }
        return name to desc
    }

    private fun readWaypoint(
        reader: TokenReader,
        endTag: String,
        attrs: Map<String, String>
    ): MirroredWaypoint {
        val lat = attrs["lat"]?.toDoubleOrNull() ?: 0.0
        val lon = attrs["lon"]?.toDoubleOrNull() ?: 0.0
        var name = ""
        var desc = ""
        while (reader.hasNext()) {
            when (val t = reader.next()) {
                is XmlToken.StartTag -> when (t.localName) {
                    "name" -> name = reader.readTextContent("name")
                    "desc" -> desc = reader.readTextContent("desc")
                    else -> reader.skipToEndTag(t.localName)
                }
                is XmlToken.EndTag -> if (t.localName == endTag) break
                is XmlToken.Text -> {}
            }
        }
        return MirroredWaypoint(lat, lon, name, desc)
    }

    private fun readRoute(reader: TokenReader): List<MirroredWaypoint> {
        val points = mutableListOf<MirroredWaypoint>()
        while (reader.hasNext()) {
            when (val t = reader.next()) {
                is XmlToken.StartTag -> when (t.localName) {
                    "name" -> reader.readTextContent("name")
                    "rtept" -> points.add(readWaypoint(reader, "rtept", t.attributes))
                    else -> reader.skipToEndTag(t.localName)
                }
                is XmlToken.EndTag -> if (t.localName == "rte") break
                is XmlToken.Text -> {}
            }
        }
        return points
    }
}
