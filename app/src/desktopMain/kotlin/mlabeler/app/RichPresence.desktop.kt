package mlabeler.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.RandomAccessFile
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SocketChannel

/**
 * Discord Rich Presence over Discord's local connection (a named pipe on Windows, a socket elsewhere), without a
 * library: frames of an 8-byte header (operation, length; little endian) and JSON. One background thread keeps the
 * connection, tries again while Discord isn't running and sends at most one change every few seconds (Discord takes
 * five in 20 s). When the program ends, Discord clears what it showed by itself.
 */
actual object RichPresence {
    /**
     * The Discord application "mLabeler" (discord.com/developers/applications): its name is what the profile says is
     * being played. MLABELER_DISCORD_ID replaces it (for trying another one).
     */
    private const val APP_ID = "1558325691102007440"
    private val appId: String = System.getenv("MLABELER_DISCORD_ID")?.trim()?.takeIf { it.isNotEmpty() } ?: APP_ID

    actual val supported: Boolean get() = appId.isNotEmpty()

    private val lock = Object()
    private var wanted: PresenceInfo? = null
    private var changed = false
    private var thread: Thread? = null

    actual fun show(info: PresenceInfo?) {
        if (!supported) return
        synchronized(lock) {
            if (info == wanted && thread != null) return
            wanted = info
            changed = true
            if (thread == null && info != null) thread = Thread(::loop, "mlabeler-discord").apply { isDaemon = true; start() }
            lock.notifyAll()
        }
    }

    private fun take(timeoutMs: Long): PresenceInfo? = synchronized(lock) {
        if (!changed && timeoutMs > 0) lock.wait(timeoutMs)
        changed = false
        wanted
    }

    private fun loop() {
        var conn: Ipc? = null
        var shown: PresenceInfo? = null
        var lastSend = 0L
        var want = take(0)
        while (true) {
            if (want == null) {
                conn?.let { c -> runCatching { c.setActivity(null) }; c.close() }
                conn = null
                shown = null
            } else {
                if (conn == null) {
                    conn = Ipc.open(appId)
                    shown = null
                }
                val now = System.currentTimeMillis()
                // the same again now and then: a Discord started anew gets it, a closed connection is noticed
                if (conn != null && (want != shown || now - lastSend > 60_000)) {
                    val wait = lastSend + 4_000 - now
                    if (wait > 0) {
                        Thread.sleep(wait)
                        want = take(0)
                        if (want == null) continue
                    }
                    try {
                        conn.setActivity(want)
                        shown = want
                        lastSend = System.currentTimeMillis()
                    } catch (_: Exception) {
                        conn.close()
                        conn = null
                    }
                }
            }
            want = take(if (want != null && conn == null) 20_000 else 15_000)
        }
    }

    private fun activity(p: PresenceInfo): JsonObject = buildJsonObject {
        // Discord wants 2 to 128 characters in each line
        fun line(s: String) = s.take(128).let { if (it.length < 2) it.padEnd(2, '​') else it }
        if (p.details.isNotBlank()) put("details", line(p.details))
        if (p.state.isNotBlank()) put("state", line(p.state))
        p.startEpochSec?.let { s -> putJsonObject("timestamps") { put("start", s) } }
        putJsonObject("assets") {
            // a picture from the web needs no art uploaded to the Discord application
            put("large_image", "https://raw.githubusercontent.com/Megageorgio/mlabeler/main/art/icon-256.png")
            put("large_text", "mLabeler ${AppInfo.VERSION}")
        }
    }

    /** One connection to Discord. */
    private class Ipc(private val io: Pipe) {
        fun setActivity(p: PresenceInfo?) {
            val msg = buildJsonObject {
                put("cmd", "SET_ACTIVITY")
                putJsonObject("args") {
                    put("pid", ProcessHandle.current().pid())
                    put("activity", p?.let { activity(it) } ?: JsonNull)
                }
                put("nonce", java.util.UUID.randomUUID().toString())
            }
            send(1, msg.toString())
            val (op, body) = receive()
            if (op == 2) throw IllegalStateException("closed")
            val evt = runCatching { Json.parseToJsonElement(body).jsonObject["evt"]?.jsonPrimitive?.content }.getOrNull()
            if (evt == "ERROR") throw IllegalStateException(body)
        }

        fun close() = runCatching { io.close() }

        private fun send(op: Int, json: String) {
            val payload = json.encodeToByteArray()
            val b = ByteBuffer.allocate(8 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            b.putInt(op).putInt(payload.size).put(payload)
            io.write(b.array())
        }

        private fun receive(): Pair<Int, String> {
            val head = ByteBuffer.wrap(io.read(8)).order(ByteOrder.LITTLE_ENDIAN)
            val op = head.int
            val len = head.int
            if (len !in 0..1_000_000) throw IllegalStateException("bad frame")
            return op to io.read(len).decodeToString()
        }

        companion object {
            /** Connects and introduces the program; null when Discord isn't running or refuses. */
            fun open(appId: String): Ipc? {
                for (path in candidates()) {
                    val pipe = runCatching { Pipe.open(path) }.getOrNull() ?: continue
                    val c = Ipc(pipe)
                    try {
                        c.send(0, buildJsonObject { put("v", 1); put("client_id", appId) }.toString())
                        val (op, body) = c.receive()
                        val evt = runCatching { Json.parseToJsonElement(body).jsonObject["evt"]?.jsonPrimitive?.content }.getOrNull()
                        if (op == 1 && evt == "READY") return c
                    } catch (_: Exception) {
                    }
                    c.close()
                }
                return null
            }

            private fun candidates(): List<String> {
                if (System.getProperty("os.name").lowercase().startsWith("windows")) return (0..9).map { "\\\\.\\pipe\\discord-ipc-$it" }
                val dirs = listOfNotNull(System.getenv("XDG_RUNTIME_DIR"), System.getenv("TMPDIR"), System.getenv("TMP"), System.getenv("TEMP"), "/tmp")
                    .map { it.trimEnd('/') }.distinct()
                // Discord from Flatpak and Snap keeps its socket in a folder of its own
                val subs = listOf("", "app/com.discordapp.Discord/", "snap.discord/", ".flatpak/com.discordapp.Discord/xdg-run/")
                return (0..9).flatMap { i -> dirs.flatMap { d -> subs.map { s -> "$d/${s}discord-ipc-$i" } } }
                    .filter { java.io.File(it).exists() }
            }
        }
    }

    /** The bytes of the connection: a named pipe file on Windows, a Unix socket elsewhere. */
    private interface Pipe {
        fun write(b: ByteArray)
        fun read(n: Int): ByteArray
        fun close()

        companion object {
            fun open(path: String): Pipe = if (path.startsWith("\\\\")) {
                val f = RandomAccessFile(path, "rw")
                object : Pipe {
                    override fun write(b: ByteArray) = f.write(b)
                    override fun read(n: Int) = ByteArray(n).also { f.readFully(it) }
                    override fun close() = f.close()
                }
            } else {
                val ch = SocketChannel.open(StandardProtocolFamily.UNIX)
                ch.connect(UnixDomainSocketAddress.of(path))
                object : Pipe {
                    override fun write(b: ByteArray) {
                        val buf = ByteBuffer.wrap(b)
                        while (buf.hasRemaining()) ch.write(buf)
                    }
                    override fun read(n: Int): ByteArray {
                        val buf = ByteBuffer.allocate(n)
                        while (buf.hasRemaining()) if (ch.read(buf) < 0) throw java.io.EOFException()
                        return buf.array()
                    }
                    override fun close() = ch.close()
                }
            }
        }
    }
}
