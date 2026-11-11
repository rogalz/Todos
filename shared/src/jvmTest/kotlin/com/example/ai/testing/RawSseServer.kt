package com.example.ai.testing

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * A hand-written HTTP server on a plain socket that streams whatever a test scripts, for the tests
 * that need a **real** engine and a real socket: `testApplication`'s in-memory engine has no
 * sockets, so it can neither enforce a socket timeout nor show a connection being closed.
 *
 * It answers every request with [status] and `Content-Type: text/event-stream` and then runs
 * [serve] on that connection. The response has no length, so the client reads until the stream ends.
 */
class RawSseServer(
    private val status: String = "200 OK",
    private val serve: (Connection) -> Unit,
) : AutoCloseable {

    class Connection(private val socket: Socket) {
        private val out = socket.getOutputStream()

        /** Writes [text] and flushes. Throws [IOException] once the client is gone, which ends the script. */
        fun send(text: String) {
            out.write(text.toByteArray())
            out.flush()
        }

        fun sleep(millis: Long) = Thread.sleep(millis)

        /** Blocks until the client closes the connection. */
        fun awaitClientClose() {
            val input = socket.getInputStream()
            while (input.read() != -1) {
                // the client only sent its request; anything more is ignored
            }
        }
    }

    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val sockets = CopyOnWriteArrayList<Socket>()

    /** Number of connections accepted so far. */
    val connectionCount: Int get() = sockets.size

    val baseUrl: String = "http://127.0.0.1:${server.localPort}"

    init {
        thread(isDaemon = true, name = "raw-sse-accept") {
            try {
                while (true) {
                    val socket = server.accept()
                    sockets += socket
                    thread(isDaemon = true, name = "raw-sse-connection") { handle(socket) }
                }
            } catch (e: IOException) {
                // the server socket was closed: the test is over
            }
        }
    }

    private fun handle(socket: Socket) {
        try {
            val input = socket.getInputStream()
            // The request: read up to the blank line that ends the headers.
            var tail = 0
            while (tail != END_OF_HEADERS) {
                val byte = input.read()
                if (byte == -1) return
                tail = (tail shl 8) or byte
            }
            val connection = Connection(socket)
            connection.send("HTTP/1.1 $status\r\nContent-Type: text/event-stream\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n")
            serve(connection)
        } catch (e: IOException) {
            // the client left while the script was still writing: expected
        } finally {
            // A script that returns ends the stream: the client sees a normal end of the response.
            runCatching { socket.close() }
        }
    }

    override fun close() {
        runCatching { server.close() }
        sockets.forEach { runCatching { it.close() } }
    }

    private companion object {
        /** `\r\n\r\n` as the last four bytes read. */
        const val END_OF_HEADERS = 0x0D0A0D0A
    }
}

/** One SSE message as it appears on the wire. */
fun sse(event: String? = null, data: String? = null, comment: String? = null): String = buildString {
    if (comment != null) append(": ").append(comment).append('\n')
    if (event != null) append("event: ").append(event).append('\n')
    if (data != null) append("data: ").append(data).append('\n')
    append('\n')
}
