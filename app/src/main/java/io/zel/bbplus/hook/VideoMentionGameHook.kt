package io.zel.bbplus.hook

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Space
import io.github.libxposed.api.XposedInterface
import io.zel.bbplus.BbplusRuntime
import io.zel.bbplus.BbplusSettings
import java.lang.reflect.Proxy

class VideoMentionGameHook(private val runtime: BbplusRuntime) {

    private val mentionType by lazy {
        runtime.classLoader.loadClass("com.bapis.bilibili.app.viewunite.common.Mention")
    }

    private val gameType by lazy {
        runtime.classLoader
            .loadClass("com.bapis.bilibili.app.viewunite.common.MentionType")
            .getField("MENTION_TYPE_GAME").get(null)
    }

    private val videoMentionsType by lazy {
        runtime.classLoader.loadClass("com.bapis.bilibili.app.viewunite.common.VideoMentions")
    }

    fun startHook() {
        val componentType = runCatching {
            runtime.classLoader.loadClass("com.bilibili.biligame.videocard.GameVideoMentionedComponent")
        }.getOrNull() ?: run {
            runtime.log("VideoMentionGameHook skipped: GameVideoMentionedComponent not found")
            return
        }

        val headerComponentType = runCatching {
            runtime.classLoader.loadClass("com.bilibili.biligame.videocard.GameVideoMentionedHeaderComponent")
        }.getOrNull()

        val viewEntryType = runCatching {
            runtime.classLoader.loadClass("com.bilibili.app.gemini.ui.UIComponent\$ViewEntry")
        }.getOrNull() ?: run {
            runtime.log("VideoMentionGameHook skipped: ViewEntry not found")
            return
        }

        val hostContinuation = runCatching {
            runtime.classLoader.loadClass("kotlin.coroutines.Continuation")
        }.getOrNull() ?: run {
            runtime.log("VideoMentionGameHook skipped: host Continuation not found")
            return
        }

        if (hookComponent(componentType, viewEntryType, hostContinuation)) {
            runtime.log("VideoMentionGameHook installed on ${componentType.name}.createViewEntry/bindToView")
        }
        if (headerComponentType != null &&
            hookComponent(headerComponentType, viewEntryType, hostContinuation)
        ) {
            runtime.log("VideoMentionGameHook installed on ${headerComponentType.name}.createViewEntry/bindToView")
        }

        installDataFilters()
    }

    private fun hookComponent(
        componentType: Class<*>,
        viewEntryType: Class<*>,
        hostContinuation: Class<*>,
    ): Boolean {
        val createViewEntry = runCatching {
            componentType.getDeclaredMethod("createViewEntry", Context::class.java, ViewGroup::class.java)
                .apply { isAccessible = true }
        }.getOrNull() ?: return false

        val bindToView = runCatching {
            componentType.getDeclaredMethod("bindToView", viewEntryType, hostContinuation)
                .apply { isAccessible = true }
        }.getOrNull() ?: return false

        val unit = runtime.classLoader.loadClass("kotlin.Unit").getField("INSTANCE").get(null)

        runtime.xposed.hook(createViewEntry)
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
            .intercept { chain ->
                if (!BbplusSettings.isBlockMentionGame(runtime.prefs)) return@intercept chain.proceed()
                runCatching {
                    val context = chain.getArgs().getOrNull(0) as? Context
                        ?: return@intercept chain.proceed()
                    emptyViewEntry(context, viewEntryType)
                }.getOrElse {
                    runtime.log("VideoMentionGame ${componentType.name} createViewEntry failed", it)
                    chain.proceed()
                }
            }

        runtime.xposed.hook(bindToView)
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
            .intercept { chain ->
                if (!BbplusSettings.isBlockMentionGame(runtime.prefs)) return@intercept chain.proceed()
                unit
            }

        return true
    }

    private fun installDataFilters() {
        val factoryType = runCatching {
            runtime.classLoader.loadClass("zy0.a")
        }.getOrNull() ?: run {
            runtime.log("VideoMentionGameHook: section factory zy0.a not found")
            return
        }

        val mentionType = runCatching {
            runtime.classLoader.loadClass("com.bapis.bilibili.app.viewunite.common.Mention")
        }.getOrNull() ?: return

        val sectionItemType = runCatching {
            runtime.classLoader.loadClass("com.bilibili.playerbizcommonv2.videomentioned.MentionedSectionItem")
        }.getOrNull() ?: return

        val cardItemType = runCatching {
            runtime.classLoader.loadClass("com.bilibili.playerbizcommonv2.videomentioned.MentionedCardItem")
        }.getOrNull() ?: return

        val convertSection = runCatching {
            factoryType.getDeclaredMethod("e", mentionType).apply { isAccessible = true }
        }.getOrNull()

        val convertCard = runCatching {
            factoryType.getDeclaredMethod("g", mentionType).apply { isAccessible = true }
        }.getOrNull()

        val isGame = runCatching {
            factoryType.getDeclaredMethod("f", mentionType).apply { isAccessible = true }
        }.getOrNull()

        if (convertCard != null && isGame != null) {
            runtime.xposed.hook(convertCard)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    if (!BbplusSettings.isBlockMentionGame(runtime.prefs)) return@intercept chain.proceed()
                    runCatching {
                        val mention = chain.getArgs().getOrNull(0) ?: return@intercept chain.proceed()
                        val game = isGame.invoke(chain.getThisObject(), mention) == java.lang.Boolean.TRUE
                        if (game) {
                            runtime.log("VideoMentionGame blocked card: ${mention.javaClass.name}")
                            null
                        } else {
                            chain.proceed()
                        }
                    }.getOrElse {
                        runtime.log("VideoMentionGame card filter failed", it)
                        chain.proceed()
                    }
                }
            runtime.log("VideoMentionGameHook installed on ${factoryType.name}.g(Mention)")
        } else {
            runtime.log("VideoMentionGameHook: card filter targets not found")
        }

        if (convertSection != null) {
            val cardListField = runCatching {
                sectionItemType.getDeclaredField("c").apply { isAccessible = true }
            }.getOrNull()

            val getTypeMethod = runCatching {
                cardItemType.getMethod("getType").apply { isAccessible = true }
            }.getOrNull()

            val gameConstant = runCatching {
                runtime.classLoader
                    .loadClass("com.bilibili.playerbizcommonv2.videomentioned.MentionedCardType")
                    .getField("GAME").get(null)
            }.getOrNull()

            if (cardListField != null && getTypeMethod != null && gameConstant != null) {
                runtime.xposed.hook(convertSection)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept { chain ->
                        val result = chain.proceed()
                        if (!BbplusSettings.isBlockMentionGame(runtime.prefs)) return@intercept result
                        runCatching {
                            if (!sectionItemType.isInstance(result)) return@runCatching
                            val cards = cardListField.get(result) as? MutableList<*> ?: return@runCatching
                            cards.removeAll { card ->
                                runCatching { getTypeMethod.invoke(card) == gameConstant }.getOrDefault(false)
                            }
                        }.onFailure { runtime.log("VideoMentionGame section filter failed", it) }
                        result
                    }
                runtime.log("VideoMentionGameHook installed on ${factoryType.name}.e(Mention)")
            }
        }

        installTitleClear()
        installAreaCallbackShortCircuit()
    }

    private fun installTitleClear() {
        val iType = runCatching {
            runtime.classLoader.loadClass("com.bilibili.playerbizcommonv2.videomentioned.i")
        }.getOrNull() ?: run {
            runtime.log("VideoMentionGameHook: state factory i not found")
            return
        }

        val videoMentionsType = runCatching {
            runtime.classLoader.loadClass("com.bapis.bilibili.app.viewunite.common.VideoMentions")
        }.getOrNull() ?: return

        val convert = runCatching {
            iType.getDeclaredMethod("a", videoMentionsType).apply { isAccessible = true }
        }.getOrNull() ?: run {
            runtime.log("VideoMentionGameHook: i.a(VideoMentions) not found")
            return
        }

        val gType = runCatching {
            runtime.classLoader.loadClass("com.bilibili.playerbizcommonv2.videomentioned.G")
        }.getOrNull() ?: return

        val titleField = runCatching {
            gType.getDeclaredField("a").apply { isAccessible = true }
        }.getOrNull() ?: return

        runtime.xposed.hook(convert)
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
            .intercept { chain ->
                val result = chain.proceed()
                if (!BbplusSettings.isBlockMentionGame(runtime.prefs)) return@intercept result
                runCatching {
                    if (gType.isInstance(result)) {
                        val videoMentions = chain.getArgs().getOrNull(0)
                        val mentions = videoMentionsType.getMethod("getMentionsList")
                            .invoke(videoMentions) as? List<*> ?: return@runCatching
                        if (mentions.isNotEmpty() && mentions.all {
                                mentionType.getMethod("getMType").invoke(it) == gameType
                            }
                        ) {
                            titleField.set(result, "")
                            runtime.log("VideoMentionGame: detail area title cleared")
                        }
                    }
                }.onFailure { runtime.log("VideoMentionGame title clear failed", it) }
                result
            }
        runtime.log("VideoMentionGameHook installed on ${iType.name}.a(VideoMentions)")
    }

    private fun installAreaCallbackShortCircuit() {
        val hType = runCatching {
            runtime.classLoader.loadClass("com.bilibili.ship.theseus.ugc.intro.h")
        }.getOrNull() ?: run {
            runtime.log("VideoMentionGameHook: area callback h not found")
            return
        }

        val kType = runCatching {
            runtime.classLoader.loadClass("Ny0.k")
        }.getOrNull() ?: return

        val callback = runCatching {
            hType.getDeclaredMethod("a", kType).apply { isAccessible = true }
        }.getOrNull() ?: run {
            runtime.log("VideoMentionGameHook: h.a(Ny0.k) not found")
            return
        }

        val moduleType = runCatching {
            runtime.classLoader.loadClass("com.bapis.bilibili.app.viewunite.common.Module")
        }.getOrNull() ?: return

        val hasVideoMentions = runCatching {
            moduleType.getMethod("hasVideoMentions")
        }.getOrNull() ?: return

        val getVideoMentions = runCatching {
            moduleType.getMethod("getVideoMentions")
        }.getOrNull() ?: return

        val getMentionsList = runCatching {
            videoMentionsType.getMethod("getMentionsList")
        }.getOrNull() ?: return

        val getMType = runCatching {
            mentionType.getMethod("getMType")
        }.getOrNull() ?: return

        runtime.xposed.hook(callback)
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
            .intercept { chain ->
                if (!BbplusSettings.isBlockMentionGame(runtime.prefs)) return@intercept chain.proceed()
                runCatching {
                    val k = chain.getArgs().getOrNull(0) ?: return@intercept chain.proceed()
                    val moduleField = kType.getDeclaredField("a").apply { isAccessible = true }
                    val module = moduleField.get(k) ?: return@intercept chain.proceed()
                    if (hasVideoMentions.invoke(module) != java.lang.Boolean.TRUE) {
                        return@intercept chain.proceed()
                    }
                    val mentions = getMentionsList.invoke(getVideoMentions.invoke(module)) as? List<*>
                        ?: return@intercept chain.proceed()
                    if (mentions.isNotEmpty() && mentions.all { getMType.invoke(it) == gameType }) {
                        runtime.log(
                            "VideoMentionGame: detail area short-circuited " +
                                "(${mentions.size} game mentions)"
                        )
                        return@intercept null
                    }
                }.onFailure { runtime.log("VideoMentionGame area short-circuit failed", it) }
                chain.proceed()
            }
        runtime.log("VideoMentionGameHook installed on ${hType.name}.a(Ny0.k)")
    }

    private fun emptyViewEntry(context: Context, viewEntryType: Class<*>): Any =
        Proxy.newProxyInstance(
            viewEntryType.classLoader,
            arrayOf(viewEntryType),
        ) { _, method, _ ->
            if (method.name == "getRoot" && method.parameterCount == 0) {
                Space(context).apply {
                    visibility = View.GONE
                    layoutParams = ViewGroup.LayoutParams(0, 0)
                }
            } else {
                null
            }
        }
}
