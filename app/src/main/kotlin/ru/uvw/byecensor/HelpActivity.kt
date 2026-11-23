package ru.uvw.byecensor

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * How to point an app at this proxy, per app.
 *
 * A proxy nobody can configure is a proxy nobody uses, and every app hides its proxy settings
 * somewhere else. The screen is a list of what is known — and what is not: Chrome and YouTube take
 * no proxy of their own, so saying "turn it on there" would be a lie that costs an hour.
 */
class HelpActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0b0b0b"))
            setPadding(0, dp(24), 0, dp(24))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0b0b0b"))
            addView(root)
        })

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setPadding(dp(20), dp(8), dp(20), dp(14))
            setOnClickListener { finish() }
            addView(TextView(this@HelpActivity).apply {
                text = "←"
                textSize = 22f
                setTextColor(Color.parseColor("#d0d0d0"))
                setPadding(0, 0, dp(14), 0)
            })
            addView(TextView(this@HelpActivity).apply {
                text = "Помощь"
                textSize = 20f
                setTextColor(Color.WHITE)
            })
        })

        // первое, на что натыкаются: приложение настроено, Bye, Censor! не запущен, и в приложении
        // «нет сети» — выглядит как поломка, а это просто выключенный прокси
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#2a1614"))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(dp(12), 0, dp(12), dp(10)) }
            addView(TextView(this@HelpActivity).apply {
                text = "Важно"
                textSize = 16f
                setTextColor(Color.parseColor("#e08a80"))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(TextView(this@HelpActivity).apply {
                text = "Приложения, настроенные на этот прокси, без запущенного Bye, Censor! работать " +
                    "не будут: они стучатся в 127.0.0.1:1099, а слушать там некому.\n\n" +
                    "Если приложение пишет «нет сети» — откройте Bye, Censor!. Прокси поднимается сам, " +
                    "как только приложение открыто."
                textSize = 14f
                setTextColor(Color.parseColor("#d8c9c7"))
                setPadding(0, dp(8), 0, 0)
                setLineSpacing(dp(4).toFloat(), 1f)
            })
        })

        section(root, "Приложения", "Куда вписать адрес прокси. Хост, порт, логин и пароль — в «Настройках» → «Параметры подключения»")

        article(
            root, "Telegram",
            """
            Проще всего кнопкой: «Настройки» → «Параметры подключения» → «Настроить Telegram
            автоматически». Ядро перезапустится, откроется Telegram с уже вписанными адресом,
            портом, логином и паролем — останется включить «Использовать прокси».

            Руками: Настройки → «Данные и память» → вниз «Настройка прокси» → «Добавить прокси» →
            SOCKS5, сервер 127.0.0.1, порт 1099, логин и пароль — из «Параметров подключения».
            """.trimIndent(),
        )

        article(
            root, "Firefox",
            """
            Проще всего расширением: «Proxy Toggle» из дополнений Firefox. Оно работает с SOCKS5
            с логином и паролем, и режим поднимается одним переключателем.

            Установка: меню → Дополнения → Proxy Toggle → Добавить. Потом откройте расширение,
            выберите SOCKS5, сервер 127.0.0.1, порт 1099, логин и пароль — из «Параметров
            подключения» Bye, Censor!.

            Ещё вариант — FoxyProxy Standard: тоже работает и тоже умеет пароль, но вёрстка у него
            десктопная и на телефоне неудобная.

            Без расширений — через about:config (только Beta и Nightly): network.proxy.type = 1,
            network.proxy.socks = 127.0.0.1, network.proxy.socks_port = 1099,
            network.proxy.socks_version = 5, network.proxy.socks_remote_dns = true.
            Логин и пароль Firefox при этом спросить не сможет, так что без расширения наш
            SOCKS с паролем не заработает.
            """.trimIndent(),
        )

        article(
            root, "Chrome",
            """
            Сам Chrome для Android расширений не умеет и своих настроек прокси не имеет: он берёт
            системные, а системный прокси на Android — только HTTP, без SOCKS. Так что обычный
            Chrome пойдёт мимо прокси.

            Если браузер через прокси нужен — есть Chromium-браузеры, которые ставят расширения
            из Chrome Web Store: Lemur Browser и Mises Browser. В них ставится Proxy SwitchyOmega
            (или его новая версия ZeroOmega), а он умеет SOCKS5 с логином и паролем.

            Это не проверено на этом телефоне — если попробуете, скажите, что вышло.
            """.trimIndent(),
        )

        article(
            root, "YouTube",
            """
            Тоже не выйдет, и по той же причине: у приложения нет своих настроек прокси,
            оно ходит как системное.

            Через прокси YouTube пойдёт только если смотреть его в браузере, который умеет
            SOCKS, — но см. выше: Chrome не умеет.
            """.trimIndent(),
        )

        article(
            root, "WhatsApp",
            """
            Не выйдет. У WhatsApp есть своя настройка прокси, но это не SOCKS и не HTTP: он ждёт
            их собственный сервер (порты 80, 443 или 5222), а поле принимает только хост и порт,
            без логина и пароля. Наш прокси без пароля не работает, да и протокол другой.

            Если очень нужен WhatsApp через прокси — только системный VPN, а он и есть то, из-за
            чего приложения ругаются.
            """.trimIndent(),
        )

        section(root, "Как это работает", "Коротко")
        root.addView(Ui.card(this).apply {
            addView(Ui.line(this@HelpActivity,
                "На телефоне поднят локальный SOCKS-прокси на 127.0.0.1:1099 с логином и паролем. " +
                    "Он не VPN: приложения, которые ругаются на включённый VPN, его не видят. " +
                    "Кнопка на главном экране выбирает, идти ли трафику через сервер или напрямую — " +
                    "сам прокси при этом работает всегда."))
        })
    }

    private fun section(root: LinearLayout, title: String, subtitle: String) {
        root.addView(TextView(this).apply {
            text = title
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(dp(24), dp(18), dp(24), dp(2))
        })
        root.addView(TextView(this).apply {
            text = subtitle
            textSize = 13f
            setTextColor(Color.parseColor("#8b8b8b"))
            setPadding(dp(24), 0, dp(24), dp(8))
        })
    }

    private fun article(root: LinearLayout, title: String, body: String) {
        root.addView(Ui.card(this).apply {
            addView(Ui.title(this@HelpActivity, title))
            addView(TextView(this@HelpActivity).apply {
                text = body
                textSize = 14f
                setTextColor(Color.parseColor("#c9c9c9"))
                setPadding(0, dp(6), 0, 0)
                setLineSpacing(dp(4).toFloat(), 1f)
            })
        })
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
