package io.zel.bbplus.hook

import android.view.View
import io.github.libxposed.api.XposedInterface
import io.zel.bbplus.BbplusRuntime
import io.zel.bbplus.BbplusSettings

class ShareToOverflowHook(private val runtime: BbplusRuntime) {

    private val bindingType by lazy {
        runtime.classLoader.loadClass("Dy0.h0")
    }

    private val continuationType by lazy {
        runtime.classLoader.loadClass("kotlin.coroutines.Continuation")
    }

    fun startHook() {
        val shareComponentType = runCatching {
            runtime.classLoader.loadClass(
                "com.bilibili.ship.theseus.united.page.intro.module.kingposition.KingPositionComponent2\$ShareComponent",
            )
        }.getOrNull() ?: run {
            runtime.log("ShareToOverflowHook skipped: ShareComponent not found")
            return
        }

        val bind = runCatching {
            shareComponentType.getDeclaredMethod("c", bindingType, continuationType)
                .apply { isAccessible = true }
        }.getOrNull() ?: run {
            runtime.log("ShareToOverflowHook skipped: ShareComponent.c(binding, cont) not found")
            return
        }

        val getRoot = runCatching {
            bindingType.getMethod("getRoot").apply { isAccessible = true }
        }.getOrNull() ?: run {
            runtime.log("ShareToOverflowHook skipped: ViewBinding.getRoot not found")
            return
        }

        runtime.xposed.hook(bind)
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
            .intercept { chain ->
                val result = chain.proceed()
                if (!BbplusSettings.isShareToOverflow(runtime.prefs)) return@intercept result
                runCatching {
                    val binding = chain.getArgs().getOrNull(0) ?: return@intercept result
                    val shareFrame = getRoot.invoke(binding) as? View ?: return@intercept result
                    val overflowId = overflowIdOf(shareFrame) ?: return@intercept result
                    val redirect = View.OnClickListener { v ->
                        v.rootView?.findViewById<View>(overflowId)?.performClick()
                    }
                    shareFrame.setOnClickListener(redirect)
                    val shareIcon = shareFrame.findViewById<View>(shareIconIdOf(shareFrame))
                    if (shareIcon != null) {
                        shareIcon.setOnClickListener(redirect)
                        runtime.log("ShareToOverflow: frame_share + share_icon listeners redirected")
                    } else {
                        runtime.log("ShareToOverflow: frame_share listener redirected (icon not found)")
                    }
                }.onFailure { runtime.log("ShareToOverflow redirect failed", it) }
                result
            }
        runtime.log("ShareToOverflowHook installed on ${shareComponentType.name}.c")
    }

    private fun overflowIdOf(view: View): Int? {
        val resolved = runCatching {
            view.resources.getIdentifier("toolbar_action_overflow", "id", "tv.danmaku.bili")
        }.getOrNull()
        if (resolved != null && resolved != 0) return resolved
        return OVERFLOW_ID_9_7_0
    }

    private fun shareIconIdOf(view: View): Int {
        val resolved = runCatching {
            view.resources.getIdentifier("share_icon", "id", "tv.danmaku.bili")
        }.getOrNull()
        if (resolved != null && resolved != 0) return resolved
        return SHARE_ICON_ID_9_7_0
    }

    companion object {
        private const val OVERFLOW_ID_9_7_0 = 0x7f09472c
        private const val SHARE_ICON_ID_9_7_0 = 0x7f094067
    }
}