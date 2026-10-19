package com.iris.localai

import com.iris.localai.server.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

class HttpServerTest {

    private fun withServer(block: (Int) -> Unit) {
        val port = 18000 + (Math.random() * 2000).toInt()
        val server = HttpServer(InetAddress.getLoopbackAddress(), port) { req, res ->
            when (req.path) {
                "/echo" -> res.json(200, """{"method":"${req.method}","body":${HttpServer.jsonString(req.bodyText)}}""")
                "/stream" -> {
                    res.startStream("text/event-stream")
                    repeat(3) { res.chunk("data: $it\n\n") }
                    res.chunk("data: [DONE]\n\n")
                }
                else -> throw com.iris.localai.server.HttpError(404, "nope")
            }
        }
        server.start()
        try { block(port) } finally { server.stop() }
    }

    @Test fun postBodyRoundTripsUtf8() = withServer { port ->
        val c = URL("http://127.0.0.1:$port/echo").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write("你好 😀".toByteArray()) }
        assertEquals(200, c.responseCode)
        val body = c.inputStream.readBytes().decodeToString()
        assertTrue(body, body.contains("你好 😀"))
        assertEquals("*", c.getHeaderField("Access-Control-Allow-Origin"))
    }

    @Test fun streamsEvents() = withServer { port ->
        val c = URL("http://127.0.0.1:$port/stream").openConnection() as HttpURLConnection
        val body = c.inputStream.readBytes().decodeToString()
        assertEquals("data: 0\n\ndata: 1\n\ndata: 2\n\ndata: [DONE]\n\n", body)
    }

    @Test fun errorsBecomeJson() = withServer { port ->
        val c = URL("http://127.0.0.1:$port/missing").openConnection() as HttpURLConnection
        assertEquals(404, c.responseCode)
        assertTrue(c.errorStream.readBytes().decodeToString().contains("\"error\""))
    }
}
