package ru.uvw.byecensor

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import ru.uvw.byecensor.logic.Updater
import ru.uvw.byecensor.store.Store

/**
 * The menu: five rows, each a screen of its own.
 *
 * Split rather than piled into one list: the port and the password are looked at once a year,
 * the log is looked at when something is broken, and mixing them makes both harder to find.
 */
class SettingsActivity : AppCompatActivity() {
    private lateinit var store: Store
    /** Состояние разрешения на установку: с ним экран понимает, что надо перерисоваться. */
    private var canInstall = false

    override fun onResume() {
        super.onResume()
        // человек уходил на системный экран и, возможно, что-то там переключил
        val now = Updater.canInstall(this)
        if (now != canInstall) recreate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0b0b0b"))
            setPadding(0, dp(24), 0, dp(24))
        }
        setContentView(ScrollView(this).apply { addView(root) })

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setPadding(dp(20), dp(8), dp(20), dp(14))
            setOnClickListener { finish() }
            addView(TextView(this@SettingsActivity).apply {
                text = "←"
                textSize = 22f
                setTextColor(Color.parseColor("#d0d0d0"))
                setPadding(0, 0, dp(14), 0)
            })
            addView(TextView(this@SettingsActivity).apply {
                text = "Настройки"
                textSize = 20f
                setTextColor(Color.WHITE)
            })
        })

        // the switch that a person looks for first, kept on the menu itself
        val autostart = Ui.card(this)
        autostart.addView(Ui.title(this, "Соединяться при старте"))
        val state = TextView(this).apply {
            text = if (store.autostart) "включено" else "выключено"
            textSize = 15f
            setTextColor(if (store.autostart) Color.parseColor("#7bd88f") else Color.parseColor("#e0574f"))
            setPadding(0, dp(4), 0, 0)
        }
        autostart.addView(state)
        autostart.addView(Ui.line(this,
            "Прокси поднимется после загрузки телефона и после обновления приложения. " +
                "Если Android откажет — придёт уведомление с кнопкой"))
        autostart.setOnClickListener {
            store.autostart = !store.autostart
            state.text = if (store.autostart) "включено" else "выключено"
            state.setTextColor(
                if (store.autostart) Color.parseColor("#7bd88f") else Color.parseColor("#e0574f"),
            )
        }
        root.addView(autostart)

        root.addView(integrationCard())
        root.addView(updateCard())
        root.addView(section("Параметры подключения", "${store.port} · ${store.user}", "params"))
        val direct = store.directDomains.size
        val through = store.proxyDomains.size
        root.addView(section(
            "Маршрутизация",
            "${direct} напрямую · ${through} через прокси",
            "routing",
        ))
        root.addView(section("Подписка", store.subscription.ifBlank { "не задана" }, "subscription"))
        root.addView(section("Серверы", if (store.servers.isEmpty()) "пусто" else "${store.servers.size} шт.", "servers"))
        root.addView(section("Журнал ядра", "строки, которые сказал ядро", "log"))
    }

    /**
     * «Интеграция с Android»: the permissions this app needs from the system itself.
     *
     * Installing an APK is not a permission of the app but of the person: Android keeps it on a
     * system screen, and an app can only lead there. Without this row the permission existed
     * invisibly — the update dialog offered «Открыть настройки» and nothing said where that was.
     */
    private fun integrationCard(): android.view.View {
        canInstall = Updater.canInstall(this)
        val card = Ui.card(this)
        card.addView(Ui.title(this, "Интеграция с Android"))
        card.addView(Ui.state(
            this,
            if (canInstall) "установка программ: разрешено" else "установка программ: запрещено",
            canInstall,
        ))
        card.addView(Ui.line(this,
            "Без этого разрешения приложение не может поставить скачанное обновление. " +
                "Разрешение даётся системе, а не приложению: Android откроет свой экран"))
        val button = Ui.button(this, "Разрешить установку программ") {
            runCatching { Updater.openInstallPermission(this) }
                .onFailure {
                    android.widget.Toast.makeText(
                        this, "системный экран не открылся: ${it.message}",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
        }
        card.addView(button)
        return card
    }

    /**
     * «Проверять обновления»: how often to ask GitHub, and what the last answer was.
     *
     * The schedule lives here rather than in a system setting, because the only thing that
     * decides whether a check is worth making is the person who pays for the traffic.
     */
    private fun updateCard(): android.view.View {
        val card = Ui.card(this)
        val interval = Updater.Interval.of(store.updateInterval)
        card.addView(Ui.title(this, "Проверять обновления"))
        val value = TextView(this).apply {
            text = interval.label
            textSize = 15f
            setTextColor(Color.parseColor("#7bd88f"))
            setPadding(0, dp(4), 0, 0)
        }
        card.addView(value)
        val detail = Ui.line(this, updateDetail(interval))
        card.addView(detail)
        card.addView(Ui.line(this,
            "Новая версия скачивается сама, а при открытии главного экрана приложение " +
                "спросит, устанавливать ли её"))
        card.isClickable = true
        card.setOnClickListener {
            // никакого "никогда" по умолчанию и никакого скрытого согласия: выбор человека
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
        return card
    }

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
        val version = store.pendingUpdateVersion
        return if (version != null) "последняя проверка: $ago · скачано $version, ждёт установки"
        else "последняя проверка: $ago"
    }

    private fun section(title: String, value: String, key: String) =
        Ui.row(this, title, value, "нажмите, чтобы открыть") {
            startActivity(Intent(this, SectionActivity::class.java).putExtra("section", key))
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
