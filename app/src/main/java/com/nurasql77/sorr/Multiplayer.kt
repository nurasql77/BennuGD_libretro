package com.nurasql77.sorr

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.KeyEvent
import android.view.PixelCopy
import com.swordfish.libretrodroid.GLRetroView
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LAN ойыны: Host телефон ойынды іске қосады және 2-ойыншыға видео жібереді,
 * Guest телефон видеоны көрсетіп, өз түймелерін Host-қа жібереді (2-ойыншы = 2-порт).
 */
object Net {
    const val PORT = 47601
    const val MSG_INPUT = 1   // [1][action: u8][keyCode: i32]
    const val MSG_FRAME = 2   // [2][length: i32][JPEG]
    const val FRAME_WIDTH = 640
    const val FRAME_INTERVAL_MS = 45L   // шамамен 22 кадр/с
    const val JPEG_QUALITY = 65

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
}

/** Host жағы: 2-ойыншыны қабылдайды, видео жібереді, оның түймелерін 2-портқа береді. */
class HostServer(
    private val activity: Activity,
    private val game: GLRetroView,
    private val onStatus: (String) -> Unit
) {
    @Volatile private var running = false
    private var server: ServerSocket? = null
    private var pixelThread: HandlerThread? = null
    private var clientSocket: Socket? = null

    fun start() {
        running = true
        pixelThread = HandlerThread("sorr-pixelcopy").also { it.start() }

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
        clientSocket = sock
        sock.tcpNoDelay = true
        status("2-ойыншы қосылды")

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
                val type = input.readUnsignedByte()
                if (type == Net.MSG_INPUT) {
                    val action = input.readUnsignedByte()
                    val keyCode = input.readInt()
                    if (action == KeyEvent.ACTION_DOWN) held.add(keyCode) else held.remove(keyCode)
                    activity.runOnUiThread { game.sendKeyEvent(action, keyCode, 1) }
                }
            }
        } catch (e: Exception) {
            // клиент ажыратылды
        } finally {
            alive.set(false)
            // Ұсталып қалған түймелерді босатамыз
            val toRelease = held.toList()
            activity.runOnUiThread {
                for (k in toRelease) game.sendKeyEvent(KeyEvent.ACTION_UP, k, 1)
            }
            try { sock.close() } catch (e: Exception) { }
            if (running) status("2-ойыншы ажыратылды")
        }
    }

    private fun sendFrames(out: DataOutputStream, alive: AtomicBoolean) {
        var bmp: Bitmap? = null
        val baos = ByteArrayOutputStream(64 * 1024)

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
                    val ok = AtomicBoolean(false)
                    PixelCopy.request(game, target, { result ->
                        ok.set(result == PixelCopy.SUCCESS)
                        latch.countDown()
                    }, Handler(looper))
                    latch.await(500, TimeUnit.MILLISECONDS)

                    if (ok.get()) {
                        baos.reset()
                        target.compress(Bitmap.CompressFormat.JPEG, Net.JPEG_QUALITY, baos)
                        out.writeByte(Net.MSG_FRAME)
                        out.writeInt(baos.size())
                        baos.writeTo(out)
                        out.flush()
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

/** Guest жағы: Host-қа қосылады, видеоны көрсетеді, түймелерді жібереді. */
class GuestClient(
    private val host: String,
    private val activity: Activity,
    private val onFrame: (Bitmap) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onClosed: () -> Unit
) {
    @Volatile private var running = false
    @Volatile private var out: DataOutputStream? = null
    private var socket: Socket? = null
    private val sender = Executors.newSingleThreadExecutor()

    fun start() {
        running = true
        Thread {
            try {
                val s = Socket()
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(host, Net.PORT), 5000)
                socket = s
                out = DataOutputStream(s.getOutputStream().buffered())
                activity.runOnUiThread { onStatus("Host-қа қосылды") }

                val input = DataInputStream(s.getInputStream().buffered())
                while (running) {
                    val type = input.readUnsignedByte()
                    if (type == Net.MSG_FRAME) {
                        val len = input.readInt()
                        if (len <= 0 || len > 4_000_000) break
                        val buf = ByteArray(len)
                        input.readFully(buf)
                        val bmp = BitmapFactory.decodeByteArray(buf, 0, len)
                        if (bmp != null) activity.runOnUiThread { onFrame(bmp) }
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

    fun sendKey(action: Int, keyCode: Int) {
        val o = out ?: return
        sender.execute {
            try {
                synchronized(o) {
                    o.writeByte(Net.MSG_INPUT)
                    o.writeByte(action)
                    o.writeInt(keyCode)
                    o.flush()
                }
            } catch (e: Exception) {
                // жіберілмеді
            }
        }
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (e: Exception) { }
        sender.shutdownNow()
    }
}
