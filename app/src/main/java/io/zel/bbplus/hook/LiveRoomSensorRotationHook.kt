package io.zel.bbplus.hook

import android.app.Activity
import android.hardware.SensorManager
import android.view.OrientationEventListener
import io.github.libxposed.api.XposedInterface
import io.zel.bbplus.BbplusRuntime
import io.zel.bbplus.BbplusSettings
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

class LiveRoomSensorRotationHook(private val runtime: BbplusRuntime) {

    private val logged = AtomicBoolean(false)
    private var sensorListener: OrientationEventListener? = null
    private var targetActivity: WeakReference<Activity>? = null
    @Volatile
    private var isReEntrant = false

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
                if (isReEntrant) return@intercept chain.proceed()

                val thiz = chain.thisObject as? Activity
                if (thiz == null || !liveRoomActivityType.isInstance(thiz)) {
                    return@intercept chain.proceed()
                }

                val requestedOrientation = chain.args.getOrNull(0) as? Int ?: return@intercept chain.proceed()

                if (!BbplusSettings.isLiveSensorRotation(runtime.prefs)) {
                    stopSensorListener()
                    return@intercept chain.proceed()
                }

                when (requestedOrientation) {
                    ORIENTATION_LANDSCAPE -> {
                        startSensorListener(thiz)
                        if (logged.compareAndSet(false, true)) {
                            runtime.log("[SensorRotation] sensor rotation enabled for live room")
                        }
                        chain.proceed()
                    }
                    ORIENTATION_PORTRAIT, ORIENTATION_REVERSE_PORTRAIT -> {
                        stopSensorListener()
                        chain.proceed()
                    }
                    else -> chain.proceed()
                }
            }

        runtime.log(
            "LiveRoomSensorRotationHook installed on Activity.setRequestedOrientation" +
                " (guarded to $CLASS_LIVE_ROOM_ACTIVITY)",
        )
    }

    private fun startSensorListener(activity: Activity) {
        stopSensorListener()
        targetActivity = WeakReference(activity)

        val listener = object : OrientationEventListener(activity, SensorManager.SENSOR_DELAY_NORMAL) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val act = targetActivity?.get() ?: run {
                    stopSensorListener()
                    return
                }
                if (act.isFinishing || act.isDestroyed) {
                    stopSensorListener()
                    return
                }

                val target = when (orientation) {
                    in 80..101 -> ORIENTATION_REVERSE_LANDSCAPE
                    in 260..281 -> ORIENTATION_LANDSCAPE
                    else -> return
                }

                val current = runCatching { act.requestedOrientation }.getOrNull() ?: return
                if (current == target) return

                isReEntrant = true
                try {
                    act.setRequestedOrientation(target)
                } finally {
                    isReEntrant = false
                }
            }
        }

        listener.enable()
        sensorListener = listener
    }

    private fun stopSensorListener() {
        sensorListener?.disable()
        sensorListener = null
        targetActivity = null
    }

    private fun loadClass(name: String): Class<*>? =
        runCatching { runtime.classLoader.loadClass(name) }.getOrNull()

    private companion object {
        const val CLASS_LIVE_ROOM_ACTIVITY =
            "com.bilibili.bililive.room.ui.roomv3.lynx.LiveLynxActivity"
        const val ORIENTATION_LANDSCAPE = 0
        const val ORIENTATION_REVERSE_LANDSCAPE = 8
        const val ORIENTATION_PORTRAIT = 1
        const val ORIENTATION_REVERSE_PORTRAIT = 9
        const val ORIENTATION_UNKNOWN = -1
    }
}
