package io.zel.bbplus.hook

import io.github.libxposed.api.XposedInterface
import io.zel.bbplus.BbplusRuntime
import io.zel.bbplus.BbplusSettings
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Type
import java.util.concurrent.atomic.AtomicBoolean

class LiveRoomPopupHook(private val runtime: BbplusRuntime) {

    private val pkTaskLogged = AtomicBoolean(false)

    fun startHook() {
        val fastJsonClass = findFastJsonClass() ?: run {
            runtime.log("LiveRoomPopupHook skipped: FastJSON class not found")
            return
        }

        val parseMethod = findParseMethod(fastJsonClass) ?: run {
            runtime.log("LiveRoomPopupHook skipped: parse(String, Type, Feature[]) not found")
            return
        }

        val liveShoppingInfo = loadClass(CLASS_LIVE_SHOPPING_INFO)
        val liveGoodsCardInfo = loadClass(CLASS_LIVE_GOODS_CARD_INFO)
        val liveRecommendCardGoods = loadClass(CLASS_LIVE_RECOMMEND_CARD_GOODS)
        val biliLiveRoomInfo = loadClass(CLASS_BILI_LIVE_ROOM_INFO)
        val liveRoomReserveInfo = loadClass(CLASS_LIVE_ROOM_RESERVE_INFO)
        val biliLiveRoomUserInfo = loadClass(CLASS_BILI_LIVE_ROOM_USER_INFO)
        val liveRoomRecommendCard = loadClass(CLASS_LIVE_ROOM_RECOMMEND_CARD)
        val liveShoppingGotoBuyInfo = loadClass(CLASS_LIVE_SHOPPING_GOTO_BUY_INFO)
        val generalResponse = loadClass(CLASS_GENERAL_RESPONSE)
        val pkTaskWidgetData = loadClass(CLASS_PK_TASK_WIDGET_DATA)

        runtime.xposed.hook(parseMethod)
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
            .intercept { chain ->
                val original = chain.proceed()
                runCatching {
                    purify(
                        original,
                        BbplusSettings.getPurifyLivePopups(runtime.prefs),
                        liveShoppingInfo,
                        liveGoodsCardInfo,
                        liveRecommendCardGoods,
                        biliLiveRoomInfo,
                        liveRoomReserveInfo,
                        biliLiveRoomUserInfo,
                        liveRoomRecommendCard,
                        liveShoppingGotoBuyInfo,
                        generalResponse,
                        pkTaskWidgetData,
                    )
                }.getOrElse { original }
            }
        runtime.log("LiveRoomPopupHook installed on ${fastJsonClass.name}.${parseMethod.name}")
    }

    private fun purify(
        original: Any?,
        enabled: Set<String>,
        liveShoppingInfo: Class<*>?,
        liveGoodsCardInfo: Class<*>?,
        liveRecommendCardGoods: Class<*>?,
        biliLiveRoomInfo: Class<*>?,
        liveRoomReserveInfo: Class<*>?,
        biliLiveRoomUserInfo: Class<*>?,
        liveRoomRecommendCard: Class<*>?,
        liveShoppingGotoBuyInfo: Class<*>?,
        generalResponse: Class<*>?,
        pkTaskWidgetData: Class<*>?,
    ): Any? {
        if (enabled.isEmpty()) return original
        var scope = original ?: return original

        if (generalResponse != null && scope.javaClass == generalResponse) {
            scope = getFieldOrNull(scope, "data") ?: return original
        }

        val cls = scope.javaClass
        var blocked = false

        if (cls === liveShoppingInfo) {
            if (BbplusSettings.PURIFY_SHOPPING_CARD in enabled) {
                setReferenceField(scope, "shoppingCardDetail", null)
                setReferenceField(scope, "recommendCardDetail", null)
            }
            if (BbplusSettings.PURIFY_SHOPPING_SELECTED in enabled) {
                setReferenceField(scope, "selectedGoods", null)
            }
        } else if (cls === liveGoodsCardInfo || cls === liveRecommendCardGoods) {
            blocked = BbplusSettings.PURIFY_SHOPPING_CARD in enabled
        } else if (cls === biliLiveRoomInfo) {
            if (BbplusSettings.PURIFY_FOLLOW in enabled) {
                getFieldOrNull(scope, "functionCard")?.let { setReferenceField(it, "followCard", null) }
            }
            if (BbplusSettings.PURIFY_BANNER in enabled) {
                setReferenceField(scope, "bannerInfo", null)
            }
        } else if (cls === liveRoomRecommendCard) {
            blocked = BbplusSettings.PURIFY_FOLLOW in enabled
        } else if (cls === liveRoomReserveInfo) {
            if (BbplusSettings.PURIFY_RESERVE in enabled) {
                setBooleanField(scope, "showReserveDetail", false)
            }
        } else if (cls === biliLiveRoomUserInfo) {
            if (BbplusSettings.PURIFY_GIFT in enabled) {
                getFieldOrNull(scope, "functionCard")?.let { setReferenceField(it, "sengGiftCard", null) }
            }
            if (BbplusSettings.PURIFY_TASK in enabled) {
                setReferenceField(scope, "taskInfo", null)
            }
        } else if (cls === liveShoppingGotoBuyInfo) {
            blocked = BbplusSettings.PURIFY_GOTO_BUY in enabled
        } else if (cls === pkTaskWidgetData) {
            if (BbplusSettings.PURIFY_PK_WIDGET in enabled) {
                setBooleanField(scope, "show", false)
                setReferenceField(scope, "task", null)
                if (pkTaskLogged.compareAndSet(false, true)) {
                    runtime.log("[PKTaskWidget] intercepted, show forced false")
                }
            }
        }

        return if (blocked) null else original
    }

    private fun findFastJsonClass(): Class<*>? =
        listOf(
            "com.alibaba.fastjson.JSON",
            "tv.danmaku.bili.libs.fastjson.JSON",
            "com.bilibili.lib.fastjson.JSON",
        ).firstNotNullOfOrNull { name -> loadClass(name) }

    private fun findParseMethod(fastJsonClass: Class<*>): Method? {
        val featureArray = runCatching {
            fastJsonClass.classLoader.loadClass(
                fastJsonClass.`package`?.name?.plus(".parser.Feature") ?: return null,
            )
        }.getOrNull() ?: return null

        val candidates = fastJsonClass.methods.filter { method ->
            method.parameterCount == 3 &&
                method.parameterTypes[0] == String::class.java &&
                method.parameterTypes[1] == Type::class.java &&
                method.parameterTypes[2].isArray &&
                method.parameterTypes[2].componentType == featureArray
        }
        return candidates.firstOrNull { it.name == "parseObject" }
            ?: candidates.firstOrNull()?.apply { isAccessible = true }
    }

    private fun loadClass(name: String): Class<*>? =
        runCatching { runtime.classLoader.loadClass(name) }.getOrNull()

    private fun findField(clazz: Class<*>, name: String): Field? {
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            runCatching { return current.getDeclaredField(name).apply { isAccessible = true } }
            current = current.superclass
        }
        return null
    }

    private fun getFieldOrNull(obj: Any, name: String): Any? =
        findField(obj.javaClass, name)?.let { runCatching { it.get(obj) }.getOrNull() }

    private fun setReferenceField(obj: Any, name: String, value: Any?) {
        val field = findField(obj.javaClass, name) ?: return
        if (field.type.isPrimitive) return
        runCatching { field.set(obj, value) }
            .onFailure { runtime.log("LiveRoomPopupHook set $name failed", it) }
    }

    private fun setBooleanField(obj: Any, name: String, value: Boolean) {
        val field = findField(obj.javaClass, name) ?: return
        if (field.type != Boolean::class.javaPrimitiveType) return
        runCatching { field.setBoolean(obj, value) }
            .onFailure { runtime.log("LiveRoomPopupHook set $name failed", it) }
    }

    private companion object {
        const val CLASS_GENERAL_RESPONSE = "com.bilibili.okretro.GeneralResponse"
        const val CLASS_LIVE_SHOPPING_INFO =
            "com.bilibili.bililive.room.biz.shopping.beans.LiveShoppingInfo"
        const val CLASS_LIVE_GOODS_CARD_INFO =
            "com.bilibili.bililive.room.biz.shopping.beans.LiveGoodsCardInfo"
        const val CLASS_LIVE_RECOMMEND_CARD_GOODS =
            "com.bilibili.bililive.room.biz.shopping.beans.LiveShoppingRecommendCardGoodsDetail"
        const val CLASS_BILI_LIVE_ROOM_INFO =
            "com.bilibili.bililive.videoliveplayer.net.beans.gateway.roominfo.BiliLiveRoomInfo"
        const val CLASS_LIVE_ROOM_RESERVE_INFO =
            "com.bilibili.bililive.room.biz.reverse.bean.LiveRoomReserveInfo"
        const val CLASS_BILI_LIVE_ROOM_USER_INFO =
            "com.bilibili.bililive.videoliveplayer.net.beans.gateway.userinfo.BiliLiveRoomUserInfo"
        const val CLASS_LIVE_ROOM_RECOMMEND_CARD =
            "com.bilibili.bililive.videoliveplayer.net.beans.attentioncard.LiveRoomRecommendCard"
        const val CLASS_LIVE_SHOPPING_GOTO_BUY_INFO =
            "com.bilibili.bililive.room.biz.shopping.beans.LiveShoppingGotoBuyInfo"
        const val CLASS_PK_TASK_WIDGET_DATA =
            "com.bilibili.bililive.biz.pkv2.model.bean.PKTaskWidgetData"
    }
}
