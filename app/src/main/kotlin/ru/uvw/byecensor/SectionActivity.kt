package ru.uvw.byecensor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
            "routing" -> routing()
            "subscription" -> subscription()
            "servers" -> servers()
            "log" -> log()
            else -> finish()
        }
    }

    private fun title(): String = when (section) {
        "params" -> "Параметры подключения"
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
        root.addView(Ui.row(
            this,
            "HTTP-прокси без пароля",
            if (store.httpNoAuth) "включено" else "выключено",
            "нужно для системного прокси Wi-Fi: там негде вписать логин",
        ) {
            store.httpNoAuth = !store.httpNoAuth
            saved()
            recreate()
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
                "Логин и пароль у него те же, если не включён режим без пароля").apply {
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
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(server.title())
            .setItems(labels.toTypedArray()) { _, index ->
                when (labels[index]) {
                    "Сделать активным" -> {
                        store.select(server)
                        recreate()
                    }
                    "Изменить ссылку" -> LinkEditor.show(this, store, server) { recreate() }
                    else -> {
                        androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("Удалить конфигурацию?")
                            .setMessage("Вы уверены, что хотите удалить «${server.title()}»?")
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton("Удалить") { _, _ ->
                                store.forget(server)
                                recreate()
                            }
                            .show()
                    }
                }
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
