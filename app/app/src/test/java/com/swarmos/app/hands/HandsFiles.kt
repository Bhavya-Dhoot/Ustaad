package com.swarmos.app.hands

import java.io.File

/** The repository root, found by walking up from the folder the tests run in (Gradle runs them in app/app). Null when it is not above. */
fun repoRoot(): File? {
    var d: File? = File("").absoluteFile
    while (d != null) {
        if (File(d, "docs/benchmarks").isDirectory && File(d, "app/app/src/main/assets/hands").isDirectory) return d
        d = d.parentFile
    }
    return null
}

/** The skill and expert cards the app ships, read straight from the assets folder. */
fun handsAssets(): File? = repoRoot()?.resolve("app/app/src/main/assets/hands")

/** Cards of one folder (`skills` or `experts`) by name without `.md`. */
fun readCards(dir: String): Map<String, String> {
    val root = requireNotNull(handsAssets()) { "assets/hands not found above ${File("").absolutePath}" }.resolve(dir)
    val files = root.listFiles { f -> f.name.endsWith(".md") }.orEmpty().sortedBy { it.name }
    check(files.isNotEmpty()) { "no cards in $root" }
    return files.associate { it.name.removeSuffix(".md") to it.readText() }
}
