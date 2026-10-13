package com.swarmos.app.hands.android

import com.swarmos.app.hands.AppInfo
import com.swarmos.app.hands.RawNode
import java.security.MessageDigest

// The rules of the accessibility driver that need no Android class, so plain JVM tests cover them.
// AccessibilityDriver feeds these with the fields it reads; nothing here logs or stores screen content.

/** One application window as the driver sees it. pkg null: the window gave no root (secure window or gone). */
internal data class WindowCand(val active: Boolean, val focused: Boolean, val layer: Int, val pkg: String?)

/** What the driver compares about a node between read() and an action: its class, its own text and its description. */
internal data class NodeFacts(val cls: String, val text: String, val desc: String)

/** The facts of a node and of its children as read, kept per handle so an action can be checked against them. */
internal class FactNode(val facts: NodeFacts, val kids: List<FactNode>)

internal object ScreenMapping {
    const val MAX_NODES = 1500
    const val MAX_DEPTH = 40

    /** Child lookups tried, found or not. A tree that answers null for most children cannot keep the walk busy longer than this. */
    const val MAX_LOOKUPS = 3000

    /** Nodes of a subtree that its signature covers. */
    const val SIG_NODES = 300

    /** The root of every read. A child is its parent's handle plus the child index: "0.3.1". */
    const val ROOT_HANDLE = "0"

    fun childHandle(parent: String, index: Int): String = "$parent.$index"

    /** The password rule again: a password node's text is empty here whatever the caller passes. */
    fun factsOf(cls: CharSequence?, text: CharSequence?, desc: CharSequence?, password: Boolean): NodeFacts =
        NodeFacts(cls?.toString().orEmpty(), if (password) "" else text?.toString().orEmpty(), desc?.toString().orEmpty())

    /** null for the root. */
    fun parentOf(handle: String): String? {
        val i = handle.lastIndexOf('.')
        return if (i < 0) null else handle.substring(0, i)
    }

    /** 0 for the root, 2 for "0.3.1": how deep a handle sits, which the depth cap of the read counts the same way. */
    fun depthOf(handle: String): Int = handle.count { it == '.' }

    /**
     * A digest of a subtree: for the node and its descendants in reading order, at most maxNodes of them and not
     * deeper than maxDepth below the root, the depth, class, text and description of each. Text is length-prefixed, so
     * moving a letter from one text to the next changes it; so does moving a node. SHA-256, because the app that
     * draws the screen chooses the texts and could otherwise aim for a collision. A change in a node past the cap goes
     * unseen. A child that cannot be fetched (null) is skipped; release gives a fetched child back once its subtree is
     * done (the root is the caller's); checkCancelled runs before every lookup. At most 2 x maxNodes lookups.
     */
    fun <T : Any> subtreeSignature(
        root: T,
        childCount: (T) -> Int,
        childAt: (T, Int) -> T?,
        facts: (T) -> NodeFacts,
        release: (T) -> Unit = {},
        maxNodes: Int = SIG_NODES,
        maxDepth: Int = MAX_DEPTH,
        checkCancelled: () -> Unit = {},
    ): String {
        val md = MessageDigest.getInstance("SHA-256")
        fun put(n: Int) = md.update(byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()))
        fun put(s: String) { val b = s.toByteArray(Charsets.UTF_8); put(b.size); md.update(b) }
        var seen = 0
        var lookups = 0
        fun walk(node: T, depth: Int) {
            seen++
            val f = facts(node)
            put(depth); put(f.cls); put(f.text); put(f.desc)
            if (depth >= maxDepth) return
            for (i in 0 until childCount(node)) {
                if (seen >= maxNodes || lookups >= 2 * maxNodes) break
                checkCancelled()
                lookups++
                val child = childAt(node, i) ?: continue
                try { walk(child, depth + 1) } finally { release(child) }
            }
        }
        walk(root, 0)
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * The package that handles a link or the home screen, from what resolveActivity answered (pkg, cls) and the
     * handlers queryIntentActivities lists. "" when nothing resolved, or when the answer is the system's chooser or
     * resolver (it is not among the handlers, and it is named Resolver or Chooser).
     */
    fun realHandler(pkg: String?, cls: String?, handlers: List<Pair<String, String>>): String {
        if (pkg.isNullOrEmpty() || cls.isNullOrEmpty() || pkg == "android") return ""
        if (cls.endsWith("ResolverActivity") || cls.endsWith("ChooserActivity")) return ""
        return if (handlers.any { it.first == pkg && it.second == cls }) pkg else ""
    }

    /**
     * The password rule lives here: the text of a password node is never copied, whatever the caller passes.
     * A field that shows its hint as text (empty field) has no value; the hint moves to hint.
     */
    fun rawNodeOf(
        cls: String?,
        text: CharSequence?,
        desc: CharSequence?,
        hint: CharSequence?,
        showingHint: Boolean,
        resId: String?,
        clickable: Boolean,
        editable: Boolean,
        scrollable: Boolean,
        checkable: Boolean,
        checked: Boolean,
        enabled: Boolean,
        password: Boolean,
        visible: Boolean,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        children: List<RawNode>,
        handle: String,
    ): RawNode {
        val shown = if (password) "" else text?.toString().orEmpty()
        val hintText = hint?.toString().orEmpty()
        return RawNode(
            cls = cls.orEmpty(),
            text = if (showingHint) "" else shown,
            desc = desc?.toString().orEmpty(),
            hint = if (showingHint && hintText.isEmpty()) shown else hintText,
            resId = resId.orEmpty(),
            clickable = clickable, editable = editable, scrollable = scrollable,
            checkable = checkable, checked = checked, enabled = enabled,
            password = password, visible = visible,
            left = left, top = top, right = right, bottom = bottom,
            children = children, handle = handle,
        )
    }

    /**
     * Builds the tree depth first, parent before children, at most maxNodes nodes and nodes down to maxDepth
     * (the root is depth 0), after at most maxLookups child lookups (found or not). visit runs when a node is
     * entered, make runs once its children are built. A child that cannot be fetched (null) is skipped and keeps
     * its index. checkCancelled runs before every lookup; whatever it throws ends the walk.
     */
    fun <T : Any> buildTree(
        root: T,
        childCount: (T) -> Int,
        childAt: (T, Int) -> T?,
        visit: (T, String) -> Unit,
        make: (T, String, List<RawNode>) -> RawNode,
        maxNodes: Int = MAX_NODES,
        maxDepth: Int = MAX_DEPTH,
        maxLookups: Int = MAX_LOOKUPS,
        checkCancelled: () -> Unit = {},
    ): RawNode {
        var seen = 0
        var lookups = 0
        fun walk(node: T, handle: String, depth: Int): RawNode {
            seen++
            visit(node, handle)
            val kids = ArrayList<RawNode>()
            if (depth < maxDepth) {
                for (i in 0 until childCount(node)) {
                    if (seen >= maxNodes || lookups >= maxLookups) break
                    checkCancelled()
                    lookups++
                    val child = childAt(node, i) ?: continue
                    kids += walk(child, childHandle(handle, i), depth + 1)
                }
            }
            return make(node, handle, kids)
        }
        return walk(root, ROOT_HANDLE, 0)
    }

    const val PICK_NONE = -1

    /** Our own application window is the one in front (active, else focused): no screen is read and nothing is acted on. */
    const val PICK_OWN_FRONT = -2

    /**
     * The window to read, among application windows (the caller leaves out overlays, input methods and system
     * windows, so the Stop chip never counts). If the active window, else the focused one, is ours, the answer is
     * PICK_OWN_FRONT. Otherwise: of the windows that are not ours, the active one, else the focused one, else the
     * topmost; its index, or PICK_NONE when there is none. Our window behind another app does not matter. A window
     * without a root can win; the caller then reports the screen as unreadable instead of reading a window behind it.
     */
    fun pickWindow(cands: List<WindowCand>, ownPkg: String): Int {
        val front = cands.firstOrNull { it.active } ?: cands.firstOrNull { it.focused }
        if (front != null && front.pkg == ownPkg) return PICK_OWN_FRONT
        return cands.withIndex()
            .filter { it.value.pkg != ownPkg }
            .sortedWith(
                compareByDescending<IndexedValue<WindowCand>> { it.value.active }
                    .thenByDescending { it.value.focused }
                    .thenByDescending { it.value.layer }
                    .thenBy { it.index },
            )
            .firstOrNull()?.index ?: PICK_NONE
    }

    /** One entry per package (an app can have several launcher activities), sorted by label. */
    fun dedupeApps(apps: List<AppInfo>): List<AppInfo> =
        apps.distinctBy { it.pkg }.sortedBy { it.label.lowercase() }
}
