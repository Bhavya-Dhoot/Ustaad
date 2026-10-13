package com.swarmos.app.hands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiDumpTest {
    private fun n(attrs: String, kids: String = "") = "<node index=\"0\" $attrs checkable=\"false\" checked=\"false\" clickable=\"false\" enabled=\"true\" focusable=\"false\" focused=\"false\" scrollable=\"false\" long-clickable=\"false\" password=\"false\" selected=\"false\">$kids</node>"

    /** A dump shaped like the ones `uiautomator dump` writes (attribute names and order of a real one; the app is made up). */
    private val SAMPLE = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node index="0" text="" resource-id="" class="android.widget.FrameLayout" package="in.example.food" content-desc="" checkable="false" checked="false" clickable="false" enabled="true" focusable="false" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[0,0][1080,2400]">
    <node index="0" text="" resource-id="in.example.food:id/search_bar" class="android.widget.LinearLayout" package="in.example.food" content-desc="" checkable="false" checked="false" clickable="true" enabled="true" focusable="true" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[40,150][1040,270]">
      <node index="0" text="Search for restaurants and food" resource-id="in.example.food:id/hint" class="android.widget.TextView" package="in.example.food" content-desc="" checkable="false" checked="false" clickable="false" enabled="true" focusable="false" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[100,170][900,250]" />
    </node>
    <node index="1" text="biryani" resource-id="in.example.food:id/query" class="android.widget.EditText" package="in.example.food" content-desc="" checkable="false" checked="false" clickable="true" enabled="true" focusable="true" focused="true" scrollable="false" long-clickable="true" password="false" selected="false" bounds="[40,300][1040,400]" />
    <node index="2" text="Veg only" resource-id="in.example.food:id/veg" class="android.widget.Switch" package="in.example.food" content-desc="" checkable="true" checked="true" clickable="true" enabled="true" focusable="true" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[40,420][1040,500]" />
    <node index="3" text="" resource-id="in.example.food:id/list" class="androidx.recyclerview.widget.RecyclerView" package="in.example.food" content-desc="" checkable="false" checked="false" clickable="false" enabled="true" focusable="true" focused="false" scrollable="true" long-clickable="false" password="false" selected="false" bounds="[0,520][1080,2200]">
      <node index="0" text="" resource-id="" class="android.view.ViewGroup" package="in.example.food" content-desc="" checkable="false" checked="false" clickable="true" enabled="true" focusable="true" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[0,540][1080,800]">
        <node index="0" text="Tom &amp; Jerry&apos;s &quot;Caf&#233;&quot;" resource-id="" class="android.widget.TextView" package="in.example.food" content-desc="" checkable="false" checked="false" clickable="false" enabled="true" focusable="false" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[40,550][700,610]" />
        <node index="1" text="4.3 &#8226; 30 mins" resource-id="" class="android.widget.TextView" package="in.example.food" content-desc="" checkable="false" checked="false" clickable="false" enabled="true" focusable="false" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[40,620][700,680]" />
      </node>
    </node>
    <node index="4" text="Add" resource-id="in.example.food:id/add" class="android.widget.Button" package="in.example.food" content-desc="" checkable="false" checked="false" clickable="true" enabled="false" focusable="true" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[800,2250][1040,2350]" />
    <node index="5" text="&#8226;&#8226;&#8226;&#8226;" resource-id="" class="android.widget.EditText" package="in.example.food" content-desc="" hint="Password" checkable="false" checked="false" clickable="true" enabled="true" focusable="true" focused="false" scrollable="false" long-clickable="true" password="true" selected="false" bounds="[40,2000][1040,2100]" />
  </node>
</hierarchy>"""

    @Test fun parsesARealisticDumpIntoNodesWithTheFieldsTheCoreNeeds() {
        val r = UiDump.parse(SAMPLE, W, H, activity = "HomeActivity")
        assertFalse(r.unreadable)
        assertEquals("in.example.food", r.pkg)
        assertEquals("HomeActivity", r.activity)
        assertEquals(W, r.widthPx)
        val root = r.root!!
        assertEquals("android.widget.FrameLayout", root.cls)
        assertEquals(6, root.children.size)
        val edit = root.children[1]
        assertTrue(edit.editable)
        assertTrue(edit.clickable)
        assertEquals("biryani", edit.text)
        assertEquals("in.example.food:id/query", edit.resId)
        assertEquals(40, edit.left); assertEquals(300, edit.top); assertEquals(1040, edit.right); assertEquals(400, edit.bottom)
        assertEquals("[40,300][1040,400]", edit.handle)
        val sw = root.children[2]
        assertTrue(sw.checkable); assertTrue(sw.checked)
        assertTrue(root.children[3].scrollable)
        assertFalse(root.children[4].enabled)
        assertTrue(root.children[5].password)
        assertEquals("Password", root.children[5].hint)
    }

    @Test fun decodesEntitiesAndCharacterReferencesInText() {
        val r = UiDump.parse(SAMPLE, W, H)
        val card = r.root!!.children[3].children[0]
        assertEquals("Tom & Jerry's \"Café\"", card.children[0].text)
        assertEquals("4.3 • 30 mins", card.children[1].text)
    }

    @Test fun packageArgumentWinsAndOtherwiseTheFirstNodePackageIsUsed() {
        assertEquals("in.example.food", UiDump.parse(SAMPLE, W, H).pkg)
        assertEquals("given.pkg", UiDump.parse(SAMPLE, W, H, pkg = "given.pkg").pkg)
    }

    @Test fun aDumpGoesThroughTheReaderToTheListTheModelSees() {
        val s = ScreenReader.compact(UiDump.parse(SAMPLE, W, H))
        val out = ScreenReader.render(s)
        assertTrue(out, out.contains("edit \"Password\" = \"\" [password]"))
        assertTrue(out, out.contains("toggle \"Veg only\" [on]"))
        assertTrue(out, out.contains("edit \"query\" = \"biryani\""))
        assertTrue(out, out.contains("button \"Tom & Jerry's 'Café' 4.3 • 30 mins\""))
        assertTrue(out, out.contains("button \"Add\" [disabled]"))
        assertTrue(out, out.contains("[scroll]"))
        assertFalse(out, out.contains("••"))
    }

    @Test fun twoWindowsAreKeptUnderOneRoot() {
        val xml = "<hierarchy>" +
            n("text=\"\" class=\"a.A\" package=\"p1\" content-desc=\"\" bounds=\"[0,0][1080,100]\"") +
            n("text=\"\" class=\"b.B\" package=\"p2\" content-desc=\"\" bounds=\"[0,100][1080,2400]\"") + "</hierarchy>"
        val r = UiDump.parse(xml, W, H)
        assertEquals(2, r.root!!.children.size)
        assertEquals("hierarchy", r.root!!.cls)
        assertEquals("p1", r.pkg)
    }

    @Test fun brokenXmlIsAnUnreadableScreenNotAnException() {
        for (bad in listOf("", "   ", "<hierarchy><node", "not xml at all", "<hierarchy></hierarchy>", "<hierarchy><node text=\"a\" bounds=\"[0,0][1,1]\"></hierarchy>")) {
            val r = UiDump.parse(bad, W, H, pkg = "p", activity = "a")
            assertTrue("'$bad' should be unreadable", r.unreadable)
            assertNull(r.root)
            assertEquals("p", r.pkg)
        }
    }

    @Test fun aDoctypeOrEntityDeclarationIsRefusedWithoutFetchingAnything() {
        val xxe = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><hierarchy><node text=\"&e;\" class=\"a.A\" package=\"p\" bounds=\"[0,0][10,10]\"/></hierarchy>"
        assertTrue(UiDump.parse(xxe, W, H).unreadable)
        val bomb = "<!DOCTYPE lolz [<!ENTITY lol \"lol\"><!ENTITY lol2 \"&lol;&lol;&lol;\">]><hierarchy><node text=\"&lol2;\" class=\"a.A\" package=\"p\" bounds=\"[0,0][10,10]\"/></hierarchy>"
        assertTrue(UiDump.parse(bomb, W, H).unreadable)
    }

    @Test fun controlCharacterReferencesInLabelsDoNotMakeTheDumpUnreadable() {
        val xml = "<hierarchy>" + n("text=\"a&#1;b&#x8;c\" class=\"a.A\" package=\"p\" content-desc=\"\" bounds=\"[0,0][100,100]\"") + "</hierarchy>"
        val r = UiDump.parse(xml, W, H)
        assertFalse(r.unreadable)
        assertEquals("abc", r.root!!.text)
    }

    @Test fun nodeWithoutUsableBoundsGetsZeroAreaSoTheReaderSkipsIt() {
        val xml = "<hierarchy>" + n("text=\"x\" class=\"a.A\" package=\"p\" content-desc=\"\" bounds=\"garbage\"") + "</hierarchy>"
        val r = UiDump.parse(xml, W, H)
        assertNotNull(r.root)
        assertTrue(ScreenReader.compact(r).elements.isEmpty())
    }

    @Test fun editableIsDecidedFromTheClassName() {
        val xml = "<hierarchy>" +
            n("text=\"\" class=\"android.widget.AutoCompleteTextView\" package=\"p\" content-desc=\"\" bounds=\"[0,0][100,100]\"") +
            n("text=\"\" class=\"android.widget.TextView\" package=\"p\" content-desc=\"\" bounds=\"[0,100][100,200]\"") + "</hierarchy>"
        val r = UiDump.parse(xml, W, H).root!!
        assertTrue(r.children[0].editable)
        assertFalse(r.children[1].editable)
    }
}
