package tv.danko.app.ui.player

import android.content.Context
import android.os.PowerManager

/**
 * Mantiene la pantalla encendida mientras Danko TV reproduce en el teléfono.
 * Uso con contador de referencias: acquire() cuenta +1, release() cuenta -1;
 * el WakeLock se libera realmente solo cuando refs llega a 0.
 */
class WakeLockHelper private constructor(context: Context) {
    private val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val wakeLock = pm.newWakeLock(
        PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
        "DankoTV:screen"
    )
    private var refs = 0

    fun acquire() {
        synchronized(this) {
            if (refs == 0) wakeLock.acquire()
            refs++
        }
    }

    fun release() {
        synchronized(this) {
            if (refs > 0) {
                refs--
                if (refs == 0) wakeLock.release()
            }
        }
    }

    fun releaseAll() {
        synchronized(this) {
            while (refs > 0) {
                refs--
                if (refs == 0) wakeLock.release()
            }
        }
    }

    companion object {
        @Volatile private var instance: WakeLockHelper? = null

        fun get(context: Context): WakeLockHelper {
            return instance ?: synchronized(this) {
                instance ?: WakeLockHelper(context.applicationContext).also { instance = it }
            }
        }
    }
}
