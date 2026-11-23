package ru.uvw.byecensor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import ru.uvw.byecensor.logic.Config
import ru.uvw.byecensor.logic.Probe
import ru.uvw.byecensor.logic.Server
import ru.uvw.byecensor.logic.Updater
import ru.uvw.byecensor.store.Store

/**
 * One settings screen, chosen by the `section` extra.
 *
 * A single activity rather than six: every section is the same shape — a back arrow, a title, a
 * few rows — and six classes would be six copies of that shape with different words.
 */
class SectionActivity : AppCompatActivity() {
    private lateinit var store: Store
    private lateinit var root: LinearLayout
    private lateinit var section: String
    /** Состояние разрешения на установку: с ним экран понимает, что надо перерисоваться. */
    private var canInstall = false

    override fun onResume() {
        super.onResume()
        // человек уходил на системный экран и, возможно, что-то там переключил
        if (section != "update") return
        if (Updater.canInstall(this) != canInstall) recreate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        section = intent.getStringExtra("section").orEmpty()
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0b0b0b"))
            setPadding(0, dp(24), 0, dp(24))
        }
        // журнал живёт своей прокруткой и должен занимать весь экран: общий ScrollView вокруг
        // него оставлял бы снизу пустую серую полосу
        if (section == "log") {
            val column = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#0b0b0b"))
            }
            column.addView(root)
            setContentView(column)
        } else {
            setContentView(ScrollView(this).apply {
                setBackgroundColor(Color.parseColor("#0b0b0b"))
                addView(root)
            })
        }
        when (section) {
            "params" -> params()
            "update" -> update()
            "routing" -> routing()
            "subscription" -> subscription()
            "servers" -> servers()
            "log" -> log()
            else -> finish()
        }
    }

    private fun title(): String = when (section) {
        "params" -> "Параметры подключения"
        "update" -> "Автоматическое обновление программы"
        "routing" -> "Маршрутизация"
        "subscription" -> "Подписка"
        "servers" -> "Серверы"
        "log" -> "Журнал ядра"
        else -> ""
    }

    /** Save one value and mark the kernel's config stale. */
    private fun saved() {
        store.configDirty = true
        recreate()
    }

    private fun header(text: String, action: Int = 0, onAction: (() -> Unit)? = null) {
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(14))
            addView(TextView(this@SectionActivity).apply {
                this.text = "←"
                textSize = 22f
                setTextColor(Color.parseColor("#d0d0d0"))
                setPadding(dp(4), dp(4), dp(14), dp(4))
                isClickable = true
                setOnClickListener { finish() }
            })
            addView(TextView(this@SectionActivity).apply {
                this.text = text
                textSize = 20f
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (action != 0 && onAction != null) {
                addView(ImageView(this@SectionActivity).apply {
                    setImageResource(action)
                    setColorFilter(Color.parseColor("#d0d0d0"), android.graphics.PorterDuff.Mode.SRC_IN)
                    isClickable = true
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = dp(12).toFloat()
                        setColor(Color.parseColor("#181818"))
                    }
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                    layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                    setOnClickListener { onAction() }
                })
            }
        })
    }

    /**
     * «Автоматическое обновление программы»: расписание, ручная проверка, установка, разрешения.
     *
     * One screen instead of two rows in the menu, because these are steps of one story: how often
     * to look, what came back, what to do with it, and what Android must allow for the last step.
     */
    private fun update() {
        header(title())

        val intervalCard = Ui.card(this)
        intervalCard.addView(Ui.title(this, "Проверять обновления"))
        val interval = Updater.Interval.of(store.updateInterval)
        intervalCard.addView(Ui.line(this, interval.label).apply {
            setTextColor(Color.parseColor("#7bd88f"))
        })
        val detail = Ui.line(this, updateDetail(interval))
        intervalCard.addView(detail)
        intervalCard.addView(Ui.line(this,
            "Новая версия скачивается сама, а при открытии главного экрана приложение " +
                "спросит, устанавливать ли её"))
        intervalCard.isClickable = true
        intervalCard.setOnClickListener {
            val options = Updater.Interval.entries.map { it.label }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("Проверять обновления")
                .setSingleChoiceItems(options, Updater.Interval.entries.indexOf(interval)) { dialog, which ->
                    store.updateInterval = Updater.Interval.entries[which].key
                    dialog.dismiss()
                    recreate()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        root.addView(intervalCard)

        // Проверка руками: то же, что делает запуск, но по нажатию и с ответом на экране.
        // Скачанное сразу предлагается поставить — ждать следующего входа в главный экран,
        // когда человек уже здесь и явно попросил, было бы странно.
        lateinit var checkButton: TextView
        val state = Ui.line(this, store.pendingUpdateVersion?.let { "скачано $it, ждёт установки" } ?: "")
        checkButton = Ui.button(this, "Проверить обновления") { checkNow(checkButton, state) }
        root.addView(checkButton)
        root.addView(state.apply { setPadding(dp(28), 0, dp(28), dp(6)) })

        val pending = store.pendingUpdate(this)
        val pendingVersion = store.pendingUpdateVersion
        if (pending != null && pendingVersion != null && Updater.newer(pendingVersion, Updater.currentVersion(this))) {
            root.addView(Ui.button(this, "Установить обновление $pendingVersion") {
                UpdateUi.install(this, pending) {
                    store.pendingUpdateVersion = null
                    store.pendingUpdateFile = null
                }
            })
        }

        // Разрешение — последний шаг этой же истории: без него установка упирается в системный
        // экран, и хорошо бы, чтобы человек узнал об этом здесь, а не в момент обновления.
        canInstall = Updater.canInstall(this)
        val integration = Ui.card(this)
        integration.addView(Ui.title(this, "Интеграция с Android"))
        integration.addView(Ui.state(
            this,
            if (canInstall) "установка программ: разрешено" else "установка программ: запрещено",
            canInstall,
        ))
        integration.addView(Ui.line(this,
            "Без этого разрешения приложение не может поставить скачанное обновление. " +
                "Разрешение даётся системе, а не приложению: Android откроет свой экран"))
        // Тап по всему пункту, а не отдельная кнопка: пункт и есть одно действие, и вторая
        // кнопка внутри него только спрашивала, которую из двух нажимать.
        integration.addView(Ui.line(this, "нажмите, чтобы открыть настройки Android", mono = false).apply {
            setTextColor(Color.parseColor("#4f8fe0"))
        })
        integration.isClickable = true
        integration.setOnClickListener {
            runCatching { Updater.openInstallPermission(this) }
                .onFailure { toast("системный экран не открылся: ${it.message}") }
        }
        root.addView(integration)
    }

    /** Один запрос к GitHub по нажатию: скачать и сразу предложить установку. */
    private fun checkNow(button: TextView, state: TextView) {
        button.isEnabled = false
        button.alpha = 0.6f
        button.text = "проверяю…"
        state.text = ""
        Thread {
            val outcome = Updater.checkAndDownload(this)
            runOnUiThread {
                button.isEnabled = true
                button.alpha = 1f
                button.text = "Проверить обновления"
                val file = outcome.file
                val version = outcome.version
                if (file == null || version == null) {
                    state.text = outcome.text
                    return@runOnUiThread
                }
                store.pendingUpdateVersion = version
                store.pendingUpdateFile = file.name
                state.text = "скачано $version, ждёт установки"
                // Отказ здесь не отменяет обновление: файл остаётся, и главный экран спросит
                // сам — ровно так, как если бы версию нашла фоновая проверка.
                UpdateUi.offerInstall(
                    this, version, file,
                    onDecline = { toast("обновление $version ждёт: спрошу на главном экране") },
                    onStarted = {
                        store.pendingUpdateVersion = null
                        store.pendingUpdateFile = null
                    },
                )
            }
        }.apply { isDaemon = true }.start()
    }

    /** Когда проверяли в прошлый раз — и не ждёт ли установки скачанное. */
    private fun updateDetail(interval: Updater.Interval): String {
        if (interval == Updater.Interval.NEVER) return "проверка выключена"
        val last = store.lastUpdateCheck
        if (last == 0L) return "ещё не проверяли"
        val minutes = (System.currentTimeMillis() - last) / 60_000
        val ago = when {
            minutes < 1 -> "только что"
            minutes < 60 -> "$minutes мин назад"
            minutes < 60 * 24 -> "${minutes / 60} ч назад"
            else -> "${minutes / (60 * 24)} дн назад"
        }
        return "последняя проверка: $ago"
    }

    /** Everything an app needs to reach the proxy: the port and the credentials. */
    private fun params() {
        header(title())
        root.addView(valueRow("Порт", store.port.toString()) { value ->
            val port = value.toIntOrNull()
            if (port == null || port !in 1024..65535) {
                toast("порт должен быть числом от 1024 до 65535")
            } else {
                store.port = port
                saved()
            }
        })
        root.addView(valueRow("Логин", store.user) { value ->
            if (value.isBlank()) toast("логин не может быть пустым") else {
                store.user = value
                saved()
            }
        })
        root.addView(valueRow("Пароль", store.password) { value ->
            if (value.isBlank()) toast("пароль не может быть пустым") else {
                store.password = value
                saved()
            }
        })
        root.addView(valueRow("Порт HTTP", store.httpPort.toString()) { value ->
            val port = value.toIntOrNull()
            if (port == null || (port != 0 && port !in 1024..65535)) {
                toast("0 — выключить, иначе порт от 1024 до 65535")
            } else {
                store.httpPort = port
                saved()
            }
        })
        root.addView(Ui.line(this,
            "HTTP-прокси нужен браузерам: они не умеют SOCKS с паролем. 0 — выключено. " +
                "Логин и пароль у него те же").apply {
            setPadding(dp(28), dp(2), dp(28), dp(6))
        })

        // Telegram принимает настройки прокси ссылкой t.me/socks: адрес, порт, логин и пароль
        // оно вписывает само, и человеку не надо переносить четыре значения с экрана на экран.
        // Логин и пароль берутся из текущих настроек, так что ссылка всегда описывает то, что
        // действительно слушает 127.0.0.1.
        lateinit var telegramButton: TextView
        telegramButton = Ui.button(this, "Настроить Telegram автоматически") {
            configureTelegram(telegramButton)
        }
        root.addView(telegramButton)
        root.addView(Ui.line(this,
            "Ядро перезапустится, потом откроется Telegram с уже вписанным прокси " +
                "127.0.0.1:${store.port} — останется включить «Использовать прокси»").apply {
            setPadding(dp(28), dp(2), dp(28), dp(6))
        })

        root.addView(valueRow("Порт WhatsApp (чат)", store.waChatPort.toString()) { value ->
            val port = value.toIntOrNull()
            if (port == null || (port != 0 && port !in 1024..65535)) {
                toast("0 — выключить, иначе порт от 1024 до 65535")
            } else {
                store.waChatPort = port
                saved()
            }
        })
        root.addView(valueRow("Порт WhatsApp (медиа)", store.waMediaPort.toString()) { value ->
            val port = value.toIntOrNull()
            if (port == null || (port != 0 && port !in 1024..65535)) {
                toast("0 — выключить, иначе порт от 1024 до 65535")
            } else {
                store.waMediaPort = port
                saved()
            }
        })
        root.addView(Ui.line(this,
            "WhatsApp умеет ходить через собственный прокси-сервер. Впиши порт чата и порт медиа " +
                "(0 — выключено), а в самом WhatsApp укажи хост 127.0.0.1 и эти порты: " +
                "Настройки → Данные и хранилище → Прокси → Настроить прокси. Звонки через прокси " +
                "не работают — это ограничение WhatsApp, а не приложения").apply {
            setPadding(dp(28), dp(2), dp(28), dp(6))
        })

        // WhatsApp умеет принимать настройки ссылкой wa.me/proxy — тогда хост и порты оно
        // заполняет само. Ссылка нужна только как повод открыть WhatsApp: человеку её видеть
        // незачем, а если он пойдёт руками, у него под рукой те же два значения.
        //
        // Кнопка появляется только когда заданы оба порта. Ссылка с нулём в порту выглядит как
        // удавшаяся настройка и не работает — худший вид ошибки, потому что её потом ищут глазами.
        val waChat = store.waChatPort
        val waMedia = store.waMediaPort
        if (waChat > 0 && waMedia > 0) {
            lateinit var button: TextView
            button = Ui.button(this, "Настроить WhatsApp автоматически") {
                configureWhatsApp(button, waChat, waMedia)
            }
            root.addView(button)
            root.addView(Ui.line(this,
                "Ядро перезапустится, потом откроется WhatsApp с уже вписанным адресом " +
                    "127.0.0.1 и портами $waChat и $waMedia — останется нажать «Готово»").apply {
                setPadding(dp(28), dp(2), dp(28), dp(6))
            })
        } else {
            root.addView(Ui.line(this,
                "Задай оба порта — и здесь появится кнопка, которая настроит WhatsApp сама").apply {
                setPadding(dp(28), dp(2), dp(28), dp(6))
            })
        }
        root.addView(Ui.card(this).apply {
            // mux cannot be combined with xtls-rprx-vision: that flow is a different framing on
            // the same connection, and the kernel answers "unexpected response version". Saying so
            // here is better than a switch that silently does nothing.
            val flow = store.selected()?.flow.orEmpty()
            val possible = flow.isBlank()
            val label = Ui.line(this@SectionActivity, "")
            val paint = {
                label.text = when {
                    !possible -> "Мультиплексирование: недоступно (в ссылке flow=$flow)"
                    store.muxEnabled -> "Мультиплексирование: включено"
                    else -> "Мультиплексирование: выключено"
                }
                label.setTextColor(
                    when {
                        !possible -> Color.parseColor("#9a9a9a")
                        store.muxEnabled -> Color.parseColor("#7bd88f")
                        else -> Color.parseColor("#e0574f")
                    },
                )
            }
            paint()
            addView(label)
            setOnClickListener {
                if (!possible) {
                    toast("эта конфигурация ходит с xtls-rprx-vision, mux с ней несовместим")
                    return@setOnClickListener
                }
                store.muxEnabled = !store.muxEnabled
                // no recreate here: re-creating the screen threw the person back a level,
                // and this row is readable on its own without a redraw
                store.configDirty = true
                paint()
                toast(if (store.muxEnabled) "мультиплексирование включено" else "выключено")
            }
        })
        root.addView(Ui.line(this,
            "Одно соединение на все запросы вместо нового на каждый. Меньше поводов " +
                "придраться тому, кто считает соединения, но первый запрос платит за общую " +
                "сессию. Не работает вместе с xtls-rprx-vision — там mux недоступен").apply {
            setPadding(dp(28), dp(2), dp(28), dp(6))
        })
        root.addView(valueRow("Адрес теста скорости", store.speedUrl) { value ->
            if (!value.startsWith("http")) {
                toast("нужен адрес вида https://…")
            } else {
                store.speedUrl = value
                saved()
            }
        })
        root.addView(Ui.line(this,
            "Кнопка «скорость» на главном экране скачивает этот адрес через туннель и показывает " +
                "скорость. По умолчанию — файл Cloudflare на двадцать мегабайт; можно указать свой").apply {
            setPadding(dp(28), dp(2), dp(28), dp(6))
        })
        root.addView(Ui.line(this, "Эти значения вводятся в приложении, которое будет ходить через прокси").apply {
            setPadding(dp(28), dp(10), dp(28), 0)
        })
    }

    /**
     * Two lists of domains.
     *
     * The second one wins: a name that is on "always through the server" goes through the server
     * even if it also appears on "direct". Each name is a plate — long-press removes it, and the
     * field at the bottom adds one, with a square that takes it from the clipboard.
     */
    private fun routing() {
        header(title())
        domainSection(
            title = "Напрямую",
            hint = "Эти домены идут мимо сервера. Долгое нажатие — удалить",
            values = store.directDomains,
            onForget = { value -> store.directDomains = store.directDomains - value; store.configDirty = true },
            onAdd = { value -> store.directDomains = store.directDomains + value; store.configDirty = true },
        )
        domainSection(
            title = "Всегда через прокси",
            hint = "Эти домены идут через сервер, даже если попали в список выше",
            values = store.proxyDomains,
            onForget = { value -> store.proxyDomains = store.proxyDomains - value; store.configDirty = true },
            onAdd = { value -> store.proxyDomains = store.proxyDomains + value; store.configDirty = true },
        )
    }

    private fun domainSection(
        title: String,
        hint: String,
        values: List<String>,
        onForget: (String) -> Unit,
        onAdd: (String) -> Unit,
    ) {
        root.addView(TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(dp(20), dp(18), dp(20), dp(2))
        })
        root.addView(Ui.line(this, hint).apply { setPadding(dp(20), 0, dp(20), dp(10)) })

        if (values.isEmpty()) {
            root.addView(Ui.line(this, "пока пусто").apply { setPadding(dp(20), 0, dp(20), dp(8)) })
        }
        val plates = FlowLayout(this).apply {
            setPadding(dp(16), 0, dp(16), 0)
        }
        for (domain in values) {
            plates.addView(TextView(this).apply {
                text = domain
                textSize = 15f
                setTextColor(Color.WHITE)
                setPadding(dp(14), dp(9), dp(14), dp(9))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(Color.parseColor("#1a1a1a"))
                }
                isClickable = true
                setOnLongClickListener {
                    onForget(domain)
                    recreate()
                    true
                }
                layoutParams = ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { setMargins(0, 0, dp(8), dp(8)) }
            })
        }
        root.addView(plates)

        val adder = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(0))
        }
        val input = EditText(this).apply {
            setHint("домен, например ru")
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#6f6f6f"))
            inputType = InputType.TYPE_CLASS_TEXT
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        adder.addView(input)
        root.addView(adder)
        root.addView(Ui.button(this, "Добавить в «$title»") {
            val value = input.text.toString().trim()
            if (value.isBlank()) {
                toast("введите домен")
            } else {
                onAdd(value)
                recreate()
            }
        })
    }

    private fun subscription() {
        header(title())
        root.addView(valueRow("Адрес подписки", store.subscription.ifBlank { "не задана" }) { value ->
            store.subscription = value
            saved()
        })
        root.addView(Ui.button(this, "Проверить подписку") { checkSubscription() })
        root.addView(Ui.button(this, "Загрузить серверы") { loadSubscription() })
    }

    private fun servers() {
        // "+" belongs here: this is the screen where servers are added
        header(title(), R.drawable.ic_plus) { addServers() }
        val servers = store.servers
        if (servers.isEmpty()) {
            root.addView(Ui.line(this, "пусто: нажмите + на главном экране и вставьте ссылку").apply {
                setPadding(dp(28), dp(10), dp(28), 0)
            })
            return
        }
        val selected = store.selected()
        for (server in servers) {
            val card = Ui.card(this)
            card.addView(Ui.title(this, (if (server.id == selected?.id) "● " else "○ ") + server.title()))
            card.addView(Ui.line(this, "${server.protocol.uppercase()} / ${server.transport.uppercase()} · ${server.host}:${server.port}"))
            card.setOnClickListener { actions(server) }
            card.setOnLongClickListener {
                androidx.appcompat.app.AlertDialog.Builder(this@SectionActivity)
                    .setTitle("Удалить конфигурацию?")
                    .setMessage("Вы уверены, что хотите удалить «${server.title()}»?")
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton("Удалить") { _, _ ->
                        store.forget(server)
                        recreate()
                    }
                    .show()
                true
            }
            root.addView(card)
        }
        root.addView(Ui.line(this, "нажатие — действия, долгое — забыть").apply {
            setPadding(dp(28), dp(10), dp(28), 0)
        })
    }

    /**
     * What can be done with one saved server.
     *
     * A tap used to mean only "choose this one", so a link that turned out wrong had no door into
     * an edit: the row looked tappable, did nothing visible when it was already the chosen one,
     * and the only way out was to forget it and paste again.
     */
    private fun actions(server: Server) {
        val active = store.selected()?.id == server.id
        val labels = buildList {
            if (!active) add("Сделать активным")
            add("Изменить ссылку")
            add("Забыть")
        }
        AlertDialog.Builder(this)
            .setTitle(server.title())
            .setItems(labels.toTypedArray()) { _, index ->
                when (labels[index]) {
                    "Сделать активным" -> {
                        store.select(server)
                        recreate()
                    }
                    "Изменить ссылку" -> LinkEditor.show(this, store, server) { recreate() }
                    else -> forget(server)
                }
            }
            .show()
    }

    /** Deleting a configuration is not undoable, so it asks first, with its name. */
    private fun forget(server: Server) {
        AlertDialog.Builder(this)
            .setTitle("Удалить конфигурацию?")
            .setMessage("Вы уверены, что хотите удалить «${server.title()}»?")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Удалить") { _, _ ->
                store.forget(server)
                recreate()
            }
            .show()
    }

    /**
     * The kernel's own words, in a box that scrolls on its own.
     *
     * The refresh square sits in the header, not in the flow: a button that scrolls away with the
     * text it refreshes is a button you have to hunt for.
     */
    private fun log() {
        header(title(), R.drawable.ic_refresh) { recreate() }
        val lines = ProxyService.State.lines.takeLast(200)
        val text = TextView(this).apply {
            this.text = lines.joinToString("\n").ifBlank { "пока пусто" }
            textSize = 12f
            setTextColor(Color.parseColor("#9a9a9a"))
            setTypeface(android.graphics.Typeface.MONOSPACE)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val scroller = ScrollView(this).apply {
            addView(text)
            // weight: журнал забирает всё, что осталось от экрана, а не 420 точек из воздуха
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
            ).apply { setMargins(dp(12), 0, dp(12), dp(12)) }
            setBackgroundColor(Color.parseColor("#111111"))
        }
        root.addView(scroller)
        root.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
        )
        // start at the end: the last lines are the ones that matter
        scroller.post { scroller.fullScroll(View.FOCUS_DOWN) }
    }

    /** Links and subscriptions both arrive from the clipboard; this is where they are taken in. */
    private fun addServers() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if (text.isBlank()) {
            toast("в буфере пусто")
            return
        }
        val direct = ru.uvw.byecensor.logic.Server.parseAll(text)
        if (direct.isNotEmpty()) {
            val added = store.add(direct)
            toast(if (added == 0) "такие ссылки уже есть" else "добавлено: $added")
            recreate()
            return
        }
        if (text.startsWith("http://") || text.startsWith("https://")) {
            store.subscription = text.trim()
            toast("тяну подписку…")
            Thread {
                runCatching { ru.uvw.byecensor.logic.Subscription.fetch(text.trim()) }
                    .onSuccess { servers ->
                        val added = store.add(servers)
                        runOnUiThread {
                            toast(if (added == 0) "новых серверов нет" else "добавлено: $added")
                            recreate()
                        }
                    }
                    .onFailure { error ->
                        runOnUiThread { toast("подписка: ${error.message}") }
                    }
            }.apply { isDaemon = true }.start()
            return
        }
        toast("в буфере не ссылка: нужна vless://… или адрес подписки")
    }

    /** A setting that is also a thing to copy: the row edits it, the square beside it copies. */
    private fun valueRow(name: String, current: String, done: (String) -> Unit): View {
        val card = Ui.card(this)
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        line.addView(TextView(this).apply {
            text = name
            textSize = 17f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        line.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_copy)
            contentDescription = "скопировать $name"
            isClickable = true
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#181818"))
            }
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(name, current))
                toast("скопировано: $name")
            }
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        })
        card.addView(line)
        card.addView(Ui.line(this, current, mono = true))
        card.setOnClickListener { edit(name, current, done) }
        return card
    }

    private fun edit(name: String, current: String, done: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(current)
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this)
            .setTitle(name)
            .setView(input)
            .setPositiveButton("Сохранить") { _, _ -> done(input.text.toString().trim()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Restart the kernel, wait for both WhatsApp doors to answer, then hand WhatsApp a link that
     * fills its proxy settings in by itself.
     *
     * The restart is not politeness. WhatsApp probes the proxy while it saves the settings, so a
     * door that is not up yet — a port that was edited a minute ago, a kernel that is still being
     * replaced — turns the whole gesture into "proxy unavailable", and the person has to find and
     * undo half-filled settings by hand. Waiting for both doors is the difference between a button
     * that works and a button that looks broken.
     *
     * The media door is checked against Meta's certificate, not ours: it is a passthrough, and a
     * media port that answers with our certificate means the traffic is going somewhere else
     * entirely. That check needs the tunnel to be alive, which is exactly the state WhatsApp needs
     * too, so its failure is a real failure and worth saying out loud.
     */
    private fun configureWhatsApp(button: TextView, chat: Int, media: Int) {
        button.isEnabled = false
        button.alpha = 0.6f
        button.text = "перезапускаю ядро…"
        // reload replaces the kernel in place; start is for a proxy that is not running at all —
        // reload on a stopped service builds a kernel without the front ports and watchers
        if (ProxyService.State.running) ProxyService.reload(this) else ProxyService.start(this)
        Thread {
            val deadline = System.currentTimeMillis() + 25_000
            var complaint: String? = null
            var ready = false
            while (System.currentTimeMillis() < deadline) {
                val chatAnswer = Probe.whatsappPort(chat, expectOwnCertificate = true, timeoutMs = 1500)
                if (!chatAnswer.ok) {
                    complaint = "чат: ${chatAnswer.text}"
                    Thread.sleep(400)
                    continue
                }
                val mediaAnswer = Probe.whatsappPort(media, expectOwnCertificate = false, timeoutMs = 2000)
                if (!mediaAnswer.ok) {
                    complaint = "медиа: ${mediaAnswer.text}"
                    Thread.sleep(400)
                    continue
                }
                ready = true
                break
            }
            runOnUiThread {
                button.isEnabled = true
                button.alpha = 1f
                button.text = "Настроить WhatsApp автоматически"
                if (!ready) {
                    toast("прокси не поднялся — ${complaint ?: "двери не ответили за 25 секунд"}")
                    return@runOnUiThread
                }
                val link = "https://wa.me/proxy?host=127.0.0.1&chatPort=$chat" +
                    "&mediaPort=$media&chatTLS=true"
                val opened = runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(link)))
                }
                if (opened.isFailure) {
                    // WhatsApp не установлен: пусть хотя бы останется, что вписать руками
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("WhatsApp", "127.0.0.1, $chat, $media"))
                    toast("WhatsApp не открылся — адрес и порты скопированы в буфер")
                }
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * Restart the kernel, wait until the SOCKS door really answers, then hand Telegram a link that
     * fills its proxy settings in by itself.
     *
     * Same reasoning as the WhatsApp button: Telegram pings the proxy while it saves it, so a door
     * that is still coming up turns the gesture into "прокси недоступен" and the person has to
     * clean up half-filled settings. The link is built from the credentials this phone actually
     * uses — a link with a stale password would look like a successful setup and fail on connect.
     */
    private fun configureTelegram(button: TextView) {
        val port = store.port
        val user = store.user
        val password = store.password
        button.isEnabled = false
        button.alpha = 0.6f
        button.text = "перезапускаю ядро…"
        if (ProxyService.State.running) ProxyService.reload(this) else ProxyService.start(this)
        Thread {
            val deadline = System.currentTimeMillis() + 25_000
            var complaint: String? = null
            var ready = false
            while (System.currentTimeMillis() < deadline) {
                val answer = Probe.socks(port, user, password, timeoutMs = 3000)
                if (answer.ok) {
                    ready = true
                    break
                }
                complaint = answer.text
                Thread.sleep(400)
            }
            runOnUiThread {
                button.isEnabled = true
                button.alpha = 1f
                button.text = "Настроить Telegram автоматически"
                if (!ready) {
                    toast("прокси не поднялся — ${complaint ?: "SOCKS не ответил за 25 секунд"}")
                    return@runOnUiThread
                }
                val link = "https://t.me/socks?server=127.0.0.1&port=$port" +
                    "&user=${encoded(user)}&pass=${encoded(password)}"
                val opened = runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(link)))
                }
                if (opened.isFailure) {
                    // Telegram не установлен: пусть хотя бы останется, что вписать руками
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText("Telegram", "127.0.0.1, $port, $user, $password"),
                    )
                    toast("Telegram не открылся — адрес, порт, логин и пароль скопированы в буфер")
                }
            }
        }.apply { isDaemon = true }.start()
    }

    /** A value inside a t.me query: a password may contain anything the person typed. */
    private fun encoded(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun checkSubscription() {
        val url = store.subscription
        if (url.isBlank()) {
            toast("сначала задайте адрес подписки")
            return
        }
        toast("проверяю подписку…")
        Thread {
            val result = Probe.subscription(url)
            runOnUiThread { toast(result.text) }
        }.apply { isDaemon = true }.start()
    }

    private fun loadSubscription() {
        val url = store.subscription
        if (url.isBlank()) {
            toast("сначала задайте адрес подписки")
            return
        }
        toast("тяну подписку…")
        Thread {
            val answer = runCatching { ru.uvw.byecensor.logic.Subscription.fetch(url) }
            runOnUiThread {
                answer.onSuccess { servers ->
                    val added = store.add(servers)
                    toast(if (added == 0) "новых серверов нет" else "добавлено: $added")
                }.onFailure { toast("подписка: ${it.message}") }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun toast(text: String) =
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
