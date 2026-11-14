package gr.gamemap

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.concurrent.Executors

/**
 * The convoy's real-time line: two public MQTT brokers at once (if one is down or slow, the
 * other carries on), over TLS. Each driver's latest update is kept by the broker ("retained"),
 * so whoever joins sees everyone at once. Messages arrive in ~0.1 s instead of ntfy's polling.
 * Payloads are the same end-to-end encrypted text as before (Seal): the brokers see only noise.
 */
object ConvoyLink {
    private val brokers = listOf("ssl://broker.emqx.io:8883", "ssl://broker.hivemq.com:8883")
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "convoy-mqtt").apply { isDaemon = true } }
    @Volatile private var clients: List<MqttClient> = emptyList()
    @Volatile private var base: String? = null
    @Volatile private var onMessage: ((String) -> Unit)? = null
    /** Connected to at least one broker. */
    val connected: Boolean get() = clients.any { it.isConnected }

    /** Joins [topicBase] ("gameroute/<hash>"): every driver's updates come to [handler]. */
    fun start(topicBase: String, handler: (String) -> Unit) {
        if (base == topicBase && clients.isNotEmpty()) { onMessage = handler; return }
        stop()
        base = topicBase; onMessage = handler
        io.execute {
            val list = ArrayList<MqttClient>()
            for (b in brokers) {
                if (base != topicBase) return@execute
                try {
                    val c = MqttClient(b, "gr-" + java.util.UUID.randomUUID().toString().take(12), MemoryPersistence())
                    c.setCallback(object : MqttCallbackExtended {
                        override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                            // after a reconnect the subscription has to be made again
                            if (reconnect) io.execute { try { c.subscribe("$topicBase/+", 0) } catch (_: Exception) {} }
                        }
                        override fun connectionLost(cause: Throwable?) {}
                        override fun messageArrived(topic: String, m: MqttMessage) {
                            if (base != topicBase || m.payload.isEmpty()) return
                            try { onMessage?.invoke(String(m.payload)) } catch (_: Exception) {}
                        }
                        override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                    })
                    c.connect(MqttConnectOptions().apply {
                        isCleanSession = true; isAutomaticReconnect = true
                        connectionTimeout = 10; keepAliveInterval = 60; maxReconnectDelay = 15_000
                    })
                    c.subscribe("$topicBase/+", 0)
                    list += c
                } catch (e: Exception) {
                    CrashLog.note("convoy broker unavailable: $b")
                }
            }
            if (base == topicBase) clients = list else list.forEach { close(it) }
        }
    }

    /** Sends [payload] as this driver's latest state ([retained]: kept for drivers who join later). */
    fun publish(me: String, payload: String, retained: Boolean = true) {
        val b = base ?: return
        io.execute {
            for (c in clients) try {
                if (c.isConnected) c.publish("$b/$me", MqttMessage(payload.toByteArray()).apply { qos = 0; isRetained = retained })
            } catch (_: Exception) {}
        }
    }

    /** Leaves: clears this driver's kept state, then disconnects. */
    fun stop(me: String? = null) {
        val old = clients; val b = base
        clients = emptyList(); base = null; onMessage = null
        io.execute {
            for (c in old) {
                if (me != null && b != null) try { if (c.isConnected) c.publish("$b/$me", MqttMessage(ByteArray(0)).apply { isRetained = true }) } catch (_: Exception) {}
                close(c)
            }
        }
    }

    private fun close(c: MqttClient) {
        try { c.disconnect(2000) } catch (_: Exception) {}
        try { c.close() } catch (_: Exception) {}
    }
}
