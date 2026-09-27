package com.jambus.heji

/** Pure naming for copy-to-trash fallback. */
object VaultTrashPolicy {
    fun uniqueTrashName(original: String, existingNames: Iterable<String>, suffix: String): String {
        if (existingNames.none { VaultNamePolicy.conflictKey(it) == VaultNamePolicy.conflictKey(original) }) return original
        val hasExt = original.contains('.') && !original.startsWith('.')
        val ext = if (hasExt) {
            val rawExt = original.substringAfterLast('.')
            if (rawExt.equals("md", ignoreCase = true)) ".md" else ".$rawExt"
        } else ""
        val stem = if (hasExt) original.dropLast(ext.length) else original
        var attempt = 1
        while (true) {
            val candidate = "$stem-$suffix${if (attempt == 1) "" else "-$attempt"}$ext"
            if (existingNames.none { VaultNamePolicy.conflictKey(it) == VaultNamePolicy.conflictKey(candidate) }) return candidate
            attempt += 1
        }
    }

    fun uniqueNoteName(original: String, existingNames: Iterable<String>, suffix: String): String =
        uniqueTrashName(original, existingNames, suffix)
}
