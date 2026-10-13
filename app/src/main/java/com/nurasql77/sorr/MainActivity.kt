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
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
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
        private const val CORE_NAME = "libbennugd_libretro.so"
    }

    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private var retroView: GLRetroView? = null
    private var controls: TouchControlsView? = null
    private var waitingForStorageAccess = false

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
            startGame()
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
            startGame()
        }
    }

    // ---------- Мәзір ----------

    private fun showMenu() {
        retroView = null
        controls = null
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

        val info = TextView(this).apply {
            text = if (savedPath != null) "Папка: $savedPath" else "SoRR папкасы таңдалмаған"
            textSize = 14f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 32)
        }
        root.addView(info)

        if (savedPath != null) {
            val play = Button(this).apply {
                text = "Ойынды бастау"
                setOnClickListener { startGame() }
            }
            root.addView(play)
        }

        val choose = Button(this).apply {
            text = "SoRR папкасын таңдау"
            setOnClickListener { pickFolder.launch(null) }
        }
        root.addView(choose)

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

    // ---------- Ойынды бастау ----------

    private fun startGame() {
        val folderPath = prefs.getString(KEY_PATH, null)
        val folder = folderPath?.let { File(it) }
        if (folder == null || !folder.isDirectory) {
            toast("SoRR папкасын қайта таңдаңыз")
            showMenu()
            return
        }

        if (!hasStorageAccess()) {
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
            val data = GLRetroViewData(this).apply {
                coreFilePath = core.absolutePath
                gameFilePath = dat.absolutePath
                systemDirectory = folder.absolutePath
                savesDirectory = saves.absolutePath
            }

            val view = GLRetroView(this, data)
            lifecycle.addObserver(view)
            retroView = view

            // Сенсорлық басқару: экрандағы түймелер ойын экранының үстінде тұрады
            val touch = TouchControlsView(this) { keyCode, down ->
                view.sendKeyEvent(
                    if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,
                    keyCode
                )
            }
            controls = touch

            val container = FrameLayout(this)
            container.addView(
                view,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            container.addView(
                touch,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            setContentView(container)
            enterImmersiveMode()
        } catch (e: Throwable) {
            toast("Қате: ${e.message}")
            showMenu()
        }
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
        val view = retroView ?: return super.dispatchKeyEvent(event)

        // Артқа түймесі: ойыннан шығу
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) finish()
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
            controls?.visibility = View.GONE
        }

        view.sendKeyEvent(event.action, event.keyCode)
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
