package com.nurasql77.sorr

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import android.widget.ScrollView
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

        // true: если подпись APK не совпала с ключом, внутри ядра игра не запускается.
        // false (по умолчанию): при несовпадении просто скрываются защищённые ссылки.
        private const val STRICT_SIGNATURE = false

        private const val COLOR_BG = 0xFF0E1116.toInt()
        private const val COLOR_BUTTON = 0xFF2A2F3A.toInt()
        private const val COLOR_PRIMARY = 0xFFE53935.toInt()
    }

    private enum class Screen { MAIN, NETWORK, SETTINGS, GUEST, GAME }

    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private var screen = Screen.MAIN
    private var retroView: GLRetroView? = null
    private var controlsLayer: ControlsLayer? = null
    private var hostServer: HostServer? = null
    private var guestClient: GuestClient? = null
    private var discovery: DiscoveryListener? = null

    // Во время игры нажатия идут сюда (один игрок / хост: в ядро, гость: по сети хосту)
    private var keySink: ((Int, Int) -> Unit)? = null

    private var waitingForStorageAccess = false
    private var pendingHost = false

    // Выбор папки SoRR
    private val pickFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        val path = treeUriToPath(uri)
        if (path == null) {
            toast("Не удалось определить путь к папке")
        } else {
            prefs.edit().putString(KEY_PATH, path).apply()
        }
        showMainMenu()
    }

    // Разрешение на файлы для Android 10 и ниже
    private val legacyPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasStorageAccess()) {
            startGame(pendingHost)
        } else {
            toast("Доступ к файлам не разрешён")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Ядро проверяет подпись по этому пути
        try {
            Os.setenv("SORR_APK_PATH", applicationInfo.sourceDir, true)
        } catch (e: Exception) {
            // без проверки подписи
        }
        showMainMenu()
    }

    override fun onResume() {
        super.onResume()
        // Вернулись из настроек «Доступ ко всем файлам»: запускаем игру
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

    // ------------------------------------------------------------------
    // Меню
    // ------------------------------------------------------------------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun resetToMenuState(target: Screen) {
        stopNetwork()
        retroView = null
        controlsLayer = null
        keySink = null
        screen = target
    }

    private fun menuButton(label: String, primary: Boolean = false, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 16f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(if (primary) COLOR_PRIMARY else COLOR_BUTTON)
            }
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            setOnClickListener { onClick() }
        }
    }

    private fun infoText(text: String, sizeSp: Float = 13f, color: Int = Color.LTGRAY): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(color)
            setPadding(0, dp(6), 0, 0)
        }
    }

    /**
     * Экран меню: слева заголовок и пояснения, справа прокручиваемый список кнопок
     * (в альбомной ориентации всё помещается и ничего не пропадает за краем экрана).
     */
    private fun showScreen(
        title: String,
        subtitle: String,
        extraInfo: List<String>,
        buttons: LinearLayout.() -> Unit
    ) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(COLOR_BG)
            setPadding(dp(28), dp(16), dp(28), dp(16))
        }

        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        left.addView(TextView(this).apply {
            text = title
            textSize = 28f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        })
        left.addView(infoText(subtitle, 15f, 0xFFB0B8C4.toInt()))
        for (line in extraInfo) left.addView(infoText(line))
        root.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            buttons()
        }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(column)
        }
        root.addView(
            scroll,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                leftMargin = dp(28)
            }
        )

        setContentView(root)
    }

    private fun showMainMenu() {
        resetToMenuState(Screen.MAIN)
        val savedPath = prefs.getString(KEY_PATH, null)
        val ip = Net.localIp() ?: "не определён"

        showScreen(
            title = "Streets of Rage Remake",
            subtitle = "Запуск на Android через ядро BennuGD",
            extraInfo = listOf(
                if (savedPath != null) "Папка игры: $savedPath" else "Папка с игрой не выбрана",
                "IP этого телефона: $ip"
            )
        ) {
            addView(menuButton("▶  Играть", primary = true) {
                if (savedPath == null) {
                    toast("Сначала выберите папку SoRR")
                } else {
                    startGame(false)
                }
            })
            addView(menuButton("Сетевая игра (LAN)") { showNetworkMenu() })
            addView(menuButton("Папка с игрой") { pickFolder.launch(null) })
            addView(menuButton("Настройки") { showSettingsMenu() })

            // Ссылка хранится внутри ядра (.so) в защищённом виде
            val tgLink = Native.safeLink(0)
            if (tgLink != null) {
                val tgName = Native.safeLink(1) ?: "Telegram"
                addView(menuButton("✈  Telegram: $tgName") { openLink(tgLink) })
            }
        }
    }

    private fun openLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            toast("Не удалось открыть ссылку")
        }
    }

    private fun showNetworkMenu() {
        resetToMenuState(Screen.NETWORK)
        val savedPath = prefs.getString(KEY_PATH, null)
        val ip = Net.localIp() ?: "не определён"

        showScreen(
            title = "Сетевая игра (LAN)",
            subtitle = "Два телефона в одной Wi-Fi сети (или один раздаёт точку доступа). " +
                "Хост запускает игру и играет за игрока 1. Гость видит игру и играет за игрока 2.",
            extraInfo = listOf("IP этого телефона: $ip")
        ) {
            addView(menuButton("Создать игру (хост, игрок 1)", primary = true) {
                if (savedPath == null) {
                    toast("Сначала выберите папку SoRR")
                } else {
                    startGame(true)
                }
            })
            addView(menuButton("Подключиться к игре (гость, игрок 2)") { showGuestMenu() })
            addView(menuButton("Назад") { showMainMenu() })
        }
    }

    private fun showSettingsMenu() {
        resetToMenuState(Screen.SETTINGS)
        val p2Port = prefs.getInt(KEY_P2_PORT, 1)
        val testOn = prefs.getBoolean(KEY_P2_TEST, false)

        showScreen(
            title = "Настройки",
            subtitle = "Параметры сетевой игры и расположения кнопок.",
            extraInfo = listOf(
                "Порт игрока 2 нужен, если второй игрок не реагирует на кнопки.",
                "Режим «Мои кнопки = игрок 2» помогает настроить управление второго игрока в меню Controls игры."
            )
        ) {
            addView(menuButton("Порт игрока 2: $p2Port (сменить)") {
                prefs.edit().putInt(KEY_P2_PORT, if (p2Port >= 3) 1 else p2Port + 1).apply()
                showSettingsMenu()
            })
            addView(menuButton("Мои кнопки = игрок 2: " + (if (testOn) "ВКЛ" else "выкл")) {
                prefs.edit().putBoolean(KEY_P2_TEST, !testOn).apply()
                showSettingsMenu()
            })
            addView(menuButton("Сбросить расположение кнопок") {
                val editor = prefs.edit()
                prefs.all.keys.filter { it.startsWith("ctl_") }.forEach { editor.remove(it) }
                editor.apply()
                toast("Расположение кнопок сброшено")
            })
            addView(menuButton("Назад") { showMainMenu() })
        }
    }

    // Гость: поиск хоста в сети (можно ввести IP вручную)
    private fun showGuestMenu() {
        resetToMenuState(Screen.GUEST)

        var hostsBox: LinearLayout? = null
        var searching: TextView? = null

        showScreen(
            title = "Подключение к игре",
            subtitle = "На телефоне-хосте должна быть запущена «Создать игру (хост)». " +
                "Оба телефона должны быть в одной сети.",
            extraInfo = emptyList()
        ) {
            val s = infoText("Поиск хостов в сети...", 14f, Color.GRAY)
            searching = s
            addView(s)

            val box = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            hostsBox = box
            addView(box)

            val ipInput = EditText(this@MainActivity).apply {
                inputType = InputType.TYPE_CLASS_TEXT
                hint = "или введите IP вручную, например 192.168.1.5"
                setHintTextColor(Color.GRAY)
                setTextColor(Color.WHITE)
                setText(prefs.getString(KEY_GUEST_IP, ""))
            }
            addView(ipInput)

            addView(menuButton("Подключиться по IP") {
                val ip = ipInput.text.toString().trim()
                if (ip.isEmpty()) toast("Введите IP хоста") else startGuest(ip)
            })
            addView(menuButton("Назад") { showNetworkMenu() })
        }

        val shown = mutableSetOf<String>()
        val listener = DiscoveryListener(this) { ip, name ->
            if (shown.add(ip)) {
                searching?.text = "Найденные телефоны:"
                hostsBox?.addView(menuButton("$name  ($ip)", primary = true) { startGuest(ip) })
            }
        }
        discovery = listener
        listener.start()
    }

    // ------------------------------------------------------------------
    // Разрешения
    // ------------------------------------------------------------------

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

    // ------------------------------------------------------------------
    // Игра (один игрок или хост)
    // ------------------------------------------------------------------

    private fun startGame(asHost: Boolean) {
        if (STRICT_SIGNATURE && Native.isVerified() == false) {
            toast("Подпись приложения не совпадает. Установите оригинальный APK.")
            return
        }

        val folderPath = prefs.getString(KEY_PATH, null)
        val folder = folderPath?.let { File(it) }
        if (folder == null || !folder.isDirectory) {
            toast("Выберите папку SoRR заново")
            showMainMenu()
            return
        }

        if (!hasStorageAccess()) {
            pendingHost = asHost
            waitingForStorageAccess = true
            toast("Разрешите доступ ко всем файлам")
            requestStorageAccess()
            return
        }

        val dat = folder.listFiles()?.firstOrNull {
            it.isFile && it.name.equals("SorR.dat", ignoreCase = true)
        }
        if (dat == null) {
            toast("Файл SorR.dat не найден: ${folder.absolutePath}")
            return
        }

        val core = File(applicationInfo.nativeLibraryDir, CORE_NAME)
        if (!core.exists()) {
            toast("Ядро не найдено: ${core.absolutePath}")
            return
        }

        val saves = File(folder, "savegame")
        if (!saves.exists()) saves.mkdirs()

        try {
            // Ядро получает путь к APK, чтобы проверить подпись приложения
            Os.setenv("SORR_APK_PATH", applicationInfo.sourceDir, true)

            val data = GLRetroViewData(this).apply {
                coreFilePath = core.absolutePath
                gameFilePath = dat.absolutePath
                systemDirectory = folder.absolutePath
                savesDirectory = saves.absolutePath
            }

            stopNetwork()
            val view = GLRetroView(this, data)
            lifecycle.addObserver(view)
            retroView = view
            screen = Screen.GAME

            // Порт, на который уходят наши кнопки (P1 = 0). Переключатель P1/P2 меняет его прямо в игре
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

            // Хост: строка с событиями от гостя (для проверки связи)
            val hostInfo: TextView? = if (asHost) {
                TextView(this).apply {
                    setTextColor(Color.WHITE)
                    textSize = 12f
                    alpha = 0.7f
                    setBackgroundColor(0x66000000)
                    setPadding(16, 4, 16, 4)
                    text = "Игрок 2 · порт $p2Port: событий пока нет"
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

            // Экранные кнопки: положение, размер и прозрачность настраиваются и сохраняются
            val layer = ControlsLayer(this, prefs, sink)
            layer.addTo(container)
            controlsLayer = layer

            // Переключатель P1/P2: чтобы настроить кнопки каждого игрока в меню Controls
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
                    hostInfo?.text = "Игрок 2 · порт $p2Port: событий $count • $name $arrow"
                }) { toast(it) }
                server.start()
                hostServer = server
                toast("LAN-хост запущен. IP для подключения: ${Net.localIp() ?: "неизвестен"}")
            }
        } catch (e: Throwable) {
            toast("Ошибка: ${e.message}")
            showMainMenu()
        }
    }

    // ------------------------------------------------------------------
    // Гость (игрок 2)
    // ------------------------------------------------------------------

    private fun startGuest(ip: String) {
        discovery?.stop()
        discovery = null
        prefs.edit().putString(KEY_GUEST_IP, ip).apply()
        screen = Screen.GAME

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
            text = "Подключение..."
        }

        val client = GuestClient(
            host = ip,
            activity = this,
            onFrame = { bmp -> image.setImageBitmap(bmp) },
            onStatus = { toast(it) },
            onStats = { ping, fps, noVideo ->
                stats.text = if (noVideo) {
                    "Видео не приходит..."
                } else {
                    "Пинг " + (if (ping >= 0) "$ping мс" else "-") + "  •  $fps к/с"
                }
            },
            onClosed = { showMainMenu() }
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

    // ------------------------------------------------------------------
    // Кнопки и геймпад
    // ------------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val sink = keySink

        // В меню кнопка «Назад» возвращает на главный экран
        if (sink == null) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK && screen != Screen.MAIN) {
                if (event.action == KeyEvent.ACTION_UP) showMainMenu()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        // В игре: сначала закрываем режим настройки кнопок, потом выходим
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) {
                if (controlsLayer?.handleBack() != true) finish()
            }
            return true
        }
        // Кнопки громкости оставляем системе
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        ) {
            return super.dispatchKeyEvent(event)
        }

        // Подключён настоящий геймпад: прячем экранные кнопки
        if ((event.source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) {
            controlsLayer?.onPhysicalGamepad()
        }

        sink(event.action, event.keyCode)
        return true
    }

    // ------------------------------------------------------------------
    // Вспомогательное
    // ------------------------------------------------------------------

    // content://...externalstorage.../tree/primary%3ASoRR  ->  /storage/emulated/0/SoRR
    private fun treeUriToPath(uri: Uri): String? {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(uri) // например "primary:SoRR"
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
