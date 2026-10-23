package io.zel.bbplus.hook

import android.webkit.WebView
import io.github.libxposed.api.XposedInterface
import io.zel.bbplus.BbplusRuntime
import io.zel.bbplus.BbplusSettings

/**
 * 用户空间动态页（LocalAuthorSpaceActivity）由 HiloWebView 承载 H5 页面渲染，
 * 转发动态里的「相关游戏」附加卡片由网页 DOM 绘制，原生列表 hook 覆盖不到。
 *
 * 真机 9.7.0 实测结构（移动端 author_space）：
 *   <div class="additional-common">
 *     <div class="header-tip">… 相关游戏</div>
 *     <div class="common-card">… <div class="title">游戏名</div> …</div>
 *     <div class="wrap action"><button class="additional-button">下载</button></div>
 *   </div>
 *
 * 注意：PC 端结构（data-module="additional" + bili-dyn-card-link-common__mark）与移动端不同，
 * 不能混用选择器。卡片在首屏渲染完成后仍会随滚动增量出现，故用 MutationObserver 常驻监听。
 */
class SpaceH5GameCardHook(private val runtime: BbplusRuntime) {

    fun startHook() {
        val clientType = runCatching {
            runtime.classLoader.loadClass("com.bilibili.common.hilowebview.client.HiloClient")
        }.getOrNull() ?: run {
            runtime.log("SpaceH5GameCardHook skipped: HiloClient not found")
            return
        }

        val onPageFinished = runCatching {
            clientType.getDeclaredMethod(
                "onPageFinished",
                WebView::class.java,
                String::class.java,
            ).apply { isAccessible = true }
        }.getOrNull() ?: run {
            runtime.log("SpaceH5GameCardHook skipped: onPageFinished not found")
            return
        }

        runtime.xposed.hook(onPageFinished)
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
            .intercept { chain ->
                val result = chain.proceed()
                if (!BbplusSettings.isBlockDynamicGameCard(runtime.prefs)) return@intercept result
                runCatching {
                    val webView = chain.getArgs()[0] as? WebView ?: return@intercept result
                    val url = chain.getArgs()[1] as? String ?: ""
                    if (!url.contains(SPACE_HOST, ignoreCase = true)) return@intercept result
                    webView.evaluateJavascript(SCRIPT) { value ->
                        val removed = value?.trim('"')?.toIntOrNull() ?: 0
                        if (removed > 0) {
                            runtime.log("SpaceH5GameCardHook: removed $removed game card(s)")
                        }
                    }
                    runtime.log("SpaceH5GameCardHook: injected into $url")
                }.onFailure { runtime.log("SpaceH5GameCardHook inject failed", it) }
                result
            }
        runtime.log("SpaceH5GameCardHook installed on ${clientType.name}.onPageFinished")
    }

    private companion object {
        const val SPACE_HOST = "author_space"

        /**
         * 隐藏「相关游戏」附加卡片：
         * 1) 首屏与后续增量渲染都由 MutationObserver 覆盖（卡片常在滚动后才加入 DOM）；
         * 2) 判定：类名含 additional-common 的容器，且 header-tip 文本含「相关游戏」；
         * 3) 退回兜底：类名含 additional-common 且含 .title「游戏」tag 的卡片也隐藏。
         */
        const val SCRIPT = """
(function () {
  if (window.__bbplusSpaceGameCard) return 0;
  window.__bbplusSpaceGameCard = true;

  var removed = 0;
  var MARK = '相关游戏';

  function isGameCard(el) {
    if (!el || el.nodeType !== 1) return false;
    var cls = String(el.className || '');
    if (cls.indexOf('additional-common') < 0) return false;
    var tip = el.querySelector('.header-tip');
    if (tip && (tip.textContent || '').indexOf(MARK) >= 0) return true;
    // 兜底：卡片内带「游戏」类型徽标且标题非空
    var badge = el.querySelector('.badge');
    return !!badge && (badge.textContent || '').trim() === '游戏';
  }

  function hide(el) {
    if (el.getAttribute('data-bbplus-hidden') === '1') return;
    el.style.display = 'none';
    el.setAttribute('data-bbplus-hidden', '1');
    removed++;
  }

  function sweep(root) {
    var scope = root || document;
    var nodes;
    try {
      nodes = scope.querySelectorAll('.additional-common');
    } catch (e) { return; }
    for (var i = 0; i < nodes.length; i++) {
      if (isGameCard(nodes[i])) hide(nodes[i]);
    }
  }

  sweep();

  try {
    var mo = new MutationObserver(function (muts) {
      for (var i = 0; i < muts.length; i++) {
        var added = muts[i].addedNodes;
        for (var j = 0; j < added.length; j++) {
          var node = added[j];
          if (node.nodeType !== 1) continue;
          if (isGameCard(node)) hide(node);
          else if (node.querySelectorAll) sweep(node);
        }
      }
    });
    mo.observe(document.documentElement, { childList: true, subtree: true });
    // SPA 路由切换会重建 DOM，保持观察即可；页面卸载时自动失效
  } catch (e) { }

  return removed;
})();
"""
    }
}
