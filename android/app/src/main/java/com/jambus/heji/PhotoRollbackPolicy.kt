package com.jambus.heji

/** Process-only ownership proof; no fields are written to the Vault marker. */
data class PhotoRollbackFile(val name: String, val identity: String, val sha256: String)
data class PhotoRollbackIdentity(
    val vault: String,
    val directory: String,
    val files: List<PhotoRollbackFile>,
    val marker: PhotoRollbackFile
)

interface PhotoRollbackPort {
    data class Entry(val name: String, val identity: String)
    /** Null/throw means unknown, never an empty directory. */
    fun list(): List<Entry>?
    fun sha256(entry: Entry): String?
    fun delete(entry: Entry): Boolean
}

/** Only the captured outputs may be removed; uncertainty always retains the transaction. */
object PhotoRollbackPolicy {
    fun rollback(identity: PhotoRollbackIdentity, port: PhotoRollbackPort): Boolean {
      return try {
        fun uniqueListing(): List<PhotoRollbackPort.Entry>? = port.list()?.takeIf { entries ->
            entries.map { it.name }.distinct().size == entries.size &&
                entries.map { it.identity }.distinct().size == entries.size
        }
        fun owned(target: PhotoRollbackFile, entries: List<PhotoRollbackPort.Entry>): Boolean {
            val named = entries.singleOrNull { it.name == target.name }
            val identified = entries.singleOrNull { it.identity == target.identity }
            return if (named == null && identified == null) true
            else named != null && named == identified && port.sha256(named) == target.sha256
        }
        var entries = uniqueListing() ?: return false
        if (!(identity.files + identity.marker).all { owned(it, entries) }) return false
        for (file in identity.files) {
            // Recheck ownership just before each deletion, then prove strict absence.
            entries = uniqueListing() ?: return false
            if (!owned(file, entries)) return false
            val entry = entries.singleOrNull { it.name == file.name }
            if (entry != null && !port.delete(entry)) return false
            entries = uniqueListing() ?: return false
            if (entries.any { it.name == file.name || it.identity == file.identity }) return false
        }
        entries = uniqueListing() ?: return false
        if (identity.files.any { target -> entries.any { it.name == target.name || it.identity == target.identity } }) return false
        if (!owned(identity.marker, entries)) return false
        val marker = entries.singleOrNull { it.name == identity.marker.name }
        if (marker != null && !port.delete(marker)) return false
        val remaining = uniqueListing() ?: return false
        (identity.files + identity.marker).none { target ->
            remaining.any { it.name == target.name || it.identity == target.identity }
        }
      } catch (_: Exception) { false }
    }
}
