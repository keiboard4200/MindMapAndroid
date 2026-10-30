package jp.personal.mindmap

import org.json.JSONObject

/**
 * Portable combined backup. Versioned separately from the legacy map-only JSON.
 * Archive library is backed up separately through the existing Archive export.
 */
object BackupBundle {
    const val FORMAT = "mindmap-android-bundle-v1"
    const val MULTI_FORMAT = "mindmap-android-bundle-v2"

    data class Restored(
        val map: MapState,
        val layout: AutoLayout.Settings?,
        val todoJson: String?,
        val isCombined: Boolean,
        val libraryJson: String? = null
    )

    fun export(map: MapState, layout: AutoLayout.Settings, todoJson: String): String {
        return JSONObject().apply {
            put("format", FORMAT)
            put("version", 1)
            put("map", JSONObject(map.asJson()))
            put("layoutSettings", JSONObject().apply {
                put("selectedBranchOnly", layout.selectedBranchOnly)
                put("spacing", layout.spacing.toDouble())
                put("style", layout.style.name)
                put("alignment", layout.alignment.name)
                put("horizontal", layout.horizontal.name)
            })
            put("todos", JSONObject(todoJson))
        }.toString(2)
    }

    fun exportLibrary(library: MapLibrary, layout: AutoLayout.Settings, todoJson: String): String {
        return JSONObject().apply {
            put("format", MULTI_FORMAT)
            put("version", 2)
            put("library", JSONObject(library.asJson()))
            put("layoutSettings", JSONObject().apply {
                put("selectedBranchOnly", layout.selectedBranchOnly)
                put("spacing", layout.spacing.toDouble())
                put("style", layout.style.name)
                put("alignment", layout.alignment.name)
                put("horizontal", layout.horizontal.name)
            })
            put("todos", JSONObject(todoJson))
        }.toString(2)
    }

    fun parse(text: String): Restored {
        val doc = JSONObject(text)
        if (doc.optString("format") == MULTI_FORMAT) {
            require(doc.getInt("version") == 2) { "バックアップのバージョンが不正です" }
            val libraryJson = doc.getJSONObject("library").toString()
            val parsedLibrary = MapLibraryPayload.parse(libraryJson)
            val map = parsedLibrary.entries.first { it.id == parsedLibrary.activeId }.map
            val layout = parseLayout(doc.getJSONObject("layoutSettings"))
            val todoJson = doc.getJSONObject("todos").toString()
            return Restored(map, layout, todoJson, true, libraryJson)
        }
        if (doc.optString("format") != FORMAT) {
            // Old map JSON can be restored without erasing ToDo or layout settings.
            require(doc.has("nodes") && doc.has("title")) {
                "マインドマップ用JSONではありません"
            }
            return Restored(MapState.parse(text), null, null, false)
        }
        require(doc.getInt("version") == 1) { "未対応のバックアップ形式です" }
        val map = MapState.parse(doc.getJSONObject("map").toString())
        val layout = parseLayout(doc.getJSONObject("layoutSettings"))
        val todoJson = doc.getJSONObject("todos").toString()
        return Restored(map, layout, todoJson, true)
    }
    private fun parseLayout(preferences: JSONObject): AutoLayout.Settings {
        val spacing = preferences.getDouble("spacing")
        require(spacing.isFinite() && spacing in 150.0..300.0) {
            "整列の間隔設定が不正です"
        }
        return AutoLayout.Settings(
            selectedBranchOnly = preferences.getBoolean("selectedBranchOnly"),
            spacing = spacing.toFloat(),
            style = AutoLayout.Style.valueOf(preferences.getString("style")),
            alignment = AutoLayout.Align.valueOf(preferences.getString("alignment")),
            horizontal = AutoLayout.Horizontal.valueOf(preferences.getString("horizontal"))
        )
    }

}
