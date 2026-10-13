package com.swarmos.app.hands.android

import com.swarmos.app.hands.AppInfo
import com.swarmos.app.hands.RawNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun a_node_that_changed_its_label_class_or_description_is_not_the_node_that_was_read() {
        val read = ScreenMapping.factsOf("android.widget.Button", "Add", "add item", password = false)
        assertTrue(ScreenMapping.sameTarget(read, ScreenMapping.factsOf("android.widget.Button", "Add", "add item", password = false)))
        assertFalse(ScreenMapping.sameTarget(read, ScreenMapping.factsOf("android.widget.Button", "Pay", "add item", password = false)))
        assertFalse(ScreenMapping.sameTarget(read, ScreenMapping.factsOf("android.widget.Button", "Add", "pay now", password = false)))
        assertFalse(ScreenMapping.sameTarget(read, ScreenMapping.factsOf("android.widget.TextView", "Add", "add item", password = false)))
    }

    @Test fun facts_treat_missing_values_as_empty_and_ignore_the_text_of_a_password_node() {
        assertEquals(NodeFacts("", "", ""), ScreenMapping.factsOf(null, null, null, password = false))
        val a = ScreenMapping.factsOf("android.widget.EditText", "hunter2", null, password = true)
        val b = ScreenMapping.factsOf("android.widget.EditText", "hunter3", null, password = true)
        assertEquals("", a.text)
        assertTrue(ScreenMapping.sameTarget(a, b))
    }

    @Test fun link_handler_is_the_package_resolveActivity_names_when_it_is_a_listed_handler() {
        val handlers = listOf("com.zomato" to "com.zomato.Link", "com.android.chrome" to "com.google.Chrome")
        assertEquals("com.zomato", ScreenMapping.realHandler("com.zomato", "com.zomato.Link", handlers))
        assertEquals("", ScreenMapping.realHandler("com.zomato", "com.zomato.Other", handlers))
    }

    @Test fun link_handler_is_empty_for_nothing_and_for_the_system_chooser() {
        val handlers = listOf("com.zomato" to "com.zomato.Link", "com.android.chrome" to "com.google.Chrome")
        assertEquals("", ScreenMapping.realHandler(null, null, handlers))
        assertEquals("", ScreenMapping.realHandler("", "", handlers))
        assertEquals("", ScreenMapping.realHandler("android", "com.android.internal.app.ResolverActivity", handlers))
        assertEquals("", ScreenMapping.realHandler("com.android.intentresolver", "com.android.intentresolver.ChooserActivity", handlers))
        // even if a chooser were listed, its name gives it away
        val listed = handlers + ("android" to "com.android.internal.app.ResolverActivity")
        assertEquals("", ScreenMapping.realHandler("android", "com.android.internal.app.ResolverActivity", listed))
        assertEquals("", ScreenMapping.realHandler("com.vendor", "com.vendor.app.ResolverActivity", listOf("com.vendor" to "com.vendor.app.ResolverActivity")))
    }

    @Test fun default_home_app_with_no_default_set_gives_the_resolver_and_so_nothing() {
        val homes = listOf("com.vivo.launcher" to "com.vivo.launcher.Main", "com.evil.home" to "com.evil.home.Main")
        assertEquals("com.vivo.launcher", ScreenMapping.realHandler("com.vivo.launcher", "com.vivo.launcher.Main", homes))
        assertEquals("", ScreenMapping.realHandler("android", "com.android.internal.app.ResolverActivity", homes))
    }

    @Test fun failed_child_lookups_count_against_the_lookup_cap() {
        var asked = 0
        val tree = N("root", List(100_000) { N("c$it") })
        val raw = ScreenMapping.buildTree(
            root = tree,
            childCount = { it.kids.size },
            childAt = { _, _ -> asked++; null },
            visit = { _, _ -> },
            make = { n, h, kids -> node(text = n.name, handle = h).copy(children = kids) },
        )
        assertEquals(ScreenMapping.MAX_LOOKUPS, asked)
        assertEquals(1, count(raw))
    }

    @Test fun lookups_that_return_nodes_also_stop_at_the_node_cap_first() {
        var asked = 0
        val wide = N("root", List(5000) { N("c$it") })
        val raw = ScreenMapping.buildTree(
            root = wide,
            childCount = { it.kids.size },
            childAt = { n, i -> asked++; n.kids[i] },
            visit = { _, _ -> },
            make = { n, h, kids -> node(text = n.name, handle = h).copy(children = kids) },
        )
        assertEquals(ScreenMapping.MAX_NODES, count(raw))
        assertEquals(ScreenMapping.MAX_NODES - 1, asked)
    }

    @Test fun the_walk_ends_when_the_cancellation_check_throws() {
        var checks = 0
        var asked = 0
        val tree = N("root", List(1000) { N("c$it") })
        try {
            ScreenMapping.buildTree(
                root = tree,
                childCount = { it.kids.size },
                childAt = { n, i -> asked++; n.kids[i] },
                visit = { _, _ -> },
                make = { n, h, kids -> node(text = n.name, handle = h).copy(children = kids) },
                checkCancelled = { if (++checks > 10) throw IllegalStateException("stopped") },
            )
            org.junit.Assert.fail("the walk should have ended")
        } catch (e: IllegalStateException) {
            assertEquals("stopped", e.message)
        }
        assertEquals(10, asked)
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
        assertEquals(ScreenMapping.PICK_NONE, ScreenMapping.pickWindow(emptyList(), "own"))
    }

    @Test fun our_active_window_means_own_in_front_whatever_is_behind() {
        val own = WindowCand(active = true, focused = true, layer = 1, pkg = "own")
        val other = WindowCand(active = false, focused = false, layer = 9, pkg = "p.x")
        assertEquals(ScreenMapping.PICK_OWN_FRONT, ScreenMapping.pickWindow(listOf(other, own), "own"))
        assertEquals(ScreenMapping.PICK_OWN_FRONT, ScreenMapping.pickWindow(listOf(own), "own"))
    }

    @Test fun our_focused_window_means_own_in_front_when_no_window_is_active() {
        val own = WindowCand(active = false, focused = true, layer = 1, pkg = "own")
        val other = WindowCand(active = false, focused = false, layer = 9, pkg = "p.x")
        assertEquals(ScreenMapping.PICK_OWN_FRONT, ScreenMapping.pickWindow(listOf(other, own), "own"))
    }

    @Test fun the_active_window_decides_over_a_focused_window_of_ours() {
        val own = WindowCand(active = false, focused = true, layer = 9, pkg = "own")
        val other = WindowCand(active = true, focused = false, layer = 1, pkg = "p.x")
        assertEquals(1, ScreenMapping.pickWindow(listOf(own, other), "own"))
    }

    @Test fun our_window_behind_another_app_is_ignored_and_the_other_app_is_read() {
        val own = WindowCand(active = false, focused = false, layer = 9, pkg = "own")
        val other = WindowCand(active = true, focused = true, layer = 1, pkg = "p.x")
        assertEquals(1, ScreenMapping.pickWindow(listOf(own, other), "own"))
        val idle = other.copy(active = false, focused = false)
        assertEquals(1, ScreenMapping.pickWindow(listOf(own, idle), "own"))
        assertEquals(ScreenMapping.PICK_NONE, ScreenMapping.pickWindow(listOf(own), "own"))
    }

    @Test fun only_an_overlay_of_ours_on_top_still_reads_the_app() {
        // the Stop chip is an accessibility overlay: the driver does not list it as an application window
        val app = WindowCand(active = true, focused = true, layer = 1, pkg = "p.x")
        assertEquals(0, ScreenMapping.pickWindow(listOf(app), "own"))
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
