package ru.uvw.byecensor

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.uvw.byecensor.logic.Config
import ru.uvw.byecensor.logic.Server

/**
 * The rules that a phone cannot check for us: what a link means, and what the kernel is told.
 *
 * Both are pure functions, so they are tested here rather than by starting a kernel and hoping.
 */
class ServerTest {
    @Test
    fun `a vless link becomes a vless outbound`() {
        val link = "vless://1234-abcd@example.com:443?type=ws&security=tls&sni=edge.example.com" +
            "&path=%2Fws&host=edge.example.com&flow=xtls-rprx-vision#Немецкий%20узел"
        val server = Server.parse(link)
        assertNotNull(server)
        assertEquals("vless", server?.protocol)
        assertEquals("example.com", server?.host)
        assertEquals(443, server?.port)
        assertEquals("1234-abcd", server?.secret)
        assertEquals("ws", server?.transport)
        assertEquals("tls", server?.security)
        assertEquals("edge.example.com", server?.sni)
        assertEquals("/ws", server?.path)
        assertEquals("Немецкий узел", server?.name)

        val outbound = server!!.outbound()
        assertEquals("vless", outbound.getString("protocol"))
        val user = outbound.getJSONObject("settings").getJSONArray("vnext")
            .getJSONObject(0).getJSONArray("users").getJSONObject(0)
        assertEquals("1234-abcd", user.getString("id"))
        assertEquals("xtls-rprx-vision", user.getString("flow"))
        assertEquals("ws", outbound.getJSONObject("streamSettings").getString("network"))
    }

    @Test
    fun `a trojan link keeps its password and its tls`() {
        val server = Server.parse("trojan://secret-pass@host.example:8443?sni=host.example#Узел")
        assertNotNull(server)
        assertEquals("trojan", server?.protocol)
        assertEquals("secret-pass", server?.secret)
        assertEquals("tls", server?.security)
        assertEquals("host.example", server?.sni)
    }

    @Test
    fun `a vmess link is read from its base64 json`() {
        val json = """{"v":"2","ps":"Тест","add":"1.2.3.4","port":"8080","id":"uuid-1",
            |"net":"ws","path":"/p","host":"h.example","tls":"tls"}""".trimMargin()
        val encoded = java.util.Base64.getEncoder().encodeToString(json.toByteArray())
        val server = Server.parse("vmess://$encoded")
        assertNotNull(server)
        assertEquals("Тест", server?.name)
        assertEquals("1.2.3.4", server?.host)
        assertEquals(8080, server?.port)
        assertEquals("uuid-1", server?.secret)
        assertEquals("tls", server?.security)
    }

    @Test
    fun `a shadowsocks link is read in both of its shapes`() {
        val credentials = java.util.Base64.getEncoder()
            .encodeToString("aes-256-gcm:pass123".toByteArray())
        val plain = Server.parse("ss://$credentials@1.1.1.1:8388#Узел")
        assertEquals("aes-256-gcm", plain?.method)
        assertEquals("pass123", plain?.secret)
        assertEquals(8388, plain?.port)

        val whole = java.util.Base64.getEncoder()
            .encodeToString("aes-128-gcm:other@2.2.2.2:8389".toByteArray())
        val wrapped = Server.parse("ss://$whole#Другой")
        assertEquals("other", wrapped?.secret)
        assertEquals("2.2.2.2", wrapped?.host)
    }

    @Test
    fun `anything that is not a link is refused`() {
        assertNull(Server.parse("просто текст"))
        assertNull(Server.parse("https://example.com/sub"))
        assertNull(Server.parse("vless://без-собаки:443"))
        assertEquals(0, Server.parseAll("мусор\nещё мусор").size)
    }

    @Test
    fun `a subscription body with several links yields all of them`() {
        val body = """
            vless://a@one.example:443?type=tcp#Первый
            trojan://b@two.example:443#Второй
            мусорная строка
        """.trimIndent()
        val servers = Server.parseAll(body)
        assertEquals(2, servers.size)
        assertEquals("Первый", servers[0].name)
        assertEquals("Второй", servers[1].name)
    }
}

class ConfigTest {
    private val server = Server(
        id = "1", name = "Узел", protocol = "vless",
        host = "example.com", port = 443, secret = "uuid", security = "tls",
    )

    @Test
    fun `the inbound is a password-protected socks proxy on loopback`() {
        val config = Config.build(server, 1080, "user1", "pass1", viaServer = true)
        val inbound = config.getJSONArray("inbounds").getJSONObject(0)
        assertEquals("socks", inbound.getString("protocol"))
        assertEquals("127.0.0.1", inbound.getString("listen"))
        assertEquals(1080, inbound.getInt("port"))
        val settings = inbound.getJSONObject("settings")
        assertEquals("password", settings.getString("auth"))
        val account = settings.getJSONArray("accounts").getJSONObject(0)
        assertEquals("user1", account.getString("user"))
        assertEquals("pass1", account.getString("pass"))
    }

    @Test
    fun `a proxy without credentials is refused`() {
        // an open local proxy is a gift to every other app on the phone
        val noUser = runCatching {
            Config.build(server, 1080, "", "pass", viaServer = true)
        }.exceptionOrNull()
        assertTrue(noUser is IllegalArgumentException)
        val noPass = runCatching {
            Config.build(server, 1080, "user", "", viaServer = true)
        }.exceptionOrNull()
        assertTrue(noPass is IllegalArgumentException)
    }

    @Test
    fun `a domain on both lists goes through the server`() {
        // "ru direct, but 2ip.ru through the proxy" — the stronger list is read first
        val config = Config.build(
            server, 1080, "u", "p",
            directDomains = listOf("ru"),
            proxyDomains = listOf("2ip.ru"),
            viaServer = true,
        )
        val rules = config.getJSONObject("routing").getJSONArray("rules")
        assertEquals(2, rules.length())
        assertEquals("proxy", rules.getJSONObject(0).getString("outboundTag"))
        assertEquals("2ip.ru", rules.getJSONObject(0).getJSONArray("domain").getString(0))
        assertEquals("direct", rules.getJSONObject(1).getString("outboundTag"))
        assertEquals("ru", rules.getJSONObject(1).getJSONArray("domain").getString(0))
    }

    @Test
    fun `without the server everything goes direct`() {
        val config = Config.build(server, 1080, "u", "p", viaServer = false)
        val rules = config.getJSONObject("routing").getJSONArray("rules")
        assertEquals(1, rules.length())
        assertEquals("direct", rules.getJSONObject(0).getString("outboundTag"))
        // and the proxy outbound is not even there: nothing to carry traffic to
        assertEquals(2, config.getJSONArray("outbounds").length())
    }

    @Test
    fun `proxy routing needs a server, direct does not`() {
        val direct = Config.build(null, 1080, "u", "p", viaServer = false)
        assertTrue(direct.getJSONArray("outbounds").length() == 2) // direct + block, no proxy
        val refused = runCatching {
            Config.build(null, 1080, "u", "p", viaServer = true)
        }.exceptionOrNull()
        assertTrue(refused is IllegalArgumentException)
    }

    @Test
    fun `an http port adds a second, password-protected inbound`() {
        // browsers speak HTTP proxy, not SOCKS with a password — the second door is for them
        val config = Config.build(server, 1099, "u", "p", viaServer = true, httpPort = 1100)
        val inbounds = config.getJSONArray("inbounds")
        assertEquals(2, inbounds.length())
        val http = inbounds.getJSONObject(1)
        assertEquals("http", http.getString("protocol"))
        assertEquals(1100, http.getInt("port"))
        assertEquals("127.0.0.1", http.getString("listen"))
        val account = http.getJSONObject("settings").getJSONArray("accounts").getJSONObject(0)
        assertEquals("u", account.getString("user"))
        assertEquals("p", account.getString("pass"))
    }

    @Test
    fun `no http port means no second door`() {
        val config = Config.build(server, 1099, "u", "p", viaServer = true, httpPort = 0)
        assertEquals(1, config.getJSONArray("inbounds").length())
    }

    @Test
    fun `a bad port is refused`() {
        for (port in listOf(80, 0, 70000)) {
            val error = runCatching { Config.build(server, port, "u", "p", viaServer = true) }
                .exceptionOrNull()
            assertTrue("порт $port должен быть отвергнут", error is IllegalArgumentException)
        }
    }
}
