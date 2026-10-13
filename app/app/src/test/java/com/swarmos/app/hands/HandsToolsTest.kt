package com.swarmos.app.hands

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandsToolsTest {
    private fun mapped(json: String): HandsTools.Mapped = HandsTools.parseStep(json)!!.mapped
    private fun callOf(json: String): ToolCall = (mapped(json) as HandsTools.Mapped.Call).call
    private fun badOf(json: String): String = (mapped(json) as HandsTools.Mapped.Bad).message

    @Test fun theToolListHasTheSixteenToolsWithTheirRisks() {
        assertEquals(
            listOf("read_screen", "tap", "type", "press_enter", "scroll", "back", "home", "open_app", "find_app", "open_link", "wait", "ask_user", "ask_expert", "search_my_data", "done", "fail"),
            HandsTools.names,
        )
        val acts = HandsTools.specs.filter { it.risk == Risk.ACT }.map { it.name }.toSet()
        assertEquals(setOf("tap", "type", "press_enter", "scroll", "back", "home", "open_app", "open_link"), acts)
        for (r in listOf("read_screen", "find_app", "wait", "ask_user", "ask_expert", "search_my_data", "done", "fail")) {
            assertEquals(r, Risk.READ, HandsTools.spec(r)!!.risk)
        }
    }

    @Test fun everyToolParameterSchemaIsFlat() {
        for (s in HandsTools.specs) {
            assertEquals("object", (s.params["type"] as JsonPrimitive).content)
            val props = s.params["properties"] as JsonObject
            for ((k, v) in props) {
                val type = ((v as JsonObject)["type"] as JsonPrimitive).content
                assertTrue("${s.name}.$k", type == "string" || type == "integer")
            }
            val req = (s.params["required"] as JsonArray).map { (it as JsonPrimitive).content }
            assertTrue(props.keys.containsAll(req))
        }
    }

    @Test fun theStepSchemaIsOneFlatObjectWithNoOneOfOrRef() {
        val schema = HandsTools.stepSchema
        val text = schema.toString()
        assertFalse(text.contains("oneOf")); assertFalse(text.contains("\$ref")); assertFalse(text.contains("anyOf"))
        assertEquals(listOf("note", "tool"), (schema["required"] as JsonArray).map { (it as JsonPrimitive).content })
        val props = schema["properties"] as JsonObject
        assertEquals(setOf("note", "tool", "id", "text", "direction", "app"), props.keys)
        val toolEnum = ((props["tool"] as JsonObject)["enum"] as JsonArray).map { (it as JsonPrimitive).content }
        assertEquals(HandsTools.names, toolEnum)
        val dir = ((props["direction"] as JsonObject)["enum"] as JsonArray).map { (it as JsonPrimitive).content }
        assertEquals(listOf("down", "up"), dir)
        assertEquals("integer", ((props["id"] as JsonObject)["type"] as JsonPrimitive).content)
    }

    @Test fun theToolLinesForThePromptNameEveryToolAndMarkOptionalArguments() {
        val lines = HandsTools.promptLines()
        for (n in HandsTools.names) assertTrue(n, lines.contains("- $n"))
        assertTrue(lines.contains("- scroll {direction, id?}"))
    }

    // ------------------------------------------------------------ mapping

    @Test fun aCompleteStepMapsToACall() {
        val c = callOf(step("tap", id = 12))
        assertEquals("tap", c.tool)
        assertEquals("12", (c.args["id"] as JsonPrimitive).content)
        val t = callOf(step("type", id = 3, text = "biryani"))
        assertEquals("biryani", (t.args["text"] as JsonPrimitive).content)
        val s = callOf(step("scroll", direction = "up", id = 5))
        assertEquals("up", (s.args["direction"] as JsonPrimitive).content)
        assertEquals("5", (s.args["id"] as JsonPrimitive).content)
    }

    @Test fun aMissingArgumentIsAMessageForTheModelNotAnException() {
        assertEquals("tap needs id", badOf(step("tap")))
        assertEquals("type needs id", badOf(step("type", text = "x")))
        assertEquals("type needs id and text", badOf(step("type")))
        assertEquals("wait needs id", badOf(step("wait")))
        assertEquals("open_app needs app", badOf(step("open_app")))
        assertEquals("find_app needs text", badOf(step("find_app")))
        assertEquals("open_link needs text", badOf(step("open_link", text = "  ")))
        assertEquals("ask_expert needs app and text", badOf(step("ask_expert")))
        assertEquals("done needs text", badOf(step("done")))
        assertEquals("fail needs text", badOf(step("fail", text = "")))
        assertEquals("scroll needs direction (down or up)", badOf(step("scroll")))
        assertEquals("scroll needs direction (down or up)", badOf(step("scroll", direction = "left")))
    }

    @Test fun typingAnEmptyStringIsAllowedBecauseItClearsAField() {
        val c = callOf(step("type", id = 2, text = ""))
        assertEquals("", (c.args["text"] as JsonPrimitive).content)
    }

    @Test fun unknownOrMissingToolIsAMessageListingTheTools() {
        assertTrue(badOf("""{"note":"x","tool":"format_disk"}""").contains("unknown tool 'format_disk'"))
        val noTool = HandsTools.parseStep("""{"note":"x","tool":""}""")!!.mapped as HandsTools.Mapped.Bad
        assertTrue(noTool.message.contains("no tool given"))
    }

    @Test fun waitIsClampedToTenSeconds() {
        assertEquals("10", (callOf(step("wait", id = 99)).args["id"] as JsonPrimitive).content)
        assertEquals("1", (callOf(step("wait", id = 0)).args["id"] as JsonPrimitive).content)
        assertEquals("4", (callOf(step("wait", id = 4)).args["id"] as JsonPrimitive).content)
    }

    @Test fun argumentsThatTheToolDoesNotTakeAreDropped() {
        val c = callOf(step("home", id = 7, text = "x", app = "y"))
        assertTrue(c.args.isEmpty())
    }

    @Test fun anIdWrittenAsAStringOrWholeNumberIsAccepted() {
        assertEquals("5", (callOf("""{"note":"","tool":"tap","id":"5"}""").args["id"] as JsonPrimitive).content)
        assertEquals("6", (callOf("""{"note":"","tool":"tap","id":6.0}""").args["id"] as JsonPrimitive).content)
        assertEquals("tap needs id", badOf("""{"note":"","tool":"tap","id":"abc"}"""))
    }

    // ------------------------------------------------------------ parsing the reply

    @Test fun aReplyWrappedInTextIsStillFound() {
        val p = HandsTools.parseStep("Sure! Here is my step:\n" + step("press_enter", note = "search now") + "\nHope that helps.")!!
        assertEquals("search now", p.note)
        assertEquals("press_enter", (p.mapped as HandsTools.Mapped.Call).call.tool)
    }

    @Test fun aReplyCutOffByTheTokenCapStillGivesTheToolAndItsArguments() {
        val p = HandsTools.parseStep("""{"note": "typing the dish", "tool": "type", "id": 4, "text": "biryani", "direct""")!!
        val c = (p.mapped as HandsTools.Mapped.Call).call
        assertEquals("type", c.tool)
        assertEquals("4", (c.args["id"] as JsonPrimitive).content)
        assertEquals("biryani", (c.args["text"] as JsonPrimitive).content)
    }

    @Test fun textWithoutAnyToolIsNull() {
        assertNull(HandsTools.parseStep("I think we should tap the button."))
        assertNull(HandsTools.parseStep(""))
        assertNull(HandsTools.parseStep("{\"note\":\"only a note\"}"))
    }

    // ------------------------------------------------------------ links

    @Test fun onlyTheListedSchemesMayBeOpened() {
        for (ok in listOf(
            "https://example.com/a?b=c", "http://example.com", "geo:0,0?q=biryani", "tel:+919800000000", "mailto:a@b.c", "sms:12345?body=hi",
            "market://details?id=com.example", "HTTPS://EXAMPLE.COM", "  https://example.com  ",
        )) assertTrue(ok, HandsTools.linkAllowed(ok))
    }

    @Test fun everyOtherSchemeIsRefused() {
        for (bad in listOf(
            "intent://scan/#Intent;scheme=zxing;end", "file:///sdcard/x", "content://contacts/people", "javascript:alert(1)",
            "ustaad://do?q=order", "upi://pay?pa=a@b&am=100", "data:text/html,hi", "ftp://x", "tg://resolve?domain=x",
            "android-app://com.example", "//example.com", "example.com", "", "   ", ":https://x", "http//x",
        )) assertFalse(bad, HandsTools.linkAllowed(bad))
    }

    @Test fun controlCharactersAndSchemeTricksAreRefused() {
        for (bad in listOf("jav\tascript:alert(1)", "java\nscript:alert(1)", "https://a.b/\u0000x", "https://a.b/\r\nHost: x", "\u0000https://x", "ht​tps://x")) {
            assertFalse(bad.replace("\u0000", "<nul>"), HandsTools.linkAllowed(bad))
        }
        assertFalse(HandsTools.linkAllowed("https://x/" + "a".repeat(2100)))
    }

    @Test fun linkSchemeIsReadFromTheStartOnly() {
        assertEquals("https", HandsTools.linkScheme("HTTPS://x"))
        assertNull(HandsTools.linkScheme("x https://y"))
        assertNotNull(HandsTools.linkScheme("intent://x"))
    }

    @Test fun theStepSchemaTextBoundsThemselvesForTheTokenCap() {
        val props = HandsTools.stepSchema["properties"] as JsonObject
        assertEquals(HandsTools.NOTE_MAX.toString(), ((props["note"] as JsonObject)["maxLength"] as JsonPrimitive).content)
    }
}
