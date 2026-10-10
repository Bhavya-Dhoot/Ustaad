p = r"H:\UStaaf\.claude\worktrees\agent-ac34ad0bc1b4ddd46\docs\benchmarks\2026-10-10-memorycloud-inapp.md"
s = open(p, encoding="utf-8").read()
old1 = "# driver C (state route `args`): llama-server CPU build, port 8080 on 127.0.0.1, defaults then the planner's flags\n"
old2 = "libllama-server.so -m .../models/Qwen3-30B-A3B-Instruct-2507-Q4_K_M.gguf --host 127.0.0.1 --port 8080 -t 6 -c 8192 -np 2 --reasoning off --no-webui \\\n"
assert old1 in s and old2 in s
s = s.replace(old1, "# driver C: llama-server CPU build (a split server never runs on the GPU build), port 8080 on 127.0.0.1, the app's chat defaults, then the planner's flags\n# (the flags on the second line are the `Loading: llama-server ...` line of the state route, copied from the run; the defaults were not read back)\n", 1)
s = s.replace(old2, "libllama-server.so -m .../models/Qwen3-30B-A3B-Instruct-2507-Q4_K_M.gguf --host 127.0.0.1 --port 8080 <app defaults> \\\n", 1)
open(p, "w", encoding="utf-8").write(s)
print("ok")
