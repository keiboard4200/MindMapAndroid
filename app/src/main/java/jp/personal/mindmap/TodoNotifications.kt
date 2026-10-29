package jp.personal.mindmap

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/**
 * Best-effort reminder notifications. setAndAllowWhileIdle is intentionally
 * inexact, so no Android exact-alarm special access is needed.
 */
object TodoNotifications {
    private const val CHANNEL_ID = "mindmap_todo_due"
    private const val ACTION_REMIND = "jp.personal.mindmap.TODO_REMIND"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(CHANNEL_ID, "ToDoの期限通知",
                NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "ToDoの期限や事前リマインダー"
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun pendingIntent(context: Context, id: Long, flags: Int): PendingIntent? {
        val intent = Intent(context, TodoAlarmReceiver::class.java)
            .setAction(ACTION_REMIND)
            .putExtra("todo_id", id)
        return PendingIntent.getBroadcast(
            context, id.toInt(), intent, flags or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun cancel(context: Context, id: Long) {
        val intent = pendingIntent(context, id, PendingIntent.FLAG_NO_CREATE)
        if (intent != null) {
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(intent)
            intent.cancel()
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel(id.toInt())
    }

    fun schedule(context: Context, task: TodoTask) {
        cancel(context, task.id)
        val due = task.dueAt ?: return
        if (task.done || task.remindBeforeMinutes < 0) return
        val whenToNotify = due - task.remindBeforeMinutes * 60_000L
        if (whenToNotify <= System.currentTimeMillis()) return
        val intent = pendingIntent(context, task.id, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenToNotify, intent)
    }

    fun show(context: Context, id: Long) {
        val task = TodoStore(context).tasks.firstOrNull { it.id == id } ?: return
        if (task.done || task.remindBeforeMinutes < 0) return
        val expected = (task.dueAt ?: return) - task.remindBeforeMinutes * 60_000L
        if (System.currentTimeMillis() + 30_000L < expected) return
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return
        createChannel(context)
        val open = Intent(context, MainActivity::class.java)
            .putExtra("open_todo", true)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val tap = PendingIntent.getActivity(context, id.toInt(), open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("ToDo: " + task.title)
            .setContentText(if (task.remindBeforeMinutes == 0) "期限になりました"
                else if (task.remindBeforeMinutes == 60) "期限の1時間前です"
                else "期限の前日です")
            .setAutoCancel(true)
            .setContentIntent(tap)
            .setCategory(Notification.CATEGORY_REMINDER)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(id.toInt(), notification)
    }
}

class TodoAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "jp.personal.mindmap.TODO_REMIND") {
            val id = intent.getLongExtra("todo_id", -1L)
            if (id > 0) TodoNotifications.show(context, id)
        }
    }
}

class TodoRebootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_TIME_CHANGED ||
            intent.action == Intent.ACTION_TIMEZONE_CHANGED) {
            TodoStore(context).rescheduleAll()
        }
    }
}
