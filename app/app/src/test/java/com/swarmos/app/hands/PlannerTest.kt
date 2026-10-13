package com.swarmos.app.hands

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannerTest {
    private val skills = MapSkillBook(FOOD_CARDS)
    private val apps = listOf(
        AppInfo(FOOD, "FakeFood"), AppInfo("com.fake.maps", "FakeMaps"), AppInfo("com.fake.calc", "Calculator"),
    )
    private val domains = listOf("cooking", "money_basics")

    private fun plan(brain: ScriptedBrain, task: String = "order a biryani", list: List<AppInfo> = apps): List<SubTask> =
        runBlocking { Planner(brain, skills).plan(task, list, domains) }

    @Test fun aValidPlanIsParsedWithTheAppResolvedToItsPackage() {
        val brain = ScriptedBrain().reply(
            planJson(
                subJson("KNOWLEDGE", "what goes in a biryani", expert = "Cooking"),
                subJson("PHONE", "order a biryani in the app", expert = "food_delivery", app = "FakeFood"),
                subJson("DATA", "find my address notes"),
                subJson("REPLY", "tell the person"),
            ),
        )
        val p = plan(brain)
        assertEquals(4, p.size)
        assertEquals(SubTask("what goes in a biryani", Kind.KNOWLEDGE, "cooking"), p[0])
        assertEquals(SubTask("order a biryani in the app", Kind.PHONE, "food_delivery", FOOD), p[1])
        assertEquals(Kind.DATA, p[2].kind)
        assertEquals(Kind.REPLY, p[3].kind)
        assertEquals(1, brain.calls.size)
    }

    @Test fun unknownExpertsBecomeEmpty() {
        val brain = ScriptedBrain().reply(
            planJson(
                subJson("KNOWLEDGE", "q", expert = "astrology"),
                subJson("PHONE", "g", expert = "underwater_basketry", app = FOOD),
            ),
        )
        val p = plan(brain)
        assertEquals("", p[0].expert)
        assertEquals("", p[1].expert)
    }

    @Test fun anAppIsFoundByPackageThenExactLabelThenAContainedLabel() {
        val list = listOf(AppInfo("com.a.swiggy", "Swiggy Instamart"), AppInfo("in.swiggy.android", "Swiggy"), AppInfo("com.b", "Maps Pro Navigator"))
        fun resolve(name: String) = AppMatch.resolve(name, list)?.pkg
        assertEquals("com.a.swiggy", resolve("com.a.swiggy"))
        assertEquals("in.swiggy.android", resolve("swiggy"))
        assertEquals("com.b", resolve("navigator"))
        assertEquals("in.swiggy.android", resolve("Swiggy food delivery"))
        assertEquals(null, resolve("zomato"))
        assertEquals(null, resolve(""))
    }

    @Test fun aPhoneSubtaskWithoutAnAppIsKeptWithAnEmptyApp() {
        val p = plan(ScriptedBrain().reply(planJson(subJson("PHONE", "set an alarm"))))
        assertEquals(listOf(SubTask("set an alarm", Kind.PHONE)), p)
    }

    @Test fun aReplyAroundTheJsonIsStillRead() {
        val p = plan(ScriptedBrain().reply("Here is the plan:\n" + planJson(subJson("PHONE", "g", app = FOOD)) + "\nDone."))
        assertEquals(FOOD, p[0].app)
    }

    @Test fun anInvalidPlanIsAskedForOnceMoreAndTheSecondAnswerIsUsed() {
        val brain = ScriptedBrain().reply(
            planJson(subJson("PHONE", "g", app = "Nonexistent App")),
            planJson(subJson("PHONE", "g", app = "FakeFood")),
        )
        val p = plan(brain)
        assertEquals(FOOD, p[0].app)
        assertEquals(2, brain.calls.size)
        assertFalse(brain.calls[0].user.contains("could not be used"))
        assertTrue(brain.calls[1].user, brain.calls[1].user.contains("could not be used") && brain.calls[1].user.contains("Nonexistent App"))
    }

    @Test fun eachKindOfBadAnswerIsRepaired() {
        val bad = listOf(
            "not json at all",
            """{"subtasks":[]}""",
            """{"plan":[]}""",
            planJson(subJson("DANCE", "g")),
            planJson(subJson("PHONE", "", app = FOOD)),
            """{"subtasks":["x"]}""",
        )
        for (b in bad) {
            val brain = ScriptedBrain().reply(b, planJson(subJson("REPLY", "ok")))
            val p = plan(brain)
            assertEquals(b, listOf(Kind.REPLY), p.map { it.kind })
            assertEquals(2, brain.calls.size)
        }
    }

    @Test fun twoInvalidAnswersFallBackToOnePhoneSubtaskWithTheTaskAsGoal() {
        val brain = ScriptedBrain().reply("nope", planJson(subJson("PHONE", "g", app = "Nothing Installed")))
        val p = plan(brain, task = "do the thing")
        assertEquals(listOf(SubTask("do the thing", Kind.PHONE, "", "")), p)
        assertEquals(2, brain.calls.size)
    }

    @Test fun aModelThatThrowsTwiceAlsoFallsBack() {
        val brain = ScriptedBrain() // empty script: every call throws
        val p = plan(brain, task = "t")
        assertEquals(listOf(SubTask("t", Kind.PHONE)), p)
        assertEquals(2, brain.calls.size)
    }

    @Test fun aThrowThenAGoodAnswerIsUsed() {
        val brain = ScriptedBrain().replyWith({ throw IllegalStateException("device busy") }, { planJson(subJson("REPLY", "ok")) })
        assertEquals(listOf(Kind.REPLY), plan(brain).map { it.kind })
    }

    @Test fun atMostEightSubtasksAreKept() {
        val subs = (1..12).map { subJson("KNOWLEDGE", "q$it") }.toTypedArray()
        val p = plan(ScriptedBrain().reply(planJson(*subs)))
        assertEquals(8, p.size)
        assertEquals("q8", p.last().goal)
    }

    @Test fun thePromptHoldsTheTaskCategoriesWithTheirAppsAndAtMostFortyOtherLabels() {
        val many = apps + (0 until 60).map { AppInfo("com.other.app$it", "Other%02d".format(it)) }
        val brain = ScriptedBrain().reply(planJson(subJson("REPLY", "x")))
        plan(brain, task = "order a biryani", list = many)
        val c = brain.calls.single()
        assertTrue(c.user.contains("Task: order a biryani"))
        assertTrue(c.user.contains("food_delivery: FakeFood ($FOOD)"))
        val others = c.user.lines().first { it.startsWith("Other installed apps: ") }.removePrefix("Other installed apps: ").split(", ")
        assertEquals(40, others.size)
        assertTrue(c.user.contains("Other00") && c.user.contains("Other37"))
        assertFalse(c.user.contains("Other38"))
        assertTrue(c.user.contains("cooking, money_basics"))
        assertTrue(c.user.contains("Calculator"))
    }

    @Test fun planningMayUseTheClusterAndTheStrongestTier() {
        val both = ScriptedBrain().reply(planJson(subJson("REPLY", "x")))
        plan(both)
        assertEquals(Tier.STRONG, both.calls.single().tier)
        assertFalse(both.calls.single().onPhoneOnly)
        assertSame(Planner.SCHEMA, both.calls.single().schema)
        val fastOnly = ScriptedBrain(strong = false).reply(planJson(subJson("REPLY", "x")))
        plan(fastOnly)
        assertEquals(Tier.FAST, fastOnly.calls.single().tier)
    }
}
