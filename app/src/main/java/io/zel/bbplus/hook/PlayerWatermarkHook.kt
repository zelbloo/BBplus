package io.zel.bbplus.hook

import android.view.View
import android.view.ViewGroup
import android.widget.Space
import io.github.libxposed.api.XposedInterface
import io.zel.bbplus.BbplusRuntime
import io.zel.bbplus.BbplusSettings
import java.util.concurrent.atomic.AtomicInteger

class PlayerWatermarkHook(private val runtime: BbplusRuntime) {

    fun startHook() {
        val layerType = runCatching {
            runtime.classLoader.loadClass("com.bilibili.ship.theseus.cheese.player.watermark.c")
        }.getOrNull() ?: run {
            runtime.log("PlayerWatermarkHook skipped: cheese watermark layer not found")
            return
        }

        hookContentCollector()
        hookWatermarkConfig()
        hookLayerView(layerType)
        hookDiagnostics()
    }

    private fun isBlocking(): Boolean = BbplusSettings.isBlockPlayerWatermark(runtime.prefs)

    private fun hookContentCollector() {
        val collectorType = runCatching {
            runtime.classLoader
                .loadClass("com.bilibili.ship.theseus.cheese.player.watermark.CheeseNativeWatermarkService\$4\$a")
        }.getOrNull()
        val hostContinuation = runCatching {
            runtime.classLoader.loadClass("kotlin.coroutines.Continuation")
        }.getOrNull()
        val unit = runCatching {
            runtime.classLoader.loadClass("kotlin.Unit").getField("INSTANCE").get(null)
        }.getOrNull()
        if (collectorType == null || hostContinuation == null || unit == null) {
            runtime.log("PlayerWatermarkHook skipped: content collector not found")
            return
        }

        val emit = runCatching {
            collectorType.getDeclaredMethod("emit", Object::class.java, hostContinuation)
                .apply { isAccessible = true }
        }.getOrNull() ?: run {
            runtime.log("PlayerWatermarkHook skipped: content collector emit not found")
            return
        }

        runtime.xposed.hook(emit)
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
            .intercept { chain ->
                if (!isBlocking()) return@intercept chain.proceed()
                diag("WM-BLOCK CheeseNativeWatermarkService\$4\$a.emit")
                unit
            }
        runtime.log("PlayerWatermarkHook installed on ${collectorType.name}.emit")
    }

    private fun hookWatermarkConfig() {
        val configType = runCatching {
            runtime.classLoader.loadClass("com.bapis.bilibili.app.playerunite.pugvanymodel.WatermarkConfig")
        }.getOrNull() ?: run {
            runtime.log("PlayerWatermarkHook skipped: WatermarkConfig not found")
            return
        }

        val getWatermark = runCatching {
            configType.getMethod("getWatermark")
        }.getOrNull() ?: run {
            runtime.log("PlayerWatermarkHook skipped: WatermarkConfig.getWatermark not found")
            return
        }

        runtime.xposed.hook(getWatermark)
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
            .intercept { chain ->
                if (!isBlocking()) return@intercept chain.proceed()
                diag("WM-BLOCK WatermarkConfig.getWatermark")
                ""
            }
        runtime.log("PlayerWatermarkHook installed on ${configType.name}.getWatermark")
    }

    private fun hookLayerView(layerType: Class<*>) {
        val getView = runCatching {
            layerType.getDeclaredMethod("getView").apply { isAccessible = true }
        }.getOrNull() ?: run {
            runtime.log("PlayerWatermarkHook skipped: layer getView not found")
            return
        }

        runtime.xposed.hook(getView)
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
            .intercept { chain ->
                if (!isBlocking()) return@intercept chain.proceed()
                diag("WM-BLOCK cheese layer getView")
                runCatching {
                    val original = chain.proceed() as? View
                    val context = original?.context ?: runtime.hostContext
                    Space(context).apply {
                        visibility = View.GONE
                        layoutParams = ViewGroup.LayoutParams(0, 0)
                    }
                }.getOrElse {
                    runtime.log("PlayerWatermark layer view fallback failed", it)
                    chain.proceed()
                }
            }
        runtime.log("PlayerWatermarkHook installed on ${layerType.name}.getView")
    }

    private fun hookDiagnostics() {
        hookStatic("com.bilibili.ship.theseus.cheese.player.watermark.CheeseWatermarkLayerKt", "a",
            "h", "androidx.compose.runtime.Composer", "int", "DIAG cheese composable")
        hookStatic("com.bilibili.ship.theseus.cheese.player.watermark.CheeseNativeWatermarkService\$2\$a", "emit",
            "java.lang.Object", "kotlin.coroutines.Continuation", "DIAG cheese service \$2\$a.emit")
        hookStatic("com.bilibili.tgwt.watermark.e", "c",
            "com.bilibili.tgwt.watermark.f", "androidx.compose.runtime.Composer", "int", "DIAG tgwt WatermarkUi")
        hookStatic("com.bilibili.tgwt.watermark.e", "a",
            "com.bilibili.tgwt.watermark.WatermarkUiMode", "com.bilibili.ogvcommon.play.resolver.Watermark",
            "androidx.compose.runtime.Composer", "int", "DIAG tgwt IconizedWatermarkUi")
        hookStatic("com.bilibili.tgwt.watermark.e", "b",
            "com.bilibili.tgwt.watermark.WatermarkUiMode", "com.bilibili.ogvcommon.play.resolver.Watermark",
            "androidx.compose.runtime.Composer", "int", "DIAG tgwt PlainTextWatermarkUi")
        hookConstructor("com.bilibili.tgwt.watermark.b", "DIAG tgwt PlayerWatermarkService ctor")
        hookConstructor("com.bilibili.tgwt.watermark.PgcPlayerWatermarkFunctionWidget", "DIAG PgcPlayerWatermarkFunctionWidget ctor")
    }

    private fun hookStatic(className: String, methodName: String, vararg paramTypes: String) {
        val clazz = runCatching { runtime.classLoader.loadClass(className) }.getOrNull()
        val hostContinuation = runCatching {
            runtime.classLoader.loadClass("kotlin.coroutines.Continuation")
        }.getOrNull()
        if (clazz == null) return

        val params = runCatching {
            paramTypes.map { name ->
                when (name) {
                    "int" -> Integer.TYPE
                    "java.lang.Object" -> Object::class.java
                    else -> runtime.classLoader.loadClass(name) ?: return
                }
            }.toTypedArray()
        }.getOrNull() ?: return

        val method = runCatching {
            clazz.getDeclaredMethod(methodName, *params).apply { isAccessible = true }
        }.getOrNull() ?: return

        runtime.xposed.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
            .intercept { chain ->
                diag("FIRE ${clazz.name}.$methodName")
                chain.proceed()
            }
        runtime.log("PlayerWatermarkHook diag installed on ${clazz.name}.$methodName")
    }

    private fun hookConstructor(className: String, tag: String) {
        val clazz = runCatching { runtime.classLoader.loadClass(className) }.getOrNull() ?: return
        val ctor = runCatching { clazz.getDeclaredConstructors().firstOrNull() }.getOrNull() ?: return
        ctor.isAccessible = true
        runtime.xposed.hook(ctor)
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
            .intercept { chain ->
                diag("FIRE $tag")
                chain.proceed()
            }
        runtime.log("PlayerWatermarkHook diag installed on $tag")
    }

    private val diagCounters = HashMap<String, AtomicInteger>()

    private fun diag(tag: String) {
        val counter = diagCounters.getOrPut(tag) { AtomicInteger() }
        val count = counter.incrementAndGet()
        if (count <= 30) {
            runtime.log("$tag (#$count)")
        } else if (count == 31) {
            runtime.log("$tag (more...)")
        }
    }
}