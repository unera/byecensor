package ru.uvw.byecensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.uvw.byecensor.logic.Config
import ru.uvw.byecensor.logic.Traffic

/**
 * The counters, as the kernel is told about them.
 *
 * Checked here because the shape is not obvious and the failure is silent: a config with the
 * flags in "levels" instead of "system" starts fine and answers every query with an empty object,
 * and nothing on the phone says why. That was found by running a kernel by hand; these tests keep
 * it from coming back.
 */
class TrafficConfigTest {
    private fun config(apiPort: Int) = Config.build(
        server = null,
        port = 1099,
        user = "byecensor",
        password = "secret",
        viaServer = false,
        apiPort = apiPort,
    )

    @Test
    fun `the counters are switched on in system, not in levels`() {
        val root = config(Traffic.API_PORT)
        assertTrue("без stats ядро счётчиков не ведёт", root.has("stats"))
        val system = root.getJSONObject("policy").getJSONObject("system")
        assertTrue(system.getBoolean("statsInboundUplink"))
        assertTrue(system.getBoolean("statsInboundDownlink"))
        assertNull(
            "флаги в levels не включают статистику и молча дают пустой ответ",
            root.getJSONObject("policy").getJSONObject("levels").optJSONObject("0")?.opt("statsInboundUplink"),
        )
    }

    @Test
    fun `the api door listens on loopback and answers from the kernel`() {
        val root = config(Traffic.API_PORT)
        assertEquals("api", root.getJSONObject("api").getString("tag"))
        assertEquals(
            "StatsService",
            root.getJSONObject("api").getJSONArray("services").getString(0),
        )
        val door = (0 until root.getJSONArray("inbounds").length())
            .map { root.getJSONArray("inbounds").getJSONObject(it) }
            .first { it.optString("tag") == "api" }
        assertEquals("127.0.0.1", door.getString("listen"))
        assertEquals(Traffic.API_PORT, door.getInt("port"))
        assertEquals("dokodemo-door", door.getString("protocol"))

        // без этого правила запрос умирает на "failed to dial"
        val first = root.getJSONObject("routing").getJSONArray("rules").getJSONObject(0)
        assertEquals("api", first.getString("outboundTag"))
        assertEquals("api", first.getJSONArray("inboundTag").getString(0))
    }

    @Test
    fun `without a port there is no stats section at all`() {
        val root = config(0)
        assertFalse(root.has("stats"))
        assertFalse(root.has("api"))
        assertNull(root.getJSONObject("policy").optJSONObject("system"))
        val first = root.getJSONObject("routing").getJSONArray("rules").getJSONObject(0)
        assertTrue(first.optString("outboundTag") != "api")
    }

    @Test
    fun `bytes read as a person reads them`() {
        assertEquals("0 Б", Traffic.format(0))
        assertEquals("512 Б", Traffic.format(512))
        assertEquals("1.00 КБ", Traffic.format(1024))
        assertEquals("1.50 КБ", Traffic.format(1536))
        assertEquals("15.0 МБ", Traffic.format(15L * 1024 * 1024))
        assertEquals("1.46 ГБ", Traffic.format(1500L * 1024 * 1024))
    }
}
