package com.nurasql77.sorr

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.KeyEvent
import android.view.PixelCopy
import com.swordfish.libretrodroid.GLRetroView
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * LAN ойыны.
 *  - Host: ойынды іске қосады, видеоны (JPEG) Guest-қа жібереді, Guest түймелерін 2-ойыншы ретінде береді.
 *  - Guest: Host-ты автоматты табады (UDP broadcast) немесе IP бойынша қосылады.
 */
object Net {
    const val PORT = 47601              // TCP: ойын байланысы
    const val DISCOVERY_PORT = 47602    // UDP: Host іздеу

    const val MSG_INPUT = 1   // [1][action: u8][keyCode: i32]
    const val MSG_FRAME = 2   // [2][length: i32][JPEG]
    const val MSG_PING = 3    // [3][time: i64]
    const val MSG_PONG = 4    // [4][time: i64]

    const val FRAME_WIDTH = 640
    const val FRAME_INTERVAL_MS = 40L   // шамамен 25 кадр/с
    const val QUALITY_START = 65
    const val QUALITY_MIN = 35
    const val QUALITY_MAX = 75

    /** Осы телефонның Wi-Fi / hotspot IPv4 адресі. */
    fun localIp(): String? {
        try {
            val list = NetworkInterface.getNetworkInterfaces() ?: return null
            for (ni in Collections.list(list)) {
                if (!ni.isUp || ni.isLoopback) continue
                for (addr in Collections.list(ni.inetAddresses)) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            // IP табылмады
        }
        return null
    }

    /** Барлық белсенді желілердің broadcast адрестері. */
    fun broadcastAddresses(): List<InetAddress> {
        val result = mutableListOf<InetAddress>()
        try {
            result.add(InetAddress.getByName("255.255.255.255"))
            val list = NetworkInterface.getNetworkInterfaces()
            if (list != null) {
                for (ni in Collections.list(list)) {
                    if (!ni.isUp || ni.isLoopback) continue
                    for (ia in ni.interfaceAddresses) {
                        val b = ia.broadcast
                        if (b != null && !result.contains(b)) result.add(b)
                    }
                }
            }
        } catch (e: Exception) {
            // тек 255.255.255.255 қалады
        }
        return result
    }
}

/** Host жағы. */
class HostServer(
    private val activity: Activity,
    private val game: GLRetroView,
    private val guestPort: Int,
    private val onGuestInput: (action: Int, keyCode: Int) -> Unit,
    private val onStatus: (String) -> Unit
) {
    @Volatile private var running = false
    private var server: ServerSocket? = null
    private var pixelThread: HandlerThread? = null
    @Volatile private var clientSocket: Socket? = null

    fun start() {
        running = true
        pixelThread = HandlerThread("sorr-pixelcopy").also { it.start() }

        // 1) Guest-тарға "мен мұндамын" деп хабарлау
        Thread {
            var sock: DatagramSocket? = null
            try {
                sock = DatagramSocket().apply { broadcast = true }
                val payload = "SORR1|${Build.MODEL}|${Net.PORT}".toByteArray()
                while (running) {
                    for (addr in Net.broadcastAddresses()) {
                        try {
                            sock.send(DatagramPacket(payload, payload.size, addr, Net.DISCOVERY_PORT))
                        } catch (e: Exception) {
                            // осы адрес жұмыс істемеді
                        }
                    }
                    Thread.sleep(1000)
                }
            } catch (e: Exception) {
                // тоқтатылды
            } finally {
                sock?.close()
            }
        }.also {
            it.isDaemon = true
            it.start()
        }

        // 2) Guest-ты қабылдау
        Thread {
            try {
                val s = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(Net.PORT))
                }
                server = s
                while (running) {
                    val sock = s.accept()
                    handleClient(sock)
                }
            } catch (e: Exception) {
                if (running) status("Желі қатесі: ${e.message}")
            }
        }.also {
            it.isDaemon = true
            it.start()
        }
    }

    fun stop() {
        running = false
        try { clientSocket?.close() } catch (e: Exception) { }
        try { server?.close() } catch (e: Exception) { }
        pixelThread?.quitSafely()
        pixelThread = null
    }

    private fun status(text: String) {
        activity.runOnUiThread { onStatus(text) }
    }

    private fun handleClient(sock: Socket) {
        // Жаңа Guest келсе, ескісін жабамыз
        try { clientSocket?.close() } catch (e: Exception) { }
        clientSocket = sock
        sock.tcpNoDelay = true
        status("2-ойыншы қосылды (порт $guestPort)")

        val alive = AtomicBoolean(true)
        val held = mutableSetOf<Int>()

        try {
            val input = DataInputStream(sock.getInputStream().buffered())
            val out = DataOutputStream(sock.getOutputStream().buffered())

            Thread { sendFrames(out, alive) }.also {
                it.isDaemon = true
                it.start()
            }

            while (running && alive.get()) {
                when (input.readUnsignedByte()) {
                    Net.MSG_INPUT -> {
                        val action = input.readUnsignedByte()
                        val keyCode = input.readInt()
                        if (action == KeyEvent.ACTION_DOWN) held.add(keyCode) else held.remove(keyCode)
                        activity.runOnUiThread {
                            game.sendKeyEvent(action, keyCode, guestPort)
                            onGuestInput(action, keyCode)
                        }
                    }
                    Net.MSG_PING -> {
                        val ts = input.readLong()
                        synchronized(out) {
                            out.writeByte(Net.MSG_PONG)
                            out.writeLong(ts)
                            out.flush()
                        }
                    }
                    else -> break
                }
            }
        } catch (e: Exception) {
            // Guest ажыратылды
        } finally {
            alive.set(false)
            // Ұсталып қалған түймелерді босатамыз
            val toRelease = held.toList()
            activity.runOnUiThread {
                for (k in toRelease) game.sendKeyEvent(KeyEvent.ACTION_UP, k, guestPort)
            }
            try { sock.close() } catch (e: Exception) { }
            if (running) status("2-ойыншы ажыратылды")
        }
    }

    private fun sendFrames(out: DataOutputStream, alive: AtomicBoolean) {
        var bmp: Bitmap? = null
        val baos = ByteArrayOutputStream(64 * 1024)
        var quality = Net.QUALITY_START
        var failures = 0
        var reported = false

        try {
            while (running && alive.get()) {
                val t0 = SystemClock.elapsedRealtime()
                val vw = game.width
                val vh = game.height
                val looper = pixelThread?.looper

                if (vw > 0 && vh > 0 && looper != null) {
                    val w = Net.FRAME_WIDTH
                    val h = (w * vh / vw).coerceAtLeast(2)
                    var target = bmp
                    if (target == null || target.width != w || target.height != h) {
                        target = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp = target
                    }

                    val latch = CountDownLatch(1)
                    val code = AtomicInteger(-1)
                    PixelCopy.request(game, target, { result ->
                        code.set(result)
                        latch.countDown()
                    }, Handler(looper))
                    latch.await(500, TimeUnit.MILLISECONDS)

                    if (code.get() == PixelCopy.SUCCESS) {
                        failures = 0
                        baos.reset()
                        target.compress(Bitmap.CompressFormat.JPEG, quality, baos)

                        val tw = SystemClock.elapsedRealtime()
                        synchronized(out) {
                            out.writeByte(Net.MSG_FRAME)
                            out.writeInt(baos.size())
                            baos.writeTo(out)
                            out.flush()
                        }
                        val sendMs = SystemClock.elapsedRealtime() - tw

                        // Желі баяу болса сапаны түсіреміз, жақсы болса көтереміз
                        quality = when {
                            sendMs > 40 -> (quality - 5).coerceAtLeast(Net.QUALITY_MIN)
                            sendMs < 12 -> (quality + 2).coerceAtMost(Net.QUALITY_MAX)
                            else -> quality
                        }
                    } else {
                        failures++
                        if (failures >= 30 && !reported) {
                            reported = true
                            status("Видео түсіру істемей тұр (PixelCopy коды ${code.get()})")
                        }
                    }
                }

                val spent = SystemClock.elapsedRealtime() - t0
                val wait = Net.FRAME_INTERVAL_MS - spent
                if (wait > 0) Thread.sleep(wait)
            }
        } catch (e: Exception) {
            alive.set(false)
        }
    }
}

/** Guest-тың Host іздеуі (UDP broadcast тыңдау). */
class DiscoveryListener(
    private val activity: Activity,
    private val onFound: (ip: String, name: String) -> Unit
) {
    @Volatile private var running = false
    private var socket: DatagramSocket? = null
    private var lock: WifiManager.MulticastLock? = null

    fun start() {
        running = true
        try {
            val wifi = activity.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            lock = wifi?.createMulticastLock("sorr-discovery")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            // lock алынбады, broadcast бәрібір жұмыс істеуі мүмкін
        }

        Thread {
            try {
                val s = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(Net.DISCOVERY_PORT))
                    soTimeout = 1000
                }
                socket = s
                val buf = ByteArray(256)
                while (running) {
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        val parts = String(p.data, 0, p.length).split("|")
                        val ip = p.address.hostAddress
                        if (parts.size >= 3 && parts[0] == "SORR1" && ip != null) {
                            val name = parts[1]
                            activity.runOnUiThread { onFound(ip, name) }
                        }
                    } catch (e: SocketTimeoutException) {
                        // күтеміз
                    }
                }
            } catch (e: Exception) {
                // тоқтатылды
            } finally {
                try { socket?.close() } catch (e: Exception) { }
            }
        }.also {
            it.isDaemon = true
            it.start()
        }
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (e: Exception) { }
        try { lock?.release() } catch (e: Exception) { }
        lock = null
    }
}

/** Guest жағы. */
class GuestClient(
    private val host: String,
    private val activity: Activity,
    private val onFrame: (Bitmap) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onStats: (pingMs: Int, fps: Int, noVideo: Boolean) -> Unit,
    private val onClosed: () -> Unit
) {
    @Volatile private var running = false
    @Volatile private var out: DataOutputStream? = null
    @Volatile private var lastRtt = -1
    @Volatile private var lastFrameAt = 0L
    private val frames = AtomicInteger(0)
    private var socket: Socket? = null
    private val sender = Executors.newSingleThreadExecutor()

    fun start() {
        running = true
        lastFrameAt = SystemClock.elapsedRealtime()

        Thread {
            try {
                val s = Socket()
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(host, Net.PORT), 5000)
                socket = s
                out = DataOutputStream(s.getOutputStream().buffered())
                activity.runOnUiThread { onStatus("Host-қа қосылды") }

                startStatsLoop()

                val input = DataInputStream(s.getInputStream().buffered())
                while (running) {
                    when (input.readUnsignedByte()) {
                        Net.MSG_FRAME -> {
                            val len = input.readInt()
                            if (len <= 0 || len > 4_000_000) break
                            val buf = ByteArray(len)
                            input.readFully(buf)
                            val bmp = BitmapFactory.decodeByteArray(buf, 0, len)
                            if (bmp != null) {
                                frames.incrementAndGet()
                                lastFrameAt = SystemClock.elapsedRealtime()
                                activity.runOnUiThread { onFrame(bmp) }
                            }
                        }
                        Net.MSG_PONG -> {
                            val ts = input.readLong()
                            lastRtt = (SystemClock.elapsedRealtime() - ts).toInt()
                        }
                        else -> break
                    }
                }
            } catch (e: Exception) {
                if (running) {
                    activity.runOnUiThread { onStatus("Байланыс жоқ: ${e.message}") }
                }
            } finally {
                val wasRunning = running
                running = false
                try { socket?.close() } catch (e: Exception) { }
                if (wasRunning) activity.runOnUiThread { onClosed() }
            }
        }.also {
            it.isDaemon = true
            it.start()
        }
    }

    // Әр секунд сайын ping жібереді және статистика көрсетеді
    private fun startStatsLoop() {
        Thread {
            try {
                while (running) {
                    Thread.sleep(1000)
                    sendRaw { o ->
                        o.writeByte(Net.MSG_PING)
                        o.writeLong(SystemClock.elapsedRealtime())
                    }
                    val fps = frames.getAndSet(0)
                    val noVideo = SystemClock.elapsedRealtime() - lastFrameAt > 3000
                    val rtt = lastRtt
                    activity.runOnUiThread { onStats(rtt, fps, noVideo) }
                }
            } catch (e: Exception) {
                // тоқтатылды
            }
        }.also {
            it.isDaemon = true
            it.start()
        }
    }

    fun sendKey(action: Int, keyCode: Int) {
        sendRaw { o ->
            o.writeByte(Net.MSG_INPUT)
            o.writeByte(action)
            o.writeInt(keyCode)
        }
    }

    private fun sendRaw(writer: (DataOutputStream) -> Unit) {
        val o = out ?: return
        try {
            sender.execute {
                try {
                    synchronized(o) {
                        writer(o)
                        o.flush()
                    }
                } catch (e: Exception) {
                    // жіберілмеді
                }
            }
        } catch (e: Exception) {
            // stop() кейін
        }
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (e: Exception) { }
        sender.shutdownNow()
    }
}
