package ru.uvw.byecensor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import ru.uvw.byecensor.logic.Config
import ru.uvw.byecensor.store.Store
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Holds the kernel process.
 *
 * Deliberately not a VpnService: the kernel listens on loopback as a SOCKS proxy with a password,
 * and the apps that want it are pointed at it. That is why this app never shows up as a VPN and
 * why the apps that refuse to run "with a VPN on" have nothing to refuse.
 *
 * The kernel binary ships as `libxray.so` in the APK's native libraries: since Android 10 an app
 * may not execute a file from its own data directory, and the native library directory is the one
 * place it may.
 */
class ProxyService : Service() {
    private var process: Process? = null
    private var reader: Thread? = null
    /** Set while we are deliberately replacing the kernel, so its exit is not reported as a crash. */
    private var replacing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A reload is one command, not "stop" followed by "start": two commands race, and the
        // start can land in the instance that is already dying — which is exactly what made the
        // tile report "ядро остановилось" and stay down.
        when (intent?.action) {
            ACTION_STOP -> {
                stopKernel(replacing = false)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RELOAD -> {
                startForeground(NOTIFICATION_ID, notification("Перечитываю настройки…"))
                replaceKernel()
                return START_STICKY
            }
        }
        if (process != null) return START_STICKY
        startForeground(NOTIFICATION_ID, notification("Запускаю ядро…"))
        val started = runCatching { launchKernel() }
        started.onFailure { fail(it.message ?: "ядро не запустилось") }
        return if (started.isSuccess) START_STICKY else START_NOT_STICKY
    }

    /** Replace a running kernel with one built from the current settings. */
    private fun replaceKernel() {
        stopKernel(replacing = true)
        val started = runCatching { launchKernel() }
        started.onFailure { fail(it.message ?: "ядро не запустилось") }
    }

    /** Build the config from the store and start the kernel with it. */
    private fun launchKernel() {
        val store = Store(this)
        val server = store.selected()
        val viaServer = store.viaServer && server != null
        val config = Config.build(
            server, store.port, store.user, store.password,
            store.directDomains, store.proxyDomains, viaServer, store.httpPort,
            store.httpNoAuth,
        )
        startKernel(config.toString())
    }

    override fun onDestroy() {
        stopKernel(replacing = false)
        super.onDestroy()
    }

    private fun startKernel(config: String): Unit {
        val binary = File(applicationInfo.nativeLibraryDir, "libxray.so")
        require(binary.exists()) { "в приложении нет ядра (libxray.so)" }
        val assets = File(filesDir, "assets").apply { mkdirs() }
        for (name in listOf("geoip.dat", "geosite.dat")) {
            val target = File(assets, name)
            if (!target.exists()) {
                assetsFromApk(name, target)
            }
        }
        val configFile = File(filesDir, "config.json").apply { writeText(config) }
        val builder = ProcessBuilder(binary.absolutePath, "run", "-c", configFile.absolutePath)
        builder.environment()["XRAY_LOCATION_ASSET"] = assets.absolutePath
        builder.redirectErrorStream(true)
        val proc = builder.start()
        process = proc
        State.running = true
        State.startedAt = System.currentTimeMillis()
        reader = Thread {
            runCatching {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    State.log(line)
                    if (line.contains("failed", ignoreCase = true) ||
                        line.contains("error", ignoreCase = true)
                    ) {
                        State.error = line.trim()
                    }
                }
            }
            // the kernel is gone: say so, unless we are the ones who replaced it
            State.running = false
            State.ready = false
            if (!replacing) notify("Ядро остановилось")
        }.apply { isDaemon = true; start() }

        // Ready means "the port answers", not "a line appeared in the log": with loglevel warning
        // the kernel never announces itself, and the screen sat on "запускаю…" for minutes.
        val port = Store(this).port
        Thread {
            val deadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < deadline && process === proc) {
                val answered = runCatching {
                    java.net.Socket().use { it.connect(java.net.InetSocketAddress(Config.LISTEN, port), 400) }
                    true
                }.getOrDefault(false)
                if (answered) {
                    State.ready = true
                    notify(describe())
                    return@Thread
                }
                Thread.sleep(200)
            }
            if (process === proc && !State.ready) {
                State.error = "ядро не открыло порт $port за 15 секунд"
                State.log("ошибка: ${State.error}")
                notify("Ядро не открыло порт $port")
            }
        }.apply { isDaemon = true; start() }
    }

    private fun assetsFromApk(name: String, target: File) {
        assets.open(name).use { input -> target.outputStream().use { input.copyTo(it) } }
    }

    private fun stopKernel(replacing: Boolean) {
        this.replacing = replacing
        process?.let { proc ->
            runCatching { proc.destroy() }
            runCatching { proc.waitFor() }
        }
        process = null
        reader = null
        State.running = false
        State.ready = false
    }

    private fun fail(message: String) {
        State.running = false
        State.error = message
        State.log("ошибка: $message")
        notify(message)
        stopSelf()
    }

    private fun notify(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification(text))
    }

    /**
     * What the shade should say: where the proxy is, and where the traffic goes.
     *
     * A notification that only says "running" makes a person open the app to answer the one
     * question they had. The mode and the server name are the answer.
     */
    private fun describe(): String {
        val store = Store(this)
        val server = store.selected()
        val where = when {
            !store.viaServer -> "напрямую"
            server == null -> "напрямую, сервер не выбран"
            else -> "через ${server.title()}"
        }
        return "${Config.LISTEN}:${store.port} · $where"
    }

    private fun notification(text: String, viaServer: Boolean = Store(this).viaServer): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // IMPORTANCE_LOW silences the channel and, with it, the dot on the launcher icon:
            // the whole point of this notification is "the proxy is up", so it asks for a
            // normal importance and a channel id of its own (importance cannot be raised later).
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL, "Прокси работает", NotificationManager.IMPORTANCE_DEFAULT,
            ))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Bye, Censor!")
            .setContentText(text)
            // the status bar carries the difference the launcher dot cannot: a shield when the
            // traffic goes through the server, an arrow when it goes out as is
            .setSmallIcon(if (viaServer) R.drawable.ic_stat_proxy else R.drawable.ic_stat_direct)
            .setColor(if (viaServer) 0xFF4F8FE0.toInt() else 0xFF7A7A7A.toInt())
            .setContentIntent(open)
            // ongoing: the proxy is a state, not an event, and the launcher marks a state with a
            // dot. only-alert-once: an update must not buzz every time the mode changes.
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    /** What the screens read: the kernel's state, and the last lines it said. */
    object State {
        @Volatile var running = false
        @Volatile var ready = false
        @Volatile var startedAt = 0L
        @Volatile var error: String? = null
        val lines = CopyOnWriteArrayList<String>()

        fun log(line: String) {
            lines.add(line)
            while (lines.size > 200) lines.removeAt(0)
        }
    }

    companion object {
        private const val CHANNEL = "proxy-active"
        private const val NOTIFICATION_ID = 4101
        const val ACTION_STOP = "ru.uvw.byecensor.STOP"
        const val ACTION_RELOAD = "ru.uvw.byecensor.RELOAD"

        fun start(context: Context) {
            val intent = Intent(context, ProxyService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ProxyService::class.java).setAction(ACTION_STOP))
        }

        /** Ask a running kernel to rebuild itself from the current settings. */
        fun reload(context: Context) {
            context.startForegroundService(
                Intent(context, ProxyService::class.java).setAction(ACTION_RELOAD),
            )
        }
    }
}
