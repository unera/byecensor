package ru.uvw.byecensor.logic

import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import javax.net.ssl.SSLSocketFactory

/**
 * The questions a person actually has when nothing works, asked in the order that separates
 * them: is the server reachable at all, does the local proxy accept my credentials, and does
 * traffic come out of the other end.
 *
 * Each probe answers with a sentence a human can act on, not with an exception class: "прокси не
 * принял логин или пароль" says more than `SocketTimeoutException`.
 */
object Probe {
    /** What came back: a short verdict, and whether it counts as success. */
    data class Result(val ok: Boolean, val text: String)

    /**
     * Read exactly what was asked for.
     *
     * A socket read may return fewer bytes than the buffer holds, and every leftover byte stays in
     * the stream: reading four reply bytes as "up to four" leaves the rest of the address to be
     * mistaken for TLS. That is how a perfectly good tunnel produced a BAD_DECRYPT handshake.
     */
    private fun readFully(input: java.io.InputStream, size: Int): ByteArray {
        val buffer = ByteArray(size)
        var read = 0
        while (read < size) {
            val count = input.read(buffer, read, size - read)
            if (count < 0) throw Stage("прокси закрыл соединение на середине ответа")
            read += count
        }
        return buffer
    }

    /** A failure with the stage it happened at, so the answer can name it. */
    private class Stage(text: String) : IOException(text)

    /**
     * Reachability of the upstream: a plain TCP connect.
     *
     * This cannot prove the proxy will work — the server may reject the key — but it proves the
     * network path, which is the first thing to rule out.
     */
    fun server(server: Server, timeoutMs: Int = 5000): Result {
        val started = System.currentTimeMillis()
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(server.host, server.port), timeoutMs)
                val ms = System.currentTimeMillis() - started
                Result(true, "${server.host}:${server.port} отвечает, ${ms} мс")
            }
        } catch (error: IOException) {
            Result(false, "${server.host}:${server.port} не отвечает: ${error.message ?: "нет связи"}")
        }
    }

    /**
     * The whole chain through our own SOCKS, in plain HTTP.
     *
     * @returns the exit address, which is the one thing a person wants to see to believe it.
     */
    fun socks(
        port: Int,
        user: String,
        password: String,
        targetHost: String = "api.ipify.org",
        timeoutMs: Int = 8000,
    ): Result = try {
        open(port, user, password, targetHost, 80, timeoutMs).use { socket ->
            val request = "GET / HTTP/1.1\r\nHost: $targetHost\r\nUser-Agent: byecensor\r\n" +
                "Connection: close\r\n\r\n"
            socket.getOutputStream().write(request.toByteArray(Charsets.UTF_8))
            socket.getOutputStream().flush()
            val answer = socket.getInputStream().bufferedReader().readText()
            val body = answer.substringAfter("\r\n\r\n", "").trim()
            if (body.isBlank()) Result(false, "прокси ответил, но тело пустое: ${answer.take(80)}")
            else Result(true, body.trim().take(46))
        }
    } catch (error: Stage) {
        Result(false, error.message ?: "прокси не пустил")
    } catch (error: IOException) {
        Result(false, short(error))
    }

    /**
     * The same chain over HTTPS.
     *
     * `ifconfig.me` answers only over TLS, and an app that uses this proxy will use HTTP and
     * HTTPS both — so the test that matters is the one that ends in a TLS handshake. After the
     * SOCKS negotiation the socket is wrapped in TLS and the request goes through it.
     */
    /**
     * The proxy test, honest about which half is broken.
     *
     * A TLS failure through a working proxy almost always means the path mangles large records
     * (an MTU problem between the phone and the server), while small requests still get through.
     * Rather than reporting "не работает", try the plain-HTTP request as well and say which of the
     * two arrived: that difference is the diagnosis.
     */
    fun proxy(port: Int, user: String, password: String): Result {
        val secure = socksHttps(port, user, password)
        if (secure.ok) return secure
        val plain = socks(port, user, password)
        return when {
            plain.ok -> Result(
                false,
                "HTTPS не проходит (${secure.text}), а HTTP проходит: ${plain.text}. " +
                    "Похоже на MTU канала до сервера",
            )
            else -> Result(false, secure.text)
        }
    }


    /**
     * Does a WhatsApp door answer, and whose certificate comes back?
     *
     * The chat door presents our own self-signed certificate (that is what Meta's own proxy does),
     * the media door forwards straight to `whatsapp.net`, so the certificate there must be Meta's.
     * Telling those two apart is exactly what a "медиа недоступно" complaint needs: it separates
     * "the kernel is not listening" from "the traffic does not reach WhatsApp".
     */

    /** Trusts the certificate the chat door presents, which is ours and self-signed by design. */
    private fun ownCertificateFactory(): SSLSocketFactory {
        val trustAll = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
        })
        val context = javax.net.ssl.SSLContext.getInstance("TLS")
        context.init(null, trustAll, java.security.SecureRandom())
        return context.socketFactory
    }

    fun whatsappPort(port: Int, expectOwnCertificate: Boolean, timeoutMs: Int = 8000): Result = try {
        val started = System.currentTimeMillis()
        val raw = java.net.Socket()
        raw.connect(java.net.InetSocketAddress("127.0.0.1", port), timeoutMs)
        raw.soTimeout = timeoutMs
        // The chat door presents OUR self-signed certificate — that is the design, the same one
        // Meta's proxy uses — so verifying it against the system roots would report a failure
        // where everything is in fact fine. The media door is a passthrough, and there the
        // certificate must come from Meta; a permissive context would hide exactly that.
        val factory = if (expectOwnCertificate) ownCertificateFactory() else SSLSocketFactory.getDefault() as SSLSocketFactory
        val serverName = if (expectOwnCertificate) "proxy.whatsapp.net" else "whatsapp.net"
        val tls = factory.createSocket(raw, serverName, port, true) as javax.net.ssl.SSLSocket
        try {
            tls.startHandshake()
            val issuer = tls.session.peerCertificates.firstOrNull()
                ?.let { (it as? java.security.cert.X509Certificate)?.issuerDN?.name }
                .orEmpty()
            val own = issuer.contains("proxy.whatsapp.net")
            val ms = System.currentTimeMillis() - started
            when {
                expectOwnCertificate && !own -> Result(false, "не наш сертификат: $issuer")
                !expectOwnCertificate && own -> Result(false, "вернулся наш сертификат вместо whatsapp.net")
                else -> Result(true, "$ms мс")
            }
        } finally {
            runCatching { tls.close() }
        }
    } catch (error: IOException) {
        Result(false, short(error))
    }

    fun socksHttps(
        port: Int,
        user: String,
        password: String,
        targetHost: String = "ifconfig.me",
        timeoutMs: Int = 12000,
    ): Result = try {
        val started = System.currentTimeMillis()
        val raw = open(port, user, password, targetHost, 443, timeoutMs)
        val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory)
            .createSocket(raw, targetHost, 443, true) as javax.net.ssl.SSLSocket
        try {
            tls.startHandshake()
            val request = "GET /ip HTTP/1.1\r\nHost: $targetHost\r\nUser-Agent: byecensor\r\n" +
                "Accept: text/plain\r\nConnection: close\r\n\r\n"
            tls.outputStream.write(request.toByteArray(Charsets.UTF_8))
            tls.outputStream.flush()
            val answer = tls.inputStream.bufferedReader().readText()
            val status = answer.lineSequence().firstOrNull().orEmpty()
            val body = answer.substringAfter("\r\n\r\n", "").trim()
            val ms = System.currentTimeMillis() - started
            if (body.isBlank()) {
                Result(false, "TLS прошёл, но ответ пустой: $status")
            } else {
                // short on purpose: two lines of prose push the screen around, and the only two
                // things worth reading are where we came out and how long it took
                Result(true, "${body.trim().take(46)} · $ms мс")
            }
        } finally {
            runCatching { tls.close() }
        }
    } catch (error: Stage) {
        Result(false, error.message ?: "прокси не пустил")
    } catch (error: IOException) {
        Result(false, short(error))
    }

    /**
     * SOCKS5 with a password, then CONNECT to the target.
     *
     * Written by hand rather than through a library: the two things worth telling apart are "the
     * proxy refused my password" and "the proxy is fine, the upstream is not", and only the
     * handshake separates them.
     *
     * @returns a socket already connected to the target, positioned after the reply.
     */
    private fun open(
        port: Int,
        user: String,
        password: String,
        host: String,
        hostPort: Int,
        timeoutMs: Int,
    ): Socket {
        val socket = Socket()
        socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
        socket.soTimeout = timeoutMs
        val out = socket.getOutputStream()
        val input = socket.getInputStream()

        // greeting: version 5, one method — username and password
        out.write(byteArrayOf(0x05, 0x01, 0x02))
        out.flush()
        val greeting = readFully(input, 2)
        if (greeting[0].toInt() != 0x05) throw Stage("это не SOCKS5: версия ${greeting[0]}")
        if (greeting[1].toInt() != 0x02) throw Stage("прокси не принял вход с паролем")

        val userBytes = user.toByteArray(Charsets.UTF_8)
        val passBytes = password.toByteArray(Charsets.UTF_8)
        val auth = ByteArray(3 + userBytes.size + passBytes.size)
        auth[0] = 0x01
        auth[1] = userBytes.size.toByte()
        userBytes.copyInto(auth, 2)
        auth[2 + userBytes.size] = passBytes.size.toByte()
        passBytes.copyInto(auth, 3 + userBytes.size)
        out.write(auth)
        out.flush()
        val authAnswer = readFully(input, 2)
        if (authAnswer[1].toInt() != 0x00) throw Stage("прокси не принял логин или пароль")

        val hostBytes = host.toByteArray(Charsets.UTF_8)
        val connect = ByteArray(7 + hostBytes.size)
        connect[0] = 0x05
        connect[1] = 0x01
        connect[2] = 0x00
        connect[3] = 0x03
        connect[4] = hostBytes.size.toByte()
        hostBytes.copyInto(connect, 5)
        connect[5 + hostBytes.size] = (hostPort shr 8).toByte()
        connect[6 + hostBytes.size] = (hostPort and 0xff).toByte()
        out.write(connect)
        out.flush()
        val reply = readFully(input, 4)
        if (reply[1].toInt() != 0x00) {
            throw Stage("прокси не выпустил наружу (код ${reply[1]}): похоже, не работает сервер")
        }
        // the rest of the reply is the bound address; read it to stay in sync with the stream
        when (reply[3].toInt()) {
            0x01 -> readFully(input, 4 + 2)
            0x04 -> readFully(input, 16 + 2)
            0x03 -> {
                val length = readFully(input, 1)[0].toInt()
                if (length > 0) readFully(input, length + 2)
            }
            else -> throw Stage("прокси ответил не по протоколу SOCKS5")
        }
        return socket
    }

    /**
     * One short sentence about a failure.
     *
     * A native TLS error is a page of OpenSSL internals; what a person needs is the class of the
     * problem, not the stack of the library that noticed it.
     */
    private fun short(error: Throwable): String {
        val text = error.message.orEmpty()
        val kind = error.javaClass.simpleName
        return when {
            text.contains("BAD_DECRYPT") || text.contains("BAD_RECORD_MAC") ->
                "ошибка TLS: поток через прокси повреждён"
            text.contains("timed out", true) || error is java.net.SocketTimeoutException ->
                "нет ответа от прокси"
            text.contains("refused", true) -> "прокси не слушает: приложение не запущено"
            text.contains("unreachable", true) || text.contains("unknown host", true) ->
                "нет сети"
            text.isBlank() -> "ошибка: $kind"
            else -> "ошибка: " + text.lineSequence().first().take(60)
        }
    }

    /** A subscription URL: does it answer, and with how many links. */
    fun subscription(url: String, timeoutMs: Int = 15000): Result = runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("User-Agent", "byecensor/0.1")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) return@runCatching Result(false, "подписка ответила кодом $code")
            val body = connection.inputStream.bufferedReader().readText()
            val direct = Server.parseAll(body)
            val servers = if (direct.isNotEmpty()) direct else Server.parseAll(
                String(java.util.Base64.getMimeDecoder().decode(body.trim()), Charsets.UTF_8),
            )
            if (servers.isEmpty()) Result(false, "ответ получен, но ссылок в нём нет")
            else Result(true, "в подписке ${servers.size} серверов")
        } finally {
            connection.disconnect()
        }
    }.getOrElse { error ->
        Result(false, "подписка не открылась: ${error.message ?: "нет связи"}")
    }
}
