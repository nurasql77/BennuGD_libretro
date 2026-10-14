package com.nurasql77.sorr

/**
 * Мост к защищённым функциям внутри ядра (libbennugd_libretro.so):
 *  - link(id): расшифрованная строка (ссылка, название канала); пустая, если подпись APK не совпала;
 *  - verified(): true, если APK подписан нашим ключом.
 * Если ядро собрано без защиты, функций нет: тогда safeLink/isVerified возвращают null.
 */
object Native {
    private var loadTried = false
    private var loadOk = false

    @JvmStatic
    external fun link(id: Int): String

    @JvmStatic
    external fun verified(): Boolean

    private fun load(): Boolean {
        if (!loadTried) {
            loadTried = true
            loadOk = try {
                System.loadLibrary("bennugd_libretro")
                true
            } catch (t: Throwable) {
                false
            }
        }
        return loadOk
    }

    /** Защищённая строка или null (нет защиты в ядре / подпись не совпала). */
    fun safeLink(id: Int): String? {
        if (!load()) return null
        return try {
            link(id).takeIf { it.isNotEmpty() }
        } catch (t: Throwable) {
            null
        }
    }

    /** true / false: результат проверки подписи; null: в ядре нет защиты. */
    fun isVerified(): Boolean? {
        if (!load()) return null
        return try {
            verified()
        } catch (t: Throwable) {
            null
        }
    }
}
