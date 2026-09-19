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

    @Volatile
    private var sensorOrientation: Int = ORIENTATION_UNKNOWN

    private var sensorListener: OrientationEventListener? = null
    private var currentActivity: WeakReference<Activity>? = null
    private val logged = AtomicBoolean(false)

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

        runtime.xposed.hook(setOrientationMethod)
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
            .intercept { chain ->
                if (!BbplusSettings.isLiveSensorRotation(runtime.prefs)) {
                    return@intercept chain.proceed()
                }

                val thiz = chain.thisObject as? Activity ?: return@intercept chain.proceed()
                val className = thiz.javaClass.name
                if (!className.contains(CLASS_KEYWORD)) {
                    return@intercept chain.proceed()
                }

                val requested = chain.args.getOrNull(0) as? Int ?: return@intercept chain.proceed()

                when (requested) {
                    ORIENTATION_LANDSCAPE -> {
                        ensureSensorStarted(thiz)
                        val target = sensorOrientation
                        if (target != ORIENTATION_UNKNOWN && target != ORIENTATION_LANDSCAPE) {
                            if (logged.compareAndSet(false, true)) {
                                runtime.log("[SensorRotation] override LANDSCAPE(0) -> REVERSE_LANDSCAPE(8)")
                            }
                            return@intercept chain.proceed(arrayOf(target))
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

        runtime.log("LiveRoomSensorRotationHook installed on Activity.setRequestedOrientation")
    }

    private fun ensureSensorStarted(activity: Activity) {
        if (sensorListener != null) {
            currentActivity = WeakReference(activity)
            return
        }
        startSensorListener(activity)
    }

    private fun startSensorListener(activity: Activity) {
        currentActivity = WeakReference(activity)

        val listener = object : OrientationEventListener(activity, SensorManager.SENSOR_DELAY_NORMAL) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val target = when (orientation) {
                    in 80..101 -> ORIENTATION_REVERSE_LANDSCAPE
                    in 260..281 -> ORIENTATION_LANDSCAPE
                    else -> return
                }
                sensorOrientation = target
            }
        }

        if (listener.canDetectOrientation()) {
            listener.enable()
            sensorListener = listener
            runtime.log("[SensorRotation] sensor listener started")
        } else {
            runtime.log("[SensorRotation] device cannot detect orientation")
        }
    }

    private fun stopSensorListener() {
        sensorListener?.disable()
        sensorListener = null
        currentActivity = null
        sensorOrientation = ORIENTATION_UNKNOWN
    }

    private companion object {
        const val CLASS_KEYWORD = "bililive"
        const val ORIENTATION_LANDSCAPE = 0
        const val ORIENTATION_REVERSE_LANDSCAPE = 8
        const val ORIENTATION_PORTRAIT = 1
        const val ORIENTATION_REVERSE_PORTRAIT = 9
        const val ORIENTATION_UNKNOWN = -1
    }
}
