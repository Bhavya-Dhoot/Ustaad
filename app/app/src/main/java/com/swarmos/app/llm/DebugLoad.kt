package com.swarmos.app.llm

/**
 * Experiments without a rebuild: a debug build's `POST /v1/models/load` accepts `args` (llama-server tuning flags) and `env`
 * (environment variables of the child) for that one load. Nothing here is ever a default, a release or demo build ignores both
 * fields (a release or demo build refuses a request that carries them), and both are checked against narrow rules because the
 * route is reachable by any paired phone: no path, no URL, no model, host or key flag can get through, and a value is a short
 * word or number.
 * Pure, for unit tests.
 */
object DebugLoad {
    const val MAX_ENV = 8
    const val MAX_ARGS = 16

    /** Environment names of ggml's own switches only (for example GGML_OPENCL_Q4K_DENSE_DP4A). */
    val ENV_NAME = Regex("^GGML_[A-Z0-9_]+$")

    /** A value is a short word or number: letters, digits and . _ , : + - only, so there is no path in it. */
    val VALUE = Regex("^[A-Za-z0-9._,:+-]{1,64}$")

    private val FLAGS = setOf(
        "-b", "--batch-size", "-ub", "--ubatch-size", "-ctxcp", "--ctx-checkpoints", "-cram", "--cache-ram",
        "-fa", "--flash-attn", "--spec-type", "--spec-default",
        "--spec-draft-n-max", "--spec-draft-n-min", "--spec-draft-p-min", "--spec-draft-p-split",
        "--draft", "--draft-n", "--draft-max", "--draft-min", "--draft-n-min", "--draft-p-min", "--draft-p-split",
    )

    /** Every `--spec-ngram-*` flag of this llama.cpp takes a small number. */
    private val FLAG_PREFIXES = listOf("--spec-ngram-")

    class Accepted(val args: List<String>, val env: Map<String, String>)

    /** The accepted extras, or the plain reason the request is refused. */
    sealed interface Result {
        data class Ok(val accepted: Accepted) : Result
        data class Refused(val reason: String) : Result
    }

    private fun flagAllowed(flag: String) = flag in FLAGS || FLAG_PREFIXES.any { flag.startsWith(it) }

    /** Checks [args] and [env] of a load request on a build where [debugBuild] says whether debug routes exist. Empty extras always pass. */
    fun check(args: List<String>, env: Map<String, String>, debugBuild: Boolean): Result {
        if (args.isEmpty() && env.isEmpty()) return Result.Ok(Accepted(emptyList(), emptyMap()))
        if (!debugBuild) return Result.Refused("args and env on a load are for debug builds only")
        if (args.size > MAX_ARGS) return Result.Refused("at most $MAX_ARGS args")
        if (env.size > MAX_ENV) return Result.Refused("at most $MAX_ENV env variables")
        for ((name, value) in env) {
            if (!ENV_NAME.matches(name)) return Result.Refused("env name $name must match ${ENV_NAME.pattern}")
            if (!VALUE.matches(value)) return Result.Refused("env value of $name must be 1 to 64 characters of letters, digits and . _ , : + -")
        }
        var previousWasFlag = false
        for (token in args) {
            if (token.startsWith("-")) {
                if (!flagAllowed(token)) return Result.Refused("flag $token is not allowed (tuning flags only)")
                previousWasFlag = true
            } else {
                if (!previousWasFlag) return Result.Refused("value $token has no flag before it")
                if (!VALUE.matches(token)) return Result.Refused("value $token must be 1 to 64 characters of letters, digits and . _ , : + -")
                previousWasFlag = false
            }
        }
        return Result.Ok(Accepted(args, env))
    }
}
