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
    private var sensorTargetOrientation: Int = ORIENTATION_UNKNOWN

    fun startHook() {
        hookAbilityImplB()
        hookActivitySetOrientation()
        runtime.log("LiveRoomSensorRotationHook installed")
    }

    /**
     * Hook p2869kH.c.b(int) — the centralized orientation setter used by
     * ~48 callers via N(0). When sensor rotation is active and the sensor
     * detects REVERSE_LANDSCAPE, we override LANDSCAPE(0) -> REVERSE_LANDSCAPE(8).
     */
    private fun hookAbilityImplB() {
        val abilityImplType = loadClass(CLASS_ABILITY_IMPL) ?: run {
            runtime.log("LiveRoomSensorRotationHook skipped: LiveRoomActivityAbilityImpl not found")
            return
        }

        val bMethod = abilityImplType.declaredMethods.firstOrNull { m ->
            m.name == "b" &&
                m.parameterCount == 1 &&
                m.parameterTypes[0] == Int::class.javaPrimitiveType
        }?.apply { isAccessible = true } ?: run {
            runtime.log("LiveRoomSensorRotationHook skipped: b(int) not found")
            return
        }

        runtime.xposed.hook(bMethod)
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
            .intercept { chain ->
                if (!BbplusSettings.isLiveSensorRotation(runtime.prefs)) {
                    return@intercept chain.proceed()
                }

                val orientation = chain.args.getOrNull(0) as? Int ?: return@intercept chain.proceed()

                when (orientation) {
                    ORIENTATION_LANDSCAPE -> {
                        if (sensorTargetOrientation != ORIENTATION_UNKNOWN) {
                            if (logged.compareAndSet(false, true)) {
                                runtime.log("[SensorRotation] b() override LANDSCAPE -> $sensorTargetOrientation")
                            }
                            return@intercept chain.proceed(arrayOf(sensorTargetOrientation))
                        }
                        ensureSensorStarted()
                        chain.proceed()
                    }
                    ORIENTATION_PORTRAIT, ORIENTATION_REVERSE_PORTRAIT -> {
                        stopSensorListener()
                        chain.proceed()
                    }
                    else -> chain.proceed()
                }
            }
        runtime.log("LiveRoomSensorRotationHook installed on ${abilityImplType.name}.b(int)")
    }

    /**
     * Hook Activity.setRequestedOrientation(int) — catches the direct call from
     * LiveZoomWidgetV3 (p782Oy/i case 1) which bypasses the ability wrapper.
     */
    private fun hookActivitySetOrientation() {
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

                val orientation = chain.args.getOrNull(0) as? Int ?: return@intercept chain.proceed()

                when (orientation) {
                    ORIENTATION_LANDSCAPE -> {
                        if (sensorTargetOrientation != ORIENTATION_UNKNOWN) {
                            return@intercept chain.proceed(arrayOf(sensorTargetOrientation))
                        }
                        ensureSensorStarted()
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

    private fun ensureSensorStarted() {
        if (sensorListener != null) return
        val act = targetActivity?.get() ?: return
        startSensorListener(act)
    }

    private fun startSensorListener(activity: Activity) {
        stopSensorListener()
        targetActivity = WeakReference(activity)

        val listener = object : OrientationEventListener(activity, SensorManager.SENSOR_DELAY_NORMAL) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN_VALUE) return
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

                if (target == sensorTargetOrientation) return
                sensorTargetOrientation = target

                val current = runCatching { act.requestedOrientation }.getOrNull() ?: return
                if (current == target) return

                act.setRequestedOrientation(target)
                runtime.log("[SensorRotation] sensor -> ${if (target == 8) "REVERSE_LANDSCAPE(8)" else "LANDSCAPE(0)"}")
            }
        }

        listener.enable()
        sensorListener = listener
    }

    private fun stopSensorListener() {
        sensorListener?.disable()
        sensorListener = null
        targetActivity = null
        sensorTargetOrientation = ORIENTATION_UNKNOWN
    }

    private fun loadClass(name: String): Class<*>? =
        runCatching { runtime.classLoader.loadClass(name) }.getOrNull()

    private companion object {
        const val CLASS_ABILITY_IMPL = "p2869kH.c"
        const val CLASS_LIVE_ROOM_ACTIVITY =
            "com.bilibili.bililive.room.ui.roomv3.lynx.LiveLynxActivity"
        const val ORIENTATION_LANDSCAPE = 0
        const val ORIENTATION_REVERSE_LANDSCAPE = 8
        const val ORIENTATION_PORTRAIT = 1
        const val ORIENTATION_REVERSE_PORTRAIT = 9
        const val ORIENTATION_UNKNOWN = -1
        const val ORIENTATION_UNKNOWN_VALUE = -1
    }
}
