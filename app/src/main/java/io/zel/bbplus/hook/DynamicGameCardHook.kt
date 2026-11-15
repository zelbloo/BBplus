package io.zel.bbplus.hook

import io.github.libxposed.api.XposedInterface
import io.zel.bbplus.BbplusRuntime
import io.zel.bbplus.BbplusSettings

class DynamicGameCardHook(private val runtime: BbplusRuntime) {

    fun startHook() {
        // 9.18.0: 附加卡片类从 fS.L0 迁到 DS.S0（fS 包从 297 类缩到 5 类）；
        // 字段名仍为 v（由 AdditionCommon.getCardType() 赋值）。
        val additionalType = runCatching {
            runtime.classLoader.loadClass(CARD_CLASS_918)
        }.getOrNull() ?: runCatching {
            runtime.classLoader.loadClass(CARD_CLASS_970)
        }.getOrNull() ?: run {
            runtime.log("DynamicGameCardHook skipped: additional card class not found")
            return
        }

        val cardTypeField = runCatching {
            additionalType.getDeclaredField(CARD_TYPE_FIELD).apply { isAccessible = true }
        }.getOrNull() ?: run {
            runtime.log("DynamicGameCardHook skipped: ${additionalType.name}.${CARD_TYPE_FIELD} not found")
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
        const val CARD_CLASS_918 = "DS.S0"
        const val CARD_CLASS_970 = "fS.L0"
        const val CARD_TYPE_FIELD = "v"
        const val GAME_CARD_TYPE = "game"
    }
}