package io.zel.bbplus.hook

import io.github.libxposed.api.XposedInterface
import io.zel.bbplus.BbplusRuntime
import io.zel.bbplus.BbplusSettings

class DmActivityMetaHook(private val runtime: BbplusRuntime) {

    fun startHook() {
        val dmReplyType = runCatching {
            runtime.classLoader.loadClass("com.bapis.bilibili.community.service.dm.v1.DmViewReply")
        }.getOrNull() ?: run {
            runtime.log("DmActivityMetaHook skipped: DmViewReply not found")
            return
        }

        val onNext = runCatching {
            runtime.classLoader
                .loadClass("com.bapis.bilibili.community.service.dm.v1.DmMossKtxKt\$suspendDmView\$\$inlined\$suspendCall\$1")
                .getDeclaredMethod("onNext", Any::class.java)
                .apply { isAccessible = true }
        }.getOrNull() ?: run {
            runtime.log("DmActivityMetaHook skipped: onNext not found")
            return
        }

        val metaField = runCatching {
            dmReplyType.getDeclaredField("activityMeta_").apply { isAccessible = true }
        }.getOrNull()

        val defaultEmptyList = runCatching {
            val def = dmReplyType.getMethod("getDefaultInstance").invoke(null)
            def.javaClass.getDeclaredField("activityMeta_").apply { isAccessible = true }.get(def)
        }.getOrNull()

        val getActivityMetaList = runCatching {
            dmReplyType.getMethod("getActivityMetaList").apply { isAccessible = true }
        }.getOrNull()

        val getActivityMetaCount = runCatching {
            dmReplyType.getMethod("getActivityMetaCount").apply { isAccessible = true }
        }.getOrNull()

        runtime.xposed.hook(onNext)
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
            .intercept { chain ->
                if (BbplusSettings.isBlockCloudTvPopup(runtime.prefs) && metaField != null && defaultEmptyList != null) {
                    runCatching {
                        val reply = chain.getArgs().getOrNull(0) ?: return@runCatching
                        if (!dmReplyType.isInstance(reply)) return@runCatching
                        val current = metaField.get(reply)
                        if (current is List<*> && current.isNotEmpty()) {
                            metaField.set(reply, defaultEmptyList)
                        }
                    }.onFailure { runtime.log("DmActivityMeta field clear failed", it) }
                }
                chain.proceed()
            }
        runtime.log("DmActivityMetaHook installed on ${onNext.declaringClass.name}.onNext")

        if (getActivityMetaList != null) {
            runtime.xposed.hook(getActivityMetaList)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    if (BbplusSettings.isBlockCloudTvPopup(runtime.prefs)) {
                        java.util.Collections.emptyList<Any>()
                    } else {
                        chain.proceed()
                    }
                }
            runtime.log("DmActivityMetaHook installed on ${dmReplyType.name}.getActivityMetaList")
        }

        if (getActivityMetaCount != null) {
            runtime.xposed.hook(getActivityMetaCount)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    if (BbplusSettings.isBlockCloudTvPopup(runtime.prefs)) {
                        0
                    } else {
                        chain.proceed()
                    }
                }
            runtime.log("DmActivityMetaHook installed on ${dmReplyType.name}.getActivityMetaCount")
        }
    }
}