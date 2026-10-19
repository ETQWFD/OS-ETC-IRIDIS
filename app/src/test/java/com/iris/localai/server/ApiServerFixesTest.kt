package com.iris.localai.server

import com.iris.localai.engine.PromptMessage
import kotlinx.coroutines.awaitCancellation
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 2.0.0: local API audit — request parsing, stop sequences, model-switch guard, HTTP server details. */
class ApiServerFixesTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun parse(body: String, ollama: Boolean = false) = ChatRequestParser.parse(body, ollama, json)

    private fun status(block: () -> Unit): Int = try {
        block(); -1
    } catch (e: HttpError) {
        e.status
    }

    // ---- request parsing ----

    @Test fun nullOptionalObjectsAreAbsentNotErrors() {
        val cr = parse("""{"model":null,"stream":null,"stream_options":null,"max_tokens":null,"stop":null,
            "messages":[{"role":"user","content":"你好"}]}""")
        assertNull(cr.model)
        assertFalse(cr.stream)
        assertFalse(cr.includeUsage)
        assertNull(cr.maxTokens)
        assertTrue(cr.stop.isEmpty())
        val o = parse("""{"options":null,"messages":[{"role":"user","content":"hi"}]}""", ollama = true)
        assertTrue(o.stream) // Ollama streams by default
    }

    @Test fun samplingExtrasAndStopAreParsed() {
        val cr = parse("""{"messages":[{"role":"system","content":"s"},{"role":"user","content":"q"}],
            "presence_penalty":0.5,"frequency_penalty":-0.25,"seed":42,"max_tokens":100.0,"top_k":20,
            "stop":["\n\n","结束"],"stream":true,"stream_options":{"include_usage":true}}""")
        assertEquals(0.5f, cr.presencePenalty)
        assertEquals(-0.25f, cr.frequencyPenalty)
        assertEquals(42, cr.seed)
        assertEquals(100, cr.maxTokens)
        assertEquals(20, cr.topK)
        assertEquals(listOf("\n\n", "结束"), cr.stop)
        assertTrue(cr.stream && cr.includeUsage)
        assertEquals(PromptMessage.SYSTEM, cr.messages.first().role)

        assertEquals(listOf("END"), parse("""{"stop":"END","messages":[{"role":"user","content":"q"}]}""").stop)
        val o = parse("""{"messages":[{"role":"user","content":"q"}],"options":{"stop":["<|x|>"],"seed":7,"num_predict":64}}""", ollama = true)
        assertEquals(listOf("<|x|>"), o.stop)
        assertEquals(7, o.seed)
        assertEquals(64, o.maxTokens)
    }

    @Test fun badShapesAre400() {
        assertEquals(400, status { parse("""{"stop":5,"messages":[{"role":"user","content":"q"}]}""") })
        assertEquals(400, status { parse("""{"stop":[1],"messages":[{"role":"user","content":"q"}]}""") })
        assertEquals(400, status { parse("""{"messages":["hi"]}""") })
        assertEquals(400, status { parse("""{"messages":[{"role":"system","content":"only system"}]}""") })
        assertEquals(400, status { parse("""[1,2]""") })
        assertEquals(400, status { parse("""{"messages":[{"role":"user","content":[{"type":"image_url","image_url":{"url":"x"}}]}]}""") })
    }

    @Test fun contentPartsAreJoined() {
        val cr = parse("""{"messages":[{"role":"user","content":[{"type":"text","text":"你"},{"type":"input_text","text":"好"}]},
            {"role":"assistant","content":null,"tool_calls":[]},{"role":"tool","content":"x"}]}""")
        assertEquals("你好", cr.messages[0].content)
        assertEquals(2, cr.messages.size) // tool message dropped, null content = ""
        assertEquals("", cr.messages[1].content)
    }

    // ---- stop sequences ----

    private fun run(stops: List<String>, vararg pieces: String): Pair<String, Boolean> {
        val f = StopSequenceFilter(stops)
        val out = StringBuilder()
        for (p in pieces) {
            out.append(f.push(p))
            if (f.hit) break
        }
        out.append(f.flush())
        return out.toString() to f.hit
    }

    @Test fun stopSequenceSplitAcrossPieces() {
        assertEquals("Hello " to true, run(listOf("END"), "Hello E", "N", "D and more"))
        assertEquals("答案是42" to true, run(listOf("。结束"), "答案是", "42。", "结", "束后面"))
    }

    @Test fun partialMatchIsReleasedWhenItDoesNotComplete() {
        val f = StopSequenceFilter(listOf("END"))
        assertEquals("a", f.push("aEN"))      // "EN" held back
        assertEquals("ENx", f.push("x"))      // not a stop after all
        assertEquals("", f.push("E"))
        assertEquals("E", f.flush())          // end of generation releases the tail
        assertFalse(f.hit)
    }

    @Test fun earliestStopWinsAndNoStopsPassThrough() {
        assertEquals("ab" to true, run(listOf("zz", "c"), "abczz"))
        assertEquals("plain" to false, run(emptyList(), "pla", "in"))
        assertEquals("" to true, run(listOf("x"), "xyz"))
        assertFalse(StopSequenceFilter(listOf("")).active)
    }

    // ---- model switch guard ----

    @Test fun modelSwitchOnlyBlockedWhileBusy() {
        assertTrue(ApiPolicy.modelSwitchBlocked(engineBusy = true, requestedFile = "b.gguf", readyFile = "a.gguf"))
        assertFalse(ApiPolicy.modelSwitchBlocked(engineBusy = true, requestedFile = "a.gguf", readyFile = "a.gguf"))
        assertFalse(ApiPolicy.modelSwitchBlocked(engineBusy = true, requestedFile = null, readyFile = "a.gguf"))
        assertFalse(ApiPolicy.modelSwitchBlocked(engineBusy = false, requestedFile = "b.gguf", readyFile = "a.gguf"))
    }

    // ---- HTTP server ----

    @Test fun errorBodies() {
        assertTrue(HttpServer.errorJson("x", 503).contains("\"type\":\"server_error\""))
        assertTrue(HttpServer.errorJson("x").contains("\"type\":\"invalid_request_error\""))
        assertEquals("{\"error\":\"坏了\"}", HttpServer.errorBody("/api/chat", 400, "坏了"))
        assertTrue(HttpServer.errorBody("/v1/chat/completions", 401, "k").startsWith("{\"error\":{\"message\""))
    }

    @Test fun corsAllowHeadersEchoesOnlyHeaderNames() {
        val h = HttpServer.corsAllowHeaders("content-type, x-stainless-os, X-Stainless-Lang, bad header, evil\u0000")
        assertEquals("Authorization, Content-Type, x-stainless-os, X-Stainless-Lang", h)
        assertEquals("Authorization, Content-Type", HttpServer.corsAllowHeaders(null))
    }

    private fun withServer(handler: suspend (HttpRequest, HttpResponder) -> Unit, block: (Int) -> Unit) {
        var lastError: Exception? = null
        repeat(5) {
            val port = 20000 + (Math.random() * 20000).toInt()
            val server = HttpServer(InetAddress.getLoopbackAddress(), port, handler)
            try {
                server.start()
            } catch (e: java.io.IOException) {
                lastError = e; return@repeat
            }
            try { block(port) } finally { server.stop() }
            return
        }
        throw AssertionError("no free port", lastError)
    }

    private fun connect(port: Int) = Socket().apply {
        connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 2000)
        soTimeout = 5000
    }

    @Test fun clientDisconnectCancelsTheRunningHandler() {
        val started = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        withServer({ _, _ ->
            started.countDown()
            try { awaitCancellation() } finally { cancelled.countDown() }
        }) { port ->
            val s = connect(port)
            val body = """{"messages":[{"role":"user","content":"你好"}]}""".toByteArray()
            s.getOutputStream().write(("POST /v1/chat/completions HTTP/1.1\r\nHost: x\r\nContent-Type: application/json\r\n" +
                "Content-Length: ${body.size}\r\n\r\n").toByteArray() + body)
            s.getOutputStream().flush()
            assertTrue("handler never started", started.await(3, TimeUnit.SECONDS))
            s.close()
            assertTrue("handler kept running after the client left", cancelled.await(3, TimeUnit.SECONDS))
        }
    }

    @Test fun expect100ContinueIsAnswered() = withServer({ req, res ->
        res.json(200, """{"len":${req.body.size}}""")
    }) { port ->
        val s = connect(port)
        val body = "x".repeat(2048).toByteArray()
        val out = s.getOutputStream()
        out.write("POST /e HTTP/1.1\r\nHost: x\r\nContent-Length: ${body.size}\r\nExpect: 100-continue\r\n\r\n".toByteArray())
        out.flush()
        val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
        assertEquals("HTTP/1.1 100 Continue", reader.readLine())
        assertEquals("", reader.readLine())
        out.write(body); out.flush()
        assertEquals("HTTP/1.1 200 OK", reader.readLine())
        val rest = reader.readText()
        assertTrue(rest, rest.endsWith("""{"len":2048}"""))
        s.close()
    }

    @Test fun preflightEchoesRequestedHeadersAndForbiddenHasItsReason() = withServer({ req, res ->
        if (req.method == "OPTIONS") res.preflight(req.headers) else throw HttpError(403, "no")
    }) { port ->
        connect(port).use { s ->
            s.getOutputStream().write(("OPTIONS /v1/chat/completions HTTP/1.1\r\nHost: x\r\nOrigin: chrome-extension://abc\r\n" +
                "Access-Control-Request-Method: POST\r\nAccess-Control-Request-Headers: authorization,content-type,x-stainless-arch\r\n" +
                "Access-Control-Request-Private-Network: true\r\n\r\n").toByteArray())
            val head = s.getInputStream().readBytes().decodeToString()
            assertTrue(head, head.startsWith("HTTP/1.1 204 No Content"))
            assertTrue(head, head.contains("Access-Control-Allow-Headers: Authorization, Content-Type, x-stainless-arch\r\n"))
            assertTrue(head, head.contains("Access-Control-Allow-Private-Network: true\r\n"))
        }
        connect(port).use { s ->
            s.getOutputStream().write("GET /api/tags HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
            val resp = s.getInputStream().readBytes().decodeToString()
            assertTrue(resp, resp.startsWith("HTTP/1.1 403 Forbidden"))
            assertTrue(resp, resp.endsWith("{\"error\":\"no\"}")) // Ollama path → Ollama error shape
        }
    }

    @Test fun finishedRequestIsNotCancelledByTheWatcher() {
        val done = CountDownLatch(1)
        var cancelled = false
        withServer({ _, res ->
            try { res.json(200, "{}") } catch (e: kotlinx.coroutines.CancellationException) { cancelled = true; throw e } finally { done.countDown() }
        }) { port ->
            connect(port).use { s ->
                s.getOutputStream().write("GET /x HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
                val resp = s.getInputStream().readBytes().decodeToString()
                assertTrue(resp, resp.startsWith("HTTP/1.1 200 OK"))
            }
            if (!done.await(3, TimeUnit.SECONDS)) fail("handler did not finish")
            assertFalse(cancelled)
        }
    }
}
