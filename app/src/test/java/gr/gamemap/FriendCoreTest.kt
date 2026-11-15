package gr.gamemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Two phones becoming friends, inviting each other, new codes, removing and blocking. */
@RunWith(RobolectricTestRunner::class)
class FriendCoreTest {
    /** ntfy in memory: every message kept with an increasing id. */
    class Net : FriendCore.Wire {
        val msgs = ArrayList<Triple<String, String, String>>()
        override fun post(topic: String, body: String) { synchronized(msgs) { msgs += Triple("m${msgs.size + 1}", topic, body) } }
        override fun poll(topics: List<String>, since: String): List<Triple<String, String, String>> = synchronized(msgs) {
            val from = if (since.startsWith("m")) since.drop(1).toInt() else 0
            msgs.drop(from).filter { it.second in topics }
        }
    }

    class Mem : FriendCore.Store {
        val m = HashMap<String, String>()
        override fun get(key: String) = m[key]
        override fun put(key: String, value: String?) { if (value == null) m.remove(key) else m[key] = value }
    }

    private var now = 1_000_000_000L
    private val net = Net()
    private fun phone(name: String) = FriendCore(Mem(), net) { now }.apply { this.name = name }

    @Test
    fun friends() {
        val a = phone("Vyto"); val b = phone("Nikos")
        assertTrue(a.code, a.code.matches(Regex("VYTO-[0-9]{4}")))
        assertTrue(phone("Νίκος").code.startsWith("NIKOS-"))
        assertTrue(phone("").code.startsWith("DRIVER-"))

        // A adds B by code ("kx7p 4821" typed any way)
        assertTrue(a.request(b.code.lowercase().replace("-", " ")))
        assertTrue(a.requested(b.code))
        assertFalse("not my own code", a.request(a.code))
        val news = b.check()
        assertEquals(1, news.requests.size)
        assertEquals("Vyto", b.requests.single().name)
        assertTrue("nothing shared before accepting", a.friends.isEmpty())

        b.accept(b.requests.single())
        assertEquals("Vyto", b.friends.single().name)
        val na = a.check()
        assertEquals("Nikos", na.accepted.single().name)
        assertEquals("Nikos", a.friends.single().name)
        assertFalse(a.requested(b.code))
        assertEquals("the sender sees it was accepted", "accepted", a.pending.single().status)
        assertEquals("Nikos", a.pending.single().name)

        // a new code: friends stay, the old code stops working
        val old = b.code
        while (b.code == old) b.newCode()
        assertNotEquals(old, b.code)
        assertTrue("same name, new digits", b.code.startsWith("NIKOS-"))
        assertEquals(1, b.friends.size)
        val c = phone("Eleni")
        c.request(old)
        assertTrue("old code: no request arrives", b.check().requests.isEmpty())
        c.request(b.code)
        assertEquals("Eleni", b.check().requests.single().name)
        b.decline(b.requests.single())
        assertTrue(b.requests.isEmpty())
        assertEquals("the sender sees it was declined", 1, c.check().declined.size)
        assertTrue(c.pending.any { it.status == "declined" })
        assertFalse(c.requested(b.code))
    }

    @Test
    fun invites() {
        val a = phone("Vyto"); val b = phone("Nikos")
        a.request(b.code); b.check(); b.accept(b.requests.single()); a.check()
        val fb = a.friends.single()

        // two invites to the same convoy: one entry ("2 invites")
        a.invite(fb, "k7p2qx", "Acropolis Museum")
        a.invite(fb, "k7p2qx", "Acropolis Museum")
        b.check()
        assertEquals(1, b.invites.size)
        assertEquals(2, b.invites.single().count)
        assertEquals("Acropolis Museum", b.invites.single().dest)
        assertEquals("sent", a.sentTo(fb.fid, "k7p2qx")!!.status)

        b.answer("k7p2qx", "seen"); a.check()
        assertEquals("seen", a.sentTo(fb.fid, "k7p2qx")!!.status)
        b.answer("k7p2qx", "joined"); a.check()
        assertEquals("joined", a.sentTo(fb.fid, "k7p2qx")!!.status)
        assertTrue("joined: gone from the list", b.invites.isEmpty())

        // the convoy ends: the invite disappears
        a.invite(fb, "zzz999", "Sounio"); b.check()
        assertEquals(1, b.invites.size)
        a.cancelInvites("zzz999"); b.check()
        assertTrue(b.invites.isEmpty())

        // older than 2 h: never shown
        a.invite(fb, "old111", "Glyfada"); b.check()
        assertEquals(1, b.invites.size)
        now += FriendCore.INVITE_TTL + 1
        assertTrue(b.invites.isEmpty())
    }

    @Test
    fun removeAndBlock() {
        val a = phone("Vyto"); val b = phone("Nikos")
        a.request(b.code); b.check(); b.accept(b.requests.single()); a.check()
        a.remove(a.friends.single().fid); b.check()
        assertTrue("removed on both phones", a.friends.isEmpty() && b.friends.isEmpty())

        // blocked: their requests never show
        a.request(b.code); b.check()
        val r = b.requests.single()
        b.block(r.fid, r.name)
        a.request(b.code)
        assertTrue(b.check().requests.isEmpty())
        assertEquals("Vyto", b.blockedNames().single().second)
        b.unblock(r.fid)
        a.request(b.code)
        assertEquals(1, b.check().requests.size)
    }

    @Test
    fun others_cant_read_or_fake() {
        val a = phone("Vyto"); val b = phone("Nikos")
        a.request(b.code); b.check(); b.accept(b.requests.single()); a.check()
        a.invite(a.friends.single(), "k7p2qx", "Acropolis Museum")
        // nothing readable on the wire, and no names or codes in the topics
        for ((_, topic, body) in net.msgs) {
            assertTrue(body.startsWith("e1."))
            assertFalse(body.contains("Acropolis") || body.contains("Vyto") || topic.contains(b.code.replace("-", "")))
        }
        // someone else writing to B's request topic with the wrong key: ignored
        val stranger = phone("X")
        net.post(net.msgs.first().second, "e1.not-a-real-message")
        assertTrue(b.check().requests.isEmpty())
        assertTrue(stranger.friends.isEmpty())
    }
}
