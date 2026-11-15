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

    /**
     * 9.18.0 关键：`com.bilibili.playerbizcommonv2.videomentioned.a` 是**纯接口**，7 个方法全为 abstract，
     * ILHook 无法在其上挂 hook（会抛 "is abstract: it has no body to hook" 直接崩溃）。
     * 必须 hook 具体实现类。9.18 有 4 个实现：
     *  - DA0.b               → GAME 判定（b()/c() 均为 MENTION_TYPE_GAME）
     *  - EA0.a               → STREAMER 判定
     *  - .../videomentioned/toy/e      → toy 场景
     *  - .../videomentioned/seed/u     → seed 场景
     * 逐类尝试，能挂几个挂几个；任一失败不影响启动。
     */
    private fun installDataFilters() {
        val mentionType = runCatching {
            runtime.classLoader.loadClass("com.bapis.bilibili.app.viewunite.common.Mention")
        }.getOrNull() ?: return

        val sectionItemType = runCatching {
            runtime.classLoader.loadClass("com.bilibili.playerbizcommonv2.videomentioned.MentionedSectionItem")
        }.getOrNull() ?: return

        val cardItemType = runCatching {
            runtime.classLoader.loadClass("com.bilibili.playerbizcommonv2.videomentioned.MentionedCardItem")
        }.getOrNull() ?: return

        val gameConstant = runCatching {
            runtime.classLoader
                .loadClass("com.bilibili.playerbizcommonv2.videomentioned.MentionedCardType")
                .getField("GAME").get(null)
        }.getOrNull()

        val getTypeMethod = runCatching {
            cardItemType.getMethod("getType").apply { isAccessible = true }
        }.getOrNull()

        val cardListGetter = runCatching {
            sectionItemType.getMethod("getCards").apply { isAccessible = true }
        }.getOrNull()

        val sectionField = if (cardListGetter == null) {
            runCatching { sectionItemType.getDeclaredField("c").apply { isAccessible = true } }.getOrNull()
        } else null

        // ---- 9.18 路径：逐个具体实现类挂 ----
        var installed = 0
        for (implName in IMPL_CLASSES_918) {
            val implType = runCatching { runtime.classLoader.loadClass(implName) }.getOrNull() ?: continue
            installed += hookImpl(implType, mentionType, sectionItemType, getTypeMethod, gameConstant, cardListGetter, sectionField) ?: 0
        }

        // ---- 9.7 回落：zy0.a 是具体类，直接挂 ----
        if (installed == 0) {
            val oldFactory = runCatching {
                runtime.classLoader.loadClass(FACTORY_CLASS_970)
            }.getOrNull()
            if (oldFactory != null) {
                installed += hookImpl(
                    oldFactory, mentionType, sectionItemType,
                    getTypeMethod, gameConstant, cardListGetter, sectionField,
                ) ?: 0
            }
        }

        if (installed > 0) {
            runtime.log("VideoMentionGameHook: installed on $installed concrete impl(s)")
        } else {
            runtime.log("VideoMentionGameHook: no hookable card factory found")
        }

        installTitleClear()
        installAreaCallbackShortCircuit()
    }

    /**
     * 在给定实现类上挂 b(Mention)->Z（isGame）、d(Mention,Map)->Card、g(Mention)->Section。
     * 每个方法都要求**非 abstract** 才挂，避免 9.18 接口/抽象类计时崩。
     * @return 成功挂上的方法数
     */
    private fun hookImpl(
        implType: Class<*>,
        mentionType: Class<*>,
        sectionItemType: Class<*>,
        getTypeMethod: java.lang.reflect.Method?,
        gameConstant: Any?,
        cardListGetter: java.lang.reflect.Method?,
        sectionField: java.lang.reflect.Field?,
    ): Int {
        var count = 0

        fun findConcrete(name: String, vararg types: Class<*>): java.lang.reflect.Method? =
            runCatching {
                implType.getDeclaredMethod(name, *types).apply { isAccessible = true }
            }.getOrNull()?.takeIf { m ->
                // 抽象方法没有实现体，ILHook 会直接抛异常
                (m.modifiers and java.lang.reflect.Modifier.ABSTRACT) == 0
            }

        val isGame = findConcrete(IS_GAME_METHOD_918, mentionType)
            ?: findConcrete(IS_GAME_METHOD_970, mentionType)

        val convertCard = findConcrete(CARD_METHOD_918, mentionType, java.util.Map::class.java)
            ?: findConcrete(CARD_METHOD_970, mentionType)

        val convertSection = findConcrete(SECTION_METHOD_918, mentionType)
            ?: findConcrete(SECTION_METHOD_970, mentionType)

        if (convertCard != null && isGame != null) {
            runCatching {
                runtime.xposed.hook(convertCard)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept { chain ->
                        if (!BbplusSettings.isBlockMentionGame(runtime.prefs)) {
                            return@intercept chain.proceed()
                        }
                        runCatching {
                            val mention = chain.getArgs().getOrNull(0)
                                ?: return@intercept chain.proceed()
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
                count++
                runtime.log("VideoMentionGameHook installed on ${implType.name}.${convertCard.name}(Mention)")
            }.onFailure { runtime.log("VideoMentionGame card hook failed on ${implType.name}", it) }
        }

        if (convertSection != null && getTypeMethod != null && gameConstant != null &&
            (cardListGetter != null || sectionField != null)
        ) {
            runCatching {
                runtime.xposed.hook(convertSection)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept { chain ->
                        val result = chain.proceed()
                        if (!BbplusSettings.isBlockMentionGame(runtime.prefs)) {
                            return@intercept result
                        }
                        runCatching {
                            if (!sectionItemType.isInstance(result)) return@runCatching
                            val cards = (if (cardListGetter != null) {
                                cardListGetter.invoke(result)
                            } else {
                                sectionField!!.get(result)
                            }) as? MutableList<*> ?: return@runCatching
                            cards.removeAll { card ->
                                runCatching { getTypeMethod.invoke(card) == gameConstant }
                                    .getOrDefault(false)
                            }
                        }.onFailure { runtime.log("VideoMentionGame section filter failed", it) }
                        result
                    }
                count++
                runtime.log("VideoMentionGameHook installed on ${implType.name}.${convertSection.name}(Mention)")
            }.onFailure { runtime.log("VideoMentionGame section hook failed on ${implType.name}", it) }
        }

        return count
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

        // 9.18: 标题持有类从 G 迁到 H（G 在 9.18 变成 Compose 滚动辅助类）
        val gType = runCatching {
            runtime.classLoader.loadClass(TITLE_HOLDER_918)
        }.getOrNull() ?: runCatching {
            runtime.classLoader.loadClass(TITLE_HOLDER_970)
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
            runtime.classLoader.loadClass(CALLBACK_ARG_918)
        }.getOrNull() ?: runCatching {
            runtime.classLoader.loadClass(CALLBACK_ARG_970)
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
        runtime.log("VideoMentionGameHook installed on ${hType.name}.a(${kType.name})")
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

    private companion object {
        // 9.18.0：videomentioned.a 是纯接口，必须 hook 具体实现类
        val IMPL_CLASSES_918 = listOf(
            "DA0.b",
            "EA0.a",
            "com.bilibili.ship.theseus.united.page.intro.module.videomentioned.seed.u",
            "com.bilibili.ship.theseus.ugc.intro.videomentioned.toy.e",
        )
        const val SECTION_METHOD_918 = "g"
        const val CARD_METHOD_918 = "d"
        const val IS_GAME_METHOD_918 = "b"
        const val TITLE_HOLDER_918 = "com.bilibili.playerbizcommonv2.videomentioned.H"
        const val CALLBACK_ARG_918 = "QA0.k"

        // 9.7.0 回落（保持向后兼容）
        const val FACTORY_CLASS_970 = "zy0.a"
        const val SECTION_METHOD_970 = "e"
        const val CARD_METHOD_970 = "g"
        const val IS_GAME_METHOD_970 = "f"
        const val TITLE_HOLDER_970 = "com.bilibili.playerbizcommonv2.videomentioned.G"
        const val CALLBACK_ARG_970 = "Ny0.k"
    }
}