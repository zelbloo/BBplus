package io.zel.bbplus

import android.app.Application
import android.content.Context
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.util.concurrent.atomic.AtomicBoolean

class BbplusModule : XposedModule() {

    private var packageName: String = ""
    private var processName: String = ""
    private val runtimeStarted = AtomicBoolean(false)

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        processName = param.getProcessName()
        log(
            Log.INFO,
            TAG,
            "BBplus loaded on $frameworkName($frameworkVersionCode), api=$apiVersion, process=$processName",
        )
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (param.getPackageName() != TARGET_PACKAGE) return
        if (processName.isNotBlank() && processName != param.getPackageName()) return

        packageName = param.getPackageName()
        log(Log.INFO, TAG, "BBplus entering ${param.getPackageName()}")

        val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java)
        attach.isAccessible = true
        hook(attach)
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
            .intercept { chain ->
                chain.proceed()
                val application = chain.getThisObject() as? Application ?: return@intercept null
                startRuntimeOnce(
                    application,
                    application.javaClass.classLoader ?: param.getDefaultClassLoader(),
                )
            }
    }

    private fun startRuntimeOnce(application: Application, classLoader: ClassLoader) {
        if (runtimeStarted.compareAndSet(false, true)) {
            BbplusRuntime.start(
                xposed = this,
                packageName = packageName,
                application = application,
                classLoader = classLoader,
            ) { message, throwable ->
                if (throwable == null) {
                    log(Log.INFO, TAG, message)
                } else {
                    log(Log.WARN, TAG, message, throwable)
                }
            }
        }
    }

    companion object {
        private const val TAG = "BBplus"
        const val TARGET_PACKAGE = "tv.danmaku.bili"
    }
}