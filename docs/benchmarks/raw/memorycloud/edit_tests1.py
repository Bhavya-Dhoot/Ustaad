p = r"H:\UStaaf\.claude\worktrees\agent-ac34ad0bc1b4ddd46\app\app\src\test\java\com\swarmos\app\memcloud\SplitCoordinatorTest.kt"
s = open(p, encoding="utf-8").read()
pairs = [
("""    private fun dto(state: String) = RpcStateDto(state, 50052, "10.0.0.2", 1, 100, 90, 0, 0, null, emptyList(), 0, 9000, emptyList())""",
 """    private fun dto(state: String) = RpcStateDto(
        state = state, port = 50052, bind = "10.0.0.2", pid = 1, rssMb = 100, anonMb = 90, cachedBytes = 0, cachedFiles = 0,
        reason = null, lastLog = emptyList(), thermalStatus = 0, ramAvailMb = 9000, args = emptyList(),
    )"""),
("""        awaitCall("activity cleared") { lastActivityIsClear() }
        assertEquals(0, env.count("rpcRenew").coerceAtMost(0))
    }""", """        awaitCall("activity cleared") { lastActivityIsClear() }
    }"""),
("""        assertEquals(1, env.count("stopLocal chat").coerceAtLeast(1)) // llama-server killed
""", """        assertEquals("llama-server was killed", ServerState.STOPPED, env.local[Role.CHAT]!!.state)
"""),
("""        assertEquals(after, env.count("rpcRenew"))
        assertTrue(lastActivityIsClear().not() || true)
    }""", """        assertEquals(after, env.count("rpcRenew"))
    }"""),
("""        awaitCall("this phone restored") { env.count("restoreLocal chat small.gguf") >= 1 }
        assertTrue(s.pendingRestore.isEmpty() || true)
    }""", """        awaitCall("this phone restored") { env.count("restoreLocal chat small.gguf") >= 1 }
        assertTrue(c.state.value.pendingRestore.isEmpty())
    }"""),
]
for a, b in pairs:
    assert a in s, a[:60]
    s = s.replace(a, b, 1)
open(p, "w", encoding="utf-8").write(s)
print("ok")
