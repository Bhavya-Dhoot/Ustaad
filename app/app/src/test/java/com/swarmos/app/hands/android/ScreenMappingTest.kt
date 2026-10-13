package com.swarmos.app.hands.android

import com.swarmos.app.hands.AppInfo
import com.swarmos.app.hands.RawNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenMappingTest {
    private class N(val name: String, val kids: List<N> = emptyList())

    private fun node(
        text: CharSequence? = "x",
        password: Boolean = false,
        showingHint: Boolean = false,
        hint: CharSequence? = null,
        handle: String = "0",
    ): RawNode = ScreenMapping.rawNodeOf(
        cls = "android.widget.EditText", text = text, desc = "d", hint = hint, showingHint = showingHint,
        resId = "id/x", clickable = true, editable = true, scrollable = false, checkable = false, checked = false,
        enabled = true, password = password, visible = true, left = 1, top = 2, right = 3, bottom = 4,
        children = emptyList(), handle = handle,
    )

    private fun build(root: N, maxNodes: Int = ScreenMapping.MAX_NODES, maxDepth: Int = ScreenMapping.MAX_DEPTH): Pair<RawNode, List<String>> {
        val visited = ArrayList<String>()
        val raw = ScreenMapping.buildTree(
            root = root,
            childCount = { it.kids.size },
            childAt = { n, i -> n.kids[i] },
            visit = { n, h -> visited += "$h=${n.name}" },
            make = { n, h, kids -> node(text = n.name, handle = h).copy(children = kids) },
            maxNodes = maxNodes, maxDepth = maxDepth,
        )
        return raw to visited
    }

    private fun count(n: RawNode): Int = 1 + n.children.sumOf { count(it) }

    @Test fun handles_are_child_index_paths() {
        assertEquals("0", ScreenMapping.ROOT_HANDLE)
        assertEquals("0.3", ScreenMapping.childHandle("0", 3))
        assertEquals("0.3.1", ScreenMapping.childHandle("0.3", 1))
        assertEquals("0.12.0", ScreenMapping.childHandle(ScreenMapping.childHandle("0", 12), 0))
    }

    @Test fun parent_and_ancestors_of_a_handle() {
        assertEquals("0.3", ScreenMapping.parentOf("0.3.1"))
        assertEquals("0", ScreenMapping.parentOf("0.3"))
        assertNull(ScreenMapping.parentOf("0"))
        assertEquals(listOf("0.3", "0"), ScreenMapping.ancestorsOf("0.3.1"))
        assertEquals(emptyList<String>(), ScreenMapping.ancestorsOf("0"))
    }

    @Test fun password_node_gives_empty_text_and_keeps_the_flag() {
        val n = node(text = "hunter2", password = true)
        assertEquals("", n.text)
        assertTrue(n.password)
        assertFalse(node(text = "hunter2").password)
        assertEquals("hunter2", node(text = "hunter2").text)
    }

    @Test fun password_node_with_hint_state_still_has_no_text() {
        val n = node(text = "secret", password = true, showingHint = true)
        assertEquals("", n.text)
        assertEquals("", n.hint)
    }

    @Test fun empty_field_showing_its_hint_has_no_value_and_the_hint_moves_to_hint() {
        val n = node(text = "Search dishes", showingHint = true, hint = null)
        assertEquals("", n.text)
        assertEquals("Search dishes", n.hint)
        val m = node(text = "Search dishes", showingHint = true, hint = "Search")
        assertEquals("", m.text)
        assertEquals("Search", m.hint)
    }

    @Test fun missing_fields_become_empty_strings() {
        val n = ScreenMapping.rawNodeOf(
            cls = null, text = null, desc = null, hint = null, showingHint = false, resId = null,
            clickable = false, editable = false, scrollable = false, checkable = false, checked = false,
            enabled = true, password = false, visible = true, left = 0, top = 0, right = 0, bottom = 0,
            children = emptyList(), handle = "0",
        )
        assertEquals("", n.cls)
        assertEquals("", n.text)
        assertEquals("", n.desc)
        assertEquals("", n.hint)
        assertEquals("", n.resId)
    }

    @Test fun tree_gets_handles_and_visits_parent_before_children() {
        val tree = N("root", listOf(N("a", listOf(N("a0"), N("a1"))), N("b")))
        val (raw, visited) = build(tree)
        assertEquals(listOf("0=root", "0.0=a", "0.0.0=a0", "0.0.1=a1", "0.1=b"), visited)
        assertEquals("0", raw.handle)
        assertEquals("0.0.1", raw.children[0].children[1].handle)
        assertEquals("0.1", raw.children[1].handle)
        assertEquals(5, count(raw))
    }

    @Test fun tree_stops_at_the_node_cap() {
        val wide = N("root", List(5000) { N("c$it") })
        val (raw, visited) = build(wide)
        assertEquals(ScreenMapping.MAX_NODES, count(raw))
        assertEquals(ScreenMapping.MAX_NODES, visited.size)
        assertEquals("0.1498", raw.children.last().handle)
    }

    @Test fun tree_stops_at_the_depth_cap() {
        var chain = N("leaf")
        repeat(100) { chain = N("n", listOf(chain)) }
        val (raw, _) = build(chain)
        assertEquals(ScreenMapping.MAX_DEPTH + 1, count(raw))
        val (small, _) = build(chain, maxDepth = 2)
        assertEquals(3, count(small))
    }

    @Test fun unfetchable_child_is_skipped_and_keeps_its_index() {
        val tree = N("root", listOf(N("a"), N("gone"), N("c")))
        val raw = ScreenMapping.buildTree(
            root = tree,
            childCount = { it.kids.size },
            childAt = { n, i -> n.kids[i].takeIf { it.name != "gone" } },
            visit = { _, _ -> },
            make = { n, h, kids -> node(text = n.name, handle = h).copy(children = kids) },
        )
        assertEquals(listOf("0.0", "0.2"), raw.children.map { it.handle })
    }

    @Test fun active_window_wins_then_focused_then_topmost() {
        val a = WindowCand(active = false, focused = false, layer = 5, pkg = "p.a")
        val b = WindowCand(active = true, focused = false, layer = 1, pkg = "p.b")
        val c = WindowCand(active = false, focused = true, layer = 3, pkg = "p.c")
        assertEquals(1, ScreenMapping.pickWindow(listOf(a, b, c), "own"))
        assertEquals(1, ScreenMapping.pickWindow(listOf(a, c), "own"))
        assertEquals(0, ScreenMapping.pickWindow(listOf(a, a.copy(layer = 2)), "own"))
        assertEquals(-1, ScreenMapping.pickWindow(emptyList(), "own"))
    }

    @Test fun windows_of_our_own_package_are_never_picked() {
        val own = WindowCand(active = true, focused = true, layer = 9, pkg = "own")
        val other = WindowCand(active = false, focused = false, layer = 1, pkg = "p.x")
        assertEquals(1, ScreenMapping.pickWindow(listOf(own, other), "own"))
        assertEquals(-1, ScreenMapping.pickWindow(listOf(own), "own"))
    }

    @Test fun a_window_without_root_can_win_so_the_screen_is_reported_unreadable() {
        val secure = WindowCand(active = true, focused = false, layer = 2, pkg = null)
        val behind = WindowCand(active = false, focused = false, layer = 1, pkg = "p.x")
        assertEquals(0, ScreenMapping.pickWindow(listOf(secure, behind), "own"))
    }

    @Test fun swipe_forward_goes_up_and_backward_goes_down() {
        assertEquals(700 to 300, ScreenMapping.swipeSpan(0, 1000, forward = true))
        assertEquals(300 to 700, ScreenMapping.swipeSpan(0, 1000, forward = false))
        assertEquals(1200 to 800,ScreenMapping.swipeSpan(500, 1500, forward = true))
    }

    @Test fun app_list_has_one_entry_per_package_sorted_by_label() {
        val got = ScreenMapping.dedupeApps(
            listOf(
                AppInfo("p.z", "zomato"),
                AppInfo("p.s", "Swiggy"),
                AppInfo("p.s", "Swiggy Alt"),
                AppInfo("p.a", "Amazon"),
            ),
        )
        assertEquals(listOf("p.a", "p.s", "p.z"), got.map { it.pkg })
        assertEquals("Swiggy", got[1].label)
    }
}
