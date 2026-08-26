package pl.piekoszek.prompter.scripts

import org.json.JSONObject
import java.io.File
import java.util.UUID

/** One saved prompter script (title + full text). */
data class Script(
    val id: String,
    val title: String,
    val text: String,
    /** Epoch millis of the last save; used for list ordering. */
    val updatedAt: Long,
)

/**
 * Persists scripts as one JSON file per script under a directory
 * (typically `context.filesDir/"scripts"`).
 *
 * Files are written atomically (tmp + rename); unreadable/corrupt files are
 * skipped, never fatal.
 */
class ScriptStore(private val dir: File) {

    fun list(): List<Script> =
        dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") }
            ?.mapNotNull { runCatching { read(it) }.getOrNull() }
            ?.sortedByDescending { it.updatedAt }
            ?: emptyList()

    fun load(id: String): Script? =
        runCatching { read(fileFor(id)) }.getOrNull()

    fun save(script: Script) {
        dir.mkdirs()
        val f = fileFor(script.id)
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(toJson(script))
        if (!tmp.renameTo(f)) {
            // Rename can fail across some FUSE mounts; fall back to copy.
            tmp.copyTo(f, overwrite = true)
            tmp.delete()
        }
    }

    fun delete(id: String) {
        fileFor(id).delete()
    }

    /** Creates a new script with a fresh id (caller then [save]s it). */
    fun newId(): String = UUID.randomUUID().toString()

    private fun fileFor(id: String): File {
        // Defend against path traversal in persisted ids.
        val safe = id.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return File(dir, safe + ".json")
    }

    private fun toJson(s: Script): String =
        JSONObject()
            .put("id", s.id)
            .put("title", s.title)
            .put("text", s.text)
            .put("updatedAt", s.updatedAt)
            .toString()

    private fun read(f: File): Script =
        JSONObject(f.readText()).let { j ->
            Script(
                id = j.getString("id"),
                title = j.optString("title", ""),
                text = j.optString("text", ""),
                updatedAt = j.optLong("updatedAt", 0L),
            )
        }
}
