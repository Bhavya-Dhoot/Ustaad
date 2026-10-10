import os
base = r"H:\UStaaf\.claude\worktrees\agent-ac34ad0bc1b4ddd46\app\app\src"
main = os.path.join(base, "main", "java", "com", "swarmos", "app", "memcloud")
test = os.path.join(base, "test", "java", "com", "swarmos", "app", "memcloud")

def edit(d, name, pairs):
    p = os.path.join(d, name)
    s = open(p, encoding="utf-8").read()
    for old, new in pairs:
        if old not in s:
            raise SystemExit("missing in %s: %s" % (name, old[:80]))
        s = s.replace(old, new, 1)
    open(p, "w", encoding="utf-8").write(s)

edit(main, "GgufMeta.kt", [
# wrap the parser so every kind of corruption is one clean error
("""    /** [fileBytes] is the full file length; the last tensor's size is derived from it. */
    fun read(input: InputStream, fileName: String, fileBytes: Long): GgufInfo {
        val r = Src(BufferedInputStream(input, 1 shl 20))""", """    /** [fileBytes] is the full file length; the last tensor's size is derived from it. A corrupt file gives [GgufFormatException], nothing else. */
    fun read(input: InputStream, fileName: String, fileBytes: Long): GgufInfo = try {
        parse(input, fileName, fileBytes)
    } catch (e: GgufFormatException) {
        throw e
    } catch (e: EOFException) {
        throw GgufFormatException("$fileName ends inside its header (truncated or not a GGUF file)")
    } catch (e: OutOfMemoryError) {
        throw GgufFormatException("$fileName has an implausible header size (corrupt file)")
    } catch (e: RuntimeException) {
        throw GgufFormatException("$fileName has a corrupt header: ${e.javaClass.simpleName}")
    }

    private fun parse(input: InputStream, fileName: String, fileBytes: Long): GgufInfo {
        val r = Src(BufferedInputStream(input, 1 shl 20), fileBytes)"""),
("""        if (tensorCount < 0 || tensorCount > 10_000_000 || kvCount < 0 || kvCount > 1_000_000) {
            throw GgufFormatException("implausible header: $tensorCount tensors, $kvCount keys")
        }""", """        // every key takes at least 12 bytes and every tensor entry at least 24, so counts beyond the file size are corrupt
        if (tensorCount < 0 || kvCount < 0 || tensorCount > MAX_TENSORS || kvCount > MAX_KEYS ||
            tensorCount > fileBytes / 24 || kvCount > fileBytes / 12) {
            throw GgufFormatException("implausible header: $tensorCount tensors, $kvCount keys in a file of $fileBytes bytes")
        }"""),
("""    private const val MAX_KEPT_STRING = 512L""", """    private const val MAX_KEPT_STRING = 512L
    private const val MAX_TENSORS = 100_000L
    private const val MAX_KEYS = 100_000L
    private const val MAX_BLOCKS = 4096"""),
("""        val blockCount = ints["$arch.block_count"]?.toInt() ?: throw GgufFormatException("no $arch.block_count")""", """        val blockCount = ints["$arch.block_count"]?.toInt()?.takeIf { it in 1..MAX_BLOCKS }
            ?: throw GgufFormatException("no usable $arch.block_count (it must be between 1 and $MAX_BLOCKS)")"""),
("""                val fixed = fixedSize(elem)
                if (fixed != null) {
                    r.skip(n * fixed)
                } else if (elem == 8) {
                    var i = 0L
                    while (i < n) { r.skip(r.u64()); i++ }""", """                val fixed = fixedSize(elem)
                if (n < 0) throw GgufFormatException("array of $n elements in $key")
                if (fixed != null) {
                    if (n > r.remaining() / fixed) throw GgufFormatException("array of $n elements in $key does not fit in the file")
                    r.skip(n * fixed)
                } else if (elem == 8) {
                    if (n > r.remaining() / 8) throw GgufFormatException("array of $n strings in $key does not fit in the file")
                    var i = 0L
                    while (i < n) { r.skip(r.u64()); i++ }"""),
("""    private class Src(private val ins: InputStream) {
        var pos = 0L
            private set
""", """    private class Src(private val ins: InputStream, private val fileBytes: Long) {
        var pos = 0L
            private set

        /** Bytes of the file not read yet. */
        fun remaining(): Long = (fileBytes - pos).coerceAtLeast(0)
"""),
("""        fun skip(n: Long) {
            var left = n""", """        fun skip(n: Long) {
            if (n < 0 || n > remaining()) throw GgufFormatException("a value of $n bytes at offset $pos runs past the end of the file")
            var left = n"""),
("""        fun bytes(n: Int): ByteArray {
            val out = ByteArray(n)""", """        fun bytes(n: Int): ByteArray {
            if (n < 0 || n > remaining()) throw GgufFormatException("a value of $n bytes at offset $pos runs past the end of the file")
            val out = ByteArray(n)"""),
])

edit(test, "SplitAndLogTest.kt", [(
"""    @Test fun blockIndexOnlyMatchesRepeatingLayers() {""", """    /** A header whose counts claim far more than the file can hold must fail cleanly, not allocate. */
    @Test fun aCorruptHeaderGivesACleanErrorAndNeverAnOutOfMemory() {
        fun le(n: Long, bytes: Int) = ByteArray(bytes) { ((n shr (8 * it)) and 0xFF).toByte() }
        // claims 2^40 tensors
        val manyTensors = "GGUF".toByteArray() + le(3, 4) + le(1L shl 40, 8) + le(0, 8)
        // one key whose value is a string array of 2^40 entries
        val key = "tokenizer.ggml.tokens".toByteArray()
        val hugeArray = "GGUF".toByteArray() + le(3, 4) + le(0, 8) + le(1, 8) + le(key.size.toLong(), 8) + key + le(9, 4) + le(8, 4) + le(1L shl 40, 8)
        // a string longer than the file
        val k2 = "general.name".toByteArray()
        val longString = "GGUF".toByteArray() + le(3, 4) + le(0, 8) + le(1, 8) + le(k2.size.toLong(), 8) + k2 + le(8, 4) + le(1L shl 40, 8)
        // block_count that would allocate a huge array
        val arch = GgufBuilder().apply {
            kvStr("general.architecture", "x")
            kvU32("x.block_count", 2_000_000_000)
        }.build()
        for ((name, bytes) in listOf("tensors" to manyTensors, "array" to hugeArray, "string" to longString, "blocks" to arch, "empty" to ByteArray(0), "cut" to moeFile().copyOf(100))) {
            val e = runCatching { GgufMeta.read(bytes.inputStream(), "bad-$name.gguf", bytes.size.toLong()) }.exceptionOrNull()
            assertTrue("$name: $e", e is GgufFormatException)
        }
    }

    @Test fun blockIndexOnlyMatchesRepeatingLayers() {"""),
])
print("ok")
