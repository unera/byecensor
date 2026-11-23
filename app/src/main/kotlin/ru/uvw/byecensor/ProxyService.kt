package ru.uvw.byecensor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import android.os.PowerManager
import android.os.IBinder
import ru.uvw.byecensor.logic.Config
import ru.uvw.byecensor.logic.Probe
import ru.uvw.byecensor.logic.Traffic
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
    /**
     * Kept while the kernel runs.
     *
     * A foreground service keeps the process out of the freezer in theory; on phones that add
     * their own battery rules (Nothing OS among them) it is not enough, and the socket keeps
     * accepting connections that nobody serves — an app sees "connected, then no network". A
     * partial wake lock is the one thing those rules respect.
     */
    private var wakeLock: PowerManager.WakeLock? = null
    private var reader: Thread? = null
    /** Set while we are deliberately replacing the kernel, so its exit is not reported as a crash. */
    private var replacing = false
    /** The user-facing ports, held open across kernel restarts. */
    private val frontPorts = mutableListOf<FrontPort>()
    /** Reads the kernel's traffic counters while it runs. */
    private var trafficThread: Thread? = null

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
                // Reload is also how a stopped service gets asked to come up (the main screen
                // toggles the mode that way). When there is no kernel yet there is nothing to
                // replace — and a "replace" alone would never bind the front ports, so the
                // kernel would listen on a port nothing is pointed at.
                if (process == null) {
                    return if (startEverything()) START_STICKY else START_NOT_STICKY
                }
                replaceKernel()
                return START_STICKY
            }
        }
        if (process != null) return START_STICKY
        startForeground(NOTIFICATION_ID, notification("Запускаю ядро…"))
        return if (startEverything()) START_STICKY else START_NOT_STICKY
    }

    /** Front ports, wake lock, watchdogs and the kernel itself — a start from nothing. */
    private fun startEverything(): Boolean {
        startFrontPorts()
        val started = runCatching { launchKernel() }
        started.onSuccess {
            holdWakeLock()
            watchNetwork()
            watchHealth()
            watchTraffic()
        }
        started.onFailure { fail(it.message ?: "ядро не запустилось") }
        return started.isSuccess
    }

    /** Replace a running kernel with one built from the current settings. */
    private fun replaceKernel() {
        stopKernel(replacing = true)
        // a service that was restarted by ACTION_RELOAD has no front ports yet, and without them
        // the new kernel is unreachable even though it is running
        startFrontPorts()
        val started = runCatching { launchKernel() }
        started.onSuccess { holdWakeLock() }
        started.onFailure { fail(it.message ?: "ядро не запустилось") }
    }


    /**
     * Two watchdogs, for two different failures this setup actually has.
     *
     * The first follows the network: a phone that moves between Wi-Fi and mobile data keeps the
     * old tunnel open on a path that no longer exists, and every app then sees "connected, no
     * internet" until someone toggles the switch by hand. A kernel restart on change is cheap.
     *
     * The second is a heartbeat through the tunnel itself: the local socket can stay open while
     * the far side is dead, and nothing on this phone notices. Two failures in a row is enough
     * to mean "restart", because a single one is usually just a slow moment.
     */
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null
    private var healthThread: Thread? = null

    private fun watchNetwork() {
        if (networkCallback != null) return
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                if (process == null) return
                State.log("сеть сменилась: перезапускаю ядро")
                replaceKernel()
            }
        }
        runCatching { manager.registerDefaultNetworkCallback(callback) }
        networkCallback = callback
    }

    private fun watchHealth() {
        if (healthThread?.isAlive == true) return
        healthThread = Thread {
            val store = Store(this)
            var failures = 0
            while (process != null) {
                Thread.sleep(45_000)
                if (process == null) break
                val answer = runCatching {
                    Probe.socks(store.port, store.user, store.password, timeoutMs = 6000)
                }.getOrNull()
                if (answer?.ok == true) {
                    failures = 0
                } else {
                    failures += 1
                    if (failures >= 2 && process != null) {
                        State.log("туннель не отвечает дважды: перезапускаю ядро")
                        failures = 0
                        replaceKernel()
                    }
                }
            }
        }.apply { isDaemon = true }
        healthThread?.start()
    }


    /**
     * Bind the ports the apps are pointed at, once per service lifetime.
     *
     * The kernel restarts often — on a network change, on a failed heartbeat, on a settings
     * change — and every restart used to close these ports for a moment. Now they stay open and
     * the kernel lives one step behind them.
     */
    private fun startFrontPorts() {
        val store = Store(this)
        val wanted = mutableListOf(store.port)
        if (store.httpPort > 0) wanted += store.httpPort
        for (port in wanted) {
            if (frontPorts.any { it.port() == port }) continue
            runCatching {
                FrontPort(port, FrontPort.kernelPort(port)).also {
                    it.start()
                    frontPorts += it
                }
            }.onFailure { State.log("порт $port занять не удалось: ${it.message}") }
        }
    }

    private fun stopFrontPorts() {
        frontPorts.forEach { it.stop() }
        frontPorts.clear()
    }

    /** Build the config from the store and start the kernel with it. */
    private fun launchKernel() {
        Probe.network = runCatching {
            getSystemService(ConnectivityManager::class.java)?.activeNetwork
        }.getOrNull()
        val store = Store(this)
        val server = store.selected()
        val viaServer = store.viaServer && server != null
        val (certFile, keyFile) = waCertificate()
        val config = Config.build(
            server, FrontPort.kernelPort(store.port), store.user, store.password,
            store.directDomains, store.proxyDomains, viaServer,
            if (store.httpPort > 0) FrontPort.kernelPort(store.httpPort) else 0,
            store.waChatPort, store.waMediaPort, certFile, keyFile, store.muxEnabled,
            Traffic.API_PORT,
        )
        State.totalUp = store.trafficUp
        State.totalDown = store.trafficDown
        startKernel(config.toString())
    }

    /**
     * Follow the kernel's counters for as long as it lives.
     *
     * The reading resets them, so the numbers move out of the kernel and into this app's sums in
     * one step: nothing is counted twice, and nothing is left behind in a process that is about
     * to be restarted. Ten seconds is the cost of one short-lived process per tick — far less
     * than a connection, and invisible next to the numbers it moves.
     */
    private fun watchTraffic() {
        if (trafficThread?.isAlive == true) return
        trafficThread = Thread {
            val store = Store(this)
            val binary = File(applicationInfo.nativeLibraryDir, "libxray.so")
            val assets = File(filesDir, "assets")
            while (process != null) {
                Thread.sleep(TRAFFIC_TICK_MS)
                if (process == null) break
                collectTraffic(store, binary, assets)
            }
        }.apply { isDaemon = true }
        trafficThread?.start()
    }

    /** Read the counters, add them to both sums, and let the kernel start them from zero. */
    private fun collectTraffic(store: Store, binary: File, assets: File) {
        synchronized(trafficLock) {
            fold(store, Traffic.query(binary, assets) ?: return)
        }
    }

    override fun onDestroy() {
        stopKernel(replacing = false)
        super.onDestroy()
    }


    /**
     * The self-signed certificate the WhatsApp chat port presents.
     *
     * Meta's own proxy does exactly this: the client expects TLS on the chat port and accepts a
     * self-signed certificate, because the transport is not what protects the conversation — the
     * end-to-end encryption inside it does. The pair ships in the APK's assets and is copied once
     * to the app's own directory, where the kernel can read it by path.
     */
    private fun waCertificate(): Pair<String?, String?> {
        val cert = File(filesDir, "wa-proxy.crt")
        val key = File(filesDir, "wa-proxy.key")
        runCatching {
            if (!cert.exists()) assets.open("wa-proxy.crt").use { input ->
                cert.outputStream().use { input.copyTo(it) }
            }
            if (!key.exists()) assets.open("wa-proxy.key").use { input ->
                key.outputStream().use { input.copyTo(it) }
            }
        }
        return if (cert.exists() && key.exists()) cert.absolutePath to key.absolutePath else null to null
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
        // a session is this connection, not this service: the clock next to it starts here too,
        // and the totals on the right are what keeps what the previous connection carried
        State.sessionUp = 0
        State.sessionDown = 0
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

    private fun holdWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(PowerManager::class.java) ?: return
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "byecensor:kernel").apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { runCatching { if (it.isHeld) it.release() } }
        wakeLock = null
    }

    private fun stopKernel(replacing: Boolean) {
        this.replacing = replacing
        // the kernel dies with its counters: take the last of them before it goes
        process?.let {
            runCatching {
                collectTraffic(
                    Store(this),
                    File(applicationInfo.nativeLibraryDir, "libxray.so"),
                    File(filesDir, "assets"),
                )
            }
        }
        trafficThread = null
        process?.let { proc ->
            runCatching { proc.destroy() }
            runCatching { proc.waitFor() }
        }
        process = null
        reader = null
        State.running = false
        State.ready = false
        if (!replacing) {
            releaseWakeLock()
            stopFrontPorts()
        }
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
        /** Traffic carried since the proxy came up: the kernel's counters, summed as they pass. */
        @Volatile var sessionUp = 0L
        @Volatile var sessionDown = 0L
        /** Traffic carried since the person last reset the counter: survives restarts. */
        @Volatile var totalUp = 0L
        @Volatile var totalDown = 0L
        val lines = CopyOnWriteArrayList<String>()

        fun log(line: String) {
            lines.add(line)
            while (lines.size > 200) lines.removeAt(0)
        }
    }

    companion object {
        /**
         * One reading is handed out once, so two readers folding at the same moment would lose
         * one of the two numbers: the lock covers both the query and the add-up.
         */
        private val trafficLock = Any()

        /** Move one reading from the kernel into the sums the screens read. */
        private fun fold(store: Store, read: Traffic.Read) {
            if (read.up == 0L && read.down == 0L) return
            val up = store.trafficUp + read.up
            val down = store.trafficDown + read.down
            store.trafficUp = up
            store.trafficDown = down
            State.sessionUp += read.up
            State.sessionDown += read.down
            State.totalUp = up
            State.totalDown = down
        }

        /**
         * Read the counters now instead of at the next tick.
         *
         * The screen calls this when something has just moved traffic — the end of a test, the way
         * back into the app — because ten seconds of a stale number read like a broken counter.
         */
        fun pollTraffic(context: Context) {
            val app = context.applicationContext
            Thread {
                synchronized(trafficLock) {
                    fold(
                        Store(app),
                        Traffic.query(
                            File(app.applicationInfo.nativeLibraryDir, "libxray.so"),
                            File(app.filesDir, "assets"),
                        ) ?: return@synchronized,
                    )
                }
            }.apply { isDaemon = true }.start()
        }

        private const val CHANNEL = "proxy-active"
        /** How often the counters are read out of the kernel. */
        private const val TRAFFIC_TICK_MS = 10_000L
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

        /**
         * Burn it down and start again.
         *
         * `stopService` is the only thing that works when the service believes it is running and
         * the phone disagrees: an ACTION_STOP would be delivered to an instance that has nothing
         * left to stop. After a stop the next start is a fresh instance with fresh ports.
         */
        fun restart(context: Context) {
            runCatching { context.stopService(Intent(context, ProxyService::class.java)) }
            start(context)
        }

        /** Ask a running kernel to rebuild itself from the current settings. */
        fun reload(context: Context) {
            context.startForegroundService(
                Intent(context, ProxyService::class.java).setAction(ACTION_RELOAD),
            )
        }
    }
}
