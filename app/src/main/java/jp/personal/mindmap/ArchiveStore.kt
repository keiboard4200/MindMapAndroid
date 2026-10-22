package jp.personal.mindmap

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * App-local, persistent library of immutable root-subtree snapshots.
 * Archive means "save a copy": it NEVER removes a root from the active map.
 */
class ArchiveStore(private val context: Context) {
    data class Entry(
        val id: String, val title: String, val savedAt: Long,
        val nodeCount: Int, val json: String
    )
    private val file: File get() = File(context.filesDir, "mindmap_archive.json")

    fun entries(): List<Entry> {
        if (!file.exists()) return emptyList()
        val root = JSONArray(file.readText(Charsets.UTF_8))
        val list = mutableListOf<Entry>()
        for (i in 0 until root.length()) {
            val obj = root.getJSONObject(i)
            list.add(Entry(
                obj.getString("id"),
                obj.optString("title", "ルート"),
                obj.optLong("savedAt", 0L),
                obj.optInt("nodeCount", 1),
                obj.getString("json")
            ))
        }
        return list
    }

    private fun commit(items: List<Entry>) {
        val data = JSONArray()
        for (entry in items) data.put(JSONObject().apply {
            put("id", entry.id)
            put("title", entry.title)
            put("savedAt", entry.savedAt)
            put("nodeCount", entry.nodeCount)
            put("json", entry.json)
        })
        val tmp = File(context.filesDir, "mindmap_archive.json.tmp")
        tmp.writeText(data.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    fun saveRoot(map: MapState, rootId: Long): Entry {
        val node = map.find(rootId) ?: error("保存するルートがありません")
        require(node.parentId == null) { "ルートノードだけ保存できます" }
        val items = entries()
        require(items.size < 300) { "アーカイブの上限は300件です" }
        val archive = Entry(
            UUID.randomUUID().toString(), node.title, System.currentTimeMillis(),
            1 + map.descendants(rootId).size,
            map.copyJson(rootId, withDescendants = true)
        )
        commit(listOf(archive) + items)
        return archive
    }

    /** Downloadable backup; separate from active-map JSON. */
    fun exportJson(): String = if (file.exists()) file.readText(Charsets.UTF_8) else "[]"

    /** Merge valid archive entries without replacing existing entries. */
    fun importJson(json: String): Int {
        val array = JSONArray(json)
        require(array.length() <= 300) { "アーカイブが多すぎます" }
        val old = entries()
        val existingIds = old.mapTo(mutableSetOf()) { it.id }
        val incoming = mutableListOf<Entry>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val data = obj.getString("json")
            val snapshot = MapState.parse(data)
            require(snapshot.roots().size == 1) { "アーカイブにはルートが1個必要です" }
            val key = obj.optString("id", "")
            if (key.isNotEmpty() && key in existingIds) continue
            val id = if (key.isNotEmpty()) key else UUID.randomUUID().toString()
            existingIds.add(id)
            incoming.add(Entry(
                id,
                obj.optString("title", snapshot.root().title).take(300),
                obj.optLong("savedAt", System.currentTimeMillis()),
                snapshot.nodes.size,
                snapshot.asJson()
            ))
        }
        require(old.size + incoming.size <= 300) { "アーカイブの上限は300件です" }
        if (incoming.isNotEmpty()) commit(incoming + old)
        return incoming.size
    }

    fun delete(id: String) {
        val items = entries()
        require(items.any { it.id == id }) { "該当アーカイブがありません" }
        commit(items.filterNot { it.id == id })
    }
}
