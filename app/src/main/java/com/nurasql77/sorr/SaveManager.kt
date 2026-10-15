package com.nurasql77.sorr

import android.content.SharedPreferences
import java.io.File

/**
 * Профили сохранений и режим «без сохранения».
 *
 * Игра пишет сохранения в папку <SoRR>/savegame. Профили лежат рядом в <SoRR>/sorr_profiles/<имя>.
 * Смена профиля: текущий savegame переезжает в sorr_profiles/<активный>, выбранный профиль становится savegame.
 * Режим «без сохранения»: настоящий savegame откладывается в sorr_profiles/_hold, игра получает чистую папку
 * (с меткой .sorr_temp); после выхода (или при следующем запуске, если приложение закрыли аварийно)
 * всё возвращается на место. Ничего из ваших сохранений не удаляется.
 */
class SaveManager(private val gameDir: File, private val prefs: SharedPreferences) {

    companion object {
        const val ACTIVE_DIR = "savegame"
        const val PROFILES_DIR = "sorr_profiles"
        const val TEMP_MARKER = ".sorr_temp"
        const val KEY_ACTIVE = "save_active"
        const val DEFAULT_NAME = "main"

        private const val HOLD = "_hold"
        private const val TRASH = "_temp"

        /** Допустимое имя профиля: буквы, цифры, пробел, дефис, подчёркивание; до 24 символов. */
        fun cleanName(raw: String): String? {
            val name = raw.trim().filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' }
                .trim().take(24)
            if (name.isEmpty() || name.startsWith("_")) return null
            return name
        }
    }

    private val activeDir get() = File(gameDir, ACTIVE_DIR)
    private val profilesDir get() = File(gameDir, PROFILES_DIR)

    fun activeName(): String = prefs.getString(KEY_ACTIVE, DEFAULT_NAME) ?: DEFAULT_NAME

    fun listProfiles(): List<String> {
        val names = sortedSetOf(activeName())
        profilesDir.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith("_") }
            ?.forEach { names.add(it.name) }
        return names.toList()
    }

    /** Переключить активный профиль. false: не удалось (ничего не потеряно). */
    fun switchTo(target: String): Boolean {
        val current = activeName()
        if (target == current) return true

        profilesDir.mkdirs()
        val targetDir = File(profilesDir, target)
        if (!targetDir.isDirectory) return false

        val currentStored = File(profilesDir, current)
        if (currentStored.exists()) return false   // защита: не затираем существующую копию

        val active = activeDir
        if (active.exists()) {
            if (!active.renameTo(currentStored)) return false
        } else {
            currentStored.mkdirs()
        }
        if (!targetDir.renameTo(active)) {
            // откат
            currentStored.renameTo(active)
            return false
        }
        prefs.edit().putString(KEY_ACTIVE, target).apply()
        return true
    }

    /** Создать новый пустой профиль и сразу сделать его активным. */
    fun createProfile(rawName: String): Boolean {
        val name = cleanName(rawName) ?: return false
        if (listProfiles().any { it.equals(name, ignoreCase = true) }) return false
        profilesDir.mkdirs()
        if (!File(profilesDir, name).mkdirs()) return false
        return switchTo(name)
    }

    /** Начать сессию без сохранения. */
    fun beginTemporary(): Boolean {
        endTemporary()   // вернуть всё на место, если прошлая сессия оборвалась
        profilesDir.mkdirs()
        val hold = File(profilesDir, HOLD)
        if (hold.exists()) return false   // неясное состояние: ничего не трогаем

        val active = activeDir
        if (active.exists()) {
            if (!active.renameTo(hold)) return false
        } else {
            hold.mkdirs()
        }
        if (!active.mkdirs()) {
            hold.renameTo(active)
            return false
        }
        try {
            File(active, TEMP_MARKER).createNewFile()
        } catch (e: Exception) {
            // метка не создалась: откатываем
            active.deleteRecursively()
            hold.renameTo(active)
            return false
        }
        return true
    }

    /** Закончить сессию без сохранения: вернуть настоящие сохранения. */
    fun endTemporary() {
        val active = activeDir
        val hold = File(profilesDir, HOLD)
        if (!File(active, TEMP_MARKER).exists() || !hold.exists()) return

        val trash = File(profilesDir, TRASH)
        if (trash.exists()) trash.deleteRecursively()   // только наша временная папка
        if (!active.renameTo(trash)) return
        if (!hold.renameTo(active)) {
            trash.renameTo(active)
        }
    }

    fun recoverIfNeeded() = endTemporary()
}
