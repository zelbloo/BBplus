package io.zel.bbplus.hook

import android.app.Activity
import io.github.libxposed.api.XposedInterface
import io.zel.bbplus.BbplusRuntime
import io.zel.bbplus.BbplusSettings
import java.util.concurrent.atomic.AtomicBoolean

class LiveRoomSensorRotationHook(private val runtime: BbplusRuntime) {

    private val hookedLogged = AtomicBoolean(false)

    fun startHook() {
        val setOrientationMethod = runCatching {
            Activity::class.java.getMethod(
                "setRequestedOrientation",
                Int::class.javaPrimitiveType,
            )
        }.getOrNull() ?: run {
            runtime.log("LiveRoomSensorRotationHook skipped: setRequestedOrientation not found")
            return
        }

        val liveRoomActivityType = loadClass(CLASS_LIVE_ROOM_ACTIVITY) ?: run {
            runtime.log("LiveRoomSensorRotationHook skipped: LiveLynxActivity not found")
            return
        }

        runtime.xposed.hook(setOrientationMethod)
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
            .intercept { chain ->
                if (!BbplusSettings.isLiveSensorRotation(runtime.prefs)) {
                    return@intercept chain.proceed()
                }

                val thiz = chain.thisObject as? Activity
                if (thiz == null || !liveRoomActivityType.isInstance(thiz)) {
                    return@intercept chain.proceed()
                }

                val requestedOrientation = chain.args.getOrNull(0) as? Int
                if (requestedOrientation != ORIENTATION_LANDSCAPE) {
                    return@intercept chain.proceed()
                }

                if (hookedLogged.compareAndSet(false, true)) {
                    runtime.log("[SensorRotation] LANDSCAPE(0) -> SENSOR_LANDSCAPE(6)")
                }

                chain.proceed(arrayOf(ORIENTATION_SENSOR_LANDSCAPE))
            }

        runtime.log(
            "LiveRoomSensorRotationHook installed on Activity.setRequestedOrientation" +
                " (guarded to $CLASS_LIVE_ROOM_ACTIVITY)",
        )
    }

    private fun loadClass(name: String): Class<*>? =
        runCatching { runtime.classLoader.loadClass(name) }.getOrNull()

    private companion object {
        const val CLASS_LIVE_ROOM_ACTIVITY =
            "com.bilibili.bililive.room.ui.roomv3.lynx.LiveLynxActivity"
        const val ORIENTATION_LANDSCAPE = 0
        const val ORIENTATION_SENSOR_LANDSCAPE = 6
    }
}
