package com.lsl.kotlin_agent_app.agent.tools.rss

import java.io.StringReader
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

internal data class RssItem(
    val title: String? = null,
    val link: String? = null,
    val guid: String? = null,
    val author: String? = null,
    val publishedAt: String? = null,
    val summary: String? = null,
)

internal class RssParseException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

internal object RssParser {
    fun parse(xmlText: String): List<RssItem> {
        val xml = xmlText.trim()
        if (xml.isEmpty()) return emptyList()
        val factory =
            try {
                XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
            } catch (t: Throwable) {
                throw RssParseException("xml parser unavailable", t)
            }
        val parser = factory.newPullParser()
        try {
            parser.setInput(StringReader(xml))
            parser.nextTag()
            val root = parser.name?.lowercase().orEmpty()
            return when (root) {
                "rss" -> parseRss(parser)
                "feed" -> parseAtom(parser)
                else -> {
                    // Some feeds use <rdf:RDF> for RSS 1.0; treat as unsupported for now.
                    throw RssParseException("unsupported feed root: $root")
                }
            }
        } catch (t: RssParseException) {
            throw t
        } catch (t: Throwable) {
            throw RssParseException("failed to parse feed", t)
        }
    }

    private fun parseRss(parser: XmlPullParser): List<RssItem> {
        // Expect currently at <rss>
        val out = mutableListOf<RssItem>()
        var inItem = false
        var title: String? = null
        var link: String? = null
        var guid: String? = null
        var author: String? = null
        var pubDate: String? = null
        var desc: String? = null

        while (true) {
            when (parser.next()) {
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.START_TAG -> {
                    val name = parser.name.lowercase()
                    if (name == "item") {
                        inItem = true
                        title = null
                        link = null
                        guid = null
                        author = null
                        pubDate = null
                        desc = null
                        continue
                    }
                    if (!inItem) continue
                    when (name) {
                        "title" -> title = readText(parser)
                        "link" -> link = readText(parser)
                        "guid" -> guid = readText(parser)
                        "author" -> author = readText(parser)
                        "creator" -> {
                            // Common: <dc:creator>
                            author = readText(parser)
                        }
                        "pubdate" -> pubDate = readText(parser)
                        "description" -> desc = readText(parser)
                        else -> {
                            // Skip unknown subtrees but keep parser state correct.
                            skipSubtree(parser)
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val name = parser.name.lowercase()
                    if (name == "item" && inItem) {
                        inItem = false
                        out.add(
                            RssItem(
                                title = title?.trim()?.takeIf { it.isNotBlank() },
                                link = link?.trim()?.takeIf { it.isNotBlank() },
                                guid = guid?.trim()?.takeIf { it.isNotBlank() },
                                author = author?.trim()?.takeIf { it.isNotBlank() },
                                publishedAt = normalizePublishedAt(pubDate),
                                summary = desc?.trim()?.takeIf { it.isNotBlank() },
                            ),
                        )
                    }
                }
            }
        }
        return out
    }

    private fun parseAtom(parser: XmlPullParser): List<RssItem> {
        // Expect currently at <feed>
        val out = mutableListOf<RssItem>()
        while (true) {
            val ev = parser.next()
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev != XmlPullParser.START_TAG) continue
            if (parser.name.lowercase() != "entry") continue
            out.add(parseAtomEntry(parser))
        }
        return out
    }

    private fun parseAtomEntry(parser: XmlPullParser): RssItem {
        var title: String? = null
        var link: String? = null
        var id: String? = null
        var author: String? = null
        var published: String? = null
        var updated: String? = null
        var summary: String? = null

        while (true) {
            when (val ev = parser.next()) {
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.START_TAG -> {
                    val name = parser.name.lowercase()
                    when (name) {
                        "title" -> title = readText(parser)
                        "id" -> id = readText(parser)
                        "link" -> {
                            val href = parser.getAttributeValue(null, "href")
                            val rel = parser.getAttributeValue(null, "rel")
                            if (!href.isNullOrBlank()) {
                                if (link.isNullOrBlank() || rel.equals("alternate", ignoreCase = true)) {
                                    link = href
                                }
                            }
                            skipSubtree(parser)
                        }
                        "summary" -> summary = readText(parser)
                        "published" -> published = readText(parser)
                        "updated" -> updated = readText(parser)
                        "author" -> {
                            author = parseAtomAuthor(parser)
                        }
                        else -> skipSubtree(parser)
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (ev == XmlPullParser.END_TAG && parser.name.lowercase() == "entry") break
                }
            }
        }

        val at = published?.trim()?.takeIf { it.isNotBlank() } ?: updated?.trim()?.takeIf { it.isNotBlank() }
        return RssItem(
            title = title?.trim()?.takeIf { it.isNotBlank() },
            link = link?.trim()?.takeIf { it.isNotBlank() },
            guid = id?.trim()?.takeIf { it.isNotBlank() },
            author = author?.trim()?.takeIf { it.isNotBlank() },
            publishedAt = normalizePublishedAt(at),
            summary = summary?.trim()?.takeIf { it.isNotBlank() },
        )
    }

    private fun parseAtomAuthor(parser: XmlPullParser): String? {
        var name: String? = null
        while (true) {
            when (val ev = parser.next()) {
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.START_TAG -> {
                    if (parser.name.lowercase() == "name") {
                        name = readText(parser)
                    } else {
                        skipSubtree(parser)
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (ev == XmlPullParser.END_TAG && parser.name.lowercase() == "author") break
                }
            }
        }
        return name
    }

    private fun readText(parser: XmlPullParser): String {
        val sb = StringBuilder()
        while (true) {
            when (parser.next()) {
                XmlPullParser.TEXT -> sb.append(parser.text)
                XmlPullParser.CDSECT -> sb.append(parser.text)
                XmlPullParser.ENTITY_REF -> sb.append(parser.text)
                XmlPullParser.END_TAG -> break
            }
        }
        return sb.toString()
    }

    private fun skipSubtree(parser: XmlPullParser) {
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }
}

private val rssRfc2822Like: DateTimeFormatter =
    DateTimeFormatterBuilder()
        .parseCaseInsensitive()
        .appendPattern("EEE, dd MMM yyyy HH:mm:ss Z")
        .toFormatter(Locale.US)

private fun normalizePublishedAt(raw: String?): String? {
    val s = raw?.trim().orEmpty()
    if (s.isEmpty()) return null

    // Atom (RFC3339)
    runCatching { return OffsetDateTime.parse(s).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) }

    // RSS (pubDate commonly RFC 2822 / RFC 1123 variants)
    runCatching { return ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME).toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) }
    runCatching { return ZonedDateTime.parse(s, rssRfc2822Like).toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) }

    return s
}

