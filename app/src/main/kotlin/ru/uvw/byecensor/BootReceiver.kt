package ru.uvw.byecensor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import ru.uvw.byecensor.store.Store

/**
 * Starts the proxy when the phone boots or when this app is updated.
 *
 * Both are moments when the process is gone and nobody is looking at the screen. Android may
 * refuse a foreground service started from the background — since Android 14 the allowed types
 * at boot are a short list, and a local proxy is not on it. When that happens the person gets a
 * notification with a tap: a tap is user action, and after it the service starts for real.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        val store = Store(context)
        if (!store.autostart || store.servers.isEmpty()) return
        val started = runCatching { ProxyService.start(context) }
        started.onFailure { error ->
            val reason = error.message ?: error.javaClass.simpleName
            ProxyService.State.log("автозапуск (${intent.action}): $reason")
            invite(context, reason)
        }
    }

    /** An invitation to tap, because only a tap makes the start legal again. */
    private fun invite(context: Context, reason: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(
                "autostart", "Автозапуск", NotificationManager.IMPORTANCE_DEFAULT,
            ))
        }
        val open = PendingIntent.getActivity(
            context, 1, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, "autostart")
            .setContentTitle("Прокси не поднялся сам")
            .setContentText("Android не разрешил запуск из фона. Нажмите, чтобы включить")
            .setStyle(Notification.BigTextStyle().bigText(
                "Android не разрешил запуск из фона: $reason\nНажмите, чтобы включить прокси вручную",
            ))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(4201, notification) }
    }
}
