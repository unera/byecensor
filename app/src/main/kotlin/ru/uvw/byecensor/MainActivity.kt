package ru.uvw.byecensor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import ru.uvw.byecensor.logic.Config
import ru.uvw.byecensor.logic.Probe
import ru.uvw.byecensor.logic.Server
import ru.uvw.byecensor.store.Store

/**
 * The one screen: how long it has been up, the button that starts it, and the configurations.
 *
 * The shape follows what people already know from other clients — a clock, one big button, the
 * state in a pill, and the list of configurations under it — because a proxy client is a thing
 * used in a hurry and from memory.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var store: Store
    private lateinit var button: ImageView
    private lateinit var clock: TextView
    private lateinit var pill: TextView
    private lateinit var result: TextView
    private lateinit var configs: LinearLayout
    private lateinit var probes: LinearLayout
    private lateinit var uplinkSquare: ImageView
    private lateinit var proxySquare: ImageView
    private lateinit var speedSquare: ImageView
    private lateinit var chevron: TextView
    private var expanded = true
    private val ticker = Handler(Looper.getMainLooper())
    /** Last measured latency per configuration id, in milliseconds. */
    private val latency = mutableMapOf<String, Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0b0b0b"))
        }
        // Колонка, а не наложение: прокрутка занимает всё, что осталось, панель внизу — ровно
        // своей высоты. Раньше панель висела поверх содержимого, и при коротком списке
        // серверов её серый фон занимал всю пустоту экрана.
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0b0b0b"))
        }
        column.addView(ScrollView(this).apply {
            // без явного фона прокрутка показывает цвет темы — светлую полосу под содержимым
            setBackgroundColor(Color.parseColor("#0b0b0b"))
            addView(root)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
        ))
        column.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#111111"))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(10), dp(24), dp(10))
            // помощь слева, настройки справа: две крайние точки полосы, между ними пусто
            addView(TextView(this@MainActivity).apply {
                text = "? помощь"
                textSize = 14f
                setTextColor(Color.parseColor("#8b8b8b"))
                isClickable = true
                setOnClickListener {
                    startActivity(Intent(this@MainActivity, HelpActivity::class.java))
                }
            })
            addView(View(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            })
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_settings)
                isClickable = true
                setOnClickListener {
                    startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                }
                layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
            })
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        setContentView(column)
        build(root)
    }

    override fun onResume() {
        super.onResume()
        askForBatteryFreedom()
        // прокси живёт всегда: приложение открыто — значит локальный порт уже слушает, и
        // приложениям не нужно ждать, пока человек нажмёт что-то
        if (!ProxyService.State.running) ProxyService.start(this)
        if (store.configDirty) {
            store.configDirty = false
            runCatching { ProxyService.reload(this) }
            pending("применяю настройки…")
        }
        refresh()
        ticker.post(object : Runnable {
            override fun run() {
                refresh()
                ticker.postDelayed(this, 1000)
            }
        })
    }

    override fun onPause() {
        ticker.removeCallbacksAndMessages(null)
        super.onPause()
    }


    /**
     * Ask once for the battery exemption.
     *
     * A foreground service is supposed to be enough, and in plain Android it is. Phones with
     * their own battery rules freeze a sideloaded app the moment it leaves the screen, and then
     * the proxy socket accepts connections that nobody serves — the app that points at it says
     * "no network" while this screen still shows it running.
     */
    private fun askForBatteryFreedom() {
        val power = getSystemService(PowerManager::class.java) ?: return
        if (power.isIgnoringBatteryOptimizations(packageName)) return
        if (store.askedBatteryFreedom) return
        store.askedBatteryFreedom = true
        AlertDialog.Builder(this)
            .setTitle("Не давать системе усыплять прокси")
            .setMessage(
                "Пока прокси работает, телефон будет держать его процесс. Без этого экран " +
                    "гаснет — и приложения видят «нет сети», хотя прокси запущен.",
            )
            .setPositiveButton("Разрешить") { _, _ ->
                runCatching {
                    startActivity(Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:$packageName"),
                    ))
                }
            }
            .setNegativeButton("Позже", null)
            .show()
    }

    private fun build(root: LinearLayout) {
        // время — вверху: это первое, что хочется увидеть
        root.addView(TextView(this).apply {
            text = "Время подключения"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#c9c9c9"))
            setPadding(0, dp(10), 0, dp(2))
        })
        clock = TextView(this).apply {
            text = "00:00:00"
            textSize = 32f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTypeface(android.graphics.Typeface.MONOSPACE)
            letterSpacing = 0.08f
        }
        root.addView(clock)

        // тесты — на строку ниже, мельче, с подписью под каждым
        probes = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(5), dp(10), dp(5), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            val uplink = probeColumn("тест", R.drawable.ic_uplink, "проверить сервер") { testUplink() }
            uplinkSquare = uplink.first
            addView(uplink.second)
            addView(View(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            })
            val proxy = probeColumn("тест", R.drawable.ic_proxy, "проверить прокси") { testProxy() }
            proxySquare = proxy.first
            addView(proxy.second)

            addView(View(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            })
            val speed = probeColumn("тест", R.drawable.ic_speed, "скорость") { testSpeed() }
            speedSquare = speed.first
            addView(speed.second)
        }
        root.addView(probes)

        button = ImageView(this).apply {
            isClickable = true
            background = rounded(Color.parseColor("#161616"), 26f)
            setPadding(dp(26), dp(26), dp(26), dp(26))
            setOnClickListener { buttonPressed() }
            layoutParams = LinearLayout.LayoutParams(dp(110), dp(110)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(18)
            }
        }
        // версия — над кнопкой: в переписке чаще всего спрашивают именно её, а искать её
        // в настройках ради одной строки — лишний путь
        root.addView(TextView(this).apply {
            text = "версия ${appVersion()}"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#6b7280"))
            setPadding(0, dp(12), 0, 0)
        })
        root.addView(button)

        pill = TextView(this).apply {
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#c9c9c9"))
            setPadding(dp(20), dp(10), dp(20), dp(10))
            background = rounded(Color.parseColor("#161616"), 20f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(16)
            }
        }
        root.addView(pill)

        result = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#8b8b8b"))
            setPadding(dp(20), dp(12), dp(20), 0)
        }
        root.addView(result)

        // configurations: a header that folds, and one row per server
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#1a1a1a"))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            isClickable = true
            setOnClickListener {
                expanded = !expanded
                refreshConfigs()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(dp(12), dp(18), dp(12), 0) }
        }
        header.addView(TextView(this).apply {
            text = "КОНФИГУРАЦИИ"
            textSize = 14f
            letterSpacing = 0.1f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        chevron = TextView(this).apply {
            text = "⌄"
            textSize = 20f
            setTextColor(Color.parseColor("#4f8fe0"))
        }
        header.addView(chevron)
        root.addView(header)

        configs = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(dp(12), 0, dp(12), 0) }
        }
        root.addView(configs)
    }

    private fun glyph(text: String, size: Float, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(Color.parseColor("#4f8fe0"))
        setPadding(dp(16), dp(4), dp(4), dp(4))
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun rounded(color: Int, radiusDp: Float): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(radiusDp.toInt()).toFloat()
        setColor(color)
    }

    private fun refresh() {
        val configured = store.servers.isNotEmpty()
        val running = ProxyService.State.running
        val ready = ProxyService.State.ready

        val viaServer = store.viaServer
        button.setImageResource(
            when {
                !configured -> R.drawable.ic_alert
                viaServer -> R.drawable.ic_pause
                else -> R.drawable.ic_power
            },
        )
        button.setColorFilter(
            when {
                !configured -> Color.parseColor("#e0574f")
                viaServer && ready -> Color.parseColor("#7bd88f")
                else -> Color.parseColor("#4f8fe0")
            },
            PorterDuff.Mode.SRC_IN,
        )

        clock.text = if (ready) uptime() else "00:00:00"
        pill.text = when {
            !configured -> "нет конфигурации · напрямую"
            !viaServer -> if (ready) "Напрямую" else "Поднимаю прокси…"
            ready -> "Соединено"
            running -> "Запущено, ждём соединения…"
            else -> "Не подключено"
        }
        pill.setTextColor(
            when {
                !configured -> Color.parseColor("#e0574f")
                viaServer && ready -> Color.parseColor("#7bd88f")
                else -> Color.parseColor("#c9c9c9")
            },
        )
        probes.visibility = if (configured) View.VISIBLE else View.GONE
        refreshConfigs()
    }

    /**
     * The list of configurations.
     *
     * Folded, it shows the one that is in use — that is the only line worth a glance. Unfolded, it
     * shows everything, because that is what unfolding is for.
     */
    private fun refreshConfigs() {
        chevron.text = if (expanded) "⌄" else "›"
        configs.removeAllViews()
        val servers = store.servers
        if (servers.isEmpty()) {
            configs.addView(Ui.line(this, "пусто: нажмите + и вставьте ссылку или подписку").apply {
                setPadding(dp(14), dp(12), dp(14), dp(12))
            })
            return
        }
        val selected = store.selected()
        val visible = if (expanded) servers else listOfNotNull(selected ?: servers.first())
        for (server in visible) {
            configs.addView(configRow(server, server.id == selected?.id))
        }
    }

    /** One configuration: name, protocol and transport, the measured latency, and its own menu. */
    private fun configRow(server: Server, active: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(if (active) Color.parseColor("#2a6bd4") else Color.parseColor("#171717"))
            isClickable = true
            setOnClickListener {
                store.select(server)
                // a running kernel holds the old server: restart it so the tap does what it says
                if (ProxyService.State.running) {
                    runCatching { ProxyService.reload(this@MainActivity) }
                    result.text = "переключаю на ${server.title()}…"
                }
                refresh()
            }
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(this).apply {
            text = server.title()
            textSize = 17f
            setTextColor(Color.WHITE)
        })
        texts.addView(TextView(this).apply {
            text = "${server.protocol.uppercase()} / ${server.transport.uppercase()}"
            textSize = 12f
            setTextColor(if (active) Color.parseColor("#dce6ff") else Color.parseColor("#8b8b8b"))
        })
        row.addView(texts)
        latency[server.id]?.let { ms ->
            row.addView(TextView(this).apply {
                text = "${ms}ms"
                textSize = 12f
                setTextColor(Color.parseColor("#7bd88f"))
                setPadding(0, 0, dp(12), 0)
            })
        }
        row.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_proxy)
            setColorFilter(
                if (active) Color.WHITE else Color.parseColor("#9a9a9a"),
                PorterDuff.Mode.SRC_IN,
            )
            setPadding(dp(6), dp(6), dp(6), dp(6))
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
            isClickable = true
            setOnClickListener { details(server) }
        })
        return row
    }

    /**
     * Everything about one configuration, and the questions worth asking about it.
     *
     * The actions are a list rather than dialog buttons: all three of the dialog's buttons were
     * already taken, and editing the link is what people look for after a server was pasted — a
     * link that turned out wrong should not have to be deleted and pasted again.
     *
     * The proxy test belongs to the configuration the kernel is running. Offering it on another
     * one would test a different server and report the answer as if it were about this one — so
     * for an inactive row the second button makes it active instead, and says why.
     */
    private fun details(server: Server) {
        val active = store.selected()?.id == server.id
        val running = ProxyService.State.running
        val text = buildString {
            appendLine("${server.protocol.uppercase()} · ${server.host}:${server.port}")
            appendLine("транспорт: ${server.transport}, защита: ${server.security}")
            if (server.sni.isNotBlank()) appendLine("sni: ${server.sni}")
            if (server.path.isNotBlank()) appendLine("path: ${server.path}")
            if (server.fingerprint.isNotBlank()) appendLine("отпечаток TLS: ${server.fingerprint}")
            latency[server.id]?.let { appendLine("задержка: $it мс") }
            if (!active) appendLine()
            if (!active) append("проверка прокси идёт через активный конфиг, а этот — не он")
        }.trim()

        val actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val dialog = AlertDialog.Builder(this)
            .setTitle(server.title())
            .setMessage(text)
            .setView(actions)
            .setNegativeButton("Закрыть", null)
            .create()

        fun action(label: String, run: () -> Unit) {
            actions.addView(TextView(this).apply {
                this.text = label
                textSize = 16f
                setTextColor(Color.parseColor("#4f8fe0"))
                setPadding(dp(20), dp(14), dp(20), dp(14))
                setOnClickListener { dialog.dismiss(); run() }
            })
        }

        action("Изменить ссылку") { editLink(server) }
        action("Проверить сервер") {
            pending("проверяю uplink…")
            setProbesBusy(true)
            Thread {
                val answer = Probe.server(server)
                runOnUiThread {
                    showResult(answer, uplinkSquare)
                    answer.text.substringAfter("отвечает,").trim().substringBefore(" мс")
                        .toIntOrNull()?.let { latency[server.id] = it; refreshConfigs() }
                }
            }.apply { isDaemon = true }.start()
        }
        if (active && running) {
            action("Проверить прокси") {
                pending("проверяю прокси…")
                setProbesBusy(true)
                Thread {
                    val answer = Probe.socksHttps(store.port, store.user, store.password)
                    runOnUiThread { showResult(answer, proxySquare) }
                }.apply { isDaemon = true }.start()
            }
        } else if (!active) {
            action("Сделать активным") {
                store.select(server)
                if (running) runCatching { ProxyService.reload(this) }
                refresh()
            }
        }
        action("Забыть") { confirmForget(server) }
        dialog.show()
    }

    /** The share link of a saved server, editable in place: see [LinkEditor]. */
    private fun editLink(server: Server) {
        LinkEditor.show(
            context = this,
            store = store,
            server = server,
            onSaved = { saved ->
                result.text = "ссылка сохранена: ${saved.title()}"
                result.setTextColor(Color.parseColor("#7bd88f"))
                if (ProxyService.State.running && store.selected()?.id == saved.id) {
                    runCatching { ProxyService.reload(this) }
                }
                refresh()
            },
        )
    }

    /** The wait before a verdict: white, because it is neither good news nor bad yet. */
    private fun pending(text: String) {
        result.text = text
        result.setTextColor(Color.WHITE)
    }

    /** Deleting a configuration is not undoable, so it asks first, with its name. */
    private fun confirmForget(server: Server) {
        AlertDialog.Builder(this)
            .setTitle("Удалить конфигурацию?")
            .setMessage("Вы уверены, что хотите удалить «${server.title()}»?")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Удалить") { _, _ ->
                store.forget(server)
                refresh()
            }
            .show()
    }

    /** Prefix a message with "ошибка" unless the message already says that word. */
    private fun withPrefix(text: String): String =
        if (text.startsWith("ошибка", ignoreCase = true)) text else "ошибка: $text"

    /** Show a probe's verdict: green when it worked, red when it did not, and on its own square. */

    /** "чат 1097: 12 мс · медиа 1096: нет соединения" — only when the doors are switched on. */
    /**
     * WhatsApp doors: silent when they answer, red and explicit when they do not.
     *
     * A working door needs no line — the operator pressed "test" to learn about a problem, not
     * to read that everything is fine twice.
     */
    private fun whatsappFailures(): String {
        val chat = store.waChatPort
        val media = store.waMediaPort
        if (chat <= 0 && media <= 0) return ""
        val parts = mutableListOf<String>()
        if (chat > 0) {
            val answer = Probe.whatsappPort(chat, expectOwnCertificate = true)
            if (!answer.ok) parts += "WA чат $chat: " + withPrefix(answer.text)
        }
        if (media > 0) {
            val answer = Probe.whatsappPort(media, expectOwnCertificate = false)
            if (!answer.ok) parts += "WA медиа $media: " + withPrefix(answer.text)
        }
        return parts.joinToString("\n")
    }

    /**
     * The line to show after a run: the check's own verdict, or the WhatsApp doors' complaint.
     * A failure anywhere decides the colour, because red is the only signal that needs acting on.
     */
    private fun combine(answer: Probe.Result, failures: String): Probe.Result = when {
        failures.isEmpty() -> answer
        answer.ok -> Probe.Result(false, failures)
        else -> Probe.Result(false, answer.text + "\n" + failures)
    }

    /** Download the configured file through the tunnel and report the rate. */
    private fun testSpeed() {
        if (!ProxyService.State.running) {
            result.text = "прокси не запущен"
            return
        }
        pending("измеряю скорость…")
        setProbesBusy(true)
        Thread {
            val answer = Probe.speed(store.speedUrl, store.port, store.user, store.password)
            runOnUiThread {
                showResult(answer, speedSquare)
                speedSquare.setImageResource(R.drawable.ic_speed)
            }
        }.apply { isDaemon = true }.start()
    }

    private fun showResult(answer: Probe.Result, square: ImageView?) {
        setProbesBusy(false)
        result.text = if (answer.ok) answer.text else withPrefix(answer.text)
        result.setTextColor(
            if (answer.ok) Color.parseColor("#7bd88f") else Color.parseColor("#e0574f"),
        )
        square?.background = rounded(
            if (answer.ok) Color.parseColor("#14301f") else Color.parseColor("#3a1a18"), 16f,
        )
        square?.setColorFilter(
            if (answer.ok) Color.parseColor("#7bd88f") else Color.parseColor("#e0574f"),
            android.graphics.PorterDuff.Mode.SRC_IN,
        )
    }

    /** Is the upstream reachable: the question about the chosen server. */
    private fun testUplink() {
        val server = store.selected() ?: run {
            result.text = "сервер не выбран"
            return
        }
        pending("проверяю uplink…")
        setProbesBusy(true)
        Thread {
            val answer = Probe.server(server)
            runOnUiThread {
                showResult(answer, uplinkSquare)
                answer.text.substringAfter("отвечает,").trim().substringBefore(" мс")
                    .toIntOrNull()?.let { latency[server.id] = it; refreshConfigs() }
            }
        }.apply { isDaemon = true }.start()
    }

    /** Does traffic come out of the other end, over HTTPS. */
    /** One run at a time: a second press cancels the right to display the first one's result. */
    private var probeRun = 0
    private var probeDone = 0

    private fun startProbeRun(): Int {
        probeRun += 1
        return probeRun
    }

    /** Returns true only for the run that is still current and has not reported yet. */
    private fun finishProbeRun(run: Int): Boolean {
        if (run != probeRun || run == probeDone) return false
        probeDone = run
        return true
    }

    private fun testProxy() {
        if (!ProxyService.State.running) {
            result.text = "прокси не запущен"
            return
        }
        pending("проверяю прокси…")
        setProbesBusy(true)
        val run = startProbeRun()
        // A socket timeout is not a promise: DNS, a TLS handshake and a stalled tunnel can each
        // outlast it. The watchdog is what guarantees the screen always ends with an answer.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (finishProbeRun(run)) {
                showResult(Probe.Result(false, "проверка не уложилась в 20 секунд"), proxySquare)
            }
        }, 20_000)
        Thread {
            val answer = try {
                // 8 seconds per attempt: the whole run has to stay short enough to wait through
                val proxy = Probe.proxy(store.port, store.user, store.password, timeoutMs = 8000)
                combine(proxy, whatsappFailures())
            } catch (error: Throwable) {
                Probe.Result(false, "проверка сорвалась: " + (error.message ?: error.javaClass.simpleName))
            }
            runOnUiThread {
                if (finishProbeRun(run)) showResult(answer, proxySquare)
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * A probe with its word: the square from [probeSquare] and "тест" underneath, half the size
     * of the status pill's text.
     */
    private fun probeColumn(
        caption: String,
        icon: Int,
        label: String,
        onClick: () -> Unit,
    ): Pair<ImageView, LinearLayout> {
        val square = probeSquare(icon, label, onClick)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(square)
            addView(TextView(this@MainActivity).apply {
                text = caption
                textSize = 8f
                setTextColor(Color.parseColor("#8b8b8b"))
                gravity = Gravity.CENTER
                setPadding(0, dp(3), 0, 0)
            })
        }
        return square to column
    }

    /**
     * A probe: a square a fifth smaller than before, with a word under it.
     *
     * The glyph alone was a guess; "тест" under it is the smallest thing that turns a guess into
     * a label without turning the button into a paragraph.
     */
    private fun probeSquare(icon: Int, label: String, onClick: () -> Unit): ImageView =
        ImageView(this).apply {
            setImageResource(icon)
            contentDescription = label
            isClickable = true
            background = rounded(Color.parseColor("#161616"), 14f)
            setPadding(dp(11), dp(11), dp(11), dp(11))
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(dp(54), dp(54))
        }

    /** While a probe runs, neither asks again: a second tap would race the first answer. */
    private fun setProbesBusy(busy: Boolean) {
        for (square in listOfNullable(uplinkSquare, proxySquare, speedSquare)) {
            square?.isEnabled = !busy
            square?.alpha = if (busy) 0.4f else 1f
        }
    }

    private fun listOfNullable(vararg squares: ImageView?) = squares.filterNotNull()

    private fun menu() {
        val items = arrayOf("Вставить ссылку из буфера", "Проверить прокси", "Настройки")
        AlertDialog.Builder(this)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> addFromClipboard()
                    1 -> {
                        pending("проверяю прокси…")
        setProbesBusy(true)
                        Thread {
                            val answer = Probe.socksHttps(store.port, store.user, store.password)
                            runOnUiThread { result.text = answer.text }
                        }.apply { isDaemon = true }.start()
                    }
                    else -> startActivity(Intent(this, SettingsActivity::class.java))
                }
            }
            .show()
    }

    private fun uptime(): String {
        val started = ProxyService.State.startedAt
        if (started == 0L) return "00:00:00"
        val seconds = (System.currentTimeMillis() - started) / 1000
        return "%02d:%02d:%02d".format(seconds / 3600, (seconds % 3600) / 60, seconds % 60)
    }

    /**
     * The button chooses what the proxy does, not whether it exists.
     *
     * The local SOCKS is always up — that is the whole point of this app: an app is pointed at
     * `127.0.0.1:1099` once. Pressing the button switches between "carry traffic to the server"
     * and "hand it to the network as is", and the kernel is restarted with the other config.
     */
    private fun buttonPressed() {
        if (store.servers.isEmpty()) {
            result.text = "жду ссылку в буфере обмена"
            addFromClipboard()
            return
        }
        store.viaServer = !store.viaServer
        runCatching { ProxyService.reload(this) }
        pending(if (store.viaServer) "включаю сервер…" else "переключаю напрямую…")
        button.postDelayed({ refresh() }, 600)
    }

    private fun addFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if (text.isBlank()) {
            result.text = "в буфере пусто"
            return
        }
        val single = Server.parseAll(text)
        if (single.isNotEmpty()) {
            val added = store.add(single)
            result.text = if (added == 0) "такая ссылка уже есть" else "добавлено серверов: $added"
            refresh()
            return
        }
        if (text.startsWith("http://") || text.startsWith("https://")) {
            store.subscription = text.trim()
            result.text = "тяну подписку…"
            Thread {
                val answer = Probe.subscription(text.trim())
                val fetched = if (answer.ok) {
                    runCatching { ru.uvw.byecensor.logic.Subscription.fetch(text.trim()) }
                        .getOrDefault(emptyList())
                } else emptyList()
                val added = if (fetched.isNotEmpty()) store.add(fetched) else 0
                runOnUiThread {
                    result.text = when {
                        !answer.ok -> answer.text
                        added == 0 -> "в подписке нет новых серверов"
                        else -> "из подписки добавлено: $added"
                    }
                    refresh()
                }
            }.apply { isDaemon = true }.start()
            return
        }
        result.text = "в буфере не ссылка: нужна vless://… или адрес подписки"
    }

    private fun copy(name: String, value: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(name, value))
        result.text = "скопировано: $name"
    }

    /** The version the system knows: BuildConfig would need a build feature this app does not use. */
    private fun appVersion(): String = runCatching {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0).versionName
    }.getOrNull() ?: "—"

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
