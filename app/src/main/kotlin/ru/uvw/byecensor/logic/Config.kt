package ru.uvw.byecensor.logic

import org.json.JSONArray
import org.json.JSONObject

/**
 * The xray config, built from one server and the local settings.
 *
 * Two decisions live here and nowhere else:
 *
 * 1. the inbound is a SOCKS proxy with a username and a password. That is the whole point of
 *    this app: a local proxy an app can be pointed at, not a VPN that takes over the device —
 *    so no VpnService, no "turn off your VPN" screens, and no tun device;
 * 2. routing is a mode, not a script: "everything through the proxy", "Russian addresses
 *    direct" and "direct by default" cover what people actually do. Anything more is a
 *    config editor, and this app is not one.
 */
object Config {
    /** Where the app listens: loopback only, because nobody else should reach it. */
    const val LISTEN = "127.0.0.1"

    /**
     * The kernel's config.
     *
     * The local SOCKS is always there: that is what makes this app usable — an app is pointed at
     * `127.0.0.1` once and never touched again. The switch only decides whether that traffic goes
     * to a server or straight out, and the domain list decides what goes straight out either way.
     *
     * @param server - the chosen upstream; null with [viaServer] true is a mistake, not a state.
     * @param port - the local SOCKS port.
     * @param user - the SOCKS username; required, because an open local proxy is a gift to
     *   anything else on the phone.
     * @param password - the SOCKS password, same reason.
     * @param directDomains - domains that must not go through the server (`ru` covers a country).
     * @param viaServer - false means everything goes direct, which is a proxy that is up and unused.
     * @returns the JSON the kernel reads.
     */
    fun build(
        server: Server?,
        port: Int,
        user: String,
        password: String,
        directDomains: List<String> = emptyList(),
        proxyDomains: List<String> = emptyList(),
        viaServer: Boolean = true,
        httpPort: Int = 0,
        waChatPort: Int = 0,
        waMediaPort: Int = 0,
        waCertFile: String? = null,
        waKeyFile: String? = null,
        mux: Boolean = true,
    ): JSONObject {
        require(port in 1024..65535) { "порт должен быть от 1024 до 65535" }
        require(user.isNotBlank()) { "нужен логин SOCKS: без него к прокси подключится кто угодно" }
        require(password.isNotBlank()) { "нужен пароль SOCKS: без него к прокси подключится кто угодно" }
        if (viaServer && server == null) {
            throw IllegalArgumentException("нужен сервер: без него прокси некуда идти")
        }

        val inbound = JSONObject()
            .put("tag", "socks-in")
            .put("listen", LISTEN)
            .put("port", port)
            .put("protocol", "socks")
            .put("settings", JSONObject()
                .put("auth", "password")
                .put("udp", true)
                .put("accounts", JSONArray().put(JSONObject().put("user", user).put("pass", password))))

        // a second door, for apps that speak HTTP proxy and not SOCKS — a browser, mostly.
        // Same credentials, because a proxy without them is open to every app on the phone.
        val inbounds = JSONArray().put(inbound)
        if (httpPort > 0) {
            inbounds.put(JSONObject()
                .put("tag", "http-in")
                .put("listen", LISTEN)
                .put("port", httpPort)
                .put("protocol", "http")
                .put("settings", JSONObject()
                    .put("auth", "password")
                    .put("accounts", JSONArray().put(JSONObject().put("user", user).put("pass", password)))))
        }

        // WhatsApp's own proxy ports. The client expects the same thing Meta's proxy does:
        // TLS on the chat port (self-signed, which the client accepts) and raw TCP on the media
        // port. Both are plain forwarders — the end-to-end encryption stays inside the client,
        // and we only hand the bytes to the tunnel.
        if (waChatPort > 0 && waCertFile != null && waKeyFile != null) {
            inbounds.put(JSONObject()
                .put("tag", "wa-chat")
                .put("listen", LISTEN)
                .put("port", waChatPort)
                .put("protocol", "dokodemo-door")
                .put("settings", JSONObject()
                    .put("address", "g.whatsapp.net")
                    .put("port", 5222)
                    .put("network", "tcp")
                    .put("followRedirect", false))
                .put("streamSettings", JSONObject()
                    .put("network", "tcp")
                    .put("security", "tls")
                    .put("tlsSettings", JSONObject().put("certificates", JSONArray().put(
                        JSONObject().put("certificateFile", waCertFile).put("keyFile", waKeyFile),
                    )))))
        }
        if (waMediaPort > 0) {
            inbounds.put(JSONObject()
                .put("tag", "wa-media")
                .put("listen", LISTEN)
                .put("port", waMediaPort)
                .put("protocol", "dokodemo-door")
                .put("settings", JSONObject()
                    .put("address", "whatsapp.net")
                    .put("port", 443)
                    // TCP only, on purpose: with "tcp,udp" the door stopped answering altogether
                    // on the kernel we ship (24.0.0), and a media port that is dead is worse than
                    // one without UDP. Calls still work over TCP, they just report a worse line.
                    .put("network", "tcp")
                    .put("followRedirect", false)))
        }

        val outbounds = JSONArray()
        if (server != null && viaServer) {
            outbounds.put(server.outbound(mux))
        }
        outbounds.put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
        outbounds.put(JSONObject().put("tag", "block").put("protocol", "blackhole"))

        val rules = JSONArray()
        if (!viaServer) {
            // the proxy is up but carries nothing: every request leaves the phone as itself
            rules.put(JSONObject().put("type", "field")
                .put("network", "tcp,udp")
                .put("outboundTag", "direct"))
        } else {
            // "always through the server" comes first: rules are read top to bottom, and a name on
            // both lists means the person wanted it proxied, not spared
            val through = proxyDomains.map { it.trim() }.filter { it.isNotBlank() }
            if (through.isNotEmpty()) {
                rules.put(JSONObject().put("type", "field")
                    .put("domain", JSONArray(through))
                    .put("outboundTag", "proxy"))
            }
            // the WhatsApp doors carry their own destination, so they need a rule of their own:
            // otherwise the domain rules would send g.whatsapp.net straight out
            val waTags = JSONArray()
            if (waChatPort > 0) waTags.put("wa-chat")
            if (waMediaPort > 0) waTags.put("wa-media")
            if (waTags.length() > 0) {
                rules.put(JSONObject().put("type", "field")
                    .put("inboundTag", waTags)
                    .put("outboundTag", "proxy"))
            }
            val direct = directDomains.map { it.trim() }.filter { it.isNotBlank() }
            if (direct.isNotEmpty()) {
                rules.put(JSONObject().put("type", "field")
                    .put("domain", JSONArray(direct))
                    .put("outboundTag", "direct"))
            }
        }

        return JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("inbounds", inbounds)
            .put("outbounds", outbounds)
            .put("routing", JSONObject().put("domainStrategy", "IPIfNonMatch").put("rules", rules))
            .put("policy", JSONObject().put("levels", JSONObject()
                .put("0", JSONObject().put("handshake", 4).put("connIdle", 300))))
    }
}
