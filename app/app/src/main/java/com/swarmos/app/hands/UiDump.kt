package com.swarmos.app.hands

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.ErrorHandler
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/** Reads the XML of `uiautomator dump` into a [RawScreen]. The root driver uses it; the core tests use it on real dumps. */
object UiDump {
    private val BOUNDS = Regex("""\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]""")
    private const val MAX_DEPTH = 120

    /** Character references to control characters (some apps put them in labels) make XML 1.0 unreadable; drop them. */
    private val BAD_CHAR_REF = Regex("&#(?:0*(?:[0-8]|11|12|1[4-9]|2\\d|3[01])|[xX]0*(?:[0-8]|[bBcCeEfF]|1[0-9a-fA-F]));")

    fun parse(xml: String, widthPx: Int, heightPx: Int, pkg: String = "", activity: String = ""): RawScreen {
        val unreadable = RawScreen(pkg, activity, widthPx, heightPx, null, true)
        // A dump never has a DTD. Refuse one outright, whatever the parser would have done with it.
        if (xml.contains("<!DOCTYPE", ignoreCase = true) || xml.contains("<!ENTITY", ignoreCase = true)) return unreadable
        return try {
            val doc = factory().newDocumentBuilder().apply {
                setEntityResolver { _, _ -> InputSource(StringReader("")) }
                setErrorHandler(Strict)
            }.parse(InputSource(StringReader(BAD_CHAR_REF.replace(xml, ""))))
            val top = children(doc.documentElement).mapNotNull { toNode(it, 0) }
            val root = when {
                top.isEmpty() -> null
                top.size == 1 -> top[0]
                else -> synthetic(top, widthPx, heightPx)
            }
            if (root == null) return unreadable
            val usedPkg = pkg.ifEmpty { firstPackage(doc.documentElement) }
            RawScreen(usedPkg, activity, widthPx, heightPx, root, false)
        } catch (e: Exception) {
            unreadable
        }
    }

    private fun factory(): DocumentBuilderFactory {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = false
        // Not every XML implementation knows every switch (Android's does not), each one is best effort on top of the DOCTYPE refusal above.
        fun feature(name: String, on: Boolean) {
            try { f.setFeature(name, on) } catch (_: Exception) { }
        }
        feature("http://apache.org/xml/features/disallow-doctype-decl", true)
        feature("http://xml.org/sax/features/external-general-entities", false)
        feature("http://xml.org/sax/features/external-parameter-entities", false)
        feature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        try { f.isXIncludeAware = false } catch (_: Exception) { }
        try { f.isExpandEntityReferences = false } catch (_: Exception) { }
        return f
    }

    private object Strict : ErrorHandler {
        override fun warning(e: SAXParseException) {}
        override fun error(e: SAXParseException) { throw e }
        override fun fatalError(e: SAXParseException) { throw e }
    }

    private fun children(e: Element): List<Element> {
        val out = ArrayList<Element>()
        var c: Node? = e.firstChild
        while (c != null) {
            if (c.nodeType == Node.ELEMENT_NODE && c.nodeName == "node") out += c as Element
            c = c.nextSibling
        }
        return out
    }

    private fun firstPackage(e: Element): String {
        for (c in children(e)) {
            val p = c.getAttribute("package")
            if (p.isNotEmpty()) return p
            val d = firstPackage(c)
            if (d.isNotEmpty()) return d
        }
        return ""
    }

    private fun bool(e: Element, name: String, default: Boolean = false): Boolean =
        if (e.hasAttribute(name)) e.getAttribute(name) == "true" else default

    private fun toNode(e: Element, depth: Int): RawNode? {
        if (depth > MAX_DEPTH) return null
        val bounds = e.getAttribute("bounds")
        val m = BOUNDS.matchEntire(bounds.trim())
        val (l, t, r, b) = if (m != null) m.groupValues.drop(1).map { it.toInt() } else listOf(0, 0, 0, 0)
        val cls = e.getAttribute("class")
        val editable = cls.contains("EditText") || cls.contains("AutoCompleteTextView")
        return RawNode(
            cls = cls,
            text = e.getAttribute("text"),
            desc = e.getAttribute("content-desc"),
            hint = e.getAttribute("hint"),
            resId = e.getAttribute("resource-id"),
            clickable = bool(e, "clickable"),
            editable = editable,
            scrollable = bool(e, "scrollable"),
            checkable = bool(e, "checkable"),
            checked = bool(e, "checked"),
            enabled = bool(e, "enabled", true),
            password = bool(e, "password"),
            visible = bool(e, "visible-to-user", true),
            left = l, top = t, right = r, bottom = b,
            children = children(e).mapNotNull { toNode(it, depth + 1) },
            handle = bounds,
        )
    }

    private fun synthetic(top: List<RawNode>, w: Int, h: Int) = RawNode(
        cls = "hierarchy", text = "", desc = "", hint = "", resId = "",
        clickable = false, editable = false, scrollable = false, checkable = false, checked = false,
        enabled = true, password = false, visible = true,
        left = 0, top = 0, right = maxOf(w, 1), bottom = maxOf(h, 1),
        children = top, handle = "",
    )
}
