package io.zel.bbplus.setting

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import io.github.libxposed.api.XposedInterface
import io.zel.bbplus.BbplusRuntime
import io.zel.bbplus.BbplusSettings
import java.lang.reflect.Proxy

object SettingsPanel {

    fun install(runtime: BbplusRuntime) {
        val helpFragmentType = runCatching {
            runtime.classLoader.loadClass("com.bilibili.app.preferences.fragment.HelpFragment")
        }.getOrNull() ?: run {
            runtime.log("SettingsPanel skipped: HelpFragment not found")
            return
        }

        val onActivityCreated = runCatching {
            helpFragmentType.getDeclaredMethod("onActivityCreated", Bundle::class.java)
                .apply { isAccessible = true }
        }.getOrNull() ?: run {
            runtime.log("SettingsPanel skipped: HelpFragment.onActivityCreated not found")
            return
        }

        runtime.xposed.hook(onActivityCreated)
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
            .intercept { chain ->
                val result = chain.proceed()
                runCatching {
                    injectEntry(runtime, chain.getThisObject())
                }.onFailure { runtime.log("SettingsPanel inject failed", it) }
                result
            }
        runtime.log("SettingsPanel installed on ${helpFragmentType.name}.onActivityCreated")
    }

    private fun injectEntry(runtime: BbplusRuntime, fragment: Any) {
        val activity = runCatching {
            fragment.javaClass.methods
                .first { it.name == "requireActivity" && it.parameterCount == 0 }
                .invoke(fragment) as? Activity
        }.getOrNull() ?: return

        val screen = getPreferenceScreenOrNull(fragment) ?: return
        val prefType = runCatching {
            activity.classLoader.loadClass("androidx.preference.Preference")
        }.getOrNull() ?: return

        val existing = runCatching {
            screen.javaClass.getMethod("findPreference", CharSequence::class.java).invoke(screen, ENTRY_KEY)
        }.getOrNull()
        if (existing != null) return

        val pref = prefType.getConstructor(Context::class.java).newInstance(activity)
        prefType.getMethod("setKey", String::class.java).invoke(pref, ENTRY_KEY)
        prefType.getMethod("setTitle", CharSequence::class.java).invoke(pref, ENTRY_TITLE)
        prefType.getMethod("setPersistent", Boolean::class.javaPrimitiveType).invoke(pref, false)

        val clickSetter = prefType.methods.first {
            it.name == "setOnPreferenceClickListener" &&
                it.parameterCount == 1 &&
                it.parameterTypes[0].isInterface
        }
        val listenerType = clickSetter.parameterTypes[0]
        val listener = Proxy.newProxyInstance(listenerType.classLoader, arrayOf(listenerType)) { _, method, _ ->
            if (method.name == "onPreferenceClick") {
                showSettingsDialog(runtime, activity)
                true
            } else {
                null
            }
        }
        clickSetter.invoke(pref, listener)
        screen.javaClass.getMethod("addPreference", prefType).invoke(screen, pref)
        runtime.log("SettingsPanel entry injected into ${fragment.javaClass.name}")
    }

    private fun getPreferenceScreenOrNull(fragment: Any): Any? =
        runCatching {
            fragment.javaClass.methods
                .firstOrNull { it.name == "getPreferenceScreen" && it.parameterCount == 0 }
                ?.invoke(fragment)
        }.getOrNull()

    private data class Palette(
        val card: Int,
        val primary: Int,
        val secondary: Int,
        val divider: Int,
        val accent: Int,
        val trackOff: Int,
        val buttonBg: Int,
    )

    private fun paletteOf(activity: Activity): Palette {
        val night = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return if (night == Configuration.UI_MODE_NIGHT_YES) {
            Palette(
                card = 0xFF1D1E23.toInt(),
                primary = 0xFFF3F4F6.toInt(),
                secondary = 0xFF9BA1AB.toInt(),
                divider = 0x18FFFFFF,
                accent = 0xFFFF809E.toInt(),
                trackOff = 0xFF3A3D45.toInt(),
                buttonBg = 0xFF26272E.toInt(),
            )
        } else {
            Palette(
                card = 0xFFFFFFFF.toInt(),
                primary = 0xFF191C20.toInt(),
                secondary = 0xFF878E98.toInt(),
                divider = 0x12000000,
                accent = 0xFFFB7299.toInt(),
                trackOff = 0xFFD9DDE4.toInt(),
                buttonBg = 0xFFFDEFF3.toInt(),
            )
        }
    }

    private fun dp(activity: Activity, value: Float): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp * 2.5f
        }

    private fun rippleBackground(activity: Activity, p: Palette, radiusDp: Float): Any? {
        return runCatching {
            val tv = android.util.TypedValue()
            if (activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) {
                tv.resourceId
            } else {
                null
            }
        }.getOrNull()
    }

    private fun sectionHeader(activity: Activity, p: Palette, text: String): TextView =
        TextView(activity).apply {
            this.text = text
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(p.secondary)
            setPadding(dp(activity, 4f), dp(activity, 22f), dp(activity, 4f), dp(activity, 8f))
        }

    private fun card(activity: Activity, p: Palette): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(p.card, 16f)
            val pad = dp(activity, 4f)
            setPadding(pad, pad, pad, pad)
        }

    private fun divider(activity: Activity, p: Palette): View =
        View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 1f),
            ).apply { marginStart = dp(activity, 16f); marginEnd = dp(activity, 16f) }
            setBackgroundColor(p.divider)
        }

    /**
     * 把条目区放进限高的滚动容器：条目较多时在列表内部滚动，
     * 避免条目把底部按钮行挤扁，导致按钮显示不全、文字被切断。
     */
    private fun scrollableSection(activity: Activity, content: View): ScrollView {
        val maxSectionHeight = (activity.resources.displayMetrics.heightPixels - dp(activity, 280f))
            .coerceAtLeast(dp(activity, 120f))
        return object : ScrollView(activity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(
                    widthMeasureSpec,
                    View.MeasureSpec.makeMeasureSpec(maxSectionHeight, View.MeasureSpec.AT_MOST),
                )
            }
        }.apply {
            isVerticalScrollBarEnabled = false
            addView(
                content,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
    }

    private fun tintSwitch(activity: Activity, p: Palette, switch: Switch) {
        runCatching {
            switch.thumbTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            switch.trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(p.accent, p.trackOff),
            )
        }
    }

    private fun switchRow(
        activity: Activity,
        prefs: SharedPreferences,
        p: Palette,
        key: String,
        title: String,
        summary: String,
    ): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 14f), dp(activity, 13f), dp(activity, 14f), dp(activity, 13f))
        }
        val textCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(TextView(activity).apply {
            text = title
            textSize = 15f
            setTextColor(p.primary)
        })
        textCol.addView(TextView(activity).apply {
            text = summary
            textSize = 12f
            setTextColor(p.secondary)
            setPadding(0, dp(activity, 2f), dp(activity, 12f), 0)
        })
        row.addView(textCol)
        val sw = Switch(activity)
        tintSwitch(activity, p, sw)
        sw.isChecked = prefs.getBoolean(key, true)
        sw.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(key, checked).apply()
        }
        row.addView(sw)
        return row
    }

    private fun purifyRow(
        activity: Activity,
        p: Palette,
        summaryView: TextView,
        onClick: () -> Unit,
    ): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 14f), dp(activity, 13f), dp(activity, 14f), dp(activity, 13f))
            isClickable = true
            isFocusable = true
        }
        rippleBackground(activity, p, 12f)?.let { id ->
            runCatching { row.setBackgroundResource(id as Int) }
        }
        row.setOnClickListener { onClick() }
        val textCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(TextView(activity).apply {
            text = "净化直播间"
            textSize = 15f
            setTextColor(p.primary)
        })
        summaryView.textSize = 12f
        summaryView.setTextColor(p.accent)
        summaryView.setPadding(0, dp(activity, 2f), dp(activity, 12f), 0)
        textCol.addView(summaryView)
        row.addView(textCol)
        row.addView(TextView(activity).apply {
            text = "›"
            textSize = 22f
            setTextColor(p.secondary)
            setPadding(dp(activity, 8f), 0, 0, dp(activity, 2f))
        })
        return row
    }

    private fun purifySummaryText(prefs: SharedPreferences): String {
        val selected = BbplusSettings.getPurifyLivePopups(prefs)
        if (selected.isEmpty()) return "未启用 · 点击选择要隐藏的内容"
        val names = BbplusSettings.purifyLabels.filter { it.first in selected }.map { it.second }
        return "已选 ${names.size}/${BbplusSettings.purifyLabels.size} 项 · ${names.joinToString("、")}"
    }

    private fun showSettingsDialog(runtime: BbplusRuntime, activity: Activity) {
        val p = paletteOf(activity)
        val prefs = runtime.prefs
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val scroll = ScrollView(activity)
        scroll.isVerticalScrollBarEnabled = false
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(p.card, 24f)
            setPadding(dp(activity, 20f), dp(activity, 24f), dp(activity, 20f), dp(activity, 20f))
        }

        root.addView(TextView(activity).apply {
            text = "BBplus 设置"
            textSize = 21f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(p.primary)
            setPadding(dp(activity, 2f), 0, 0, dp(activity, 4f))
        })

        root.addView(sectionHeader(activity, p, "通用"))

        val generalCard = card(activity, p)
        val rows = listOf(
            Triple(BbplusSettings.KEY_BLOCK_CLOUD_TV_POPUP, "去除云视听小电视贴片", "播放器云视听小电视贴片"),
            Triple(BbplusSettings.KEY_BLOCK_MENTION_GAME, "去除视频提及游戏卡片", "详情页游戏推广卡片"),
            Triple(BbplusSettings.KEY_BLOCK_DYNAMIC_GAME_CARD, "去除动态相关游戏卡片", "动态/图文动态下方附加游戏卡片"),
            Triple(BbplusSettings.KEY_SHARE_TO_OVERFLOW, "分享按钮重定向", "分享按钮打开更多操作"),
        )
        rows.forEachIndexed { i, (key, title, summary) ->
            generalCard.addView(switchRow(activity, prefs, p, key, title, summary))
            if (i < rows.lastIndex) generalCard.addView(divider(activity, p))
        }
        root.addView(generalCard)

        root.addView(sectionHeader(activity, p, "直播间"))

        val liveCard = card(activity, p)
        liveCard.addView(
            switchRow(
                activity,
                prefs,
                p,
                BbplusSettings.KEY_LIVE_SENSOR_ROTATION,
                "直播间重力感应旋屏",
                "全屏时根据重力自动旋转横屏方向",
            ),
        )
        liveCard.addView(divider(activity, p))
        val purifySummary = TextView(activity)
        purifySummary.text = purifySummaryText(prefs)
        liveCard.addView(
            purifyRow(activity, p, purifySummary) {
                showPurifyPicker(runtime, activity, p) {
                    purifySummary.text = purifySummaryText(prefs)
                }
            },
        )
        root.addView(liveCard)

        root.addView(sectionHeader(activity, p, "首页推荐"))

        val feedCard = card(activity, p)
        feedCard.addView(switchRow(activity, prefs, p, BbplusSettings.KEY_FEED_FILTER_ENABLED, "启用首页过滤", "过滤不符合条件的推荐视频"))
        feedCard.addView(divider(activity, p))

        val filterModeSummary = TextView(activity)
        filterModeSummary.text = filterModeSummaryText(prefs)
        filterModeSummary.textSize = 12f
        filterModeSummary.setTextColor(p.accent)
        feedCard.addView(settingRow(activity, p, "过滤模式", filterModeSummary) {
            showFilterModePicker(runtime, activity, p) {
                filterModeSummary.text = filterModeSummaryText(prefs)
            }
        })
        feedCard.addView(divider(activity, p))

        feedCard.addView(switchRow(activity, prefs, p, BbplusSettings.KEY_FEED_BYPASS_FOLLOWED, "已关注UP放行", "已关注UP的视频不受过滤影响"))
        feedCard.addView(divider(activity, p))

        val basicSummary = TextView(activity)
        basicSummary.text = basicFilterSummaryText(prefs)
        basicSummary.textSize = 12f
        basicSummary.setTextColor(p.secondary)
        feedCard.addView(settingRow(activity, p, "基础过滤", basicSummary) {
            showBasicFilterPicker(runtime, activity, p) {
                basicSummary.text = basicFilterSummaryText(prefs)
            }
        })
        feedCard.addView(divider(activity, p))

        val dataSummary = TextView(activity)
        dataSummary.text = dataFilterSummaryText(prefs)
        dataSummary.textSize = 12f
        dataSummary.setTextColor(p.secondary)
        feedCard.addView(settingRow(activity, p, "数据过滤", dataSummary) {
            showDataFilterPicker(runtime, activity, p) {
                dataSummary.text = dataFilterSummaryText(prefs)
            }
        })
        feedCard.addView(divider(activity, p))

        val keywordSummary = TextView(activity)
        keywordSummary.text = keywordFilterSummaryText(prefs)
        keywordSummary.textSize = 12f
        keywordSummary.setTextColor(p.secondary)
        feedCard.addView(settingRow(activity, p, "关键词过滤", keywordSummary) {
            showKeywordFilterPicker(runtime, activity, p) {
                keywordSummary.text = keywordFilterSummaryText(prefs)
            }
        })
        root.addView(feedCard)

        root.addView(sectionHeader(activity, p, "数量增强"))

        val sizeCard = card(activity, p)
        sizeCard.addView(switchRow(activity, prefs, p, BbplusSettings.KEY_FEED_SIZE_ENHANCE, "增加单次刷新数量", "模拟大屏设备获取更多推荐"))
        sizeCard.addView(divider(activity, p))
        sizeCard.addView(switchRow(activity, prefs, p, BbplusSettings.KEY_FEED_AUTO_LOAD_MORE, "数量不足自动补加载", "过滤后数量不足时自动加载更多"))

        val targetSummary = TextView(activity)
        targetSummary.text = "${BbplusSettings.getFeedTargetCount(prefs)} 条"
        targetSummary.textSize = 12f
        targetSummary.setTextColor(p.secondary)
        sizeCard.addView(settingRow(activity, p, "目标数量", targetSummary) {
            showNumberPicker(activity, prefs, p, "目标数量",
                BbplusSettings.KEY_FEED_TARGET_COUNT, 30, 1, 100) {
                targetSummary.text = "${BbplusSettings.getFeedTargetCount(prefs)} 条"
            }
        })
        sizeCard.addView(divider(activity, p))

        val maxCountSummary = TextView(activity)
        maxCountSummary.text = "${BbplusSettings.getFeedMaxCardCount(prefs)} 条"
        maxCountSummary.textSize = 12f
        maxCountSummary.setTextColor(p.secondary)
        sizeCard.addView(settingRow(activity, p, "最大缓存数量", maxCountSummary) {
            showNumberPicker(activity, prefs, p, "最大缓存数量",
                BbplusSettings.KEY_FEED_MAX_CARD_COUNT, 1000, 500, 5000) {
                maxCountSummary.text = "${BbplusSettings.getFeedMaxCardCount(prefs)} 条"
            }
        })
        root.addView(sizeCard)

        root.addView(TextView(activity).apply {
            text = "开关即时生效；首页推荐和数量增强需重启B站后生效。"
            textSize = 11f
            setTextColor(p.secondary)
            setPadding(dp(activity, 4f), dp(activity, 18f), 0, 0)
        })

        scroll.addView(root)
        dialog.setContentView(scroll)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(rounded(p.card, 24f))
            attributes = attributes.apply {
                width = (activity.resources.displayMetrics.widthPixels * 0.88f).toInt()
                height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }
    }

    // ==================== Home Feed Setting Helpers ====================

    private fun filterModeSummaryText(prefs: SharedPreferences): String {
        val mode = BbplusSettings.getFeedFilterMode(prefs)
        return if (mode == "whitelist") "白名单 · 仅展示匹配项" else "黑名单 · 排除匹配项"
    }

    private fun basicFilterSummaryText(prefs: SharedPreferences): String {
        val items = mutableListOf<String>()
        if (BbplusSettings.isFeedFilterAd(prefs)) items.add("广告")
        if (BbplusSettings.isFeedFilterLive(prefs)) items.add("直播")
        if (BbplusSettings.isFeedFilterPicture(prefs)) items.add("图文")
        if (BbplusSettings.isFeedFilterBanner(prefs)) items.add("Banner")
        return if (items.isEmpty()) "未启用" else items.joinToString("、")
    }

    private fun dataFilterSummaryText(prefs: SharedPreferences): String {
        val likeMin = BbplusSettings.getFeedLikeMin(prefs)
        val likeMax = BbplusSettings.getFeedLikeMax(prefs)
        val durMin = BbplusSettings.getFeedDurationMin(prefs)
        val durMax = BbplusSettings.getFeedDurationMax(prefs)
        val parts = mutableListOf<String>()
        if (likeMin > 0) parts.add("点赞≥$likeMin")
        if (likeMax > 0) parts.add("点赞≤$likeMax")
        if (durMin > 0) parts.add("时长≥${durMin}s")
        if (durMax > 0) parts.add("时长≤${durMax}s")
        return if (parts.isEmpty()) "未启用" else parts.joinToString("、")
    }

    private fun keywordFilterSummaryText(prefs: SharedPreferences): String {
        val titleCount = BbplusSettings.getFeedKeywordTitle(prefs).size
        val upCount = BbplusSettings.getFeedKeywordUp(prefs).size
        val catCount = BbplusSettings.getFeedKeywordCategory(prefs).size
        val reasonCount = BbplusSettings.getFeedKeywordReason(prefs).size
        val total = titleCount + upCount + catCount + reasonCount
        return if (total == 0) "未启用" else "已添加 $total 条关键词"
    }

    private fun settingRow(
        activity: Activity,
        p: Palette,
        title: String,
        summaryView: TextView,
        onClick: () -> Unit,
    ): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 14f), dp(activity, 13f), dp(activity, 14f), dp(activity, 13f))
            isClickable = true
            isFocusable = true
        }
        rippleBackground(activity, p, 12f)?.let { id ->
            runCatching { row.setBackgroundResource(id as Int) }
        }
        row.setOnClickListener { onClick() }
        val textCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(TextView(activity).apply {
            text = title
            textSize = 15f
            setTextColor(p.primary)
        })
        summaryView.setPadding(0, dp(activity, 2f), dp(activity, 12f), 0)
        textCol.addView(summaryView)
        row.addView(textCol)
        row.addView(TextView(activity).apply {
            text = "›"
            textSize = 22f
            setTextColor(p.secondary)
            setPadding(dp(activity, 8f), 0, 0, dp(activity, 2f))
        })
        return row
    }

    private fun showFilterModePicker(
        runtime: BbplusRuntime,
        activity: Activity,
        p: Palette,
        onSaved: () -> Unit,
    ) {
        val prefs = runtime.prefs
        val currentMode = BbplusSettings.getFeedFilterMode(prefs)
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(p.card, 24f)
            setPadding(dp(activity, 20f), dp(activity, 24f), dp(activity, 20f), dp(activity, 16f))
        }

        root.addView(TextView(activity).apply {
            text = "过滤模式"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(p.primary)
            setPadding(dp(activity, 2f), 0, 0, 0)
        })
        root.addView(TextView(activity).apply {
            text = "选择过滤逻辑方向。"
            textSize = 12f
            setTextColor(p.secondary)
            setPadding(dp(activity, 2f), dp(activity, 4f), 0, dp(activity, 8f))
        })

        val modes = listOf("blacklist" to "黑名单（排除匹配项）" to "默认展示所有，过滤掉符合条件的视频",
            "whitelist" to "白名单（仅展示匹配项）" to "默认隐藏所有，仅展示符合条件的视频")

        var selected = currentMode
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        modes.forEachIndexed { i, (pair, desc) ->
            val (value, label) = pair
            if (i > 0) list.addView(divider(activity, p))
            val itemRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(activity, 4f), dp(activity, 12f), dp(activity, 4f), dp(activity, 12f))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    selected = value
                    prefs.edit().putString(BbplusSettings.KEY_FEED_FILTER_MODE, value).apply()
                    onSaved()
                    dialog.dismiss()
                }
            }
            val rb = android.widget.RadioButton(activity).apply {
                isChecked = value == selected
                buttonTintList = ColorStateList.valueOf(p.accent)
                isClickable = false
            }
            itemRow.addView(rb)
            val textCol = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            textCol.addView(TextView(activity).apply {
                text = label
                textSize = 15f
                setTextColor(p.primary)
            })
            textCol.addView(TextView(activity).apply {
                text = desc
                textSize = 12f
                setTextColor(p.secondary)
                setPadding(0, dp(activity, 2f), 0, 0)
            })
            itemRow.addView(textCol)
            list.addView(itemRow)
        }
        root.addView(scrollableSection(activity, list))

        dialog.setContentView(root)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(rounded(p.card, 24f))
            attributes = attributes.apply {
                width = (activity.resources.displayMetrics.widthPixels * 0.88f).toInt()
                height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }
    }

    private fun showBasicFilterPicker(
        runtime: BbplusRuntime,
        activity: Activity,
        p: Palette,
        onSaved: () -> Unit,
    ) {
        val prefs = runtime.prefs
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(p.card, 24f)
            setPadding(dp(activity, 20f), dp(activity, 24f), dp(activity, 20f), dp(activity, 16f))
        }

        root.addView(TextView(activity).apply {
            text = "基础过滤"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(p.primary)
            setPadding(dp(activity, 2f), 0, 0, 0)
        })

        val items = listOf(
            BbplusSettings.KEY_FEED_FILTER_AD to ("过滤广告" to "移除推广、商业合作内容"),
            BbplusSettings.KEY_FEED_FILTER_LIVE to ("过滤直播" to "移除直播推荐卡片"),
            BbplusSettings.KEY_FEED_FILTER_PICTURE to ("过滤图文" to "移除图文、小图推荐"),
            BbplusSettings.KEY_FEED_FILTER_BANNER to ("过滤Banner" to "移除顶部Banner广告"),
        )

        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        items.forEachIndexed { i, (key, pair) ->
            val (label, desc) = pair
            if (i > 0) list.addView(divider(activity, p))
            val cb = CheckBox(activity).apply {
                runCatching { buttonTintList = ColorStateList.valueOf(p.accent) }
                isChecked = prefs.getBoolean(key, key == BbplusSettings.KEY_FEED_FILTER_AD)
                setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(key, checked).apply() }
            }
            val itemRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(activity, 4f), dp(activity, 10f), dp(activity, 4f), dp(activity, 10f))
                isClickable = true
                isFocusable = true
                setOnClickListener { cb.toggle() }
            }
            val textCol = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            textCol.addView(TextView(activity).apply {
                text = label
                textSize = 15f
                setTextColor(p.primary)
            })
            textCol.addView(TextView(activity).apply {
                text = desc
                textSize = 12f
                setTextColor(p.secondary)
                setPadding(0, dp(activity, 2f), 0, 0)
            })
            itemRow.addView(cb)
            itemRow.addView(textCol)
            list.addView(itemRow)
        }
        root.addView(scrollableSection(activity, list))

        val buttonRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(activity, 16f), 0, 0)
        }
        buttonRow.addView(TextView(activity).apply {
            text = "确定"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            background = rounded(p.accent, 20f)
            setPadding(dp(activity, 28f), dp(activity, 10f), dp(activity, 28f), dp(activity, 10f))
            isClickable = true
            isFocusable = true
            setOnClickListener { onSaved(); dialog.dismiss() }
        })
        root.addView(buttonRow)

        dialog.setContentView(root)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(rounded(p.card, 24f))
            attributes = attributes.apply {
                width = (activity.resources.displayMetrics.widthPixels * 0.88f).toInt()
                height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }
    }

    private fun showDataFilterPicker(
        runtime: BbplusRuntime,
        activity: Activity,
        p: Palette,
        onSaved: () -> Unit,
    ) {
        val prefs = runtime.prefs
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(p.card, 24f)
            setPadding(dp(activity, 20f), dp(activity, 24f), dp(activity, 20f), dp(activity, 16f))
        }

        root.addView(TextView(activity).apply {
            text = "数据过滤"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(p.primary)
            setPadding(dp(activity, 2f), 0, 0, 0)
        })
        root.addView(TextView(activity).apply {
            text = "设置为 0 表示不启用该条件。"
            textSize = 12f
            setTextColor(p.secondary)
            setPadding(dp(activity, 2f), dp(activity, 4f), 0, dp(activity, 8f))
        })

        val fields = listOf(
            Triple(BbplusSettings.KEY_FEED_LIKE_MIN, "点赞数下限" to "0", "0"),
            Triple(BbplusSettings.KEY_FEED_LIKE_MAX, "点赞数上限" to "0", "0"),
            Triple(BbplusSettings.KEY_FEED_DURATION_MIN, "视频时长下限(秒)" to "0", "0"),
            Triple(BbplusSettings.KEY_FEED_DURATION_MAX, "视频时长上限(秒)" to "0", "0"),
        )

        val editTexts = mutableMapOf<String, android.widget.EditText>()
        val fieldsList = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        fields.forEachIndexed { i, (key, pair, default) ->
            val (fieldLabel, fieldHint) = pair
            if (i > 0) fieldsList.addView(divider(activity, p))
            val labelView = TextView(activity).apply {
                text = fieldLabel
                textSize = 14f
                setTextColor(p.primary)
                setPadding(dp(activity, 4f), dp(activity, 10f), 0, dp(activity, 4f))
            }
            fieldsList.addView(labelView)
            val current = when (key) {
                BbplusSettings.KEY_FEED_LIKE_MIN -> prefs.getLong(key, 0L).toString()
                BbplusSettings.KEY_FEED_LIKE_MAX -> prefs.getLong(key, 0L).toString()
                BbplusSettings.KEY_FEED_DURATION_MIN -> prefs.getInt(key, 0).toString()
                BbplusSettings.KEY_FEED_DURATION_MAX -> prefs.getInt(key, 0).toString()
                else -> default
            }
            val et = android.widget.EditText(activity).apply {
                setText(current)
                hint = fieldHint
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setTextColor(p.primary)
                setHintTextColor(p.secondary)
                textSize = 15f
                setPadding(dp(activity, 12f), dp(activity, 8f), dp(activity, 12f), dp(activity, 8f))
                background = rounded(p.buttonBg, 12f)
            }
            editTexts[key] = et
            fieldsList.addView(et)
        }
        root.addView(scrollableSection(activity, fieldsList))

        val buttonRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(activity, 16f), 0, 0)
        }
        buttonRow.addView(TextView(activity).apply {
            text = "取消"
            textSize = 14f
            setTextColor(p.secondary)
            setPadding(dp(activity, 20f), dp(activity, 10f), dp(activity, 20f), dp(activity, 10f))
            isClickable = true
            isFocusable = true
            setOnClickListener { dialog.dismiss() }
        })
        buttonRow.addView(TextView(activity).apply {
            text = "保存"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            background = rounded(p.accent, 20f)
            setPadding(dp(activity, 28f), dp(activity, 10f), dp(activity, 28f), dp(activity, 10f))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val editor = prefs.edit()
                editTexts.forEach { (key, et) ->
                    val value = et.text.toString().toLongOrNull() ?: 0L
                    when (key) {
                        BbplusSettings.KEY_FEED_LIKE_MIN, BbplusSettings.KEY_FEED_LIKE_MAX ->
                            editor.putLong(key, value)
                        else -> editor.putInt(key, value.toInt())
                    }
                }
                editor.apply()
                onSaved()
                dialog.dismiss()
            }
        })
        root.addView(buttonRow)

        dialog.setContentView(root)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(rounded(p.card, 24f))
            attributes = attributes.apply {
                width = (activity.resources.displayMetrics.widthPixels * 0.88f).toInt()
                height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }
    }

    private fun showKeywordFilterPicker(
        runtime: BbplusRuntime,
        activity: Activity,
        p: Palette,
        onSaved: () -> Unit,
    ) {
        val prefs = runtime.prefs
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(p.card, 24f)
            setPadding(dp(activity, 20f), dp(activity, 24f), dp(activity, 20f), dp(activity, 16f))
        }

        root.addView(TextView(activity).apply {
            text = "关键词过滤"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(p.primary)
            setPadding(dp(activity, 2f), 0, 0, 0)
        })

        val keywordGroups = listOf(
            Triple(BbplusSettings.KEY_FEED_KEYWORD_TITLE, "标题关键词", "过滤标题含指定关键词的视频"),
            Triple(BbplusSettings.KEY_FEED_KEYWORD_UP, "UP主过滤", "过滤指定UP主的视频"),
            Triple(BbplusSettings.KEY_FEED_KEYWORD_CATEGORY, "分类过滤", "过滤指定分区的视频"),
            Triple(BbplusSettings.KEY_FEED_KEYWORD_REASON, "推荐理由", "过滤指定推荐理由的视频"),
        )

        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        keywordGroups.forEachIndexed { i, (key, label, desc) ->
            if (i > 0) content.addView(divider(activity, p))
            val count = prefs.getStringSet(key, null)?.size ?: 0
            val summaryView = TextView(activity).apply {
                text = if (count > 0) "已添加 $count 条" else "未添加"
                textSize = 12f
                setTextColor(if (count > 0) p.accent else p.secondary)
            }
            content.addView(settingRow(activity, p, label, summaryView) {
                showKeywordEditor(runtime, activity, p, key, label) {
                    summaryView.text = if (prefs.getStringSet(key, null)?.size ?: 0 > 0)
                        "已添加 ${prefs.getStringSet(key, null)?.size} 条" else "未添加"
                    onSaved()
                }
            })
        }

        content.addView(divider(activity, p))
        content.addView(switchRow(activity, prefs, p, BbplusSettings.KEY_FEED_KEYWORD_REGEX, "正则模式", "关键词支持正则表达式匹配"))
        root.addView(scrollableSection(activity, content))

        val buttonRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(activity, 16f), 0, 0)
        }
        buttonRow.addView(TextView(activity).apply {
            text = "确定"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            background = rounded(p.accent, 20f)
            setPadding(dp(activity, 28f), dp(activity, 10f), dp(activity, 28f), dp(activity, 10f))
            isClickable = true
            isFocusable = true
            setOnClickListener { onSaved(); dialog.dismiss() }
        })
        root.addView(buttonRow)

        dialog.setContentView(root)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(rounded(p.card, 24f))
            attributes = attributes.apply {
                width = (activity.resources.displayMetrics.widthPixels * 0.88f).toInt()
                height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }
    }

    private fun showKeywordEditor(
        runtime: BbplusRuntime,
        activity: Activity,
        p: Palette,
        prefKey: String,
        title: String,
        onSaved: () -> Unit,
    ) {
        val prefs = runtime.prefs
        val current = HashSet(prefs.getStringSet(prefKey, emptySet()) ?: emptySet())
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(p.card, 24f)
            setPadding(dp(activity, 20f), dp(activity, 24f), dp(activity, 20f), dp(activity, 16f))
        }

        root.addView(TextView(activity).apply {
            text = title
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(p.primary)
            setPadding(dp(activity, 2f), 0, 0, 0)
        })

        val inputLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(activity, 12f), 0, 0)
        }
        val input = android.widget.EditText(activity).apply {
            hint = "输入关键词"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setTextColor(p.primary)
            setHintTextColor(p.secondary)
            textSize = 14f
            setPadding(dp(activity, 12f), dp(activity, 8f), dp(activity, 12f), dp(activity, 8f))
            background = rounded(p.buttonBg, 12f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        inputLayout.addView(input)
        root.addView(inputLayout)

        val listContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(activity, 8f), 0, 0)
        }

        val addBtn = TextView(activity).apply {
            text = "添加"
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            background = rounded(p.accent, 16f)
            setPadding(dp(activity, 16f), dp(activity, 8f), dp(activity, 16f), dp(activity, 8f))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val keyword = input.text.toString().trim()
                if (keyword.isNotEmpty()) {
                    current.add(keyword)
                    prefs.edit().putStringSet(prefKey, current).apply()
                    input.text.clear()
                    refreshKeywordList(listContainer, current, prefs, prefKey, p, activity)
                    onSaved()
                }
            }
        }
        inputLayout.addView(addBtn)

        root.addView(scrollableSection(activity, listContainer))
        refreshKeywordList(listContainer, current, prefs, prefKey, p, activity)

        val buttonRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(activity, 16f), 0, 0)
        }
        buttonRow.addView(TextView(activity).apply {
            text = "完成"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            background = rounded(p.accent, 20f)
            setPadding(dp(activity, 28f), dp(activity, 10f), dp(activity, 28f), dp(activity, 10f))
            isClickable = true
            isFocusable = true
            setOnClickListener { onSaved(); dialog.dismiss() }
        })
        root.addView(buttonRow)

        dialog.setContentView(root)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(rounded(p.card, 24f))
            attributes = attributes.apply {
                width = (activity.resources.displayMetrics.widthPixels * 0.88f).toInt()
                height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }
    }

    private fun refreshKeywordList(
        container: LinearLayout,
        keywords: MutableSet<String>,
        prefs: SharedPreferences,
        prefKey: String,
        p: Palette,
        activity: Activity,
    ) {
        container.removeAllViews()
        if (keywords.isEmpty()) {
            container.addView(TextView(activity).apply {
                text = "暂无关键词，请在上方输入框添加"
                textSize = 12f
                setTextColor(p.secondary)
                setPadding(dp(activity, 4f), dp(activity, 8f), 0, 0)
            })
            return
        }
        keywords.forEachIndexed { i, keyword ->
            if (i > 0) container.addView(divider(activity, p))
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(activity, 4f), dp(activity, 6f), dp(activity, 4f), dp(activity, 6f))
            }
            row.addView(TextView(activity).apply {
                text = keyword
                textSize = 14f
                setTextColor(p.primary)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(activity).apply {
                text = "✕"
                textSize = 16f
                setTextColor(p.secondary)
                setPadding(dp(activity, 8f), dp(activity, 4f), dp(activity, 4f), dp(activity, 4f))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    keywords.remove(keyword)
                    prefs.edit().putStringSet(prefKey, keywords).apply()
                    refreshKeywordList(container, keywords, prefs, prefKey, p, activity)
                }
            })
            container.addView(row)
        }
    }

    private fun showNumberPicker(
        activity: Activity,
        prefs: SharedPreferences,
        p: Palette,
        title: String,
        prefKey: String,
        default: Int,
        min: Int,
        max: Int,
        onSaved: () -> Unit,
    ) {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(p.card, 24f)
            setPadding(dp(activity, 20f), dp(activity, 24f), dp(activity, 20f), dp(activity, 16f))
        }

        root.addView(TextView(activity).apply {
            text = title
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(p.primary)
            setPadding(dp(activity, 2f), 0, 0, 0)
        })
        root.addView(TextView(activity).apply {
            text = "范围: $min ~ $max"
            textSize = 12f
            setTextColor(p.secondary)
            setPadding(dp(activity, 2f), dp(activity, 4f), 0, dp(activity, 8f))
        })

        val et = android.widget.EditText(activity).apply {
            setText(default.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setTextColor(p.primary)
            textSize = 15f
            setPadding(dp(activity, 12f), dp(activity, 8f), dp(activity, 12f), dp(activity, 8f))
            background = rounded(p.buttonBg, 12f)
        }
        root.addView(et)

        val buttonRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(activity, 16f), 0, 0)
        }
        buttonRow.addView(TextView(activity).apply {
            text = "取消"
            textSize = 14f
            setTextColor(p.secondary)
            setPadding(dp(activity, 20f), dp(activity, 10f), dp(activity, 20f), dp(activity, 10f))
            isClickable = true
            isFocusable = true
            setOnClickListener { dialog.dismiss() }
        })
        buttonRow.addView(TextView(activity).apply {
            text = "保存"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            background = rounded(p.accent, 20f)
            setPadding(dp(activity, 28f), dp(activity, 10f), dp(activity, 28f), dp(activity, 10f))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val value = et.text.toString().toIntOrNull() ?: default
                val clamped = value.coerceIn(min, max)
                prefs.edit().putInt(prefKey, clamped).apply()
                onSaved()
                dialog.dismiss()
            }
        })
        root.addView(buttonRow)

        dialog.setContentView(root)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(rounded(p.card, 24f))
            attributes = attributes.apply {
                width = (activity.resources.displayMetrics.widthPixels * 0.88f).toInt()
                height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }
    }

    private fun showPurifyPicker(
        runtime: BbplusRuntime,
        activity: Activity,
        p: Palette,
        onSaved: () -> Unit,
    ) {
        val prefs = runtime.prefs
        val tempSelected = HashSet(BbplusSettings.getPurifyLivePopups(prefs))
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(p.card, 24f)
            setPadding(dp(activity, 20f), dp(activity, 24f), dp(activity, 20f), dp(activity, 16f))
        }

        root.addView(TextView(activity).apply {
            text = "净化直播间"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(p.primary)
            setPadding(dp(activity, 2f), 0, 0, 0)
        })
        root.addView(TextView(activity).apply {
            text = "勾选要在直播间隐藏的内容，留空则不启用。"
            textSize = 12f
            setTextColor(p.secondary)
            setPadding(dp(activity, 2f), dp(activity, 4f), 0, dp(activity, 8f))
        })

        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        val checkboxes = mutableMapOf<String, CheckBox>()
        BbplusSettings.purifyLabels.forEachIndexed { i, (value, label) ->
            if (i > 0) list.addView(divider(activity, p))
            val cb = CheckBox(activity)
            runCatching { cb.buttonTintList = ColorStateList.valueOf(p.accent) }
            cb.isChecked = value in tempSelected
            checkboxes[value] = cb
            val desc = when (value) {
                BbplusSettings.PURIFY_SHOPPING_CARD -> "购物袋与商品讲解卡片"
                BbplusSettings.PURIFY_SHOPPING_SELECTED -> "精选商品推荐浮窗"
                BbplusSettings.PURIFY_FOLLOW -> "「关注主播」引导卡片"
                BbplusSettings.PURIFY_RESERVE -> "直播预约提示卡片"
                BbplusSettings.PURIFY_GIFT -> "礼物 / 投喂支持浮窗"
                BbplusSettings.PURIFY_BANNER -> "顶部滚动公告横幅"
                BbplusSettings.PURIFY_TASK -> "电池任务与活动浮窗"
                BbplusSettings.PURIFY_GOTO_BUY -> "「正在去买」跳转提示"
                BbplusSettings.PURIFY_POPULARITY_TICKET -> "直播间人气票入口"
                BbplusSettings.PURIFY_SHOPPING_CART_BTN -> "直播间购物车按钮"
                BbplusSettings.PURIFY_PLAY_WITH_ME -> "直播间帮我玩按钮"
                BbplusSettings.PURIFY_PK_WIDGET -> "直播间PK贴片"
                else -> ""
            }
            val itemRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(activity, 4f), dp(activity, 10f), dp(activity, 4f), dp(activity, 10f))
                isClickable = true
                isFocusable = true
                setOnClickListener { cb.toggle() }
            }
            val textCol = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            textCol.addView(TextView(activity).apply {
                text = label
                textSize = 15f
                setTextColor(p.primary)
            })
            textCol.addView(TextView(activity).apply {
                text = desc
                textSize = 12f
                setTextColor(p.secondary)
                setPadding(0, dp(activity, 2f), 0, 0)
            })
            itemRow.addView(cb)
            itemRow.addView(textCol)
            list.addView(itemRow)
        }

        root.addView(scrollableSection(activity, list))

        val buttonRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(activity, 16f), 0, 0)
        }

        buttonRow.addView(TextView(activity).apply {
            text = "取消"
            textSize = 14f
            setTextColor(p.secondary)
            setPadding(dp(activity, 20f), dp(activity, 10f), dp(activity, 20f), dp(activity, 10f))
            isClickable = true
            isFocusable = true
            rippleBackground(activity, p, 20f)?.let { id ->
                runCatching { setBackgroundResource(id as Int) }
            }
            setOnClickListener { dialog.dismiss() }
        })

        val saveBtn = TextView(activity).apply {
            text = "保存"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            background = rounded(p.accent, 20f)
            setPadding(dp(activity, 28f), dp(activity, 10f), dp(activity, 28f), dp(activity, 10f))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val selected = checkboxes.filterValues { it.isChecked }.keys.toSet()
                prefs.edit().putStringSet(BbplusSettings.KEY_PURIFY_LIVE_POPUPS, selected).apply()
                onSaved()
                Toast.makeText(activity, "已保存，重新进入直播间后生效", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        buttonRow.addView(saveBtn)
        root.addView(buttonRow)

        dialog.setContentView(root)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(rounded(p.card, 24f))
            attributes = attributes.apply {
                width = (activity.resources.displayMetrics.widthPixels * 0.88f).toInt()
                height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }
    }

    private const val ENTRY_KEY = "bbplus_settings_entry"
    private const val ENTRY_TITLE = "BBplus 设置"
}
