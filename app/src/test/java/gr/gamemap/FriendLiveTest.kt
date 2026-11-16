package gr.gamemap

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** A friend request seen live (the open connection), timed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FriendLiveTest {
    @Test
    fun requestArrivesLive() {
        val a = FriendCore(FriendNtfyTest.Mem(), Friends.Ntfy).apply { name = "Live A" }
        val b = FriendCore(FriendNtfyTest.Mem(), Friends.Ntfy).apply { name = "Live B" }
        var gotAt = 0L
        val listener = Thread {
            val c = URL("https://ntfy.sh/${b.myTopics().joinToString(",")}/json").openConnection() as HttpURLConnection
            c.readTimeout = 60_000
            c.inputStream.bufferedReader().use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    if (line.contains("\"event\":\"message\"")) { gotAt = System.currentTimeMillis(); break }
                }
            }
        }.apply { isDaemon = true; start() }
        Thread.sleep(2500) // connected
        val sent = System.currentTimeMillis()
        a.request(b.code)
        listener.join(30_000)
        val ms = gotAt - sent
        var checks = 0
        val t1 = System.currentTimeMillis()
        while (b.requests.isEmpty() && System.currentTimeMillis() - t1 < 10_000) { checks++; b.check(); if (b.requests.isEmpty()) Thread.sleep(1500) }
        File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }.resolve("friendlive.txt")
            .writeText("request seen live after $ms ms; in the list after $checks check(s), ${System.currentTimeMillis() - sent} ms\n")
        assertTrue("arrived ($ms ms)", gotAt > 0 && ms < 5000)
        assertTrue(b.requests.isNotEmpty())
    }
}
