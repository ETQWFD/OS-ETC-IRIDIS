package com.iris.localai.server

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder

/**
 * A deliberately small HTTP/1.1 server (no third-party dependency): one request per
 * connection, Content-Length bodies, and streaming responses for Server-Sent Events / NDJSON.
 * That is all an OpenAI- or Ollama-compatible client needs.
 */
class HttpServer(
    private val bindAddress: InetAddress,
    private val port: Int,
    private val handler: suspend (HttpRequest, HttpResponder) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connections = Semaphore(MAX_CONNECTIONS)
    @Volatile private var socket: ServerSocket? = null
    @Volatile private var acceptJob: Job? = null
    private val clients: MutableSet<Socket> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    val isRunning: Boolean get() = socket?.isClosed == false

    @Throws(IOException::class)
    fun start() {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(bindAddress, port), 32)
        socket = ss
        acceptJob = scope.launch {
            while (isActive) {
                val client = try {
                    ss.accept()
                } catch (e: IOException) {
                    if (ss.isClosed) break // closed by stop()
                    Log.w(TAG, "accept failed", e) // e.g. EMFILE: back off instead of spinning or dying
                    delay(100)
                    continue
                }
                clients += client
                launch {
                    // Never let one connection's failure escape: an uncaught exception in this
                    // scope would reach the default handler and take the whole app down.
                    try {
                        connections.withPermit { serve(client) }
                    } catch (e: CancellationException) {
                        runCatching { client.close() }
                        throw e
                    } catch (t: Throwable) {
                        Log.w(TAG, "connection failed", t)
                        runCatching { client.close() }
                    } finally {
                        clients -= client
                    }
                }
            }
        }
        Log.i(TAG, "listening on ${bindAddress.hostAddress}:$port")
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
        acceptJob?.cancel()
        scope.cancel()
        // Blocking socket reads/writes ignore coroutine cancellation; closing the sockets is what
        // actually ends in-flight requests (and with them their generations).
        clients.toList().forEach { runCatching { it.close() } }
        clients.clear()
    }

    private suspend fun serve(client: Socket) {
        client.use { s ->
            s.soTimeout = READ_TIMEOUT_MS
            s.tcpNoDelay = true
            val out = s.getOutputStream()
            val input = BufferedInputStream(s.getInputStream())
            val responder = HttpResponder(out)
            val request = try {
                parse(input, out, s.inetAddress)
            } catch (e: HttpError) {
                runCatching { responder.json(e.status, errorJson(e.message ?: "bad request", e.status)) }
                return
            } catch (e: IOException) {
                return
            } catch (e: RuntimeException) { // e.g. malformed percent-encoding in the query
                runCatching { responder.json(400, errorJson("malformed request")) }
                return
            }
            // 2.0.0: a client that closes its connection cancels its request — also while it waits in
            // the generation queue or for a non-streamed reply, where no write would notice. Before,
            // an abandoned (timed-out, retried) request kept generating to max_tokens and held the
            // engine, so the in-app chat and every later request queued behind it.
            val requestJob = currentCoroutineContext().job
            val watcher = scope.launch {
                if (awaitClientGone(input) { isActive }) requestJob.cancel(CancellationException("client disconnected"))
            }
            try {
                handler(request, responder)
            } catch (e: HttpError) {
                if (!responder.committed) runCatching { responder.json(e.status, errorBody(request.path, e.status, e.message ?: "error")) }
            } catch (e: IOException) {
                // client went away
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "handler crashed", t)
                if (!responder.committed) runCatching { responder.json(500, errorBody(request.path, 500, t.message ?: "internal error")) }
            } finally {
                watcher.cancel() // its blocking read ends when client.use closes the socket
            }
        }
    }

    private fun parse(input: InputStream, out: OutputStream, remote: InetAddress): HttpRequest {
        val head = readHead(input)
        val lines = head.split("\r\n")
        val parts = lines.first().split(' ')
        if (parts.size < 3) throw HttpError(400, "malformed request line")
        val method = parts[0].uppercase()
        val target = parts[1]
        val headers = HashMap<String, String>()
        for (line in lines.drop(1)) {
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        if (headers["transfer-encoding"]?.contains("chunked", true) == true) {
            throw HttpError(411, "chunked request bodies are not supported; send Content-Length")
        }
        val rawLength = headers["content-length"]
        val length = if (rawLength == null) 0L else rawLength.toLongOrNull()?.takeIf { it >= 0 }
            ?: throw HttpError(400, "invalid Content-Length")
        if (length > MAX_BODY) throw HttpError(413, "request body too large")
        // 2.0.0: curl (bodies over 1 KiB / 1 MiB) and some HTTP stacks wait for this before sending the body.
        if (length > 0 && headers["expect"]?.equals("100-continue", ignoreCase = true) == true) {
            out.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            out.flush()
        }
        val body = ByteArray(length.toInt())
        var read = 0
        while (read < body.size) {
            val n = input.read(body, read, body.size - read)
            if (n < 0) throw IOException("truncated body")
            read += n
        }
        val path = target.substringBefore('?')
        val query = parseQuery(target.substringAfter('?', ""))
        return HttpRequest(method, path, query, headers, body, remote)
    }

    private fun readHead(input: InputStream): String {
        val buf = ByteArrayOutputStream()
        var state = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw IOException("connection closed")
            buf.write(b)
            if (buf.size() > MAX_HEAD) throw HttpError(431, "headers too large")
            state = when {
                state == 0 && b == '\r'.code -> 1
                state == 1 && b == '\n'.code -> 2
                state == 2 && b == '\r'.code -> 3
                state == 3 && b == '\n'.code -> return buf.toString(Charsets.ISO_8859_1.name()).trimEnd()
                b == '\r'.code -> 1
                else -> 0
            }
        }
    }

    companion object {
        /** Query string → map. Malformed escapes are kept verbatim instead of failing the request. */
        fun parseQuery(q: String): Map<String, String> = q.split('&').filter { it.isNotEmpty() }.associate {
            decode(it.substringBefore('=')) to decode(it.substringAfter('=', ""))
        }

        private fun decode(s: String): String =
            try { URLDecoder.decode(s, "UTF-8") } catch (e: IllegalArgumentException) { s }

        private const val TAG = "HttpServer"
        private const val MAX_CONNECTIONS = 8
        private const val MAX_HEAD = 16 * 1024
        private const val MAX_BODY = 8L * 1024 * 1024
        private const val READ_TIMEOUT_MS = 30_000

        /** OpenAI-style error body; 5xx are `server_error` (as OpenAI reports them), the rest `invalid_request_error`. */
        fun errorJson(message: String, status: Int = 400): String {
            val type = if (status >= 500) "server_error" else "invalid_request_error"
            return "{\"error\":{\"message\":${jsonString(message)},\"type\":\"$type\"}}"
        }

        /** 2.0.0: Ollama clients read `error` as a string (`{"error":"…"}`); OpenAI ones expect the object. */
        fun errorBody(path: String, status: Int, message: String): String =
            if (path.startsWith("/api/")) "{\"error\":${jsonString(message)}}" else errorJson(message, status)

        /**
         * Blocks until the client closes its side (EOF / reset) → true, or [alive] turns false
         * (→ false). Bytes the client sends meanwhile (none for a well-behaved HTTP/1.1 client after
         * a Connection: close request) are discarded.
         */
        fun awaitClientGone(input: InputStream, alive: () -> Boolean): Boolean {
            val buf = ByteArray(256)
            while (alive()) {
                try {
                    if (input.read(buf) < 0) return alive()
                } catch (e: SocketTimeoutException) {
                    continue
                } catch (e: IOException) {
                    return alive()
                }
            }
            return false
        }

        /**
         * 2.0.0: CORS preflight. The fixed list (Authorization, Content-Type) made browsers reject
         * the OpenAI JS SDK (it sends x-stainless-* headers) and any client with an extra header;
         * the requested headers are echoed back, restricted to header-name characters.
         */
        fun corsAllowHeaders(requested: String?): String {
            val asked = requested.orEmpty().split(',').map { it.trim() }
                .filter { it.isNotEmpty() && it.length <= 64 && it.all { c -> c.isLetterOrDigit() && c.code < 128 || c in "-_." } }
            return (listOf("Authorization", "Content-Type") + asked)
                .distinctBy { it.lowercase() }.take(32).joinToString(", ")
        }

        fun jsonString(s: String): String = kotlinx.serialization.json.JsonPrimitive(s).toString()
    }
}

class HttpError(val status: Int, message: String) : Exception(message)

data class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: ByteArray,
    val remote: InetAddress,
) {
    val bodyText: String get() = body.decodeToString()
}

/** Writes exactly one response. Streaming responses are terminated by closing the connection. */
class HttpResponder(private val out: OutputStream) {
    var committed = false
        private set

    fun json(status: Int, body: String) = send(status, "application/json; charset=utf-8", body.toByteArray(Charsets.UTF_8))

    fun text(status: Int, body: String) = send(status, "text/plain; charset=utf-8", body.toByteArray(Charsets.UTF_8))

    fun empty(status: Int) = send(status, "text/plain", ByteArray(0))

    /** CORS preflight answer (OPTIONS): echoes the requested headers, see [HttpServer.corsAllowHeaders]. */
    fun preflight(requestHeaders: Map<String, String>) {
        val extra = buildString {
            append("Access-Control-Max-Age: 600\r\n")
            // Chrome Private Network Access: a page / extension asking to reach 127.0.0.1 or the LAN.
            if (requestHeaders["access-control-request-private-network"].equals("true", ignoreCase = true)) {
                append("Access-Control-Allow-Private-Network: true\r\n")
            }
        }
        send(204, "text/plain", ByteArray(0), extra, HttpServer.corsAllowHeaders(requestHeaders["access-control-request-headers"]))
    }

    private fun send(status: Int, contentType: String, body: ByteArray, extra: String = "", allowHeaders: String = DEFAULT_ALLOW_HEADERS) {
        check(!committed) { "response already sent" }
        committed = true
        // One write: head and body leave in the same TCP segment(s) (tcpNoDelay is on).
        val head = head(status, contentType, "Content-Length: ${body.size}\r\n" + extra, allowHeaders).toByteArray(Charsets.ISO_8859_1)
        out.write(head + body)
        out.flush()
    }

    /** Starts a streaming response (SSE or NDJSON); write with [chunk], it ends when the socket closes. */
    fun startStream(contentType: String) {
        check(!committed)
        committed = true
        out.write(head(200, contentType, "Cache-Control: no-cache\r\nX-Accel-Buffering: no\r\n").toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    @Throws(IOException::class)
    fun chunk(text: String) {
        out.write(text.toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private fun head(status: Int, contentType: String, extra: String, allowHeaders: String = DEFAULT_ALLOW_HEADERS) = buildString {
        append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
        append("Content-Type: ").append(contentType).append("\r\n")
        append("Connection: close\r\n")
        append("Access-Control-Allow-Origin: *\r\n")
        append("Access-Control-Allow-Headers: ").append(allowHeaders).append("\r\n")
        append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
        append(extra)
        append("\r\n")
    }

    private fun reason(code: Int) = when (code) {
        200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"
        404 -> "Not Found"; 405 -> "Method Not Allowed"; 409 -> "Conflict"; 411 -> "Length Required"
        413 -> "Payload Too Large"; 429 -> "Too Many Requests"; 431 -> "Request Header Fields Too Large"
        500 -> "Internal Server Error"; 502 -> "Bad Gateway"; 503 -> "Service Unavailable"; 504 -> "Gateway Timeout"
        else -> if (code >= 500) "Internal Server Error" else "Error"
    }

    private companion object {
        const val DEFAULT_ALLOW_HEADERS = "Authorization, Content-Type"
    }
}
