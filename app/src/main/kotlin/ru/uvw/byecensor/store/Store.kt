package ru.uvw.byecensor.store

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import ru.uvw.byecensor.logic.Config
import ru.uvw.byecensor.logic.Server
import ru.uvw.byecensor.logic.Updater

/**
 * Everything the app remembers: the servers, which one is chosen, and the local settings.
 *
 * Stored as JSON in one preference so a backup is one string, and so a subscription refresh can
 * replace the list without touching the settings around it.
 */
object SpeedDefaults {
    const val DEFAULT_SPEED_URL =
        "https://speed.cloudflare.com/__down?bytes=20971520"

}

/** Логин SOCKS на первом запуске: одинаковый на всех телефонах, см. `Store.user`. */
object AccessDefaults {
    const val USER = "byecensor"
}

class Store(context: Context) {
    private val prefs = context.getSharedPreferences("ByeCensor", Context.MODE_PRIVATE)

    var servers: List<Server>
        get() {
            val raw = prefs.getString("servers", null) ?: return emptyList()
            val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
            return (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                Server(
                    id = item.optString("id"),
                    name = item.optString("name"),
                    protocol = item.optString("protocol"),
                    host = item.optString("host"),
                    port = item.optInt("port"),
                    secret = item.optString("secret"),
                    transport = item.optString("transport", "tcp"),
                    security = item.optString("security", "none"),
                    sni = item.optString("sni"),
                    path = item.optString("path"),
                    hostHeader = item.optString("hostHeader"),
                    flow = item.optString("flow"),
                    method = item.optString("method"),
                    fingerprint = item.optString("fingerprint"),
                )
            }
        }
        set(value) {
            val array = JSONArray()
            for (server in value) {
                array.put(JSONObject()
                    .put("id", server.id)
                    .put("name", server.name)
                    .put("protocol", server.protocol)
                    .put("host", server.host)
                    .put("port", server.port)
                    .put("secret", server.secret)
                    .put("transport", server.transport)
                    .put("security", server.security)
                    .put("sni", server.sni)
                    .put("path", server.path)
                    .put("hostHeader", server.hostHeader)
                    .put("flow", server.flow)
                    .put("method", server.method)
                    .put("fingerprint", server.fingerprint))
            }
            prefs.edit().putString("servers", array.toString()).apply()
        }

    /** @returns the chosen server, or the first one when the stored choice is gone. */
    fun selected(): Server? {
        val id = prefs.getString("selected", null)
        val list = servers
        return list.firstOrNull { it.id == id } ?: list.firstOrNull()
    }

    fun select(server: Server) = prefs.edit().putString("selected", server.id).apply()

    /**
     * Add servers from a pasted text, skipping the ones already known.
     *
     * @returns how many were new — the screen says that number instead of a silent nothing.
     */
    fun add(servers: List<Server>): Int {
        val known = this.servers.toMutableList()
        val ids = known.map { it.id }.toMutableSet()
        var added = 0
        for (server in servers) {
            if (ids.add(server.id)) {
                known.add(server)
                added++
            }
        }
        if (added > 0) this.servers = known
        return added
    }

    /**
     * Put an edited server in place of the old one, keeping the selection on it.
     *
     * Editing the link may change the id (the id is derived from the remark and the secret), and a
     * chosen server that silently stops being chosen because its link was fixed is a bad surprise.
     */
    fun replace(old: Server, edited: Server) {
        servers = servers.map { if (it.id == old.id) edited else it }
        if (prefs.getString("selected", null) == old.id) {
            prefs.edit().putString("selected", edited.id).apply()
        }
    }

    fun forget(server: Server) {
        servers = servers.filterNot { it.id == server.id }
        if (prefs.getString("selected", null) == server.id) prefs.edit().remove("selected").apply()
    }

    /**
     * The local SOCKS port.
     *
     * 1099, not 1080: the default port is what every proxy-aware app guesses first, and it is
     * the first thing an app that looks for a local proxy will try. Moving off it costs nothing.
     */
    var port: Int
        get() {
            val stored = prefs.getInt("port", 0)
            if (stored == 0) return 1099
            // a phone that has been on 1080 since before the move keeps a working proxy only if
            // we move it once, deliberately
            if (stored == 1080 && !prefs.getBoolean("port-moved", false)) {
                prefs.edit().putInt("port", 1099).putBoolean("port-moved", true).apply()
                return 1099
            }
            return stored
        }
        set(value) = prefs.edit().putInt("port", value).putBoolean("port-moved", true).apply()

    /**
     * The HTTP proxy port, for apps that cannot speak SOCKS.
     *
     * Zero means "no HTTP door": a browser is the usual reason to want one, and a proxy nobody
     * asked for should not open by itself.
     */
    var httpPort: Int
        get() = prefs.getInt("http-port", 0)
        set(value) = prefs.edit().putInt("http-port", value).apply()

    /**
     * Локальный прокси для WhatsApp: порт чата (текст) и порт медиа.
     *
     * WhatsApp умеет ходить через прокси, который для него выглядит как ещё один его сервер:
     * на порту чата клиент ждёт TLS, на порту медиа — обычный TCP. Ноль означает «выключено»,
     * как и у HTTP-порта выше.
     */
    var waChatPort: Int
        get() = prefs.getInt("wa-chat-port", 0)
        set(value) = prefs.edit().putInt("wa-chat-port", value).apply()

    var waMediaPort: Int
        get() = prefs.getInt("wa-media-port", 0)
        set(value) = prefs.edit().putInt("wa-media-port", value).apply()

    /**
     * Что качать для теста скорости.
     *
     * Умолчание — файл Cloudflare: он отдаёт ровно столько байт, сколько попросишь, работает по
     * HTTPS и не требует авторизации. Адрес можно заменить своим, если хочется мерить именно
     * свой сервер.
     */
    /**
     * Multiplexing: one tunnel connection for every stream.
     *
     * Fewer connection establishments is exactly what matters when a middlebox watches them —
     * but the first stream pays for the shared session, and parallel streams queue behind one
     * another, so it is a switch rather than a law.
     */
    var muxEnabled: Boolean
        get() = prefs.getBoolean("mux", true)
        set(value) = prefs.edit().putBoolean("mux", value).apply()

    var speedUrl: String
        get() = prefs.getString("speed-url", SpeedDefaults.DEFAULT_SPEED_URL)
            ?: SpeedDefaults.DEFAULT_SPEED_URL
        set(value) = prefs.edit().putString("speed-url", value.trim()).apply()

    /**
     * The SOCKS credentials.
     *
     * The login is the same on every phone — it is read aloud and typed by hand often enough that
     * a unique one would only be something else to lose. The password is the opposite: it is
     * minted from SecureRandom on the first launch of this installation (no stored value yet) and
     * then kept, because a proxy without a password is reachable by every app on the phone, and
     * "не требуется" is not a setting this app offers.
     *
     * SecureRandom, not the default generator: the default is seeded from the clock, and two
     * phones set up minutes apart would get comparable sequences. A password that can be guessed
     * from the install time is not a password.
     */
    var user: String
        get() = prefs.getString("user", null) ?: AccessDefaults.USER.also {
            prefs.edit().putString("user", it).apply()
        }
        set(value) = prefs.edit().putString("user", value.trim()).apply()

    var password: String
        get() = prefs.getString("password", null) ?: generate().also {
            prefs.edit().putString("password", it).apply()
        }
        set(value) = prefs.edit().putString("password", value.trim()).apply()

    /**
     * Whether traffic goes through the chosen server or straight out.
     *
     * The proxy itself is always up: an app is configured once with `127.0.0.1:1099` and never
     * again, and this switch decides what that proxy does — carry traffic to a server, or hand it
     * to the network untouched.
     */
    var viaServer: Boolean
        get() = prefs.getBoolean("via-server", false)
        set(value) = prefs.edit().putBoolean("via-server", value).apply()

    /**
     * Domains that must not go through the server.
     *
     * A list rather than a preset: `ru` covers a country in one word, and a person who wants their
     * bank direct should not have to pick from our geography.
     */
    var directDomains: List<String>
        get() = domains("direct-domains")
        set(value) = writeDomains("direct-domains", value)

    /**
     * Domains that always go through the server, whatever the switch says.
     *
     * The stronger list: a name here wins over the same name in the direct list, because "always"
     * is a promise and "direct" is a preference.
     */
    var proxyDomains: List<String>
        get() = domains("proxy-domains")
        set(value) = writeDomains("proxy-domains", value)

    private fun domains (key: String): List<String> =
        prefs.getString(key, null)?.split("\n")?.map { it.trim() }?.filter { it.isNotBlank() }
            ?: emptyList()

    private fun writeDomains (key: String, value: List<String>) {
        prefs.edit()
            .putString(key, value.map { it.trim() }.filter { it.isNotBlank() }.joinToString("\n"))
            .apply()
    }

    /** Start the proxy when the phone boots. Off by default: a proxy that comes up by itself is a surprise. */
    var autostart: Boolean
        get() = prefs.getBoolean("autostart", false)
        set(value) = prefs.edit().putBoolean("autostart", value).apply()

    /**
     * Something the kernel reads has changed since it started.
     *
     * The screens set this when they save a value; the main screen restarts the kernel once, on
     * return, instead of after every keystroke.
     */
    var configDirty: Boolean
        get() = prefs.getBoolean("config-dirty", false)
        set(value) = prefs.edit().putBoolean("config-dirty", value).apply()

    /** Спрашивали ли уже про исключение из оптимизации батареи. */
    var askedBatteryFreedom: Boolean
        get() = prefs.getBoolean("askedBatteryFreedom", false)
        set(value) = prefs.edit().putBoolean("askedBatteryFreedom", value).apply()

    /**
     * How often to look for a newer release on GitHub.
     *
     * Stored as the key of `Updater.Interval`: the value is read by the screens and compared
     * against the moment of the last check, so a schedule survives a restart as a plain string.
     */
    var updateInterval: String
        get() = prefs.getString("update-interval", null) ?: Updater.Interval.DEFAULT.key
        set(value) = prefs.edit().putString("update-interval", value).apply()

    /** When the app last asked GitHub, in milliseconds since epoch. */
    var lastUpdateCheck: Long
        get() = prefs.getLong("update-checked", 0)
        set(value) = prefs.edit().putLong("update-checked", value).apply()

    /** Downloaded and waiting for the person to install: version and file name, or null. */
    var pendingUpdateVersion: String?
        get() = prefs.getString("update-version", null)
        set(value) = prefs.edit().putString("update-version", value).apply()

    var pendingUpdateFile: String?
        get() = prefs.getString("update-file", null)
        set(value) = prefs.edit().putString("update-file", value).apply()

    /** Is it time to ask GitHub again? Never, when the person turned checking off. */
    fun updateDue(now: Long = System.currentTimeMillis()): Boolean {
        val interval = Updater.Interval.of(updateInterval)
        if (interval == Updater.Interval.NEVER) return false
        return now - lastUpdateCheck >= interval.hours * 3_600_000
    }

    /** The downloaded update, when the file is still where it was left. */
    fun pendingUpdate(context: Context): java.io.File? {
        val name = pendingUpdateFile ?: return null
        val file = java.io.File(java.io.File(context.filesDir, "updates"), name)
        return if (file.exists()) file else null
    }

    /**
     * Everything the kernel has carried, in bytes, since the person last reset the counter.
     *
     * The kernel's own counters die with it, so this is where they are folded: the service reads
     * them with `-reset` and adds what it read here. Two numbers, because one sum over both
     * directions is a sum nobody can check.
     */
    var trafficUp: Long
        get() = prefs.getLong("traffic-up", 0)
        set(value) = prefs.edit().putLong("traffic-up", value).apply()

    var trafficDown: Long
        get() = prefs.getLong("traffic-down", 0)
        set(value) = prefs.edit().putLong("traffic-down", value).apply()

    /** The subscription URL, when the person uses one. */
    var subscription: String
        get() = prefs.getString("subscription", "") ?: ""
        set(value) = prefs.edit().putString("subscription", value.trim()).apply()

    /**
     * A random password: 20 characters from an alphabet without the pairs that look alike
     * (l/1, 0/O), because this value is read off the screen and typed into another app.
     */
    private fun generate(): String {
        val alphabet = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val random = java.security.SecureRandom()
        return (1..20).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    }
}
