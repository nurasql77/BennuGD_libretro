package com.nurasql77.sorr

import android.Manifest
import android.app.AlertDialog
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
import android.view.View
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
        private const val KEY_LANG = "lang"
        private const val KEY_VIDEO = "video_preset"
        private const val KEY_SAVES = "saves_enabled"
        private const val CORE_NAME = "libbennugd_libretro.so"

        // true: if the APK signature does not match the key, the game will not start.
        // false (default): on mismatch only the protected links are hidden.
        private const val STRICT_SIGNATURE = false

        private const val COLOR_BG = 0xFF0E1116.toInt()
        private const val COLOR_BUTTON = 0xFF2A2F3A.toInt()
        private const val COLOR_PRIMARY = 0xFFE53935.toInt()
    }

    private enum class Screen { MAIN, NETWORK, SETTINGS, SAVES, GUEST, GAME }

    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private var screen = Screen.MAIN
    private var retroView: GLRetroView? = null
    private var controlsLayer: ControlsLayer? = null
    private var hostServer: HostServer? = null
    private var guestClient: GuestClient? = null
    private var discovery: DiscoveryListener? = null

    // Session without saving: restored when the game ends
    private var tempSaves: SaveManager? = null

    // During the game key events go here (solo / host: into the core, guest: over the network)
    private var keySink: ((Int, Int) -> Unit)? = null

    private var waitingForStorageAccess = false
    private var pendingHost = false

    // SoRR folder picker
    private val pickFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        val path = treeUriToPath(uri)
        if (path == null) {
            toast(tr(
                "Could not determine the folder path",
                "Не удалось определить путь к папке",
                "Папка жолын анықтау мүмкін болмады"
            ))
        } else {
            prefs.edit().putString(KEY_PATH, path).apply()
        }
        showMainMenu()
    }

    // File permission for Android 10 and below
    private val legacyPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasStorageAccess()) {
            startGame(pendingHost)
        } else {
            toast(tr(
                "File access was not granted",
                "Доступ к файлам не разрешён",
                "Файлдарға рұқсат берілмеді"
            ))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        Lang.code = prefs.getString(KEY_LANG, "en") ?: "en"

        // The core checks the app signature using this path
        try {
            Os.setenv("SORR_APK_PATH", applicationInfo.sourceDir, true)
        } catch (e: Exception) {
            // no signature check
        }

        // If the previous session without saving was interrupted, restore real saves
        try {
            currentFolder()?.let {
                if (hasStorageAccess()) SaveManager(it, prefs).recoverIfNeeded()
            }
        } catch (e: Exception) {
            // ignore
        }

        showMainMenu()
    }

    override fun onResume() {
        super.onResume()
        // Returned from the "All files access" settings: start the game
        if (waitingForStorageAccess && hasStorageAccess()) {
            waitingForStorageAccess = false
            startGame(pendingHost)
        }
    }

    override fun onDestroy() {
        endTempSession()
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

    private fun endTempSession() {
        try {
            tempSaves?.endTemporary()
        } catch (e: Exception) {
            // will be recovered on the next start
        }
        tempSaves = null
    }

    private fun currentFolder(): File? {
        val path = prefs.getString(KEY_PATH, null) ?: return null
        val f = File(path)
        return if (f.isDirectory) f else null
    }

    // ------------------------------------------------------------------
    // Menu
    // ------------------------------------------------------------------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun resetToMenuState(target: Screen) {
        endTempSession()
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
     * Menu screen: title and hints on the left, a scrollable list of buttons on the right
     * (in landscape everything fits and nothing falls off the screen).
     * If the resource "menu_bg" exists (branding/background.jpg), it is used as the background.
     */
    private fun showScreen(
        title: String,
        subtitle: String,
        extraInfo: List<String>,
        buttons: LinearLayout.() -> Unit
    ) {
        val frame = FrameLayout(this).apply { setBackgroundColor(COLOR_BG) }

        val bgId = resources.getIdentifier("menu_bg", "drawable", packageName)
        if (bgId != 0) {
            frame.addView(
                ImageView(this).apply {
                    setImageResource(bgId)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                },
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            frame.addView(
                View(this).apply { setBackgroundColor(0xB3000000.toInt()) },
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(28), dp(16), dp(28), dp(16))
        }

        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (applicationInfo.icon != 0) {
            left.addView(
                ImageView(this).apply { setImageResource(applicationInfo.icon) },
                LinearLayout.LayoutParams(dp(64), dp(64))
            )
        }
        left.addView(TextView(this).apply {
            text = title
            textSize = 28f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(8), 0, 0)
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

        frame.addView(
            root,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(frame)
    }

    private fun backLabel() = tr("Back", "Назад", "Артқа")

    private fun selectFolderFirst() = toast(tr(
        "Select the SoRR folder first",
        "Сначала выберите папку SoRR",
        "Алдымен SoRR папкасын таңдаңыз"
    ))

    private fun showMainMenu() {
        resetToMenuState(Screen.MAIN)
        val savedPath = prefs.getString(KEY_PATH, null)
        val ip = Net.localIp() ?: tr("unknown", "не определён", "анықталмады")

        showScreen(
            title = "Streets of Rage Remake",
            subtitle = tr(
                "Android launcher with the BennuGD core",
                "Запуск на Android через ядро BennuGD",
                "BennuGD ядросы арқылы Android-та іске қосу"
            ),
            extraInfo = listOf(
                if (savedPath != null) {
                    tr("Game folder: $savedPath", "Папка игры: $savedPath", "Ойын папкасы: $savedPath")
                } else {
                    tr("Game folder not selected", "Папка с игрой не выбрана", "Ойын папкасы таңдалмаған")
                },
                tr("This phone's IP: $ip", "IP этого телефона: $ip", "Осы телефонның IP-і: $ip")
            )
        ) {
            addView(menuButton(tr("▶  Play", "▶  Играть", "▶  Ойнау"), primary = true) {
                if (savedPath == null) selectFolderFirst() else startGame(false)
            })
            addView(menuButton(tr("Network game (LAN)", "Сетевая игра (LAN)", "Желі арқылы ойнау (LAN)")) {
                showNetworkMenu()
            })
            addView(menuButton(tr("Saves", "Сохранения", "Сақтаулар")) { showSavesMenu() })
            addView(menuButton(tr("Game folder", "Папка с игрой", "Ойын папкасы")) {
                pickFolder.launch(null)
            })
            addView(menuButton(tr("Settings", "Настройки", "Баптаулар")) { showSettingsMenu() })

            // The link is stored inside the core (.so) in protected form
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
            toast(tr("Could not open the link", "Не удалось открыть ссылку", "Сілтемені ашу мүмкін болмады"))
        }
    }

    private fun showNetworkMenu() {
        resetToMenuState(Screen.NETWORK)
        val savedPath = prefs.getString(KEY_PATH, null)
        val ip = Net.localIp() ?: tr("unknown", "не определён", "анықталмады")

        showScreen(
            title = tr("Network game (LAN)", "Сетевая игра (LAN)", "Желі арқылы ойнау (LAN)"),
            subtitle = tr(
                "Two phones on the same Wi-Fi (or one shares a hotspot). The host runs the game and plays as player 1. The guest sees the game and plays as player 2.",
                "Два телефона в одной Wi-Fi сети (или один раздаёт точку доступа). Хост запускает игру и играет за игрока 1. Гость видит игру и играет за игрока 2.",
                "Екі телефон бір Wi-Fi желісінде (немесе бірі хотспот таратады). Хост ойынды іске қосып, 1-ойыншы болып ойнайды. Қонақ ойынды көріп, 2-ойыншы болып ойнайды."
            ),
            extraInfo = listOf(tr("This phone's IP: $ip", "IP этого телефона: $ip", "Осы телефонның IP-і: $ip"))
        ) {
            addView(menuButton(
                tr("Create game (host, player 1)", "Создать игру (хост, игрок 1)", "Ойын ашу (хост, 1-ойыншы)"),
                primary = true
            ) {
                if (savedPath == null) selectFolderFirst() else startGame(true)
            })
            addView(menuButton(
                tr("Join a game (guest, player 2)", "Подключиться к игре (гость, игрок 2)", "Ойынға қосылу (қонақ, 2-ойыншы)")
            ) { showGuestMenu() })
            addView(menuButton(backLabel()) { showMainMenu() })
        }
    }

    private fun videoPresetName(index: Int): String {
        val p = Net.presets[index]
        val name = when (index) {
            0 -> tr("Low", "Экономно", "Үнемді")
            1 -> tr("Balanced", "Баланс", "Теңгерім")
            else -> tr("High", "Качество", "Сапалы")
        }
        return "$name (${p.width} px, ${p.fps} fps)"
    }

    private fun showSettingsMenu() {
        resetToMenuState(Screen.SETTINGS)
        val p2Port = prefs.getInt(KEY_P2_PORT, 1)
        val testOn = prefs.getBoolean(KEY_P2_TEST, false)
        val video = prefs.getInt(KEY_VIDEO, 1).coerceIn(0, Net.presets.size - 1)

        showScreen(
            title = tr("Settings", "Настройки", "Баптаулар"),
            subtitle = tr(
                "Language, video for the guest, buttons.",
                "Язык, видео для гостя, кнопки.",
                "Тіл, қонаққа арналған видео, түймелер."
            ),
            extraInfo = listOf(
                tr(
                    "Lower video quality makes the game smoother on the host phone.",
                    "Меньшее качество видео делает игру плавнее на телефоне-хосте.",
                    "Видео сапасын төмендетсеңіз, хост телефонда ойын жеңілірек жұмыс істейді."
                ),
                tr(
                    "Player 2 port is needed if the second player does not react to buttons.",
                    "Порт игрока 2 нужен, если второй игрок не реагирует на кнопки.",
                    "2-ойыншы порты екінші ойыншы түймелерге жауап бермесе қажет."
                ),
                tr(
                    "\"My buttons = player 2\" helps to set up player 2 in the game's Controls menu.",
                    "Режим «Мои кнопки = игрок 2» помогает настроить второго игрока в меню Controls игры.",
                    "«Менің түймелерім = 2-ойыншы» режимі ойынның Controls мәзірінде 2-ойыншыны баптауға көмектеседі."
                )
            )
        ) {
            addView(menuButton(
                tr("Language: ${Lang.name(Lang.code)}", "Язык: ${Lang.name(Lang.code)}", "Тіл: ${Lang.name(Lang.code)}")
            ) {
                Lang.code = Lang.next(Lang.code)
                prefs.edit().putString(KEY_LANG, Lang.code).apply()
                showSettingsMenu()
            })
            addView(menuButton(
                tr(
                    "Video for the guest: ${videoPresetName(video)}",
                    "Видео для гостя: ${videoPresetName(video)}",
                    "Қонаққа арналған видео: ${videoPresetName(video)}"
                )
            ) {
                prefs.edit().putInt(KEY_VIDEO, (video + 1) % Net.presets.size).apply()
                showSettingsMenu()
            })
            addView(menuButton(
                tr(
                    "Player 2 port: $p2Port (change)",
                    "Порт игрока 2: $p2Port (сменить)",
                    "2-ойыншы порты: $p2Port (ауыстыру)"
                )
            ) {
                prefs.edit().putInt(KEY_P2_PORT, if (p2Port >= 3) 1 else p2Port + 1).apply()
                showSettingsMenu()
            })
            addView(menuButton(
                tr("My buttons = player 2: ", "Мои кнопки = игрок 2: ", "Менің түймелерім = 2-ойыншы: ") +
                    (if (testOn) tr("ON", "ВКЛ", "ҚОСУЛЫ") else tr("off", "выкл", "өшірулі"))
            ) {
                prefs.edit().putBoolean(KEY_P2_TEST, !testOn).apply()
                showSettingsMenu()
            })
            addView(menuButton(
                tr("Reset button layout", "Сбросить расположение кнопок", "Түймелер орнын бастапқы күйге қайтару")
            ) {
                val editor = prefs.edit()
                prefs.all.keys.filter { it.startsWith("ctl_") }.forEach { editor.remove(it) }
                editor.apply()
                toast(tr("Button layout reset", "Расположение кнопок сброшено", "Түймелер орны қайтарылды"))
            })
            addView(menuButton(backLabel()) { showMainMenu() })
        }
    }

    // ------------------------------------------------------------------
    // Saves: profiles and "no saving" mode
    // ------------------------------------------------------------------

    private fun showSavesMenu() {
        val folder = currentFolder()
        if (folder == null) {
            selectFolderFirst()
            return
        }
        if (!hasStorageAccess()) {
            toast(tr(
                "Allow access to all files, then open Saves again",
                "Разрешите доступ ко всем файлам и откройте «Сохранения» снова",
                "Барлық файлдарға рұқсат беріп, «Сақтаулар» бөлімін қайта ашыңыз"
            ))
            requestStorageAccess()
            return
        }

        resetToMenuState(Screen.SAVES)
        val saves = SaveManager(folder, prefs)
        saves.recoverIfNeeded()
        val enabled = prefs.getBoolean(KEY_SAVES, true)
        val active = saves.activeName()
        val onText = tr("ON", "ВКЛ", "ҚОСУЛЫ")
        val offText = tr("OFF", "ВЫКЛ", "ӨШІРУЛІ")

        showScreen(
            title = tr("Saves", "Сохранения", "Сақтаулар"),
            subtitle = tr(
                "Choose which save profile the game uses, or turn saving off for a session.",
                "Выберите профиль сохранений для игры или отключите сохранение на время сессии.",
                "Ойын қолданатын сақтау профилін таңдаңыз немесе сессия кезінде сақтауды өшіріңіз."
            ),
            extraInfo = listOf(
                tr("Active profile: $active", "Активный профиль: $active", "Белсенді профиль: $active"),
                tr(
                    "Saving: ${if (enabled) onText else offText}",
                    "Сохранение: ${if (enabled) onText else offText}",
                    "Сақтау: ${if (enabled) onText else offText}"
                ),
                tr(
                    "With saving off, the game starts with clean saves and everything done in that session is discarded. Your real saves come back when you exit.",
                    "При выключенном сохранении игра стартует с чистыми сохранениями, а всё сделанное в этой сессии отбрасывается. Ваши настоящие сохранения возвращаются при выходе.",
                    "Сақтау өшірулі болса, ойын таза сақтаулармен басталады, ал сол сессиядағының бәрі жойылады. Нағыз сақтауларыңыз шыққанда қайтарылады."
                )
            )
        ) {
            addView(menuButton(
                tr(
                    "Saving: ${if (enabled) onText else offText} (tap to change)",
                    "Сохранение: ${if (enabled) onText else offText} (нажмите, чтобы сменить)",
                    "Сақтау: ${if (enabled) onText else offText} (ауыстыру үшін басыңыз)"
                ),
                primary = !enabled
            ) {
                prefs.edit().putBoolean(KEY_SAVES, !enabled).apply()
                showSavesMenu()
            })

            for (name in saves.listProfiles()) {
                val isActive = name == active
                addView(menuButton((if (isActive) "✓  " else "") + name, primary = isActive) {
                    if (!isActive) {
                        if (saves.switchTo(name)) {
                            toast(tr("Profile selected: $name", "Профиль выбран: $name", "Профиль таңдалды: $name"))
                        } else {
                            toast(tr(
                                "Could not switch the profile",
                                "Не удалось переключить профиль",
                                "Профильді ауыстыру мүмкін болмады"
                            ))
                        }
                        showSavesMenu()
                    }
                })
            }

            addView(menuButton(tr("New profile", "Новый профиль", "Жаңа профиль")) { askNewProfile(saves) })
            addView(menuButton(backLabel()) { showMainMenu() })
        }
    }

    private fun askNewProfile(saves: SaveManager) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = tr("Profile name", "Название профиля", "Профиль атауы")
        }
        AlertDialog.Builder(this)
            .setTitle(tr("New profile", "Новый профиль", "Жаңа профиль"))
            .setView(input)
            .setPositiveButton(tr("Create", "Создать", "Жасау")) { _, _ ->
                if (saves.createProfile(input.text.toString())) {
                    toast(tr("Profile created", "Профиль создан", "Профиль жасалды"))
                } else {
                    toast(tr(
                        "Invalid or existing name",
                        "Некорректное или существующее имя",
                        "Атау қате немесе бұрыннан бар"
                    ))
                }
                showSavesMenu()
            }
            .setNegativeButton(tr("Cancel", "Отмена", "Болдырмау"), null)
            .show()
    }

    // ------------------------------------------------------------------
    // Guest: find a host on the network (or enter the IP manually)
    // ------------------------------------------------------------------

    private fun showGuestMenu() {
        resetToMenuState(Screen.GUEST)

        var hostsBox: LinearLayout? = null
        var searching: TextView? = null

        showScreen(
            title = tr("Join a game", "Подключение к игре", "Ойынға қосылу"),
            subtitle = tr(
                "The host phone must have \"Create game (host)\" running. Both phones must be on the same network.",
                "На телефоне-хосте должна быть запущена «Создать игру (хост)». Оба телефона должны быть в одной сети.",
                "Хост телефонда «Ойын ашу (хост)» іске қосылған болуы керек. Екі телефон да бір желіде болуы тиіс."
            ),
            extraInfo = emptyList()
        ) {
            val s = infoText(
                tr("Searching for hosts on the network...", "Поиск хостов в сети...", "Желіден хост іздеу..."),
                14f, Color.GRAY
            )
            searching = s
            addView(s)

            val box = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            hostsBox = box
            addView(box)

            val ipInput = EditText(this@MainActivity).apply {
                inputType = InputType.TYPE_CLASS_TEXT
                hint = tr(
                    "or enter the IP manually, e.g. 192.168.1.5",
                    "или введите IP вручную, например 192.168.1.5",
                    "немесе IP-ді қолмен енгізіңіз, мысалы 192.168.1.5"
                )
                setHintTextColor(Color.GRAY)
                setTextColor(Color.WHITE)
                setText(prefs.getString(KEY_GUEST_IP, ""))
            }
            addView(ipInput)

            addView(menuButton(tr("Connect by IP", "Подключиться по IP", "IP бойынша қосылу")) {
                val ip = ipInput.text.toString().trim()
                if (ip.isEmpty()) {
                    toast(tr("Enter the host IP", "Введите IP хоста", "Хост IP-ін енгізіңіз"))
                } else {
                    startGuest(ip)
                }
            })
            addView(menuButton(backLabel()) { showNetworkMenu() })
        }

        val shown = mutableSetOf<String>()
        val listener = DiscoveryListener(this) { ip, name ->
            if (shown.add(ip)) {
                searching?.text = tr("Found phones:", "Найденные телефоны:", "Табылған телефондар:")
                hostsBox?.addView(menuButton("$name  ($ip)", primary = true) { startGuest(ip) })
            }
        }
        discovery = listener
        listener.start()
    }

    // ------------------------------------------------------------------
    // Permissions
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
    // Game (single player or host)
    // ------------------------------------------------------------------

    private fun startGame(asHost: Boolean) {
        if (STRICT_SIGNATURE && Native.isVerified() == false) {
            toast(tr(
                "App signature does not match. Install the original APK.",
                "Подпись приложения не совпадает. Установите оригинальный APK.",
                "Қолданба қолтаңбасы сәйкес емес. Түпнұсқа APK орнатыңыз."
            ))
            return
        }

        val folder = currentFolder()
        if (folder == null) {
            toast(tr("Select the SoRR folder again", "Выберите папку SoRR заново", "SoRR папкасын қайта таңдаңыз"))
            showMainMenu()
            return
        }

        if (!hasStorageAccess()) {
            pendingHost = asHost
            waitingForStorageAccess = true
            toast(tr("Allow access to all files", "Разрешите доступ ко всем файлам", "Барлық файлдарға рұқсат беріңіз"))
            requestStorageAccess()
            return
        }

        val dat = folder.listFiles()?.firstOrNull {
            it.isFile && it.name.equals("SorR.dat", ignoreCase = true)
        }
        if (dat == null) {
            toast(tr(
                "SorR.dat not found: ${folder.absolutePath}",
                "Файл SorR.dat не найден: ${folder.absolutePath}",
                "SorR.dat табылмады: ${folder.absolutePath}"
            ))
            return
        }

        val core = File(applicationInfo.nativeLibraryDir, CORE_NAME)
        if (!core.exists()) {
            toast(tr(
                "Core not found: ${core.absolutePath}",
                "Ядро не найдено: ${core.absolutePath}",
                "Ядро табылмады: ${core.absolutePath}"
            ))
            return
        }

        // Saves: restore after an interrupted session; if saving is off, start a clean temporary session
        val saveManager = SaveManager(folder, prefs)
        saveManager.recoverIfNeeded()
        if (!prefs.getBoolean(KEY_SAVES, true)) {
            if (saveManager.beginTemporary()) {
                tempSaves = saveManager
            } else {
                toast(tr(
                    "Could not start without saving. Try again.",
                    "Не удалось запустить без сохранения. Повторите попытку.",
                    "Сақтаусыз іске қосу мүмкін болмады. Қайталап көріңіз."
                ))
                return
            }
        }

        val saves = File(folder, SaveManager.ACTIVE_DIR)
        if (!saves.exists()) saves.mkdirs()

        try {
            // The core receives the APK path to check the app signature
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

            // Port our buttons go to (P1 = 0). The P1/P2 switch changes it in-game
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

            // Host: a line with the guest's events (to check the connection)
            val hostInfo: TextView? = if (asHost) {
                TextView(this).apply {
                    setTextColor(Color.WHITE)
                    textSize = 12f
                    alpha = 0.7f
                    setBackgroundColor(0x66000000)
                    setPadding(16, 4, 16, 4)
                    text = tr(
                        "Player 2 · port $p2Port: no events yet",
                        "Игрок 2 · порт $p2Port: событий пока нет",
                        "2-ойыншы · порт $p2Port: оқиға әлі жоқ"
                    )
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

            // On-screen buttons: position, size and opacity are adjustable and saved
            val layer = ControlsLayer(this, prefs, sink)
            layer.addTo(container)
            controlsLayer = layer

            // P1/P2 switch: to set up each player's buttons in the game's Controls menu
            layer.addPortSwitch(
                container,
                { if (activePort == 0) "P1" else "P2" },
                { activePort = if (activePort == 0) p2Port else 0 }
            )

            setContentView(container)
            enterImmersiveMode()

            if (asHost) {
                var count = 0
                val preset = Net.presets[prefs.getInt(KEY_VIDEO, 1).coerceIn(0, Net.presets.size - 1)]
                val server = HostServer(this, view, p2Port, preset, { action, keyCode ->
                    count++
                    val name = KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
                    val arrow = if (action == KeyEvent.ACTION_DOWN) "↓" else "↑"
                    hostInfo?.text = tr(
                        "Player 2 · port $p2Port: $count events • $name $arrow",
                        "Игрок 2 · порт $p2Port: событий $count • $name $arrow",
                        "2-ойыншы · порт $p2Port: $count оқиға • $name $arrow"
                    )
                }) { toast(it) }
                server.start()
                hostServer = server

                val ip = Net.localIp() ?: tr("unknown", "неизвестен", "белгісіз")
                toast(tr(
                    "LAN host started. IP to connect: $ip",
                    "LAN-хост запущен. IP для подключения: $ip",
                    "LAN-хост іске қосылды. Қосылу үшін IP: $ip"
                ))
            }
        } catch (e: Throwable) {
            toast(tr("Error: ${e.message}", "Ошибка: ${e.message}", "Қате: ${e.message}"))
            showMainMenu()
        }
    }

    // ------------------------------------------------------------------
    // Guest (player 2)
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
            text = tr("Connecting...", "Подключение...", "Қосылуда...")
        }

        val client = GuestClient(
            host = ip,
            activity = this,
            onFrame = { bmp -> image.setImageBitmap(bmp) },
            onStatus = { toast(it) },
            onStats = { ping, fps, noVideo ->
                val pingText = if (ping >= 0) "$ping ms" else "-"
                stats.text = if (noVideo) {
                    tr("No video...", "Видео не приходит...", "Видео келмей тұр...")
                } else {
                    tr(
                        "Ping $pingText  •  $fps fps",
                        "Пинг $pingText  •  $fps к/с",
                        "Пинг $pingText  •  $fps к/с"
                    )
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
    // Buttons and gamepad
    // ------------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val sink = keySink

        // In menus the Back button returns to the main screen
        if (sink == null) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK && screen != Screen.MAIN) {
                if (event.action == KeyEvent.ACTION_UP) showMainMenu()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        // In game: first close the button-edit mode, then exit
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) {
                if (controlsLayer?.handleBack() != true) finish()
            }
            return true
        }
        // Leave the volume keys to the system
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        ) {
            return super.dispatchKeyEvent(event)
        }

        // A real gamepad is connected: hide the on-screen buttons
        if ((event.source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) {
            controlsLayer?.onPhysicalGamepad()
        }

        sink(event.action, event.keyCode)
        return true
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    // content://...externalstorage.../tree/primary%3ASoRR  ->  /storage/emulated/0/SoRR
    private fun treeUriToPath(uri: Uri): String? {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(uri) // e.g. "primary:SoRR"
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
