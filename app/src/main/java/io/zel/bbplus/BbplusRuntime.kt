package io.zel.bbplus

import android.content.Context
import io.github.libxposed.api.XposedInterface
import io.zel.bbplus.hook.DmActivityMetaHook
import io.zel.bbplus.hook.DynamicGameCardHook
import io.zel.bbplus.hook.HomeFeedFilterHook
import io.zel.bbplus.hook.HomeFeedSizeHook
import io.zel.bbplus.hook.LiveRoomEntranceHook
import io.zel.bbplus.hook.LiveRoomPopupHook
import io.zel.bbplus.hook.LiveRoomSensorRotationHook
import io.zel.bbplus.hook.PlayerWatermarkHook
import io.zel.bbplus.hook.ShareToOverflowHook
import io.zel.bbplus.hook.SpaceH5GameCardHook
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

            // 每个 hook 单元独立隔离：任一 hook 因宿主版本差异失败（类不存在、方法抽象无法挂、
            // 签名不匹配等）都只记录日志，绝不能让宿主启动流程崩溃。
            fun install(name: String, block: () -> Unit) {
                runCatching(block).onFailure {
                    runtime.log("$name failed to install (skipped)", it)
                }
            }

            install("SettingsPanel") { SettingsPanel.install(runtime) }
            install("DmActivityMetaHook") { DmActivityMetaHook(runtime).startHook() }
            install("VideoMentionGameHook") { VideoMentionGameHook(runtime).startHook() }
            install("PlayerWatermarkHook") { PlayerWatermarkHook(runtime).startHook() }
            install("ShareToOverflowHook") { ShareToOverflowHook(runtime).startHook() }
            install("LiveRoomPopupHook") { LiveRoomPopupHook(runtime).startHook() }
            install("LiveRoomSensorRotationHook") { LiveRoomSensorRotationHook(runtime).startHook() }
            install("LiveRoomEntranceHook") { LiveRoomEntranceHook(runtime).startHook() }
            install("HomeFeedSizeHook") { HomeFeedSizeHook(runtime).startHook() }
            install("HomeFeedFilterHook") { HomeFeedFilterHook(runtime).startHook() }
            install("DynamicGameCardHook") { DynamicGameCardHook(runtime).startHook() }
            install("SpaceH5GameCardHook") { SpaceH5GameCardHook(runtime).startHook() }
            runtime.log("BBplus runtime hooks installed")
        }
    }
}