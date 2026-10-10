import os
base = r"H:\UStaaf\.claude\worktrees\agent-ac34ad0bc1b4ddd46\app\app\src"
main = os.path.join(base, "main", "java", "com", "swarmos", "app", "memcloud")
test = os.path.join(base, "test", "java", "com", "swarmos", "app", "memcloud")

def edit(d, name, pairs):
    p = os.path.join(d, name)
    s = open(p, encoding="utf-8").read()
    for old, new in pairs:
        if old not in s:
            raise SystemExit("missing in %s: %s" % (name, old[:70]))
        s = s.replace(old, new, 1)
    open(p, "w", encoding="utf-8").write(s)

edit(main, "SplitPlanner.kt", [(
"""            if (headHere) bytes += info.outputHeadBytes""", """            if (headHere) {
                if (info.hasUntiedHead) bytes += info.outputHeadBytes
                else if (i != 0) bytes += info.inputBytes // tied head: the output layer on another phone needs its own copy of the embedding table (measured: 1.6 GB received for 1.05 GB of layers)
            }""")])
edit(test, "SplitAndLogTest.kt", [(
"""        assertEquals(16 * 380_000_000L + 200_000_000L, p.shares[2].weightBytes)
    }""", """        // tied head: the last phone also receives a copy of the embedding table for the output layer
        assertEquals(16 * 380_000_000L + 200_000_000L + 300_000_000L, p.shares[2].weightBytes)
    }""")])
print("ok")
