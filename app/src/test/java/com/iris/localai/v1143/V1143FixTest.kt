package com.iris.localai.v1143

import com.iris.localai.agent.BrowserDriver
import com.iris.localai.agent.BrowserTool
import com.iris.localai.agent.HttpWebFetcher
import com.iris.localai.agent.HttpWire
import com.iris.localai.agent.NetGuard
import com.iris.localai.agent.PageSnapshot
import com.iris.localai.agent.SafeHttpClient
import com.iris.localai.agent.ToolEnv
import com.iris.localai.agent.UndoStore
import com.iris.localai.agent.WorkspaceSandbox
import com.iris.localai.engine.GenOwner
import com.iris.localai.engine.GenerationOwnership
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.URL

/**
 * 1.14.3 fixes: (1) web_fetch connects to the address it vetted (no DNS rebinding) and refuses
 * every local / private range; (2) a stop only cancels its own owner's generation.
 * Nothing here touches the network: every resolver is a fake.
 */
class V1143FixTest {

    private fun ip(s: String): InetAddress = NetGuard.literal(s) ?: error("not a literal: $s")
    private fun v6(vararg b: Int) = ByteArray(16) { b.getOrElse(it) { 0 }.toByte() }
    private fun source(path: String): String =
        listOf(path, "app/$path", "../$path").map { File(it) }.first { it.exists() }.readText()

    // ================================================================ 1. NetGuard ranges

    @Test fun ipv4RangesRefused() {
        for (a in listOf(
            "127.0.0.1", "127.255.255.254",              // loopback
            "10.0.0.1", "10.255.255.255",                // private
            "172.16.0.1", "172.31.255.255",              // private
            "192.168.0.1", "192.168.255.255",            // private
            "169.254.0.1", "169.254.169.254",            // link-local (cloud metadata)
            "100.64.0.1", "100.100.100.100", "100.127.255.255", // CGNAT 100.64/10
            "0.0.0.0", "0.1.2.3",                        // unspecified / this network
            "224.0.0.1", "239.255.255.250",              // multicast
            "240.0.0.1", "255.255.255.255",              // reserved / broadcast
            "192.0.0.1", "192.0.2.1", "198.18.0.1", "198.51.100.1", "203.0.113.1",
        )) assertFalse(a, NetGuard.isPublic(ip(a)))
    }

    @Test fun ipv4PublicAllowed() {
        for (a in listOf("1.1.1.1", "8.8.8.8", "93.184.216.34", "100.63.255.255", "100.128.0.1", "172.15.255.255", "172.32.0.1", "192.169.0.1", "11.0.0.1")) {
            assertTrue(a, NetGuard.isPublic(ip(a)))
        }
    }

    @Test fun ipv6RangesRefused() {
        for (a in listOf("::1", "::", "fc00::1", "fd12:3456::1", "fe80::1", "febf::1", "fec0::1", "ff02::1", "2001:db8::1", "2001:0:4136:e378::1", "100::1")) {
            assertFalse(a, NetGuard.isPublic(ip(a)))
        }
        // IPv4-mapped / -compatible / NAT64 / 6to4 forms of refused IPv4 (byte level: Java folds mapped ones to Inet4Address)
        assertFalse(NetGuard.isPublicV6(v6(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xFF, 0xFF, 127, 0, 0, 1)))     // ::ffff:127.0.0.1
        assertFalse(NetGuard.isPublicV6(v6(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xFF, 0xFF, 10, 1, 2, 3)))      // ::ffff:10.1.2.3
        assertFalse(NetGuard.isPublicV6(v6(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xFF, 0xFF, 100, 64, 0, 1)))    // ::ffff:100.64.0.1
        assertFalse(NetGuard.isPublicV6(v6(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 127, 0, 0, 1)))           // ::127.0.0.1
        assertFalse(NetGuard.isPublicV6(v6(0, 0x64, 0xFF, 0x9B, 0, 0, 0, 0, 0, 0, 0, 0, 192, 168, 1, 1))) // 64:ff9b::192.168.1.1
        assertFalse(NetGuard.isPublicV6(v6(0x20, 0x02, 10, 0, 0, 1)))                                     // 2002:0a00:0001::
        assertFalse(NetGuard.isPublic(ip("::ffff:127.0.0.1")))
        assertFalse(NetGuard.isPublic(ip("::ffff:192.168.1.1")))
        // public ones pass
        assertTrue(NetGuard.isPublic(ip("2606:4700:4700::1111")))
        assertTrue(NetGuard.isPublicV6(v6(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xFF, 0xFF, 8, 8, 8, 8)))
        assertTrue(NetGuard.isPublicV6(v6(0x20, 0x02, 8, 8, 8, 8)))
    }

    @Test fun literalsAndLocalNamesNeverAskDns() {
        assertNull(NetGuard.literal("example.com"))
        assertNull(NetGuard.literal("127.1"))      // not a dotted quad: goes to the resolver, whose answer is checked
        assertNull(NetGuard.literal("1.2.3.256"))
        assertEquals(ip("::1"), NetGuard.literal("[::1]"))
        var lookups = 0
        val counting: (String) -> Array<InetAddress> = { lookups++; arrayOf(ip("8.8.8.8")) }
        for (h in listOf("localhost", "LOCALHOST.", "a.localhost", "printer.local", "metadata.google.internal", "127.0.0.1", "[::1]", "::1", "10.0.0.2", "0.0.0.0", "[fe80::1%25wlan0]", "fe80::1%wlan0")) {
            try { NetGuard.resolvePublic(h, counting); fail("allowed $h") } catch (_: SecurityException) {}
        }
        assertEquals(0, lookups)
        assertEquals(listOf(ip("1.1.1.1")), NetGuard.resolvePublic("1.1.1.1", counting))
        assertEquals(0, lookups)
        assertTrue(NetGuard.obviouslyLocal("http://127.0.0.1:8080/v1/models"))
        assertTrue(NetGuard.obviouslyLocal("http://[::1]/"))
        assertTrue(NetGuard.obviouslyLocal("https://router.local/"))
        assertTrue(NetGuard.obviouslyLocal("http://100.64.1.1/"))
        assertFalse(NetGuard.obviouslyLocal("https://bing.com/"))
    }

    @Test fun resolvedNamesMustBeAllPublic() {
        assertEquals(listOf(ip("8.8.8.8")), NetGuard.resolvePublic("example.com") { arrayOf(ip("8.8.8.8")) })
        for (answer in listOf(arrayOf(ip("127.0.0.1")), arrayOf(ip("10.1.2.3")), arrayOf(ip("8.8.8.8"), ip("192.168.0.1")), arrayOf(ip("::1")), emptyArray<InetAddress>())) {
            try { NetGuard.resolvePublic("evil.example", { answer }); fail("allowed ${answer.toList()}") } catch (_: SecurityException) {}
        }
        // IDN names reach the resolver in ASCII form
        var asked = ""
        NetGuard.resolvePublic("例子.测试") { asked = it; arrayOf(ip("8.8.8.8")) }
        assertTrue(asked, asked.startsWith("xn--"))
        // old entry point keeps refusing literals / local names (no DNS needed)
        for (h in listOf("localhost", "127.0.0.1", "10.0.0.2", "192.168.1.1", "169.254.1.1", "::1", "0.0.0.0", "100.64.0.1")) {
            try { HttpWebFetcher.checkPublicHost(h); fail("allowed $h") } catch (_: SecurityException) {}
        }
    }

    // ================================================================ 1b. the fetcher

    @Test fun fetcherRefusesHostsResolvingInside() {
        for (bad in listOf("127.0.0.1", "10.0.0.5", "192.168.1.20", "169.254.169.254", "100.64.0.9", "::1", "fd00::5")) {
            var lookups = 0
            val f = HttpWebFetcher(resolve = { lookups++; arrayOf(ip(bad)) })
            for (url in listOf("http://rebind.example/", "https://rebind.example:8443/x?q=1")) {
                try { f.fetch(url); fail("fetched $url → $bad") } catch (_: SecurityException) {}
            }
            assertEquals("one lookup per request, refused before any connection", 2, lookups)
        }
        // literals and local names are refused without the resolver being asked
        val never = HttpWebFetcher(resolve = { fail("resolver asked for $it"); emptyArray<InetAddress>() })
        for (url in listOf("http://127.0.0.1:11434/api/tags", "http://localhost:8080/", "http://[::1]/", "http://10.0.0.1/", "http://x.local/")) {
            try { never.fetch(url); fail("fetched $url") } catch (_: SecurityException) {}
        }
        try { never.fetch("ftp://example.com/"); fail() } catch (_: IllegalArgumentException) {}
        try { never.fetch("file:///etc/passwd"); fail() } catch (_: IllegalArgumentException) {}
    }

    @Test fun eachHopResolvesOnceAndNeverAgain() {
        // a rebinding resolver: public on the first answer, loopback after. The client resolves once
        // per hop and connects to that very address, so the second answer can never be used for a
        // hop that was vetted with the first. Here the hop's single answer is private: refused.
        var n = 0
        val rebinding: (String) -> Array<InetAddress> = { n++; if (n == 1) arrayOf(ip("127.0.0.1")) else arrayOf(ip("8.8.8.8")) }
        try { SafeHttpClient(resolve = rebinding).get("http://rebind.example/", emptyMap(), 1024); fail() } catch (_: SecurityException) {}
        assertEquals(1, n)
    }

    @Test fun wireRequestAndParsing() {
        val req = HttpWire.request(URL("https://例子.example/搜索 a?q=天气"), "xn--fsqu00a.example", 443, true,
            mapOf("User-Agent" to "UA", "Host" to "evil", "X-Bad" to "a\r\nInjected: 1", "Accept-Encoding" to "gzip"))
        assertTrue(req, req.startsWith("GET /%E6%90%9C%E7%B4%A2%20a?q=%E5%A4%A9%E6%B0%94 HTTP/1.1\r\n"))
        assertTrue(req.contains("\r\nHost: xn--fsqu00a.example\r\n"))
        assertFalse(req.contains("evil")); assertFalse(req.contains("Injected")); assertFalse(req.contains("gzip"))
        assertTrue(req.contains("Accept-Encoding: identity\r\n")); assertTrue(req.endsWith("Connection: close\r\n\r\n"))
        assertTrue(HttpWire.request(URL("http://[2606:4700::1]:8080/"), "2606:4700::1", 8080, false, emptyMap()).contains("Host: [2606:4700::1]:8080\r\n"))
        assertEquals("/a%20b/%41", HttpWire.encodeTarget("/a b/%41"))

        fun parse(raw: String, max: Int = 1 shl 20): Pair<Int, String> {
            val input = ByteArrayInputStream(raw.toByteArray(Charsets.ISO_8859_1))
            val head = HttpWire.readHead(input)
            return head.code to String(HttpWire.readBody(input, head, max), Charsets.ISO_8859_1)
        }
        assertEquals(200 to "hello", parse("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello world"))
        assertEquals(200 to "Wikipedia in\r\nchunks.", parse("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n4\r\nWiki\r\n6;ext=1\r\npedia \r\nB\r\nin\r\nchunks.\r\n0\r\n\r\n"))
        assertEquals(404 to "gone", parse("HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 404 Not Found\r\n\r\ngone"))
        assertEquals(200 to "abc", parse("HTTP/1.0 200 OK\r\n\r\nabcdef", max = 3))
        assertEquals(200 to "Wik", parse("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n4\r\nWiki\r\n0\r\n\r\n", max = 3))
        try { parse("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n-1\r\n\r\n"); fail() } catch (_: IOException) {}
        try { parse("SSH-2.0-OpenSSH\r\n\r\n"); fail() } catch (_: IOException) {}
    }

    // ================================================================ 1c. the browser tool

    private class FakeBrowser : BrowserDriver {
        val opened = mutableListOf<String>()
        private fun snap() = PageSnapshot("P", "https://p", listOf("[1] button \"Go\""))
        override fun open(url: String) = snap().also { opened += url }
        override fun snapshot() = snap()
        override fun click(id: Int) = snap()
        override fun type(id: Int, text: String, submit: Boolean) = snap()
        override fun back() = snap()
    }

    @Test fun browserToolRefusesLocalTargetsWithoutDns() {
        val dir = kotlin.io.path.createTempDirectory("v1143").toFile()
        val b = FakeBrowser()
        val env = ToolEnv(WorkspaceSandbox(dir), UndoStore(File(dir, "u")), browser = b)
        for (u in listOf("http://127.0.0.1:8080/v1/models", "http://localhost/", "http://192.168.1.1/", "http://[::1]/", "http://100.64.0.1/")) {
            val r = BrowserTool.run(buildJsonObject { put("action", "open"); put("url", u) }, env)
            assertFalse(u, r.ok)
            assertEquals(NetGuard.REFUSED, r.summary)
        }
        assertTrue(b.opened.isEmpty())
        // a public name is passed on (the real WebViewBrowser resolves and checks it); still offline here
        assertTrue(BrowserTool.run(buildJsonObject { put("action", "open"); put("url", "bing.com") }, env).ok)
        assertEquals(listOf("https://bing.com"), b.opened)
    }

    // ================================================================ 2. cancel ownership

    @Test fun cancelOnlyStopsItsOwnersGeneration() {
        var stops = 0
        val g = GenerationOwnership { stops++ }
        val chat = GenOwner("chat"); val api = GenOwner("API")
        // nothing running: a per-owner cancel does nothing (never touches the engine)
        assertFalse(g.cancel(chat)); assertEquals(0, stops)
        // an API generation runs: chat's 停止 leaves it alone
        val apiRun = g.begin(api)
        assertSame(api, g.currentOwner)
        assertFalse(g.cancel(chat)); assertEquals(0, stops); assertFalse(apiRun.stopRequested)
        // the API's own cancel stops it
        assertTrue(g.cancel(api)); assertEquals(1, stops); assertTrue(apiRun.stopRequested)
        g.end(apiRun); assertNull(g.currentOwner)
        // a chat generation runs: an API-side cancel does nothing, chat's own does
        val chatRun = g.begin(chat)
        assertFalse(g.cancel(api)); assertEquals(1, stops); assertFalse(chatRun.stopRequested)
        assertTrue(g.cancel(chat)); assertEquals(2, stops); assertTrue(chatRun.stopRequested)
        g.end(chatRun)
        // an owner reused for the next generation starts clean (a cancel is not sticky)
        val next = g.begin(chat)
        assertFalse(next.stopRequested)
        // a late end() of an older run does not clear the newer one
        g.end(chatRun); assertSame(chat, g.currentOwner)
        g.end(next)
        assertFalse(g.cancel(chat)); assertEquals(2, stops)
    }

    @Test fun cancelAllStopsWhateverRuns() {
        var stops = 0
        val g = GenerationOwnership { stops++ }
        val api = GenOwner("API")
        val run = g.begin(api)
        assertSame(api, g.cancelAll()); assertEquals(1, stops); assertTrue(run.stopRequested)
        g.end(run)
        // with nothing registered it still asks the engine to stop (old global behaviour for load / unload)
        assertNull(g.cancelAll()); assertEquals(2, stops)
        // a throwing engine cancel never escapes
        val bad = GenerationOwnership { throw IllegalStateException("native") }
        val o = GenOwner("chat"); bad.begin(o)
        assertTrue(bad.cancel(o)); assertSame(o, bad.cancelAll())
    }

    @Test fun callersUsePerOwnerCancel() {
        val em = source("src/main/java/com/iris/localai/engine/EngineManager.kt")
        assertTrue(em.contains("fun cancel(owner: GenOwner): Boolean = ownership.cancel(owner)"))
        // load / unload are the only global stops
        assertEquals(2, Regex("\\n\\s+cancelAll\\(\\)\\n").findAll(em).count())
        assertTrue(em.contains("val run = ownership.begin(owner)"))
        assertTrue(em.contains("ownership.end(run)"))
        val vm = source("src/main/java/com/iris/localai/ui/chat/ChatViewModel.kt")
        assertFalse("ChatViewModel still calls the global cancel", vm.contains("c.engine.cancel()"))
        assertTrue(vm.contains("c.engine.cancel(engineOwner)"))
        // every generation the chat screen starts carries its owner
        val gens = Regex("c\\.engine\\.generate\\(").findAll(vm).count()
        assertEquals(gens, Regex("c\\.engine\\.generate\\([^\\n]*engineOwner\\)|\\n\\s+engineOwner,\\n").findAll(vm).count())
        // the API server never cancels the engine (a client stops by disconnecting its own request)
        val api = source("src/main/java/com/iris/localai/server/ApiRoutes.kt")
        assertFalse(api.contains("engine.cancel"))
    }
}
