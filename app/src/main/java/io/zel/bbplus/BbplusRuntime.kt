package io.zel.bbplus

import android.content.Context
import io.github.libxposed.api.XposedInterface
import io.zel.bbplus.hook.DmActivityMetaHook
import io.zel.bbplus.hook.LiveRoomEntranceHook
import io.zel.bbplus.hook.LiveRoomPopupHook
import io.zel.bbplus.hook.PlayerWatermarkHook
import io.zel.bbplus.hook.ShareToOverflowHook
import io.zel.bbplus.hook.VideoMentionGameHook
import io.zel.bbplus.setting.SettingsPanel

class BbplusRuntime internal constructor(
    val xposed: XposedInterface,
    val packageName: String,
    val hostContext: Context,
    val classLoader: ClassLoader,
    private val logger: (String, Throwable?) -> Unit,
) {
    val prefs: android.content.SharedPreferences
        get() = hostContext.getSharedPreferences(BbplusSettings.PREFS_NAME, Context.MODE_PRIVATE)

    fun log(message: String, throwable: Throwable? = null) {
        logger(message, throwable)
    }

    companion object {
        fun start(
            xposed: XposedInterface,
            packageName: String,
            application: Context,
            classLoader: ClassLoader,
            log: (String, Throwable?) -> Unit,
        ) {
            val runtime = BbplusRuntime(
                xposed = xposed,
                packageName = packageName,
                hostContext = application.applicationContext ?: application,
                classLoader = classLoader,
                logger = log,
            )
            runtime.log("BBplus runtime starting for $packageName")
            SettingsPanel.install(runtime)
            DmActivityMetaHook(runtime).startHook()
            VideoMentionGameHook(runtime).startHook()
            PlayerWatermarkHook(runtime).startHook()
            ShareToOverflowHook(runtime).startHook()
            LiveRoomPopupHook(runtime).startHook()
            LiveRoomEntranceHook(runtime).startHook()
            runtime.log("BBplus runtime hooks installed")
        }
    }
}