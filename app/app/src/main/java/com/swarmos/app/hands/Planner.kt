package com.swarmos.app.hands

import com.swarmos.app.core.SwarmJson
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Finds an installed app from what the model wrote: the package, then the exact label, then a label that contains it. */
object AppMatch {
    fun resolve(name: String, apps: List<AppInfo>): AppInfo? {
        val n = name.trim()
        if (n.isEmpty()) return null
        apps.firstOrNull { it.pkg.equals(n, ignoreCase = true) }?.let { return it }
        apps.firstOrNull { it.label.equals(n, ignoreCase = true) }?.let { return it }
        apps.filter { it.label.contains(n, ignoreCase = true) }.minByOrNull { it.label.length }?.let { return it }
        // the model wrote more than the label ("Swiggy food delivery")
        return apps.filter { it.label.length >= 4 && n.contains(it.label, ignoreCase = true) }.maxByOrNull { it.label.length }
    }
}

/**
 * Turns a task into subtasks with one completion on the strongest tier there is. The prompt holds the task, the category names
 * with the installed apps of each and some other app labels: never text read from a screen.
 */
class Planner(private val brain: Brain, private val skills: SkillBook) {

    suspend fun plan(task: String, apps: List<AppInfo>, domains: List<String>): List<SubTask> {
        val tier = if (brain.has(Tier.STRONG)) Tier.STRONG else Tier.FAST
        var problem = ""
        repeat(2) {
            val reply = try {
                brain.complete(tier, SYSTEM, userPrompt(task, apps, domains, problem), SCHEMA, MAX_TOKENS, onPhoneOnly = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problem = "the model could not answer (${e.message})"
                return@repeat
            }
            when (val r = validate(reply, apps, domains)) {
                is Checked.Ok -> return r.plan
                is Checked.Bad -> problem = r.why
            }
        }
        return listOf(SubTask(goal = task, kind = Kind.PHONE))
    }

    private fun userPrompt(task: String, apps: List<AppInfo>, domains: List<String>, problem: String): String {
        val sb = StringBuilder()
        sb.append("Task: ").append(task).append('\n')
        val byCat = LinkedHashMap<String, MutableList<AppInfo>>()
        val other = ArrayList<AppInfo>()
        for (a in apps) {
            val c = skills.categoryOf(a)
            if (c == null) other += a else byCat.getOrPut(c) { ArrayList() } += a
        }
        sb.append("Installed apps by category:\n")
        for (c in skills.categories()) {
            val list = byCat[c].orEmpty()
            sb.append("- ").append(c).append(": ").append(if (list.isEmpty()) "(none installed)" else list.joinToString(", ") { "${it.label} (${it.pkg})" }).append('\n')
        }
        if (other.isNotEmpty()) sb.append("Other installed apps: ").append(other.take(MAX_OTHER).joinToString(", ") { it.label }).append('\n')
        sb.append("Knowledge experts: ").append(if (domains.isEmpty()) "(none)" else domains.joinToString(", ")).append('\n')
        if (problem.isNotEmpty()) sb.append("\nYour last plan could not be used: ").append(problem).append(". Answer again.\n")
        return sb.toString()
    }

    private sealed interface Checked {
        data class Ok(val plan: List<SubTask>) : Checked
        data class Bad(val why: String) : Checked
    }

    private fun validate(reply: String, apps: List<AppInfo>, domains: List<String>): Checked {
        val a = reply.indexOf('{')
        val b = reply.lastIndexOf('}')
        val text = if (a >= 0 && b > a) reply.substring(a, b + 1) else reply
        val root = runCatching { SwarmJson.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: return Checked.Bad("it was not a JSON object")
        val arr = root["subtasks"] as? JsonArray ?: return Checked.Bad("it had no subtasks list")
        if (arr.isEmpty()) return Checked.Bad("the subtasks list was empty")
        val out = ArrayList<SubTask>()
        for (item in arr.take(MAX_SUBTASKS)) {
            val o = item as? JsonObject ?: return Checked.Bad("a subtask was not an object")
            fun s(k: String) = (o[k] as? JsonPrimitive)?.content?.trim().orEmpty()
            val goal = s("goal").take(300)
            if (goal.isEmpty()) return Checked.Bad("a subtask had no goal")
            val kind = Kind.entries.firstOrNull { it.name == s("kind").uppercase() } ?: return Checked.Bad("a subtask had the kind '${s("kind")}'")
            when (kind) {
                Kind.PHONE -> {
                    val name = s("app")
                    val app = if (name.isEmpty()) "" else (AppMatch.resolve(name, apps)?.pkg ?: return Checked.Bad("the app '$name' is not installed"))
                    val cat = skills.categories().firstOrNull { it.equals(s("expert"), ignoreCase = true) }.orEmpty()
                    out += SubTask(goal, kind, cat, app)
                }
                Kind.KNOWLEDGE -> out += SubTask(goal, kind, domains.firstOrNull { it.equals(s("expert"), ignoreCase = true) }.orEmpty())
                Kind.DATA, Kind.REPLY -> out += SubTask(goal, kind)
            }
        }
        return Checked.Ok(out)
    }

    companion object {
        const val MAX_SUBTASKS = 8
        const val MAX_OTHER = 40
        const val MAX_TOKENS = 500

        private const val SYSTEM = "You split a task for a phone assistant into subtasks and answer with one JSON object only.\n" +
            "kind PHONE: done by operating an installed app. app is the name of that installed app, expert is its category.\n" +
            "kind KNOWLEDGE: answered from knowledge, no phone needed. expert is one of the knowledge experts, or empty.\n" +
            "kind DATA: look something up in the person's own files and notes.\n" +
            "kind REPLY: the last subtask, when the person needs a written answer built from the earlier results.\n" +
            "Use only apps from the lists. Fewer subtasks are better; one is fine. At most $MAX_SUBTASKS. Fill every field; use \"\" when none fits."

        val SCHEMA: JsonObject = buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put(
                        "subtasks",
                        buildJsonObject {
                            put("type", "array")
                            put("minItems", 1)
                            put("maxItems", MAX_SUBTASKS)
                            put(
                                "items",
                                buildJsonObject {
                                    put("type", "object")
                                    put(
                                        "properties",
                                        buildJsonObject {
                                            put("goal", buildJsonObject { put("type", "string") })
                                            put(
                                                "kind",
                                                buildJsonObject {
                                                    put("type", "string")
                                                    put("enum", JsonArray(Kind.entries.map { JsonPrimitive(it.name) }))
                                                },
                                            )
                                            put("expert", buildJsonObject { put("type", "string") })
                                            put("app", buildJsonObject { put("type", "string") })
                                        },
                                    )
                                    put("required", JsonArray(listOf("goal", "kind", "expert", "app").map { JsonPrimitive(it) }))
                                },
                            )
                        },
                    )
                },
            )
            put("required", JsonArray(listOf(JsonPrimitive("subtasks"))))
        }
    }
}
