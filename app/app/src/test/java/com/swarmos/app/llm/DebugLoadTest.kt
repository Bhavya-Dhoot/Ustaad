package com.swarmos.app.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Debug-only experiments on a model load: extra tuning flags and GGML_* environment, narrowly checked. */
class DebugLoadTest {
    private fun check(args: List<String> = emptyList(), env: Map<String, String> = emptyMap(), debug: Boolean = true) = DebugLoad.check(args, env, debug)

    @Test fun theExperimentsTheAuditNamesGoThroughOnADebugBuild() {
        val r = check(
            args = listOf("-ub", "1024", "--spec-type", "ngram-simple", "--spec-ngram-size-n", "12", "--spec-draft-n-max", "8"),
            env = mapOf("GGML_OPENCL_Q4K_DENSE_DP4A" to "1"),
        ) as DebugLoad.Result.Ok
        assertEquals(listOf("-ub", "1024", "--spec-type", "ngram-simple", "--spec-ngram-size-n", "12", "--spec-draft-n-max", "8"), r.accepted.args)
        assertEquals(mapOf("GGML_OPENCL_Q4K_DENSE_DP4A" to "1"), r.accepted.env)
    }

    @Test fun nothingExtraIsAlwaysFineEvenOnARelease() {
        assertTrue(check(debug = false) is DebugLoad.Result.Ok)
        assertTrue(check(debug = true) is DebugLoad.Result.Ok)
    }

    @Test fun aReleaseOrDemoBuildRefusesAnyExtra() {
        assertTrue(check(args = listOf("-ub", "1024"), debug = false) is DebugLoad.Result.Refused)
        assertTrue(check(env = mapOf("GGML_OPENCL_Q4K_DENSE_DP4A" to "1"), debug = false) is DebugLoad.Result.Refused)
    }

    @Test fun onlyGgmlSwitchesAndShortValuesAreAcceptedAsEnvironment() {
        for (name in listOf("LD_PRELOAD", "PATH", "ggml_x", "GGML_", "GGML_a", "XGGML_A", "GGML-A")) {
            assertTrue("$name", check(env = mapOf(name to "1")) is DebugLoad.Result.Refused)
        }
        for (value in listOf("", "/data/x", "a b", "x".repeat(65), "1;rm")) {
            assertTrue("'$value'", check(env = mapOf("GGML_A" to value)) is DebugLoad.Result.Refused)
        }
        assertTrue(check(env = mapOf("GGML_OPENCL_FA_TUNE" to "off")) is DebugLoad.Result.Ok)
    }

    @Test fun atMostEightVariablesAndSixteenArgs() {
        assertTrue(check(env = (1..8).associate { "GGML_V$it" to "1" }) is DebugLoad.Result.Ok)
        assertTrue(check(env = (1..9).associate { "GGML_V$it" to "1" }) is DebugLoad.Result.Refused)
        assertTrue(check(args = List(16) { if (it % 2 == 0) "-b" else "512" }) is DebugLoad.Result.Ok)
        assertTrue(check(args = List(17) { if (it % 2 == 0) "-b" else "512" }) is DebugLoad.Result.Refused)
    }

    @Test fun noPathUrlModelHostKeyOrThreadFlagGetsThrough() {
        for (flag in listOf("-m", "--model", "--host", "--port", "--api-key", "--api-key-file", "-hf", "--spec-draft-model", "-md", "--spec-draft-hf",
            "--log-file", "--rpc", "-ngl", "-t", "-c", "-np", "--path", "--ssl-key-file", "--lora", "--tools", "--slot-save-path", "-mu")) {
            assertTrue(flag, check(args = listOf(flag, "1")) is DebugLoad.Result.Refused)
        }
        assertTrue(check(args = listOf("-b", "/data/local/tmp/x")) is DebugLoad.Result.Refused)
        assertTrue(check(args = listOf("512")) is DebugLoad.Result.Refused) // a value with no flag
        assertTrue(check(args = listOf("-ub", "-1")) is DebugLoad.Result.Refused)
    }
}
