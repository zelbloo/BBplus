package io.zel.bbplus.hook

import io.github.libxposed.api.XposedInterface
import io.zel.bbplus.BbplusRuntime
import io.zel.bbplus.BbplusSettings
import java.lang.reflect.Method

class HomeFeedSizeHook(private val runtime: BbplusRuntime) {

    fun startHook() {
        if (!BbplusSettings.isFeedSizeEnhance(runtime.prefs)) {
            runtime.log("HomeFeedSizeHook skipped: feature disabled")
            return
        }
        hookWindowSizeType()
        hookRawWindowSizeType()
        hookMaxCardCount()
        runtime.log("HomeFeedSizeHook installed")
    }

    private fun hookWindowSizeType() {
        val cls = loadClass("com.bilibili.pegasus.PegasusDDConfigKt") ?: return
        val method = findStaticMethod(cls, "getWindowSizeType", Any::class.java) ?: return

        runtime.xposed.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
            .intercept { 3 } // PegasusWindowProfile.Large = 3
        runtime.log("HomeFeedSizeHook: windowSizeType forced to 3")
    }

    private fun hookRawWindowSizeType() {
        val cls = loadClass("kntr.common.screen.adjust.KScreenAdjustUtilsKt") ?: return
        val method = findStaticMethod(cls, "getRawWindowSizeType", Any::class.java) ?: return

        runtime.xposed.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
            .intercept { 3 }
        runtime.log("HomeFeedSizeHook: rawWindowSizeType forced to 3")
    }

    private fun hookMaxCardCount() {
        val targetCount = BbplusSettings.getFeedMaxCardCount(runtime.prefs)

        val vmClass = loadClass("com.bilibili.pegasus.vm.B") ?: return
        val lazyField = runCatching {
            vmClass.getDeclaredField("f264855a").apply { isAccessible = true }
        }.getOrNull() ?: run {
            runtime.log("HomeFeedSizeHook: max_card_count lazy field not found")
            return
        }

        val lazy = runCatching { lazyField.get(null) }.getOrNull() ?: return

        val getValueMethod = runCatching {
            lazy.javaClass.getMethod("getValue")
        }.getOrNull() ?: return

        runtime.xposed.hook(getValueMethod)
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
            .intercept { chain ->
                val original = chain.proceed()
                val current = (original as? Number)?.toInt() ?: 500
                if (current < targetCount) targetCount else original
            }
        runtime.log("HomeFeedSizeHook: max_card_count boosted to $targetCount")
    }

    private fun loadClass(name: String): Class<*>? =
        runCatching { runtime.classLoader.loadClass(name) }.getOrNull()

    private fun findStaticMethod(cls: Class<*>, name: String, vararg paramTypes: Class<*>): Method? =
        runCatching { cls.getDeclaredMethod(name, *paramTypes).apply { isAccessible = true } }.getOrNull()
}
