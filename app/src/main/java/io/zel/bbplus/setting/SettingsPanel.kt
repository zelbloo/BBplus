package io.zel.bbplus.setting

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
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

    private fun showSettingsDialog(runtime: BbplusRuntime, activity: Activity) {
        val prefs = runtime.prefs
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(96, 64, 96, 64)
        }

        root.addView(TextView(activity).apply {
            text = "BBplus 设置"
            textSize = 20f
            setTextColor(0xFF1F1F1F.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        })

        root.addView(
            switchRow(
                activity = activity,
                prefs = prefs,
                key = BbplusSettings.KEY_BLOCK_CLOUD_TV_POPUP,
                title = "去除「云视听小电视」小弹窗",
                summary = "播放页开播前几秒左上角出现的弹窗",
            ),
        )
        root.addView(
            switchRow(
                activity = activity,
                prefs = prefs,
                key = BbplusSettings.KEY_BLOCK_MENTION_GAME,
                title = "去除「视频提及」游戏下载内容",
                summary = "视频详情页提及列表中的游戏推广卡片",
            ),
        )
        root.addView(
            switchRow(
                activity = activity,
                prefs = prefs,
                key = BbplusSettings.KEY_BLOCK_PLAYER_WATERMARK,
                title = "去除播放器水印",
                summary = "播放画面上的 bilibili + UP主名 半透明水印",
            ),
        )
        root.addView(
            switchRow(
                activity = activity,
                prefs = prefs,
                key = BbplusSettings.KEY_SHARE_TO_OVERFLOW,
                title = "分享按钮改为更多操作",
                summary = "详情页点分享直接弹出「更多」面板",
            ),
        )

        root.addView(TextView(activity).apply {
            text = "修改即时生效，弹窗/提及数据需重新加载后验证。"
            textSize = 11f
            setTextColor(0xFF9A9A9A.toInt())
            setPadding(0, 32, 0, 0)
        })

        dialog.setContentView(root)
        dialog.show()
    }

    private fun switchRow(
        activity: Activity,
        prefs: SharedPreferences,
        key: String,
        title: String,
        summary: String,
    ): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 48, 0, 48)
        }
        val textCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(TextView(activity).apply {
            text = title
            textSize = 16f
            setTextColor(0xFF1F1F1F.toInt())
        })
        textCol.addView(TextView(activity).apply {
            text = summary
            textSize = 12f
            setTextColor(0xFF8A8A8A.toInt())
        })
        row.addView(textCol)
        val switch = Switch(activity).apply {
            isChecked = prefs.getBoolean(key, true)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(key, checked).apply()
            }
        }
        row.addView(switch)
        return row
    }

    private const val ENTRY_KEY = "bbplus_settings_entry"
    private const val ENTRY_TITLE = "BBplus 设置"
    private const val ENTRY_SUMMARY = "云视听小电视弹窗 / 视频提及游戏下载 / 播放器水印 / 分享改更多 开关"
}
