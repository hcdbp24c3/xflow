package com.alchemistaloha.xflow.core.client

/**
 * Finding, in X's public scripts, which key bytes the current build of the
 * transaction id generator reads ([TransactionIdIndices]).
 *
 * The x.com document names one entry script. One of the scripts it imports
 * sets the `x-client-transaction-id` header and loads the generator with
 * `import("./<name>.js").then(e => e.default())`. The generator reads the
 * key bytes as `parseInt(key[N], 16)`-like calls, which stay visible in the
 * obfuscated text as `[N],16`: the first is the frame row, the rest are the
 * factors of the animation time. (Checked on the scripts of 2026-11-24,
 * where running the generator showed the same bytes being read.)
 */
object TransactionIdAssets {
    const val HEADER_NAME = "x-client-transaction-id"

    private val scriptUrlRegex = Regex("""https://abs\.twimg\.com/[A-Za-z0-9_./-]+\.js""")
    private val relativeScriptRegex = Regex("""["'`](\.{1,2}/[A-Za-z0-9_./-]+\.js)["'`]""")
    private val generatorImportRegex = Regex(TransactionIdScript.MODULE_IMPORT_JS_REGEX.removePrefix("/").removeSuffix("/g"))
    private val indexRegex = Regex("""\[(\d{1,2})],\s*16\b""")

    /** The scripts the document names, in order. */
    fun documentScripts(html: String): List<String> =
        scriptUrlRegex.findAll(html).map { it.value }.distinct().toList()

    /** The scripts [script] (loaded from [scriptUrl]) refers to by a relative path, as absolute addresses. */
    fun referencedScripts(script: String, scriptUrl: String): List<String> =
        relativeScriptRegex.findAll(script).mapNotNull { resolve(scriptUrl, it.groupValues[1]) }.distinct().toList()

    /** The address of the generator, when [script] is the one that sets the header. */
    fun generatorUrl(script: String, scriptUrl: String): String? {
        val at = script.indexOf(HEADER_NAME)
        if (at < 0) return null
        val nearest = generatorImportRegex.findAll(script).minByOrNull { kotlin.math.abs(it.range.first - at) } ?: return null
        return resolve(scriptUrl, "./" + nearest.groupValues[1])
    }

    /** The indices in the text of the generator, or null when it does not look as expected. */
    fun indices(generatorScript: String): TransactionIdIndices? {
        val found = indexRegex.findAll(generatorScript).map { it.groupValues[1].toInt() }.toList()
        if (found.size < 2 || found.size > 8) return null
        return TransactionIdIndices(found[0], found.drop(1))
    }

    private fun resolve(base: String, relative: String): String? {
        val parts = base.substringBefore('?').split('/').dropLast(1).toMutableList()
        for (segment in relative.split('/')) {
            when (segment) {
                "." -> {}
                ".." -> if (parts.size > 3) parts.removeAt(parts.size - 1) else return null
                else -> parts.add(segment)
            }
        }
        return parts.joinToString("/")
    }
}
