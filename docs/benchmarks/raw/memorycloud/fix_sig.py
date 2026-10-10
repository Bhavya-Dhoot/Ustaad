p = r"H:\UStaaf\.claude\worktrees\agent-ac34ad0bc1b4ddd46\app\app\src\main\java\com\swarmos\app\llm\LlamaRuntime.kt"
s = open(p, encoding="utf-8").read()
old = """        env: Map<String, String> = emptyMap(),
    ): Deferred<ServerStatus> {"""
new = """        env: Map<String, String> = emptyMap(),
        readyTimeoutMs: Long = READY_TIMEOUT_MS,
    ): Deferred<ServerStatus> {"""
assert old in s
s = s.replace(old, new, 1)
open(p, "w", encoding="utf-8").write(s)
print("ok")
