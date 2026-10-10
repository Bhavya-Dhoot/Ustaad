p = r"H:\UStaaf\.claude\worktrees\agent-ac34ad0bc1b4ddd46\app\app\src\main\java\com\swarmos\app\llm\LlamaRuntime.kt"
s = open(p, encoding="utf-8").read()

# take master's side of every hunk
while "<<<<<<< HEAD" in s:
    a = s.index("<<<<<<< HEAD")
    m = s.index("=======\n", a)
    e = s.index(">>>>>>> master", m)
    eol = s.index("\n", e) + 1
    s = s[:a] + s[m + len("=======\n"):e] + s[eol:]

# carry the ready timeout in the config
old = "val env: Map<String, String>, val cpuOnly: Boolean = false)"
assert old in s
s = s.replace(old, "val env: Map<String, String>, val cpuOnly: Boolean = false, val readyTimeoutMs: Long = READY_TIMEOUT_MS)", 1)
old = "val cfg = StartConfig(modelPath, extraArgs, port, env, readyTimeoutMs)"
assert old in s
s = s.replace(old, "val cfg = StartConfig(modelPath, extraArgs, port, env, readyTimeoutMs = readyTimeoutMs)", 1)
old = "val deadlineMs = if (gpu) GPU_READY_TIMEOUT_MS else READY_TIMEOUT_MS"
assert old in s
s = s.replace(old, "val deadlineMs = if (gpu) GPU_READY_TIMEOUT_MS else cfg.readyTimeoutMs", 1)
open(p, "w", encoding="utf-8").write(s)
print("<<<<" in s, ">>>>" in s)
