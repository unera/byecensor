package ru.uvw.byecensor.logic

import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/**
 * A subscription: one URL that answers with links.
 *
 * Panels answer in two shapes — plain lines of `vless://…` or the same text base64-encoded whole —
 * so both are accepted rather than making the person guess which panel they use.
 */
object Subscription {
    /**
     * @param url - what the person pasted.
     * @returns the servers it lists.
     * @throws IllegalArgumentException when the answer is not a list of links.
     */
    fun fetch(url: String): List<Server> {
        require(url.startsWith("http://") || url.startsWith("https://")) {
            "адрес подписки должен начинаться с http:// или https://"
        }
        val body = download(url)
        val plain = Server.parseAll(body)
        if (plain.isNotEmpty()) return plain
        // base64 whole body: the usual panel shape
        val decoded = runCatching {
            String(Base64.getMimeDecoder().decode(body.trim()), Charsets.UTF_8)
        }.getOrNull().orEmpty()
        val fromDecoded = Server.parseAll(decoded)
        if (fromDecoded.isEmpty()) {
            throw IllegalArgumentException("в ответе нет ссылок: это не подписка или она пуста")
        }
        return fromDecoded
    }

    private fun download(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            // some panels answer differently without a browser-ish agent
            setRequestProperty("User-Agent", "ByeCensor/0.1")
            instanceFollowRedirects = true
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalArgumentException("подписка ответила кодом $code")
            return connection.inputStream.bufferedReader().readText()
        } finally {
            connection.disconnect()
        }
    }
}
