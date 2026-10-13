package com.nurasql77.sorr

import android.Manifest
import android.app.AlertDialog
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
        private const val CORE_NAME = "libbennugd_libretro.so"
    }

    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private var retroView: GLRetroView? = null
    private var controlsLayer: ControlsLayer? = null
    private var hostServer: HostServer? = null
    private var guestClient: GuestClient? = null

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

        if (savedPath != null) {
            root.addView(Button(this).apply {
                text = "Ойынды бастау"
                setOnClickListener { startGame(false) }
            })
            root.addView(Button(this).apply {
                text = "LAN: ойынды ашу (Host, 1-ойыншы)"
                setOnClickListener { startGame(true) }
            })
        }

        root.addView(Button(this).apply {
            text = "LAN: қосылу (Guest, 2-ойыншы)"
            setOnClickListener { askHostIp() }
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

            val sink: (Int, Int) -> Unit = { action, keyCode -> view.sendKeyEvent(action, keyCode) }
            keySink = sink

            val container = FrameLayout(this)
            container.addView(
                view,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )

            // Сенсорлық басқару (орны, өлшемі, мөлдірлігі өзгертіледі және сақталады)
            val layer = ControlsLayer(this, prefs, sink)
            layer.addTo(container)
            controlsLayer = layer

            setContentView(container)
            enterImmersiveMode()

            if (asHost) {
                val server = HostServer(this, view) { toast(it) }
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

    private fun askHostIp() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "мысалы 192.168.1.5"
            setText(prefs.getString(KEY_GUEST_IP, ""))
        }
        AlertDialog.Builder(this)
            .setTitle("Host телефонның IP-і")
            .setView(input)
            .setPositiveButton("Қосылу") { _, _ ->
                val ip = input.text.toString().trim()
                if (ip.isEmpty()) {
                    toast("IP енгізіңіз")
                } else {
                    prefs.edit().putString(KEY_GUEST_IP, ip).apply()
                    startGuest(ip)
                }
            }
            .setNegativeButton("Болдырмау", null)
            .show()
    }

    private fun startGuest(ip: String) {
        val image = ImageView(this).apply {
            setBackgroundColor(Color.BLACK)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        val client = GuestClient(
            host = ip,
            activity = this,
            onFrame = { bmp -> image.setImageBitmap(bmp) },
            onStatus = { toast(it) },
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
