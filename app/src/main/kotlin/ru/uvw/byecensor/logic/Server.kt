package ru.uvw.byecensor.logic

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.util.Base64

/**
 * One upstream server, parsed from a share link.
 *
 * The links people paste come from subscription panels: `vless://`, `vmess://` (base64 JSON),
 * `trojan://`, `ss://`. Parsing lives here, pure, so the rules are testable without a phone:
 * what counts as a valid link, what the remark is, which fields each protocol needs.
 */
data class Server(
    val id: String,
    val name: String,
    val protocol: String,
    val host: String,
    val port: Int,
    /** vless/vmess user id, trojan password, ss password. */
    val secret: String,
    val transport: String = "tcp",
    val security: String = "none",
    val sni: String = "",
    val path: String = "",
    val hostHeader: String = "",
    val flow: String = "",
    /** ss cipher and vmess encryption. */
    val method: String = "",
) {
    /** The xray outbound for this server, exactly as the kernel wants it. */
    fun outbound(): JSONObject {
        val outbound = JSONObject().put("tag", "proxy").put("protocol", protocol)
        when (protocol) {
            "vless" -> {
                val user = JSONObject().put("id", secret).put("encryption", "none")
                if (flow.isNotBlank()) user.put("flow", flow)
                outbound.put("settings", JSONObject().put("vnext", JSONArray().put(
                    JSONObject().put("address", host).put("port", port)
                        .put("users", JSONArray().put(user)),
                )))
            }
            "vmess" -> {
                val user = JSONObject().put("id", secret).put("security", method.ifBlank { "auto" })
                outbound.put("settings", JSONObject().put("vnext", JSONArray().put(
                    JSONObject().put("address", host).put("port", port)
                        .put("users", JSONArray().put(user)),
                )))
            }
            "trojan" -> outbound.put("settings", JSONObject().put("servers", JSONArray().put(
                JSONObject().put("address", host).put("port", port).put("password", secret),
            )))
            "shadowsocks" -> outbound.put("settings", JSONObject().put("servers", JSONArray().put(
                JSONObject().put("address", host).put("port", port)
                    .put("method", method).put("password", secret),
            )))
            else -> throw IllegalArgumentException("протокол не поддержан: $protocol")
        }
        val stream = JSONObject()
        if (security == "tls" || security == "reality") {
            stream.put("security", security)
            val tls = JSONObject()
            if (sni.isNotBlank()) tls.put("serverName", sni)
            if (security == "reality") tls.put("fingerprint", "chrome")
            stream.put("tlsSettings", tls)
        }
        when (transport) {
            "ws" -> stream.put("network", "ws").put("wsSettings", JSONObject()
                .put("path", path.ifBlank { "/" })
                .put("headers", JSONObject().put("Host", hostHeader.ifBlank { host })))
            "grpc" -> stream.put("network", "grpc").put("grpcSettings", JSONObject()
                .put("serviceName", path))
            "http" -> stream.put("network", "http").put("httpSettings", JSONObject()
                .put("path", path.ifBlank { "/" })
                .put("host", JSONArray().put(hostHeader.ifBlank { host })))
            else -> stream.put("network", "tcp")
        }
        outbound.put("streamSettings", stream)
        return outbound
    }

    /** How the server is called on screen: the remark, or the address when there is none. */
    fun title(): String = name.ifBlank { "$host:$port" }

    companion object {
        /** @returns the server, or null when the text is not a link we understand. */
        fun parse(text: String): Server? {
            val link = text.trim()
            return when {
                link.startsWith("vless://", true) -> parseVless(link)
                link.startsWith("vmess://", true) -> parseVmess(link)
                link.startsWith("trojan://", true) -> parseTrojan(link)
                link.startsWith("ss://", true) -> parseShadowsocks(link)
                else -> null
            }
        }

        /** Every link found in a pasted text: subscription bodies are one link per line. */
        fun parseAll(text: String): List<Server> = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { parse(it) }
            .toList()

        private fun parseVless(link: String): Server? {
            val body = link.removePrefix("vless://")
            val (auth, rest) = splitOnce(body, '@') ?: return null
            val (hostPort, query) = splitOnce(rest, '?') ?: (rest to "")
            val (host, port) = hostPortOf(hostPort) ?: return null
            val params = queryOf(query)
            val name = fragmentOf(rest).ifBlank { "$host:$port" }
            return Server(
                id = name + auth.hashCode(), name = name, protocol = "vless",
                host = host, port = port, secret = auth,
                transport = params["type"] ?: "tcp",
                security = params["security"] ?: "none",
                sni = params["sni"] ?: params["host"] ?: "",
                path = params["path"] ?: "",
                hostHeader = params["host"] ?: "",
                flow = params["flow"] ?: "",
            )
        }

        private fun parseVmess(link: String): Server? {
            val raw = link.removePrefix("vmess://")
            val json = runCatching {
                JSONObject(String(Base64.getMimeDecoder().decode(raw), Charsets.UTF_8))
            }.getOrNull() ?: return null
            val host = json.optString("add")
            val port = json.optInt("port", 0)
            if (host.isBlank() || port <= 0) return null
            val name = json.optString("ps").ifBlank { "$host:$port" }
            return Server(
                id = name + json.optString("id").hashCode(), name = name, protocol = "vmess",
                host = host, port = port, secret = json.optString("id"),
                transport = json.optString("net", "tcp").ifBlank { "tcp" },
                security = if (json.optString("tls") == "tls") "tls" else "none",
                sni = json.optString("sni").ifBlank { json.optString("host") },
                path = json.optString("path"),
                hostHeader = json.optString("host"),
                method = json.optString("scy", "auto"),
            )
        }

        private fun parseTrojan(link: String): Server? {
            val body = link.removePrefix("trojan://")
            val (secret, rest) = splitOnce(body, '@') ?: return null
            val (hostPort, query) = splitOnce(rest, '?') ?: (rest to "")
            val (host, port) = hostPortOf(hostPort) ?: return null
            val params = queryOf(query)
            val name = fragmentOf(rest).ifBlank { "$host:$port" }
            return Server(
                id = name + secret.hashCode(), name = name, protocol = "trojan",
                host = host, port = port, secret = secret,
                transport = params["type"] ?: "tcp",
                security = params["security"] ?: "tls",
                sni = params["sni"] ?: params["peer"] ?: "",
                path = params["path"] ?: "",
                hostHeader = params["host"] ?: "",
            )
        }

        private fun parseShadowsocks(link: String): Server? {
            val body = link.removePrefix("ss://")
            val name = fragmentOf(body).ifBlank { body.take(24) }
            val withoutName = body.substringBefore('#')
            // two shapes exist: base64(method:pass)@host:port and base64(method:pass@host:port)
            val decodedWhole = runCatching {
                String(Base64.getMimeDecoder().decode(withoutName), Charsets.UTF_8)
            }.getOrNull()
            val part = if (withoutName.contains('@')) withoutName else decodedWhole ?: return null
            val (userinfo, hostPort) = splitOnce(part, '@') ?: return null
            val credentials = if (userinfo.contains(':')) userinfo else runCatching {
                String(Base64.getMimeDecoder().decode(userinfo), Charsets.UTF_8)
            }.getOrNull() ?: return null
            val (method, password) = splitOnce(credentials, ':') ?: return null
            val (host, port) = hostPortOf(hostPort) ?: return null
            return Server(
                id = name + password.hashCode(), name = name, protocol = "shadowsocks",
                host = host, port = port, secret = password, method = method,
            )
        }

        private fun splitOnce(value: String, marker: Char): Pair<String, String>? {
            val at = value.indexOf(marker)
            if (at < 0) return null
            return value.substring(0, at) to value.substring(at + 1)
        }

        private fun hostPortOf(value: String): Pair<String, Int>? {
            val hostPart = value.substringBefore('/').substringBefore('#')
            val colon = hostPart.lastIndexOf(':')
            if (colon < 0) return null
            val host = hostPart.substring(0, colon).removeSurrounding("[", "]")
            val port = hostPart.substring(colon + 1).toIntOrNull() ?: return null
            if (host.isBlank() || port <= 0 || port > 65535) return null
            return host to port
        }

        private fun queryOf(query: String): Map<String, String> =
            // the fragment is the remark, not a parameter: `…#Немецкий узел` must not end up
            // glued to the last value
            query.substringBefore('#').split('&').mapNotNull { pair ->
                val at = pair.indexOf('=')
                if (at <= 0) null else pair.substring(0, at) to
                    URLDecoder.decode(pair.substring(at + 1), "UTF-8")
            }.toMap()

        private fun fragmentOf(value: String): String {
            val at = value.indexOf('#')
            if (at < 0) return ""
            return URLDecoder.decode(value.substring(at + 1), "UTF-8")
        }
    }
}
