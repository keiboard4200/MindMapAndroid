package jp.personal.mindmap

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class TodoList(val id: Long, var name: String)
data class TodoTask(
    val id: Long,
    var listId: Long,
    var title: String,
    var memo: String = "",
    var dueAt: Long? = null,
    // -1: off; 0: due; 60: one hour; 1440: one day.
    var remindBeforeMinutes: Int = -1,
    var priority: Int = 1,
    var done: Boolean = false
)

class TodoStore(private val context: Context) {
    private val file get() = File(context.filesDir, "todos.json")
    val lists = mutableListOf(TodoList(1L, "マイToDo"))
    val tasks = mutableListOf<TodoTask>()
    var currentListId: Long = 1

    init { load() }

    fun currentList(): TodoList = lists.firstOrNull { it.id == currentListId }
        ?: lists.first().also { currentListId = it.id }

    fun addList(name: String): TodoList {
        require(lists.size < 100) { "リストの上限は100件です" }
        val next = (lists.maxOfOrNull { it.id } ?: 0L) + 1L
        return TodoList(next, name.take(100)).also {
            lists.add(it)
            currentListId = it.id
            save()
        }
    }

    fun persistSelection() { save() }

    fun deleteList(id: Long) {
        require(lists.size > 1) { "最後のリストは削除できません" }
        val removed = tasks.filter { it.listId == id }.toList()
        removed.forEach { TodoNotifications.cancel(context, it.id) }
        tasks.removeAll { it.listId == id }
        lists.removeAll { it.id == id }
        if (currentListId == id) currentListId = lists.first().id
        save()
    }

    fun addTask(task: TodoTask): TodoTask {
        require(tasks.size < 5000) { "ToDoの上限は5000件です" }
        val next = (tasks.maxOfOrNull { it.id } ?: 0L) + 1L
        val created = task.copy(id = next)
        tasks.add(created)
        save()
        TodoNotifications.schedule(context, created)
        return created
    }

    fun saveTask(task: TodoTask) {
        val old = tasks.firstOrNull { it.id == task.id } ?: return
        TodoNotifications.cancel(context, old.id)
        old.listId = task.listId
        old.title = task.title
        old.memo = task.memo
        old.dueAt = task.dueAt
        old.remindBeforeMinutes = task.remindBeforeMinutes
        old.priority = task.priority
        old.done = task.done
        save()
        TodoNotifications.schedule(context, old)
    }

    fun toggleDone(id: Long, done: Boolean) {
        val task = tasks.firstOrNull { it.id == id } ?: return
        task.done = done
        save()
        if (done) TodoNotifications.cancel(context, id)
        else TodoNotifications.schedule(context, task)
    }

    fun deleteTask(id: Long) {
        TodoNotifications.cancel(context, id)
        tasks.removeAll { it.id == id }
        save()
    }

    fun rescheduleAll() {
        tasks.forEach { TodoNotifications.schedule(context, it) }
    }

    private fun save() {
        val json = JSONObject().apply {
            put("format", "mindmap-todo-v1")
            put("currentListId", currentListId)
            put("lists", JSONArray().also { array ->
                lists.forEach { array.put(JSONObject().apply {
                    put("id", it.id); put("name", it.name)
                }) }
            })
            put("tasks", JSONArray().also { array ->
                tasks.forEach { task -> array.put(JSONObject().apply {
                    put("id", task.id)
                    put("listId", task.listId)
                    put("title", task.title)
                    put("memo", task.memo)
                    put("dueAt", task.dueAt ?: JSONObject.NULL)
                    put("remindBeforeMinutes", task.remindBeforeMinutes)
                    put("priority", task.priority)
                    put("done", task.done)
                }) }
            })
        }
        val tmp = File(context.filesDir, "todos.json.tmp")
        tmp.writeText(json.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    private fun load() {
        if (!file.exists()) return
        try {
            val obj = JSONObject(file.readText(Charsets.UTF_8))
            val storedLists = obj.getJSONArray("lists")
            val storedTasks = obj.getJSONArray("tasks")
            require(storedLists.length() in 1..100 && storedTasks.length() <= 5000)
            val parsedLists = mutableListOf<TodoList>()
            val listIds = mutableSetOf<Long>()
            for (i in 0 until storedLists.length()) {
                val item = storedLists.getJSONObject(i)
                val id = item.getLong("id")
                require(id > 0 && listIds.add(id))
                parsedLists.add(TodoList(id, item.getString("name").take(100)))
            }
            val parsedTasks = mutableListOf<TodoTask>()
            val taskIds = mutableSetOf<Long>()
            for (i in 0 until storedTasks.length()) {
                val item = storedTasks.getJSONObject(i)
                val id = item.getLong("id")
                val listId = item.getLong("listId")
                require(id > 0 && taskIds.add(id) && listId in listIds)
                val minutes = item.optInt("remindBeforeMinutes", -1)
                require(minutes in listOf(-1, 0, 60, 1440))
                parsedTasks.add(TodoTask(
                    id, listId, item.getString("title").take(300),
                    item.optString("memo", "").take(20000),
                    if (item.isNull("dueAt")) null else item.getLong("dueAt"),
                    minutes, item.optInt("priority", 1).coerceIn(0, 2),
                    item.optBoolean("done", false)
                ))
            }
            lists.clear(); lists.addAll(parsedLists)
            tasks.clear(); tasks.addAll(parsedTasks)
            currentListId = obj.optLong("currentListId", lists.first().id)
            currentList()
        } catch (_: Exception) {
            // Keep a corrupt file untouched for recovery; start with an empty list.
            lists.clear(); lists.add(TodoList(1, "マイToDo"))
            tasks.clear()
            currentListId = 1
        }
    }

    /** A separate JSON export allows moving ToDos between standalone preview APKs. */
    fun exportJson(): String {
        if (file.exists()) return file.readText(Charsets.UTF_8)
        return JSONObject().apply {
            put("format", "mindmap-todo-v1")
            put("currentListId", currentListId)
            put("lists", JSONArray().apply { put(JSONObject().put("id", 1).put("name", "マイToDo")) })
            put("tasks", JSONArray())
        }.toString()
    }

    fun importJson(json: String) {
        val obj = JSONObject(json)
        require(obj.optString("format") == "mindmap-todo-v1") { "ToDo用JSONではありません" }
        // Validate through a temporary file? Validate in memory before mutating current data.
        val arr = obj.getJSONArray("lists")
        val tasksArr = obj.getJSONArray("tasks")
        require(arr.length() in 1..100 && tasksArr.length() <= 5000) { "リスト数が不正です" }
        val ids = mutableSetOf<Long>()
        for (i in 0 until arr.length()) {
            val l = arr.getJSONObject(i)
            require(l.getLong("id") > 0 && ids.add(l.getLong("id")))
        }
        val taskIds = mutableSetOf<Long>()
        for (i in 0 until tasksArr.length()) {
            val t = tasksArr.getJSONObject(i)
            val id = t.getLong("id")
            require(id > 0 && taskIds.add(id) && t.getLong("listId") in ids)
            if (!t.isNull("dueAt")) require(t.getLong("dueAt") > 0L)
            require(t.optInt("remindBeforeMinutes", -1) in listOf(-1,0,60,1440))
        }
        tasks.forEach { TodoNotifications.cancel(context, it.id) }
        val tmp = File(context.filesDir, "todos.json.import.tmp")
        tmp.writeText(obj.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(file)) { tmp.copyTo(file, overwrite = true); tmp.delete() }
        lists.clear(); tasks.clear()
        load()
        rescheduleAll()
    }
}
