package com.swarmos.app.hands.android

import com.swarmos.app.hands.AppInfo
import com.swarmos.app.hands.RawNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    @Test fun parent_and_depth_of_a_handle() {
        assertEquals("0.3", ScreenMapping.parentOf("0.3.1"))
        assertEquals("0", ScreenMapping.parentOf("0.3"))
        assertNull(ScreenMapping.parentOf("0"))
        assertEquals(0, ScreenMapping.depthOf("0"))
        assertEquals(2, ScreenMapping.depthOf("0.3.1"))
    }

    @Test fun facts_treat_missing_values_as_empty_and_ignore_the_text_of_a_password_node() {
        assertEquals(NodeFacts("", "", ""), ScreenMapping.factsOf(null, null, null, password = false))
        val a = ScreenMapping.factsOf("android.widget.EditText", "hunter2", null, password = true)
        val b = ScreenMapping.factsOf("android.widget.EditText", "hunter3", null, password = true)
        assertEquals("", a.text)
        assertEquals(a, b)
        assertEquals(sig(F(a)), sig(F(b)))
    }

    // ---- subtree signature. F = a node as read; L = a live node (children may be null, fetches are counted).

    private fun facts(cls: String = "android.view.View", text: String = "", desc: String = "") = NodeFacts(cls, text, desc)
    private fun F(f: NodeFacts, vararg kids: FactNode) = FactNode(f, kids.toList())
    private fun sigOrNull(root: FactNode, maxNodes: Int = ScreenMapping.SIG_NODES, maxDepth: Int = ScreenMapping.MAX_DEPTH): String? =
        ScreenMapping.subtreeSignature(root, { it.kids.size }, { n, i -> n.kids[i] }, { it.facts }, maxNodes = maxNodes, maxDepth = maxDepth)
    private fun sig(root: FactNode, maxNodes: Int = ScreenMapping.SIG_NODES, maxDepth: Int = ScreenMapping.MAX_DEPTH): String =
        sigOrNull(root, maxNodes, maxDepth)!!

    private class L(val f: NodeFacts, val kids: List<L?> = emptyList())
    private fun liveSig(root: L, fetched: MutableList<L>? = null, released: MutableList<L>? = null, maxNodes: Int = ScreenMapping.SIG_NODES): String? =
        ScreenMapping.subtreeSignature(
            root, { it.kids.size }, { n, i -> n.kids[i]?.also { fetched?.add(it) } }, { it.f },
            release = { released?.add(it) }, maxNodes = maxNodes,
        )

    private fun card(child: String, parentText: String = "", cls: String = "android.view.ViewGroup") =
        F(facts(cls, parentText), F(facts("android.widget.TextView", child)))

    @Test fun same_subtree_gives_the_same_signature_and_a_change_anywhere_in_it_gives_another() {
        val read = sig(card("Add"))
        assertEquals(read, sig(card("Add")))
        assertTrue(sig(card("Pay")) != read)                  // only the child changed, the parent has empty text
        assertTrue(sig(card("Add", parentText = "x")) != read)
        assertTrue(sig(card("Add", cls = "android.widget.Button")) != read)
        val deep = F(facts(), F(facts(), F(facts(desc = "add item"))))
        assertTrue(sig(F(facts(), F(facts(), F(facts(desc = "pay now"))))) != sig(deep))
    }

    @Test fun signature_sees_where_text_sits_not_only_which_text_there_is() {
        assertTrue(sig(F(facts(), F(facts(text = "a")), F(facts(text = "b")))) != sig(F(facts(), F(facts(text = "b")), F(facts(text = "a")))))
        assertTrue(sig(F(facts(), F(facts(text = "a"), F(facts(text = "b"))))) != sig(F(facts(), F(facts(text = "a")), F(facts(text = "b")))))
        // text boundaries: ("ab", "c") is not ("a", "bc")
        assertTrue(sig(F(facts(text = "ab", desc = "c"))) != sig(F(facts(text = "a", desc = "bc"))))
    }

    @Test fun live_and_read_trees_with_the_same_facts_have_the_same_signature() {
        val read = sig(card("Add", parentText = "Item"))
        val live = L(facts("android.view.ViewGroup", "Item"), listOf(L(facts("android.widget.TextView", "Add"))))
        assertEquals(read, liveSig(live))
        val changed = L(facts("android.view.ViewGroup", "Item"), listOf(L(facts("android.widget.TextView", "Pay"))))
        assertTrue(read != liveSig(changed))
        val extraChild = L(facts("android.view.ViewGroup", "Item"), listOf(L(facts("android.widget.TextView", "Add")), L(facts(text = "Pay"))))
        assertTrue(read != liveSig(extraChild))
    }

    @Test fun a_child_that_cannot_be_fetched_is_skipped_and_every_fetched_child_is_released_but_not_the_root() {
        val fetched = ArrayList<L>()
        val released = ArrayList<L>()
        val a = L(facts(text = "a"))
        val b = L(facts(text = "b"), listOf(L(facts(text = "b0"))))
        val root = L(facts(), listOf(a, null, b))
        val live = liveSig(root, fetched, released)
        assertEquals(sig(F(facts(), F(facts(text = "a")), F(facts(text = "b"), F(facts(text = "b0"))))), live)
        assertEquals(3, fetched.size)
        assertEquals(fetched.toSet(), released.toSet())
        assertFalse(released.contains(root))
    }

    @Test fun t5_a_subtree_that_reaches_the_node_cap_cannot_be_verified() {
        fun wide(n: Int) = F(facts(), *Array(n) { F(facts(text = "c$it")) })
        assertEquals(300, ScreenMapping.SIG_NODES)
        assertNull("301 nodes: a change in the last would go unseen", sigOrNull(wide(300)))
        assertNotNull("exactly 300 nodes is complete", sigOrNull(wide(299)))
        assertNull(sigOrNull(wide(400)))
        assertNotNull(sigOrNull(wide(20)))
    }

    @Test fun t5_a_node_with_children_at_the_cap_counts_as_cut() {
        // 300 nodes in all, but the last one still has a child that would be the 301st
        val tail = F(facts(), F(facts(text = "past the cap")))
        val root = F(facts(), *(Array(298) { F(facts(text = "c$it")) } + tail))
        assertNull(sigOrNull(root))
        assertNotNull(sigOrNull(F(facts(), *(Array(298) { F(facts(text = "c$it")) } + F(facts())))))
    }

    @Test fun t4_the_signature_covers_hint_resource_id_and_the_password_enabled_checkable_and_checked_flags() {
        val base = sig(F(facts()))
        val changed = listOf(
            NodeFacts("android.view.View", "", "", hint = "Password"),
            NodeFacts("android.view.View", "", "", resId = "id/pay_now"),
            NodeFacts("android.view.View", "", "", password = true),
            NodeFacts("android.view.View", "", "", enabled = false),
            NodeFacts("android.view.View", "", "", checkable = true),
            NodeFacts("android.view.View", "", "", checked = true),
        )
        for (f in changed) assertTrue(f.toString(), sig(F(f)) != base)
        assertEquals("the same facts give the same signature", base, sig(F(NodeFacts("android.view.View", "", ""))))
        assertEquals(changed.size, changed.map { sig(F(it)) }.toSet().size)
    }

    @Test fun t4_a_search_field_that_becomes_a_password_field_after_the_read_no_longer_matches() {
        val read = sig(F(NodeFacts("android.widget.EditText", "", "", hint = "Search")))
        val later = sig(F(NodeFacts("android.widget.EditText", "", "", hint = "Password", password = true)))
        assertTrue(read != later)
        val onlyTheHint = sig(F(NodeFacts("android.widget.EditText", "", "", hint = "Password")))
        assertTrue(read != onlyTheHint)
    }

    @Test fun facts_carry_the_new_fields_and_blank_a_password_text() {
        val f = ScreenMapping.factsOf("c", "secret", "d", password = true, hint = "h", resId = "id/x", enabled = false, checkable = true, checked = true)
        assertEquals(NodeFacts("c", "", "d", "h", "id/x", password = true, enabled = false, checkable = true, checked = true), f)
        assertEquals(NodeFacts("c", "t", "d"), ScreenMapping.factsOf("c", "t", "d", password = false))
    }

    @Test fun signature_stops_at_the_depth_it_is_given_and_a_cut_chain_cannot_be_verified() {
        fun chain(leaf: String) = F(facts(), F(facts(), F(facts(text = leaf))))
        assertTrue(sig(chain("a")) != sig(chain("b")))
        assertNull(sigOrNull(chain("a"), maxDepth = 1))
        assertNotNull(sigOrNull(chain("a"), maxDepth = 2))
    }

    @Test fun signature_lookups_are_capped_and_can_be_cancelled() {
        var asked = 0
        val none = ScreenMapping.subtreeSignature(
            L(facts(), List(5000) { null }), { it.kids.size }, { _, _ -> asked++; null }, { it.f }, maxNodes = 300,
        )
        assertEquals(600, asked)
        assertNull("the lookup cap was reached: not verifiable", none)
        var checks = 0
        try {
            ScreenMapping.subtreeSignature(
                L(facts(), List(50) { L(facts()) }), { it.kids.size }, { n, i -> n.kids[i] }, { it.f },
                checkCancelled = { if (++checks > 3) throw IllegalStateException("stopped") },
            )
            org.junit.Assert.fail("should have stopped")
        } catch (e: IllegalStateException) {
            assertEquals("stopped", e.message)
        }
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

    private fun capsOf(root: N, maxNodes: Int = ScreenMapping.MAX_NODES, maxDepth: Int = ScreenMapping.MAX_DEPTH, maxLookups: Int = ScreenMapping.MAX_LOOKUPS, nullKids: Boolean = false): List<String> {
        val caps = ArrayList<String>()
        ScreenMapping.buildTree(
            root = root,
            childCount = { it.kids.size },
            childAt = { n, i -> if (nullKids) null else n.kids[i] },
            visit = { _, _ -> },
            make = { n, h, kids -> node(text = n.name, handle = h).copy(children = kids) },
            maxNodes = maxNodes, maxDepth = maxDepth, maxLookups = maxLookups,
            onCap = { caps += it },
        )
        return caps
    }

    @Test fun t5_a_read_that_reaches_the_node_cap_says_so() {
        assertEquals(listOf("nodes"), capsOf(N("root", List(5000) { N("c$it") })).distinct())
    }

    @Test fun t5_a_read_that_reaches_the_lookup_cap_says_so() {
        assertEquals(listOf("lookups"), capsOf(N("root", List(100_000) { N("c$it") }), nullKids = true).distinct())
    }

    @Test fun t5_a_read_that_reaches_the_depth_cap_says_so() {
        var chain = N("leaf")
        repeat(100) { chain = N("n", listOf(chain)) }
        assertEquals(listOf("depth"), capsOf(chain).distinct())
    }

    @Test fun t5_a_complete_read_says_nothing() {
        assertTrue(capsOf(N("root", listOf(N("a", listOf(N("a0"))), N("b")))).isEmpty())
        // exactly as many nodes as the cap allows, none left over
        assertTrue(capsOf(N("root", List(4) { N("c$it") }), maxNodes = 5).isEmpty())
        assertEquals(listOf("nodes"), capsOf(N("root", List(5) { N("c$it") }), maxNodes = 5))
    }

    @Test fun t7_enter_is_allowed_only_for_the_node_that_took_the_text() {
        assertTrue(ScreenMapping.enterAllowed("abc", "abc"))
        assertFalse("focus moved to another field", ScreenMapping.enterAllowed("other", "abc"))
        assertFalse("nothing was typed", ScreenMapping.enterAllowed("abc", null))
        assertFalse("nothing has focus", ScreenMapping.enterAllowed(null, "abc"))
        assertFalse(ScreenMapping.enterAllowed(null, null))
    }

    @Test fun t7_a_field_that_changed_after_the_text_went_in_no_longer_gets_the_enter() {
        val typed = sig(F(NodeFacts("android.widget.EditText", "biryani", "", hint = "Search")))
        val sameLater = sig(F(NodeFacts("android.widget.EditText", "biryani", "", hint = "Search")))
        val messageField = sig(F(NodeFacts("android.widget.EditText", "biryani", "", hint = "Write a message")))
        assertTrue(ScreenMapping.enterAllowed(sameLater, typed))
        assertFalse(ScreenMapping.enterAllowed(messageField, typed))
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
