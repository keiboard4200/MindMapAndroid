package jp.personal.mindmap

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Independent editable maps, stored as one atomic app-local library.
 * On first launch from the single-map prototype, the legacy file is imported
 * and left untouched for recovery.
 */
class MapLibrary(private val context: Context) {
    data class Entry(val id: Long, var map: MapState, var modifiedAt: Long)
    private val file = File(context.filesDir, "maps-v1.json")
    private val entriesInternal = mutableListOf<Entry>()
    var activeId: Long = 1L
        private set

    val entries: List<Entry> get() = entriesInternal.toList()
    val size: Int get() = entriesInternal.size

    init {
        if (file.exists()) {
            // Do not overwrite a corrupt library with a blank map.
            restoreData(file.readText(Charsets.UTF_8))
        } else {
            val old = File(context.filesDir, "mindmap.json")
            val initial = if (old.exists()) MapState.parse(old.readText(Charsets.UTF_8))
                else MapState.blank()
            entriesInternal.add(Entry(1L, initial, System.currentTimeMillis()))
            activeId = 1L
            persist()
        }
    }

    fun active(): Entry = entriesInternal.first { it.id == activeId }

    fun replaceActive(map: MapState) {
        val current = active()
        current.map = map
        current.modifiedAt = System.currentTimeMillis()
        persist()
    }

    fun persistActive() {
        active().modifiedAt = System.currentTimeMillis()
        persist()
    }

    fun select(id: Long): MapState {
        require(entriesInternal.any { it.id == id }) { "マップがありません" }
        activeId = id
        persist()
        return active().map
    }

    fun add(map: MapState): Entry {
        require(entriesInternal.size < 100) { "マップの上限は100件です" }
        val id = (entriesInternal.maxOfOrNull { it.id } ?: 0L) + 1L
        val entry = Entry(id, map, System.currentTimeMillis())
        entriesInternal.add(entry)
        activeId = id
        persist()
        return entry
    }

    fun duplicate(id: Long): Entry {
        val entry = entriesInternal.firstOrNull { it.id == id }
            ?: error("複製元がありません")
        val copy = MapState.parse(entry.map.asJson())
        copy.title = (copy.title + " コピー").take(200)
        return add(copy)
    }

    fun rename(id: Long, title: String) {
        val entry = entriesInternal.firstOrNull { it.id == id } ?: error("マップがありません")
        require(title.isNotBlank()) { "タイトルを入力してください" }
        entry.map.title = title.take(200)
        entry.modifiedAt = System.currentTimeMillis()
        persist()
    }

    fun delete(id: Long) {
        require(entriesInternal.size > 1) { "最後のマップは削除できません" }
        require(entriesInternal.removeAll { it.id == id }) { "マップがありません" }
        if (activeId == id) activeId = entriesInternal.first().id
        persist()
    }

    fun asJson(): String = JSONObject().apply {
        put("format", "mindmap-library-v1")
        put("selectedMapId", activeId)
        put("maps", JSONArray().apply {
            entriesInternal.forEach { entry ->
                put(JSONObject().apply {
                    put("id", entry.id)
                    put("modifiedAt", entry.modifiedAt)
                    put("map", JSONObject(entry.map.asJson()))
                })
            }
        })
    }.toString(2)

    fun restoreData(text: String) {
        val obj = JSONObject(text)
        require(obj.getString("format") == "mindmap-library-v1") {
            "マップライブラリの形式が違います"
        }
        val arr = obj.getJSONArray("maps")
        require(arr.length() in 1..100) { "マップ数が不正です" }
        val ids = mutableSetOf<Long>()
        val parsed = mutableListOf<Entry>()
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            val id = item.getLong("id")
            require(id > 0 && ids.add(id)) { "マップIDに重複があります" }
            parsed.add(Entry(id, MapState.parse(item.getJSONObject("map").toString()),
                item.optLong("modifiedAt", System.currentTimeMillis())))
        }
        val selected = obj.getLong("selectedMapId")
        require(selected in ids) { "選択マップのIDが不正です" }
        entriesInternal.clear()
        entriesInternal.addAll(parsed)
        activeId = selected
    }

    fun importLibrary(text: String) {
        // Validate completely before overwriting storage.
        val data = MapLibraryPayload.parse(text)
        entriesInternal.clear()
        entriesInternal.addAll(data.entries)
        activeId = data.activeId
        persist()
    }

    private fun persist() {
        val tmp = File(context.filesDir, "maps-v1.json.tmp")
        tmp.writeText(asJson(), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }
}

/** Validation without modifying the current library or invoking its constructor. */
object MapLibraryPayload {
    data class Parsed(val activeId: Long, val entries: List<MapLibrary.Entry>)
    fun parse(text: String): Parsed {
        val obj = JSONObject(text)
        require(obj.getString("format") == "mindmap-library-v1")
        val arr = obj.getJSONArray("maps")
        require(arr.length() in 1..100) { "マップ数が不正です" }
        val ids = mutableSetOf<Long>()
        val entries = mutableListOf<MapLibrary.Entry>()
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            val id = item.getLong("id")
            require(id > 0 && ids.add(id))
            entries.add(MapLibrary.Entry(id,
                MapState.parse(item.getJSONObject("map").toString()),
                item.optLong("modifiedAt", 0L)))
        }
        val selected = obj.getLong("selectedMapId")
        require(selected in ids)
        return Parsed(selected, entries)
    }
}
