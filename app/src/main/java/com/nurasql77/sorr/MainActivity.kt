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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import com.swordfish.radialgamepad.library.RadialGamePad
import com.swordfish.radialgamepad.library.config.ButtonConfig
import com.swordfish.radialgamepad.library.config.CrossConfig
import com.swordfish.radialgamepad.library.config.PrimaryDialConfig
import com.swordfish.radialgamepad.library.config.RadialGamePadConfig
import com.swordfish.radialgamepad.library.config.SecondaryDialConfig
import com.swordfish.radialgamepad.library.event.Event
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    companion object {
        private const val PREFS = "sorr"
        private const val KEY_PATH = "sorr_path"
        private const val CORE_NAME = "libbennugd_libretro.so"
        private const val ID_DPAD = 0
    }

    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private var retroView: GLRetroView? = null
    private var pads: List<View> = emptyList()
    private val heldDpad = mutableSetOf<Int>()
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
        pads = emptyList()
        heldDpad.clear()
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

            val container = FrameLayout(this)
            container.addView(
                view,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            addTouchPads(container, view)
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
            pads.forEach { it.visibility = View.GONE }
        }

        view.sendKeyEvent(event.action, event.keyCode)
        return true
    }

    // ---------- Сенсорлық басқару (RadialGamePad) ----------

    private fun addTouchPads(container: FrameLayout, game: GLRetroView) {
        val dm = resources.displayMetrics
        val size = minOf(dm.heightPixels * 0.62f, dm.widthPixels * 0.42f).toInt()

        val leftPad = RadialGamePad(buildLeftConfig(), 8f, this)
        val rightPad = RadialGamePad(buildRightConfig(), 8f, this)

        container.addView(
            leftPad,
            FrameLayout.LayoutParams(size, size, Gravity.BOTTOM or Gravity.START)
        )
        container.addView(
            rightPad,
            FrameLayout.LayoutParams(size, size, Gravity.BOTTOM or Gravity.END)
        )
        pads = listOf(leftPad, rightPad)

        for (pad in listOf(leftPad, rightPad)) {
            lifecycleScope.launch {
                pad.events()
                    .flowWithLifecycle(lifecycle, Lifecycle.State.RESUMED)
                    .collect { handlePadEvent(game, it) }
            }
        }
    }

    // Сол жақ: D-pad (диагональмен) + SELECT
    private fun buildLeftConfig() = RadialGamePadConfig(
        sockets = 6,
        primaryDial = PrimaryDialConfig.Cross(
            CrossConfig(
                id = ID_DPAD,
                useDiagonals = true
            )
        ),
        secondaryDials = listOf(
            SecondaryDialConfig.SingleButton(
                index = 1,
                scale = 1f,
                distance = 0f,
                ButtonConfig(
                    id = KeyEvent.KEYCODE_BUTTON_SELECT,
                    label = "SELECT"
                )
            )
        )
    )

    // Оң жақ: A, B, X, Y + START
    private fun buildRightConfig() = RadialGamePadConfig(
        sockets = 6,
        primaryDial = PrimaryDialConfig.PrimaryButtons(
            listOf(
                ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_A, label = "A"),
                ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_B, label = "B"),
                ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_X, label = "X"),
                ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_Y, label = "Y")
            )
        ),
        secondaryDials = listOf(
            SecondaryDialConfig.SingleButton(
                index = 2,
                scale = 1f,
                distance = 0f,
                ButtonConfig(
                    id = KeyEvent.KEYCODE_BUTTON_START,
                    label = "START"
                )
            )
        )
    )

    private fun handlePadEvent(game: GLRetroView, event: Event) {
        when (event) {
            is Event.Button -> game.sendKeyEvent(event.action, event.id)
            is Event.Direction -> if (event.id == ID_DPAD) updateDpad(game, event.xAxis, event.yAxis)
            else -> Unit
        }
    }

    // D-pad бағытын (x, y) төрт бағыттық пернеге айналдыру
    private fun updateDpad(game: GLRetroView, x: Float, y: Float) {
        val wanted = mutableSetOf<Int>()
        if (x < -0.4f) wanted.add(KeyEvent.KEYCODE_DPAD_LEFT)
        if (x > 0.4f) wanted.add(KeyEvent.KEYCODE_DPAD_RIGHT)
        if (y < -0.4f) wanted.add(KeyEvent.KEYCODE_DPAD_UP)
        if (y > 0.4f) wanted.add(KeyEvent.KEYCODE_DPAD_DOWN)

        for (key in heldDpad.toList()) {
            if (key !in wanted) {
                game.sendKeyEvent(KeyEvent.ACTION_UP, key)
                heldDpad.remove(key)
            }
        }
        for (key in wanted) {
            if (key !in heldDpad) {
                game.sendKeyEvent(KeyEvent.ACTION_DOWN, key)
                heldDpad.add(key)
            }
        }
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