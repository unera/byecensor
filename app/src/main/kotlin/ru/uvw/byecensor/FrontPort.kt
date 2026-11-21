package ru.uvw.byecensor

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * The port the phone's apps are pointed at, held open even while the kernel restarts.
 *
 * The kernel owns its own sockets: stopping it closes everything, and a client that finds the
 * port closed reports "connection refused", backs off, and takes its time coming back. So the
 * user-facing port belongs to the service instead, and is passed through to the kernel port:
 *
 *     client -> 127.0.0.1:1099 (this) -> 127.0.0.1:2099 (kernel, comes and goes)
 *
 * A connection accepted while the kernel is down waits for it (up to [waitMs]) instead of being
 * refused, which makes a kernel restart invisible to whatever is behind the port.
 */
class FrontPort(
    private val listen: Int,
    private val upstream: Int,
    private val waitMs: Long = 10_000,
) {
    /** The user-facing port this instance holds. */
    fun port(): Int = listen

    private var server: ServerSocket? = null
    private var thread: Thread? = null

    fun start() {
        if (server != null) return
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress("127.0.0.1", listen))
        server = socket
        thread = Thread {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (error: Exception) {
                    break
                }
                Thread { pipe(client) }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }
        thread?.start()
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        thread = null
    }

    private fun pipe(client: Socket) {
        val deadline = System.currentTimeMillis() + waitMs
        var kernel: Socket? = null
        while (kernel == null && System.currentTimeMillis() < deadline) {
            kernel = try {
                Socket().apply { connect(InetSocketAddress("127.0.0.1", upstream), 1000) }
            } catch (error: Exception) {
                Thread.sleep(250)
                null
            }
        }
        val target = kernel
        if (target == null) {
            runCatching { client.close() }
            return
        }
        val up = Thread { copy(target, client) }.apply { isDaemon = true }
        up.start()
        copy(client, target)
        runCatching { up.join(1000) }
        runCatching { client.close() }
        runCatching { target.close() }
    }

    private fun copy(from: Socket, to: Socket) {
        try {
            val buffer = ByteArray(16 * 1024)
            val input = from.getInputStream()
            val output = to.getOutputStream()
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                output.flush()
            }
        } catch (error: Exception) {
            // a broken side simply ends the pair; the other direction notices on its own
        } finally {
            runCatching { to.shutdownOutput() }
        }
    }

    companion object {
        /**
         * Where the kernel actually listens. Shifted away from the user-facing port so both can
         * exist at the same time; the shift reverses near the top of the range.
         */
        fun kernelPort(userPort: Int): Int = if (userPort <= 55_000) userPort + 1000 else userPort - 1000
    }
}
