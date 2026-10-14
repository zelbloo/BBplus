package io.zel.bbplus.hook

import io.github.libxposed.api.XposedInterface
import io.zel.bbplus.BbplusRuntime
import io.zel.bbplus.BbplusSettings

class DynamicGameCardHook(private val runtime: BbplusRuntime) {

    fun startHook() {
        val additionalType = runCatching {
            runtime.classLoader.loadClass("fS.L0")
        }.getOrNull() ?: run {
            runtime.log("DynamicGameCardHook skipped: fS.L0 not found")
            return
        }

        val cardTypeField = runCatching {
            additionalType.getDeclaredField("v").apply { isAccessible = true }
        }.getOrNull() ?: run {
            runtime.log("DynamicGameCardHook skipped: fS.L0.v not found")
            return
        }

        val modelType = runCatching {
            runtime.classLoader.loadClass("com.bilibili.bplus.followinglist.model.g")
        }.getOrNull() ?: run {
            runtime.log("DynamicGameCardHook skipped: followinglist.model.g not found")
            return
        }

        val flatten = runCatching {
            modelType.getDeclaredMethod("Z").apply { isAccessible = true }
        }.getOrNull() ?: run {
            runtime.log("DynamicGameCardHook skipped: model.g.Z not found")
            return
        }

        runtime.xposed.hook(flatten)
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
            .intercept { chain ->
                val result = chain.proceed()
                if (!BbplusSettings.isBlockDynamicGameCard(runtime.prefs)) return@intercept result
                runCatching {
                    if (result !is List<*>) return@intercept result
                    if (result.none { additionalType.isInstance(it) }) return@intercept result
                    val filtered = result.filterNot { item ->
                        cardTypeField.get(item) == GAME_CARD_TYPE
                    }
                    if (filtered.size != result.size) {
                        runtime.log(
                            "DynamicGameCardHook: removed " +
                                "${result.size - filtered.size} game card(s)"
                        )
                        return@intercept filtered
                    }
                    result
                }.getOrElse {
                    runtime.log("DynamicGameCardHook filter failed", it)
                    result
                }
            }
        runtime.log("DynamicGameCardHook installed on ${modelType.name}.Z")
    }

    private companion object {
        const val GAME_CARD_TYPE = "game"
    }
}
