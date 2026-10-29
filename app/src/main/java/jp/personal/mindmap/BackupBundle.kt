package jp.personal.mindmap

import org.json.JSONObject

/**
 * Portable combined backup. Versioned separately from the legacy map-only JSON.
 * Archive library is backed up separately through the existing Archive export.
 */
object BackupBundle {
    const val FORMAT = "mindmap-android-bundle-v1"

    data class Restored(
        val map: MapState,
        val layout: AutoLayout.Settings?,
        val todoJson: String?,
        val isCombined: Boolean
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

    fun parse(text: String): Restored {
        val doc = JSONObject(text)
        if (doc.optString("format") != FORMAT) {
            // Old map JSON can be restored without erasing ToDo or layout settings.
            require(doc.has("nodes") && doc.has("title")) {
                "マインドマップ用JSONではありません"
            }
            return Restored(MapState.parse(text), null, null, false)
        }
        require(doc.getInt("version") == 1) { "未対応のバックアップ形式です" }
        val map = MapState.parse(doc.getJSONObject("map").toString())
        val preferences = doc.getJSONObject("layoutSettings")
        val spacing = preferences.getDouble("spacing")
        require(spacing.isFinite() && spacing in 150.0..300.0) {
            "整列の間隔設定が不正です"
        }
        val layout = AutoLayout.Settings(
            selectedBranchOnly = preferences.getBoolean("selectedBranchOnly"),
            spacing = spacing.toFloat(),
            style = AutoLayout.Style.valueOf(preferences.getString("style")),
            alignment = AutoLayout.Align.valueOf(preferences.getString("alignment")),
            horizontal = AutoLayout.Horizontal.valueOf(preferences.getString("horizontal"))
        )
        val todoJson = doc.getJSONObject("todos").toString()
        return Restored(map, layout, todoJson, true)
    }
}
