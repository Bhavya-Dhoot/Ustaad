import re
p = r"H:\UStaaf\.claude\worktrees\agent-ac34ad0bc1b4ddd46\app\app\src\main\java\com\swarmos\app\llm\LlamaRuntime.kt"
s = open(p, encoding="utf-8").read()

# conflict block: keep master's side
a = s.index("<<<<<<< HEAD")
m = s.index("=======", a)
e = s.index(">>>>>>> master", m)
end_of_marker = s.index("\n", e) + 1
master_side = s[m + len("=======\n"):e]
s = s[:a] + master_side + s[end_of_marker:]

# the signature line before the conflict had "readyTimeoutMs" param from my side; master side starts with "    ): Deferred<ServerStatus> {"
# StartConfig carries the timeout
s = s.replace(
    "private class StartConfig(val modelPath: String?, val extraArgs: List<String>, val port: Int, val env: Map<String, String>)",
    "private class StartConfig(val modelPath: String?, val extraArgs: List<String>, val port: Int, val env: Map<String, String>, val readyTimeoutMs: Long = READY_TIMEOUT_MS)")
s = s.replace("val cfg = StartConfig(modelPath, extraArgs, port, env)", "val cfg = StartConfig(modelPath, extraArgs, port, env, readyTimeoutMs)")
s = s.replace("val deadline = System.nanoTime() + readyTimeoutMs * 1_000_000", "val deadline = System.nanoTime() + cfg.readyTimeoutMs * 1_000_000")
s = s.replace('"not ready after ${readyTimeoutMs / 1000} s;', '"not ready after ${cfg.readyTimeoutMs / 1000} s;')
open(p, "w", encoding="utf-8").write(s)
print("<<<<" in s, ">>>>" in s)
