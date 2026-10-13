package com.swarmos.app.hands

import java.security.MessageDigest

/**
 * Turns the raw UI tree of a driver into the short numbered list the model sees (and the guard checks).
 * The text of a password field is never read: its value is always empty and its label never comes from its text.
 * Every element also carries [UiElement.guard], the full normalised text the guard judges it by: never cut, never shown to the model.
 */
object ScreenReader {
    private const val LABEL_MAX = 80
    private const val TEXT_LABEL_MAX = 160

    /** Longest edit-field value kept (a longer one is cut), so a check against typed text must cut the same way. */
    const val VALUE_MAX = 160

    private val WHITE = Regex("\\s+")

    private class Cand(
        val node: RawNode,
        val role: Role,
        val label: String,
        val value: String,
        val flags: Set<Flag>,
        /** 0 editable, 1 clickable or toggle or scrollable, 2 text. Lower survives the cap first. */
        val rank: Int,
        val top: Int,
        val left: Int,
        val cx: Int,
        val cy: Int,
        val guard: String,
        val area: Long,
    )

    /** [areas]: element id to its visible area in pixels. [masked]: the digit runs that were hidden as codes. */
    class Compacted(val screen: Screen, val areas: Map<Int, Long>, val masked: Set<String>)

    fun compact(raw: RawScreen, max: Int = 60): Screen = compactFull(raw, max).screen

    fun compactFull(raw: RawScreen, max: Int = 60): Compacted {
        val root = raw.root
        if (raw.unreadable || root == null) {
            return Compacted(Screen(raw.pkg, raw.activity, emptyList(), true, hashOf(raw.pkg, raw.activity, emptyList(), true)), emptyMap(), emptySet())
        }
        val cands = ArrayList<Cand>()
        walk(root, raw.widthPx, raw.heightPx, false, "", cands)
        val kept = if (cands.size <= max) cands else cands.sortedWith(compareBy({ it.rank }, { it.top }, { it.left })).take(max)
        val ordered = kept.sortedWith(compareBy({ it.top }, { it.left }))
        val masked = LinkedHashSet<String>()
        val areas = HashMap<Int, Long>()
        val elements = ordered.mapIndexed { i, c ->
            var label = c.label
            var value = c.value
            var guard = c.guard
            // a code shown in the text of an element that talks about a code (a message, a notice): hidden before anything else sees it
            if (GuardWords.CODE.matches(GuardText.norm("$label $value"))) {
                label = GuardText.maskCodes(label, masked)
                value = GuardText.maskCodes(value, masked)
                guard = GuardText.maskCodes(guard)
            }
            // what a field called "OTP" or "Password" holds is not read, flagged or not
            if (c.role == Role.EDIT && Guard.isSecretLabel(c.guard)) value = ""
            areas[i + 1] = c.area
            UiElement(i + 1, c.role, label, value, c.flags, c.node.handle, c.cx, c.cy, guard)
        }
        return Compacted(Screen(raw.pkg, raw.activity, elements, false, hashOf(raw.pkg, raw.activity, elements, false)), areas, masked)
    }

    // ------------------------------------------------------------ walk

    /** [ancestor]: the full normalised text of the nearest clickable ancestor: a touch on this node reaches that ancestor. */
    private fun walk(n: RawNode, w: Int, h: Int, folded: Boolean, ancestor: String, out: MutableList<Cand>) {
        if (!shown(n, w, h)) return
        val own by lazy { GuardText.norm(fullText(n)) }
        if (n.password) { // never descend, never read the text
            out += cand(n, w, h, Role.EDIT, passwordLabel(n), "", 0, GuardText.join(own, ancestor))
            return
        }
        if (n.editable) {
            val label = editLabel(n)
            val value = if (n.text.isNotBlank() && clean(n.text) != clean(n.hint)) clean(n.text).take(VALUE_MAX) else ""
            out += cand(n, w, h, Role.EDIT, label, value, 0, if (n.clickable || n.checkable) own else GuardText.join(own, ancestor))
            return
        }
        val foldsChildren = n.clickable || n.checkable
        if (n.clickable || n.checkable || n.scrollable) {
            val ownLabel = clean(n.text).ifEmpty { clean(n.desc) }
            val label = when {
                ownLabel.isNotEmpty() -> ownLabel
                n.scrollable && !foldsChildren -> ""
                else -> joinedText(n, w, h)
            }
            val plain = !n.checkable && !n.scrollable
            if (label.isEmpty() && plain && hasInteractiveBelow(n, w, h)) {
                // an empty wrapper around real controls: the controls are listed, not the wrapper
            } else {
                val role = when {
                    n.checkable -> Role.TOGGLE
                    n.clickable -> Role.BUTTON
                    else -> Role.LIST
                }
                val shownLabel = label.ifEmpty { if (role == Role.LIST) "" else resName(n.resId) }
                out += cand(n, w, h, role, shownLabel.take(LABEL_MAX), "", 1, if (foldsChildren) own else GuardText.join(own, ancestor))
            }
            val below = if (foldsChildren) own else ancestor
            for (c in n.children) walk(c, w, h, folded || foldsChildren, below, out)
            return
        }
        if (!folded) {
            val t = clean(n.text).ifEmpty { clean(n.desc) }
            if (t.isNotEmpty()) {
                val role = if (clean(n.text).isEmpty() && n.cls.endsWith("ImageView")) Role.IMAGE else Role.TEXT
                out += cand(n, w, h, role, t.take(TEXT_LABEL_MAX), "", 2, GuardText.join(own, ancestor))
            }
        }
        for (c in n.children) walk(c, w, h, folded, ancestor, out)
    }

    /** Everything written on or in this node: its text (not a password's), description, hint, the words of its id, and the same for every descendant. */
    private fun fullText(n: RawNode): String {
        val sb = StringBuilder()
        fun add(x: RawNode, depth: Int) {
            if (!x.password) sb.append(x.text).append(' ')
            sb.append(x.desc).append(' ').append(x.hint).append(' ').append(GuardText.idWords(x.resId)).append(' ')
            if (depth < MAX_DEPTH) for (c in x.children) add(c, depth + 1)
        }
        add(n, 0)
        return sb.toString()
    }

    private const val MAX_DEPTH = 200

    private fun cand(n: RawNode, w: Int, h: Int, role: Role, label: String, value: String, rank: Int, guard: String): Cand {
        val flags = LinkedHashSet<Flag>()
        if (n.clickable) flags += Flag.CLICKABLE
        if (n.editable || n.password) flags += Flag.EDITABLE
        if (n.scrollable) flags += Flag.SCROLLABLE
        if (n.checkable && n.checked) flags += Flag.CHECKED
        if (!n.enabled) flags += Flag.DISABLED
        if (n.password) flags += Flag.PASSWORD
        val l = if (w > 0) maxOf(n.left, 0) else n.left
        val t = if (h > 0) maxOf(n.top, 0) else n.top
        val r = if (w > 0) minOf(n.right, w) else n.right
        val b = if (h > 0) minOf(n.bottom, h) else n.bottom
        val area = maxOf(r - l, 0).toLong() * maxOf(b - t, 0).toLong()
        return Cand(n, role, label, value, flags, rank, t, l, (l + r) / 2, (t + b) / 2, guard, area)
    }

    private fun shown(n: RawNode, w: Int, h: Int): Boolean {
        if (!n.visible) return false
        if (n.right <= n.left || n.bottom <= n.top) return false
        if (w > 0 && h > 0 && (n.right <= 0 || n.bottom <= 0 || n.left >= w || n.top >= h)) return false
        return true
    }

    private fun interactive(n: RawNode) = n.clickable || n.editable || n.scrollable || n.checkable || n.password

    private fun hasInteractiveBelow(n: RawNode, w: Int, h: Int): Boolean =
        n.children.any { shown(it, w, h) && (interactive(it) || hasInteractiveBelow(it, w, h)) }

    /** Texts of the descendants that are not controls of their own, in tree order, cut at [LABEL_MAX]. Password fields add nothing. */
    private fun joinedText(n: RawNode, w: Int, h: Int): String {
        val parts = ArrayList<String>()
        fun collect(x: RawNode) {
            for (c in x.children) {
                if (!shown(c, w, h) || c.password || interactive(c)) continue
                val t = clean(c.text).ifEmpty { clean(c.desc) }
                if (t.isNotEmpty()) parts += t
                collect(c)
            }
        }
        collect(n)
        return parts.joinToString(" ").take(LABEL_MAX).trim()
    }

    private fun editLabel(n: RawNode): String =
        clean(n.hint).ifEmpty { clean(n.desc) }.ifEmpty { resName(n.resId) }.ifEmpty { "text field" }.take(LABEL_MAX)

    private fun passwordLabel(n: RawNode): String =
        clean(n.hint).ifEmpty { clean(n.desc) }.ifEmpty { resName(n.resId) }.ifEmpty { "password" }.take(LABEL_MAX)

    private fun clean(s: String): String = WHITE.replace(s, " ").trim()

    /** "com.app:id/cart_icon" becomes "cart icon". */
    private fun resName(resId: String): String = resId.substringAfterLast('/').replace('_', ' ').trim()

    // ------------------------------------------------------------ hash

    private fun hashOf(pkg: String, activity: String, els: List<UiElement>, unreadable: Boolean): String {
        val md = MessageDigest.getInstance("SHA-256")
        fun put(s: String) {
            md.update(s.toByteArray(Charsets.UTF_8))
            md.update(0)
        }
        put(pkg)
        put(activity)
        put(if (unreadable) "unreadable" else "readable")
        for (e in els) {
            put(e.role.name)
            put(e.label)
            put(e.value)
            put(e.flags.map { it.name }.sorted().joinToString(","))
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ------------------------------------------------------------ render

    fun render(s: Screen, maxChars: Int = 2400): String {
        if (s.unreadable) return "This screen cannot be read (app: ${s.pkg.ifBlank { "unknown" }}): Ustaad does not read it. Do not tap, type or scroll; go back or go home."
        val head = "app: ${s.pkg}"
        val lines = s.elements.map { line(it) }
        val dropped = BooleanArray(lines.size)
        fun size(): Int = head.length + 1 + lines.indices.filter { !dropped[it] }.sumOf { lines[it].length + 1 } + 60
        while (size() > maxChars) {
            val live = lines.indices.filter { !dropped[it] }
            if (live.isEmpty()) break
            // text goes first, then the bottom of the screen
            val victim = live.lastOrNull { s.elements[it].role == Role.TEXT || s.elements[it].role == Role.IMAGE } ?: live.last()
            dropped[victim] = true
        }
        val sb = StringBuilder(head)
        for (i in lines.indices) if (!dropped[i]) sb.append('\n').append(lines[i])
        val n = dropped.count { it }
        if (n > 0) sb.append("\n(").append(n).append(" more elements not shown; scroll to see more)")
        return sb.toString()
    }

    private fun roleName(r: Role) = when (r) {
        Role.BUTTON -> "button"
        Role.EDIT -> "edit"
        Role.TEXT -> "text"
        Role.TOGGLE -> "toggle"
        Role.LIST -> "list"
        Role.IMAGE -> "image"
        Role.OTHER -> "item"
    }

    private fun line(e: UiElement): String {
        val sb = StringBuilder()
        sb.append(e.id).append(' ').append(roleName(e.role))
        if (e.label.isNotEmpty()) sb.append(" \"").append(e.label.replace('"', '\'')).append('"')
        if (e.role == Role.EDIT) sb.append(" = \"").append(e.value.replace('"', '\'')).append('"')
        if (e.role == Role.TOGGLE) sb.append(if (Flag.CHECKED in e.flags) " [on]" else " [off]")
        if (Flag.DISABLED in e.flags) sb.append(" [disabled]")
        if (Flag.SCROLLABLE in e.flags) sb.append(" [scroll]")
        if (Flag.PASSWORD in e.flags) sb.append(" [password]")
        return sb.toString()
    }
}
