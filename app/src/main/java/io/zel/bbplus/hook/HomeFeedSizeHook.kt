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

        // 9.18.0: max_card_count 不再有 R8 加盐的 lazy 字段，
        // 改为 ConfigManager 读取 "pegasus.max_card_count" 字符串后 parseInt（兜底 500）。
        // 两条路径都尝试：命中任一条即生效。
        if (hookConfigManagerMaxCard(targetCount)) return

        // 9.7.0 回落：com.bilibili.pegasus.vm.B 的 lazy 字段 f264855a
        val vmClass = loadClass("com.bilibili.pegasus.vm.B") ?: return
        val lazyField = runCatching {
            vmClass.getDeclaredField("f264855a").apply { isAccessible = true }
        }.getOrNull() ?: run {
            runtime.log("HomeFeedSizeHook: max_card_count target not found (9.18 ConfigManager / 9.7 vm.B)")
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
        runtime.log("HomeFeedSizeHook: max_card_count boosted to $targetCount (vm.B lazy)")
    }

    /**
     * 9.18.0 路径：max_card_count 改为从 ConfigManager 读取
     * com.bilibili.lib.blconfig.Contract.get$default(config(), "pegasus.max_card_count", ...)
     * 返回 String 后由调用方 parseInt。这里直接接管该合成方法的返回值。
     */
    private fun hookConfigManagerMaxCard(targetCount: Int): Boolean {
        val contractClass = loadClass("com.bilibili.lib.blconfig.Contract") ?: run {
            runtime.log("HomeFeedSizeHook: blconfig.Contract not found (9.18 path skipped)")
            return false
        }

        val method = contractClass.declaredMethods.firstOrNull { m ->
            m.name == "get\$default" && m.parameterTypes.size >= 2
        }?.apply { isAccessible = true } ?: run {
            runtime.log("HomeFeedSizeHook: Contract.get\$default not found (9.18 path skipped)")
            return false
        }

        runtime.xposed.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
            .intercept { chain ->
                val original = chain.proceed()
                if (original !is String) return@intercept original
                val key = chain.getArgs().getOrNull(1) as? String
                if (key != "pegasus.max_card_count") return@intercept original
                val current = original.toIntOrNull() ?: 500
                if (current < targetCount) targetCount.toString() else original
            }
        runtime.log("HomeFeedSizeHook: max_card_count boosted to $targetCount (blconfig.Contract)")
        return true
    }

    private fun loadClass(name: String): Class<*>? =
        runCatching { runtime.classLoader.loadClass(name) }.getOrNull()

    private fun findStaticMethod(cls: Class<*>, name: String, vararg paramTypes: Class<*>): Method? =
        runCatching { cls.getDeclaredMethod(name, *paramTypes).apply { isAccessible = true } }.getOrNull()
}