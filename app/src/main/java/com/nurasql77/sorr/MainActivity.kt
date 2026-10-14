package com.nurasql77.sorr

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.system.Os
import android.text.InputType
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import java.io.File

class MainActivity : ComponentActivity() {

    companion object {
        private const val PREFS = "sorr"
        private const val KEY_PATH = "sorr_path"
        private const val KEY_GUEST_IP = "guest_ip"
        private const val KEY_P2_PORT = "p2_port"
        private const val KEY_P2_TEST = "p2_test"
        private const val CORE_NAME = "libbennugd_libretro.so"
    }

    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private var retroView: GLRetroView? = null
    private var controlsLayer: ControlsLayer? = null
    private var hostServer: HostServer? = null
    private var guestClient: GuestClient? = null
    private var discovery: DiscoveryListener? = null

    // Ойын кезінде перне оқиғалары осында барады (Solo/Host: core-ға, Guest: желі арқылы Host-қа)
    private var keySink: ((Int, Int) -> Unit)? = null

    private var waitingForStorageAccess = false
    private var pendingHost = false

    // SoRR папкасын таңдау
    private val pickFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        val path = treeUriToPath(uri)
        if (path == null) {
            toast("Папка жолын анықтау мүмкін болмады")
        } else {
            prefs.edit().putString(KEY_PATH, path).apply()
        }
        showMenu()
    }

    // Android 10 және одан төмен үшін рұқсат
    private val legacyPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasStorageAccess()) {
            startGame(pendingHost)
        } else {
            toast("Файлдарға рұқсат берілмеді")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        showMenu()
    }

    override fun onResume() {
        super.onResume()
        // "Барлық файлдарға рұқсат" баптауынан қайтқанда ойынды бастау
        if (waitingForStorageAccess && hasStorageAccess()) {
            waitingForStorageAccess = false
            startGame(pendingHost)
        }
    }

    override fun onDestroy() {
        stopNetwork()
        super.onDestroy()
    }

    private fun stopNetwork() {
        hostServer?.stop()
        hostServer = null
        guestClient?.stop()
        guestClient = null
        discovery?.stop()
        discovery = null
    }

    // ---------- Мәзір ----------

    private fun showMenu() {
        stopNetwork()
        retroView = null
        controlsLayer = null
        keySink = null
        val savedPath = prefs.getString(KEY_PATH, null)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            setPadding(48, 48, 48, 48)
        }

        val title = TextView(this).apply {
            text = "Streets of Rage Remake"
            textSize = 26f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        root.addView(title)

        val ip = Net.localIp() ?: "анықталмады"
        val info = TextView(this).apply {
            text = (if (savedPath != null) "Папка: $savedPath" else "SoRR папкасы таңдалмаған") +
                "\nБұл телефонның IP: $ip"
            textSize = 14f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 32)
        }
        root.addView(info)

        val p2Port = prefs.getInt(KEY_P2_PORT, 1)

        if (savedPath != null) {
            root.addView(Button(this).apply {
                text = "Ойынды бастау"
                setOnClickListener { startGame(false) }
            })
            root.addView(Button(this).apply {
                text = "LAN: ойынды ашу (Host, 1-ойыншы)"
                setOnClickListener { startGame(true) }
            })
            root.addView(Button(this).apply {
                text = "2-ойыншы порты: $p2Port (ауыстыру)"
                setOnClickListener {
                    prefs.edit().putInt(KEY_P2_PORT, if (p2Port >= 3) 1 else p2Port + 1).apply()
                    showMenu()
                }
            })
            root.addView(Button(this).apply {
                val on = prefs.getBoolean(KEY_P2_TEST, false)
                text = "Сынақ: менің түймелерім = 2-ойыншы: " + (if (on) "ҚОСУЛЫ" else "өшірулі")
                setOnClickListener {
                    prefs.edit().putBoolean(KEY_P2_TEST, !on).apply()
                    showMenu()
                }
            })
        }

        root.addView(Button(this).apply {
            text = "LAN: қосылу (Guest, 2-ойыншы)"
            setOnClickListener { showGuestMenu() }
        })

        root.addView(Button(this).apply {
            text = "SoRR папкасын таңдау"
            setOnClickListener { pickFolder.launch(null) }
        })

        setContentView(root)
    }

    // ---------- Рұқсат ----------

    private fun hasStorageAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            legacyPermission.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        }
    }

    // ---------- Ойынды бастау (Solo немесе Host) ----------

    private fun startGame(asHost: Boolean) {
        val folderPath = prefs.getString(KEY_PATH, null)
        val folder = folderPath?.let { File(it) }
        if (folder == null || !folder.isDirectory) {
            toast("SoRR папкасын қайта таңдаңыз")
            showMenu()
            return
        }

        if (!hasStorageAccess()) {
            pendingHost = asHost
            waitingForStorageAccess = true
            toast("Файлдарға рұқсат беріңіз")
            requestStorageAccess()
            return
        }

        val dat = folder.listFiles()?.firstOrNull {
            it.isFile && it.name.equals("SorR.dat", ignoreCase = true)
        }
        if (dat == null) {
            toast("SorR.dat табылмады: ${folder.absolutePath}")
            return
        }

        val core = File(applicationInfo.nativeLibraryDir, CORE_NAME)
        if (!core.exists()) {
            toast("Core табылмады: ${core.absolutePath}")
            return
        }

        val saves = File(folder, "savegame")
        if (!saves.exists()) saves.mkdirs()

        try {
            // Core өз ішінде APK қолтаңбасын тексеру үшін APK жолын алады
            Os.setenv("SORR_APK_PATH", applicationInfo.sourceDir, true)

            val data = GLRetroViewData(this).apply {
                coreFilePath = core.absolutePath
                gameFilePath = dat.absolutePath
                systemDirectory = folder.absolutePath
                savesDirectory = saves.absolutePath
            }

            val view = GLRetroView(this, data)
            lifecycle.addObserver(view)
            retroView = view

            // Сынақ режимі: өз түймелеріміз 2-ойыншының портына барады (core P2-ні қабылдайтынын тексеру үшін)
            val p2Port = prefs.getInt(KEY_P2_PORT, 1)
            val testP2 = prefs.getBoolean(KEY_P2_TEST, false)
            var activePort = if (testP2) p2Port else 0
            val sink: (Int, Int) -> Unit = { action, keyCode ->
                view.sendKeyEvent(action, keyCode, activePort)
            }
            keySink = sink

            val container = FrameLayout(this)
            container.addView(
                view,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )

            // Host режимінде Guest-тан келген түймелерді көрсететін диагностика жазуы
            val hostInfo: TextView? = if (asHost) {
                TextView(this).apply {
                    setTextColor(Color.WHITE)
                    textSize = 12f
                    alpha = 0.7f
                    setBackgroundColor(0x66000000)
                    setPadding(16, 4, 16, 4)
                    text = "2P порт $p2Port: Guest-тан оқиға әлі жоқ"
                }.also {
                    container.addView(
                        it,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            Gravity.TOP or Gravity.CENTER_HORIZONTAL
                        ).apply { topMargin = 4 }
                    )
                }
            } else {
                null
            }

            // Сенсорлық басқару (орны, өлшемі, мөлдірлігі өзгертіледі және сақталады)
            val layer = ControlsLayer(this, prefs, sink)
            layer.addTo(container)
            controlsLayer = layer

            // P1/P2 ауыстырғыш: SoRR-дың Controls мәзірінде әр ойыншының түймелерін бөлек тағайындау үшін
            layer.addPortSwitch(
                container,
                { if (activePort == 0) "P1" else "P2" },
                { activePort = if (activePort == 0) p2Port else 0 }
            )

            setContentView(container)
            enterImmersiveMode()

            if (asHost) {
                var count = 0
                val server = HostServer(this, view, p2Port, { action, keyCode ->
                    count++
                    val name = KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
                    val arrow = if (action == KeyEvent.ACTION_DOWN) "↓" else "↑"
                    hostInfo?.text = "2P порт $p2Port: $count оқиға • $name $arrow"
                }) { toast(it) }
                server.start()
                hostServer = server
                toast("LAN Host: екінші телефон мына IP-ге қосылсын: ${Net.localIp() ?: "белгісіз"}")
            }
        } catch (e: Throwable) {
            toast("Қате: ${e.message}")
            showMenu()
        }
    }

    // ---------- Guest (2-ойыншы) ----------

    // Host-ты автоматты іздейтін мәзір (қолмен IP енгізуге де болады)
    private fun showGuestMenu() {
        stopNetwork()
        keySink = null

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.BLACK)
            setPadding(48, 48, 48, 48)
        }

        root.addView(TextView(this).apply {
            text = "LAN: Host іздеу"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })

        root.addView(TextView(this).apply {
            text = "Host телефонда \"LAN: ойынды ашу\" басылған болуы керек.\nЕкі телефон бір Wi-Fi желісінде болсын."
            textSize = 13f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 16)
        })

        val hostsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val searching = TextView(this).apply {
            text = "Іздеу..."
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
        }
        hostsBox.addView(searching)
        root.addView(hostsBox)

        val shown = mutableSetOf<String>()
        val listener = DiscoveryListener(this) { ip, name ->
            if (shown.add(ip)) {
                searching.text = "Табылған телефондар:"
                hostsBox.addView(Button(this).apply {
                    text = "$name  ($ip)"
                    setOnClickListener { startGuest(ip) }
                })
            }
        }
        discovery = listener
        listener.start()

        // Қолмен IP
        val ipInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "немесе IP қолмен: 192.168.1.5"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setText(prefs.getString(KEY_GUEST_IP, ""))
        }
        root.addView(ipInput)

        root.addView(Button(this).apply {
            text = "Осы IP-ге қосылу"
            setOnClickListener {
                val ip = ipInput.text.toString().trim()
                if (ip.isEmpty()) toast("IP енгізіңіз") else startGuest(ip)
            }
        })

        root.addView(Button(this).apply {
            text = "Артқа"
            setOnClickListener { showMenu() }
        })

        setContentView(root)
    }

    private fun startGuest(ip: String) {
        discovery?.stop()
        discovery = null
        prefs.edit().putString(KEY_GUEST_IP, ip).apply()

        val image = ImageView(this).apply {
            setBackgroundColor(Color.BLACK)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val stats = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            alpha = 0.7f
            setBackgroundColor(0x66000000)
            setPadding(16, 4, 16, 4)
            text = "Қосылуда..."
        }

        val client = GuestClient(
            host = ip,
            activity = this,
            onFrame = { bmp -> image.setImageBitmap(bmp) },
            onStatus = { toast(it) },
            onStats = { ping, fps, noVideo ->
                stats.text = if (noVideo) {
                    "Видео келмей тұр..."
                } else {
                    "Ping " + (if (ping >= 0) "$ping мс" else "-") + "  •  $fps к/с"
                }
            },
            onClosed = { showMenu() }
        )
        guestClient = client

        val sink: (Int, Int) -> Unit = { action, keyCode -> client.sendKey(action, keyCode) }
        keySink = sink

        val container = FrameLayout(this)
        container.addView(
            image,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        container.addView(
            stats,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply { topMargin = 4 }
        )

        val layer = ControlsLayer(this, prefs, sink)
        layer.addTo(container)
        controlsLayer = layer

        setContentView(container)
        enterImmersiveMode()
        client.start()
    }

    private fun enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    // ---------- Басқару (геймпад) ----------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val sink = keySink ?: return super.dispatchKeyEvent(event)

        // Артқа түймесі: алдымен басқаруды өзгерту режимін жабады, әйтпесе ойыннан шығады
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) {
                if (controlsLayer?.handleBack() != true) finish()
            }
            return true
        }
        // Дыбыс түймелерін жүйеге қалдырамыз
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        ) {
            return super.dispatchKeyEvent(event)
        }

        // Нақты геймпад қосылса, экрандағы түймелерді жасырамыз
        if ((event.source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) {
            controlsLayer?.onPhysicalGamepad()
        }

        sink(event.action, event.keyCode)
        return true
    }

    // ---------- Көмекші ----------

    // content://...externalstorage.../tree/primary%3ASoRR  ->  /storage/emulated/0/SoRR
    private fun treeUriToPath(uri: Uri): String? {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(uri) // мысалы "primary:SoRR"
            val parts = docId.split(":", limit = 2)
            val volume = parts[0]
            val relative = if (parts.size > 1) parts[1] else ""
            val base = if (volume.equals("primary", ignoreCase = true)) {
                Environment.getExternalStorageDirectory().absolutePath
            } else {
                "/storage/$volume"
            }
            if (relative.isEmpty()) base else "$base/$relative"
        } catch (e: Exception) {
            null
        }
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }
}
