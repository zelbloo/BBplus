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
        prefType.getMethod("setSummary", CharSequence::class.java).invoke(pref, ENTRY_SUMMARY)
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
            text = "净化直播间浮窗"
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
        if (selected.isEmpty()) return "未启用 · 点击选择要隐藏的浮窗"
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
            Triple(BbplusSettings.KEY_BLOCK_CLOUD_TV_POPUP, "去除云视听小电视弹窗", "开播瞬间左上角的「云视听小电视」推广小窗"),
            Triple(BbplusSettings.KEY_BLOCK_MENTION_GAME, "去除视频提及游戏卡片", "视频详情页「提及」列表中的游戏下载推广"),
            Triple(BbplusSettings.KEY_BLOCK_PLAYER_WATERMARK, "去除播放器水印", "播放画面上的 bilibili 与 UP 主名半透明水印"),
            Triple(BbplusSettings.KEY_SHARE_TO_OVERFLOW, "分享按钮改为更多操作", "详情页点「分享」直接打开「更多」操作面板"),
        )
        rows.forEachIndexed { i, (key, title, summary) ->
            generalCard.addView(switchRow(activity, prefs, p, key, title, summary))
            if (i < rows.lastIndex) generalCard.addView(divider(activity, p))
        }
        root.addView(generalCard)

        root.addView(sectionHeader(activity, p, "直播间"))

        val liveCard = card(activity, p)
        val liveRows = listOf(
            Triple(BbplusSettings.KEY_HIDE_POPULARITY_TICKET, "隐藏人气票按钮", "底部输入栏旁的「人气」快速送礼圆钮"),
            Triple(BbplusSettings.KEY_HIDE_PK_TASK_WIDGET, "隐藏PK赏金周赛贴片", "顶栏右侧活动贴片区（含礼物星球等活动卡）"),
        )
        liveRows.forEach { (key, title, summary) ->
            liveCard.addView(switchRow(activity, prefs, p, key, title, summary))
            liveCard.addView(divider(activity, p))
        }
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

        root.addView(TextView(activity).apply {
            text = "开关即时生效；直播间净化需重新进入直播间后生效。"
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
            text = "净化直播间浮窗"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(p.primary)
            setPadding(dp(activity, 2f), 0, 0, 0)
        })
        root.addView(TextView(activity).apply {
            text = "勾选要在直播间隐藏的浮窗类型，留空则不启用。"
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
        root.addView(list)

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
    private const val ENTRY_SUMMARY =
        "弹窗净化 / 视频提及游戏 / 播放器水印 / 分享重定向 / 直播间浮窗净化"
}
