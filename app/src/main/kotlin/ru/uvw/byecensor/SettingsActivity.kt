package ru.uvw.byecensor

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import ru.uvw.byecensor.store.Store

/**
 * The menu: five rows, each a screen of its own.
 *
 * Split rather than piled into one list: the port and the password are looked at once a year,
 * the log is looked at when something is broken, and mixing them makes both harder to find.
 */
class SettingsActivity : AppCompatActivity() {
    private lateinit var store: Store

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

    private fun section(title: String, value: String, key: String) =
        Ui.row(this, title, value, "нажмите, чтобы открыть") {
            startActivity(Intent(this, SectionActivity::class.java).putExtra("section", key))
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
