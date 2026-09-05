package io.zel.bbplus.hook

import android.content.Context
import android.view.View
import io.github.libxposed.api.XposedInterface
import io.zel.bbplus.BbplusRuntime
import io.zel.bbplus.BbplusSettings
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

class LiveRoomEntranceHook(private val runtime: BbplusRuntime) {

    private val seenLynxUrls: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val fastGiftGoneLogged = AtomicBoolean(false)
    private val fastGiftSlotLogged = AtomicBoolean(false)
    private val pkPendantLogged = AtomicBoolean(false)
    private val diagNullLogged = AtomicBoolean(false)
    private val shoppingCartLogged = AtomicBoolean(false)
    private val playWithMeLogged = AtomicBoolean(false)

    fun startHook() {
        hookFastGiftEntrance()
        hideTopOperationPendants()
        installLynxDiagnostics()
        hookOuterPanelFilter()
    }

    private fun hookFastGiftEntrance() {
        val entranceType = loadClass(CLASS_FAST_GIFT_ENTRANCE) ?: run {
            runtime.log("LiveRoomEntranceHook skipped: SpeedySendGiftLayout not found")
            return
        }

        val setVisibility = runCatching {
            entranceType.getMethod("setVisibility", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
        }.getOrNull()

        if (setVisibility != null) {
            runtime.xposed.hook(setVisibility)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val thiz = chain.thisObject
                    if (thiz == null || !entranceType.isInstance(thiz)) {
                        return@intercept chain.proceed()
                    }
                    val purifySet = BbplusSettings.getPurifyLivePopups(runtime.prefs)
                    if (BbplusSettings.PURIFY_POPULARITY_TICKET !in purifySet) {
                        return@intercept chain.proceed()
                    }
                    if (fastGiftGoneLogged.compareAndSet(false, true)) {
                        runtime.log("[PopularityTicket] setVisibility forced to GONE")
                    }
                    val result = chain.proceed(arrayOf(View.GONE))
                    runCatching { hideParentSlot(thiz as View) }
                    result
                }
            runtime.log(
                "LiveRoomEntranceHook installed on ${setVisibility.declaringClass.name}.setVisibility" +
                    " (guarded to ${entranceType.name})",
            )
        }

        val ctor = entranceType.declaredConstructors.firstOrNull {
            it.parameterTypes.isNotEmpty() && it.parameterTypes[0] == Context::class.java
        }?.apply { isAccessible = true } ?: run {
            runtime.log("LiveRoomEntranceHook skipped: SpeedySendGiftLayout ctor not found")
            return
        }
        runtime.xposed.hook(ctor)
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
            .intercept { chain ->
                val result = chain.proceed()
                runCatching {
                    val thiz = chain.thisObject
                    val purifySet = BbplusSettings.getPurifyLivePopups(runtime.prefs)
                    if (thiz is View && BbplusSettings.PURIFY_POPULARITY_TICKET in purifySet) {
                        thiz.visibility = View.GONE
                        hideParentSlot(thiz)
                    }
                }.onFailure { runtime.log("PopularityTicket ctor hide failed", it) }
                result
            }
        runtime.log("LiveRoomEntranceHook installed on ${entranceType.name}.<init>")
    }

    private fun hideParentSlot(view: View) {
        var current: View? = view.parent as? View
        while (current != null && current is android.widget.FrameLayout) {
            if (current.visibility != View.GONE) {
                current.visibility = View.GONE
                if (fastGiftSlotLogged.compareAndSet(false, true)) {
                    runtime.log("[PopularityTicket] FrameLayout hidden: id=0x${Integer.toHexString(current.id)}")
                }
            }
            val slotId = resolveId(current, "fr_speedy_send_gift", FR_SPEEDY_SEND_GIFT_ID)
            if (current.id == slotId) break
            current = current.parent as? View
        }
    }

    private fun hideTopOperationPendants() {
        val lynxViewType = loadClass(CLASS_LYNX_VIEW) ?: run {
            runtime.log("LiveRoomEntranceHook skipped: LynxView not found")
            return
        }
        val onAttached = runCatching {
            lynxViewType.getDeclaredMethod("onAttachedToWindow").apply { isAccessible = true }
        }.getOrNull() ?: run {
            runtime.log("LiveRoomEntranceHook skipped: LynxView.onAttachedToWindow not found")
            return
        }

        runtime.xposed.hook(onAttached)
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
            .intercept { chain ->
                val result = chain.proceed()
                runCatching {
                    val thiz = chain.thisObject as? View ?: return@runCatching
                    if (!lynxViewType.isInstance(thiz)) return@runCatching
                    if (!BbplusSettings.getPurifyLivePopups(runtime.prefs).contains(BbplusSettings.PURIFY_PK_WIDGET)) return@runCatching
                    val containerId = resolveId(thiz, "top_operation_right_lynx_container", TOP_OPERATION_RIGHT_LYNX_CONTAINER_ID)
                    var parent = thiz.parent
                    while (parent is View) {
                        if (parent.id == containerId) {
                            if (parent.visibility != View.GONE) {
                                parent.visibility = View.GONE
                                if (pkPendantLogged.compareAndSet(false, true)) {
                                    runtime.log("[PkPendant] top_operation_right_lynx_container hidden via LynxView attach")
                                }
                            }
                            break
                        }
                        parent = parent.parent
                    }
                }.onFailure { runtime.log("PkPendant attach hook failed", it) }
                result
            }
        runtime.log("LiveRoomEntranceHook installed on ${lynxViewType.name}.onAttachedToWindow")
    }

    private fun resolveId(view: View, name: String, fallback: Int): Int {
        val resolved = runCatching {
            view.resources.getIdentifier(name, "id", "tv.danmaku.bili")
        }.getOrNull()
        if (resolved != null && resolved != 0) return resolved
        return fallback
    }

    private fun installLynxDiagnostics() {
        val providerType = loadClass(CLASS_LYNX_TEMPLATE_PROVIDER) ?: run {
            runtime.log("LiveRoomEntranceHook diagnostics skipped: BiliLynxTemplateProvider not found")
            return
        }
        val requestType = loadClass("com.lynx.tasm.resourceprovider.LynxResourceRequest")
        val urlField = requestType?.let {
            runCatching { it.getField("a").apply { isAccessible = true } }.getOrNull()
        }
        val fetchMethod = providerType.declaredMethods.firstOrNull { method ->
            method.parameterCount == 2 &&
                method.parameterTypes[0].name.endsWith("LynxResourceRequest")
        }?.apply { isAccessible = true } ?: run {
            runtime.log("LiveRoomEntranceHook diagnostics skipped: fetch method not found")
            return
        }

        runtime.xposed.hook(fetchMethod)
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
            .intercept { chain ->
                if (BbplusSettings.getPurifyLivePopups(runtime.prefs).contains(BbplusSettings.PURIFY_PK_WIDGET)) {
                    runCatching {
                        val request = chain.args.getOrNull(0)
                        val url = urlField?.get(request) as? String
                        if (!url.isNullOrEmpty()) {
                            if (seenLynxUrls.add(url)) runtime.log("[LynxTemplate] $url")
                        } else if (diagNullLogged.compareAndSet(false, true)) {
                            runtime.log("[LynxTemplate] fetch fired but url unreadable")
                        }
                    }.onFailure { runtime.log("[LynxTemplate] diag failed", it) }
                }
                chain.proceed()
            }
        runtime.log("LiveRoomEntranceHook diagnostics installed on ${providerType.name}.${fetchMethod.name}")
    }

    private fun hookOuterPanelFilter() {
        val outerPanelType = loadClass(CLASS_OUTER_PANEL_VIEW) ?: run {
            runtime.log("LiveRoomEntranceHook skipped: LiveOuterPanelViewV2 not found")
            return
        }

        val setItemsMethod = runCatching {
            outerPanelType.declaredMethods.firstOrNull { method ->
                method.name == "d" &&
                    method.parameterCount == 1 &&
                    method.parameterTypes[0] == java.util.List::class.java
            }?.apply { isAccessible = true }
        }.getOrNull() ?: run {
            runtime.log("LiveRoomEntranceHook skipped: LiveOuterPanelViewV2.d(List) not found")
            return
        }

        runtime.xposed.hook(setItemsMethod)
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
            .intercept { chain ->
                val purifySet = BbplusSettings.getPurifyLivePopups(runtime.prefs)
                val hideShoppingCart = BbplusSettings.PURIFY_SHOPPING_CART_BTN in purifySet
                val hidePlayWithMe = BbplusSettings.PURIFY_PLAY_WITH_ME in purifySet

                if (!hideShoppingCart && !hidePlayWithMe) {
                    return@intercept chain.proceed()
                }

                val originalList = chain.args.getOrNull(0) as? List<*> ?: return@intercept chain.proceed()

                val filteredList = originalList.filter { item ->
                    if (item == null) return@filter true

                    val settingData = runCatching {
                        item.javaClass.getField("a").get(item)
                    }.getOrNull() ?: return@filter true

                    val bizId = runCatching {
                        settingData.javaClass.getField("bizId").getInt(settingData)
                    }.getOrNull() ?: return@filter true

                    if (hideShoppingCart && bizId == BIZ_ID_SHOPPING_CART) {
                        if (shoppingCartLogged.compareAndSet(false, true)) {
                            runtime.log("[ShoppingCart] filtered bizId=$bizId from outer panel")
                        }
                        return@filter false
                    }

                    if (hidePlayWithMe && bizId == BIZ_ID_PLAY_WITH_ME) {
                        if (playWithMeLogged.compareAndSet(false, true)) {
                            runtime.log("[PlayWithMe] filtered bizId=$bizId from outer panel")
                        }
                        return@filter false
                    }

                    true
                }

                if (filteredList.size == originalList.size) {
                    return@intercept chain.proceed()
                }

                chain.proceed(arrayOf(filteredList))
            }
        runtime.log("LiveRoomEntranceHook installed on ${outerPanelType.name}.d(List)")
    }

    private fun loadClass(name: String): Class<*>? =
        runCatching { runtime.classLoader.loadClass(name) }.getOrNull()

    private companion object {
        const val CLASS_FAST_GIFT_ENTRANCE =
            "com.bilibili.bililive.biz.interactions.fastgift.bottom.ui.c"
        const val CLASS_LYNX_VIEW = "com.lynx.tasm.LynxView"
        const val CLASS_LYNX_TEMPLATE_PROVIDER =
            "com.bilibili.lib.lynx.BiliLynxTemplateProvider"
        const val CLASS_OUTER_PANEL_VIEW =
            "com.bilibili.bililive.room.ui.roomv3.settinginteractionpanel.widget.LiveOuterPanelViewV2"
        const val TOP_OPERATION_RIGHT_LYNX_CONTAINER_ID = 0x7f0961f6
        const val FR_SPEEDY_SEND_GIFT_ID = 0x7f095443
        const val BIZ_ID_SHOPPING_CART = 33
        const val BIZ_ID_PLAY_WITH_ME = 1013
    }
}
