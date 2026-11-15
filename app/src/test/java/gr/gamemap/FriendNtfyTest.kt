package gr.gamemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Two phones becoming friends and inviting each other through the real ntfy.sh. */
@RunWith(RobolectricTestRunner::class)
class FriendNtfyTest {
    class Mem : FriendCore.Store {
        val m = HashMap<String, String>()
        override fun get(key: String) = m[key]
        override fun put(key: String, value: String?) { if (value == null) m.remove(key) else m[key] = value }
    }

    @Test
    fun overTheNetwork() {
        val log = StringBuilder()
        val a = FriendCore(Mem(), Friends.Ntfy).apply { name = "Test A" }
        val b = FriendCore(Mem(), Friends.Ntfy).apply { name = "Test B" }
        fun until(what: String, f: () -> Boolean) {
            val t0 = System.currentTimeMillis()
            while (!f()) { assertTrue("timed out: $what", System.currentTimeMillis() - t0 < 30_000); Thread.sleep(1500) }
            log.append("$what: ${System.currentTimeMillis() - t0} ms\n")
        }
        a.request(b.code)
        until("request arrives") { b.check(); b.requests.isNotEmpty() }
        b.accept(b.requests.single())
        until("accept arrives") { a.check(); a.friends.isNotEmpty() }
        assertEquals("Test B", a.friends.single().name)
        a.invite(a.friends.single(), "zz" + System.currentTimeMillis() % 10000, "Test place")
        until("invite arrives") { b.check(); b.invites.isNotEmpty() }
        b.answer(b.invites.single().convoy, "joined")
        until("joined arrives") { a.check(); a.sent.single().status == "joined" }
        // nothing new: the next check brings nothing again (since = last id)
        assertTrue(!b.check().anyChange)
        File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }.resolve("friendntfy.txt").writeText(log.toString())
    }
}
