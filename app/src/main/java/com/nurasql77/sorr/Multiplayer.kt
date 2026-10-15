package com.nurasql77.sorr

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
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
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * LAN-игра.
 *  - Хост запускает игру, шлёт гостю видео (JPEG) и отдаёт его кнопки как игрока 2.
 *  - Гость находит хост автоматически (UDP broadcast) или подключается по IP.
 */
object Net {
    const val PORT = 47601              // TCP: игра
    const val DISCOVERY_PORT = 47602    // UDP: поиск хоста

    const val MSG_INPUT = 1   // [1][action: u8][keyCode: i32]
    const val MSG_FRAME = 2   // [2][length: i32][JPEG]
    const val MSG_PING = 3    // [3][time: i64]
    const val MSG_PONG = 4    // [4][time: i64]

    /** Ширина кадра, интервал между кадрами (мс), стартовое качество JPEG. */
    class VideoPreset(val width: Int, val intervalMs: Long, val quality: Int) {
        val fps: Int get() = (1000 / intervalMs).toInt()
    }

    // 0 = экономно, 1 = баланс, 2 = качество
    val presets = listOf(
        VideoPreset(480, 67, 55),
        VideoPreset(640, 50, 65),
        VideoPreset(800, 33, 70)
    )

    const val QUALITY_MIN = 35
    const val QUALITY_MAX = 80

    // Маленькие сетевые буферы: очередь старых кадров не копится, значит нет «ускорения» после лагов
    const val SOCKET_BUFFER = 64 * 1024

    /** IPv4-адрес этого телефона в Wi-Fi / точке доступа. */
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
            // IP не найден
        }
        return null
    }

    /** Broadcast-адреса всех активных сетей. */
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
            // остаётся только 255.255.255.255
        }
        return result
    }
}

/** Сторона хоста. */
class HostServer(
    private val activity: Activity,
    private val game: GLRetroView,
    private val guestPort: Int,
    private val preset: Net.VideoPreset,
    private val onGuestInput: (action: Int, keyCode: Int) -> Unit,
    private val onStatus: (String) -> Unit
) {
    @Volatile private var running = false
    private var server: ServerSocket? = null
    private var pixelThread: HandlerThread? = null
    @Volatile private var clientSocket: Socket? = null

    fun start() {
        running = true
        // Низкий приоритет: захват видео не должен тормозить саму игру
        pixelThread = HandlerThread("sorr-pixelcopy", Process.THREAD_PRIORITY_BACKGROUND).also { it.start() }

        // 1) Сообщаем в сеть «я здесь»
        Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            var sock: DatagramSocket? = null
            try {
                sock = DatagramSocket().apply { broadcast = true }
                val payload = "SORR1|${Build.MODEL}|${Net.PORT}".toByteArray()
                while (running) {
                    for (addr in Net.broadcastAddresses()) {
                        try {
                            sock.send(DatagramPacket(payload, payload.size, addr, Net.DISCOVERY_PORT))
                        } catch (e: Exception) {
                            // этот адрес не сработал
                        }
                    }
                    Thread.sleep(1000)
                }
            } catch (e: Exception) {
                // остановлено
            } finally {
                sock?.close()
            }
        }.also {
            it.isDaemon = true
            it.start()
        }

        // 2) Принимаем гостя
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
                if (running) status(tr(
                    "Network error: ${e.message}",
                    "Ошибка сети: ${e.message}",
                    "Желі қатесі: ${e.message}"
                ))
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
        // Новый гость: старого закрываем
        try { clientSocket?.close() } catch (e: Exception) { }
        clientSocket = sock
        sock.tcpNoDelay = true
        try { sock.sendBufferSize = Net.SOCKET_BUFFER } catch (e: Exception) { }
        status(tr(
            "Player 2 connected (port $guestPort)",
            "Игрок 2 подключился (порт $guestPort)",
            "2-ойыншы қосылды (порт $guestPort)"
        ))

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
            // гость отключился
        } finally {
            alive.set(false)
            // Отпускаем зажатые кнопки
            val toRelease = held.toList()
            activity.runOnUiThread {
                for (k in toRelease) game.sendKeyEvent(KeyEvent.ACTION_UP, k, guestPort)
            }
            try { sock.close() } catch (e: Exception) { }
            if (running) status(tr("Player 2 disconnected", "Игрок 2 отключился", "2-ойыншы ажыратылды"))
        }
    }

    private fun sendFrames(out: DataOutputStream, alive: AtomicBoolean) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)

        var bmp: Bitmap? = null
        val baos = ByteArrayOutputStream(64 * 1024)
        var quality = preset.quality
        var interval = preset.intervalMs
        var nextAt = SystemClock.elapsedRealtime()
        var failures = 0
        var reported = false

        try {
            while (running && alive.get()) {
                val vw = game.width
                val vh = game.height
                val looper = pixelThread?.looper
                var sendMs = 0L

                if (vw > 0 && vh > 0 && looper != null) {
                    val w = preset.width
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
                        sendMs = SystemClock.elapsedRealtime() - tw
                    } else {
                        failures++
                        if (failures >= 30 && !reported) {
                            reported = true
                            status(tr(
                                "Video capture is not working (PixelCopy code ${code.get()})",
                                "Захват видео не работает (код PixelCopy ${code.get()})",
                                "Видео түсіру істемей тұр (PixelCopy коды ${code.get()})"
                            ))
                        }
                    }
                }

                // Желі баяу болса: сапаны және кадр жиілігін түсіреміз, жақсы болса қайтарамыз
                if (sendMs > 40) {
                    quality = (quality - 5).coerceAtLeast(Net.QUALITY_MIN)
                    interval = (interval + 10).coerceAtMost(150)
                } else if (sendMs in 1..12) {
                    quality = (quality + 2).coerceAtMost(minOf(Net.QUALITY_MAX, preset.quality + 10))
                    interval = (interval - 5).coerceAtLeast(preset.intervalMs)
                }

                // Ровный ритм без «догоняющих» всплесков
                nextAt += interval
                val now = SystemClock.elapsedRealtime()
                val wait = nextAt - now
                if (wait > 0) Thread.sleep(wait) else nextAt = now
            }
        } catch (e: Exception) {
            alive.set(false)
        }
    }
}

/** Поиск хоста гостем (слушаем UDP broadcast). */
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
            // lock не получен, broadcast всё равно может работать
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
                        // ждём
                    }
                }
            } catch (e: Exception) {
                // остановлено
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

/** Сторона гостя. */
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

    // Всегда показываем самый свежий кадр: старые отбрасываем (иначе после лага видео «ускоряется»)
    private val rawQueue = ArrayBlockingQueue<ByteArray>(1)
    private val latest = AtomicReference<Bitmap?>(null)
    private val uiPosted = AtomicBoolean(false)

    fun start() {
        running = true
        lastFrameAt = SystemClock.elapsedRealtime()
        startDecoder()

        Thread {
            try {
                val s = Socket()
                s.tcpNoDelay = true
                s.receiveBufferSize = Net.SOCKET_BUFFER
                s.connect(InetSocketAddress(host, Net.PORT), 5000)
                socket = s
                out = DataOutputStream(s.getOutputStream().buffered())
                activity.runOnUiThread {
                    onStatus(tr("Connected to the host", "Подключено к хосту", "Хостқа қосылды"))
                }

                startStatsLoop()

                val input = DataInputStream(s.getInputStream().buffered())
                while (running) {
                    when (input.readUnsignedByte()) {
                        Net.MSG_FRAME -> {
                            val len = input.readInt()
                            if (len <= 0 || len > 4_000_000) break
                            val buf = ByteArray(len)
                            input.readFully(buf)
                            rawQueue.poll()      // старый необработанный кадр выбрасываем
                            rawQueue.offer(buf)
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
                    activity.runOnUiThread {
                        onStatus(tr(
                            "No connection: ${e.message}",
                            "Нет связи: ${e.message}",
                            "Байланыс жоқ: ${e.message}"
                        ))
                    }
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

    // Декодирование в отдельном потоке: чтение из сети не блокируется
    private fun startDecoder() {
        Thread {
            while (running) {
                val data = try {
                    rawQueue.poll(200, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    null
                } ?: continue

                val bmp = BitmapFactory.decodeByteArray(data, 0, data.size) ?: continue
                frames.incrementAndGet()
                lastFrameAt = SystemClock.elapsedRealtime()

                latest.set(bmp)
                if (uiPosted.compareAndSet(false, true)) {
                    activity.runOnUiThread {
                        uiPosted.set(false)
                        latest.getAndSet(null)?.let { onFrame(it) }
                    }
                }
            }
        }.also {
            it.isDaemon = true
            it.start()
        }
    }

    // Каждую секунду шлёт ping и показывает статистику
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
                // остановлено
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
                    // не отправлено
                }
            }
        } catch (e: Exception) {
            // после stop()
        }
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (e: Exception) { }
        sender.shutdownNow()
    }
}
