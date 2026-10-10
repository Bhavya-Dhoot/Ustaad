p = r"H:\UStaaf\.claude\worktrees\agent-ac34ad0bc1b4ddd46\docs\benchmarks\2026-10-10-memorycloud-inapp.md"
s = open(p, encoding="utf-8").read()
pairs = [
("## What the unit tests cover (383 tests in the module, all pass; memcloud 44)", "## What the unit tests cover (417 tests in the app, 0 failures; 45 of them in memcloud)"),
("Not verified on a phone: the 10 s watchdog (unit-less code path, not exercised; the force-stop test below killed the whole app, rpc-server included).", "Not verified on a phone: the 10 s watchdog (the force-stop test below killed the whole worker app, rpc-server included, so the watchdog never had to act)."),
]
for a, b in pairs:
    assert a in s, a[:50]
    s = s.replace(a, b, 1)
open(p, "w", encoding="utf-8").write(s)
print("ok")
