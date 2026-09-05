package io.zel.bbplus

import android.content.SharedPreferences

object BbplusSettings {
    const val PREFS_NAME = "bbplus_prefs"

    const val KEY_BLOCK_CLOUD_TV_POPUP = "block_cloud_tv_popup"
    const val KEY_BLOCK_MENTION_GAME = "block_mention_game"
    const val KEY_BLOCK_PLAYER_WATERMARK = "block_player_watermark"
    const val KEY_SHARE_TO_OVERFLOW = "share_to_overflow"
    const val KEY_PURIFY_LIVE_POPUPS = "purify_live_popups"

    const val PURIFY_SHOPPING_CARD = "shoppingCard"
    const val PURIFY_SHOPPING_SELECTED = "shoppingSelected"
    const val PURIFY_FOLLOW = "follow"
    const val PURIFY_RESERVE = "reserve"
    const val PURIFY_GIFT = "gift"
    const val PURIFY_BANNER = "banner"
    const val PURIFY_TASK = "task"
    const val PURIFY_GOTO_BUY = "gotoBuy"
    const val PURIFY_POPULARITY_TICKET = "popularityTicket"
    const val PURIFY_SHOPPING_CART_BTN = "shoppingCartBtn"
    const val PURIFY_PLAY_WITH_ME = "playWithMe"
    const val PURIFY_PK_WIDGET = "pkWidget"

    val purifyLabels = listOf(
        PURIFY_SHOPPING_CARD to "购物卡片",
        PURIFY_SHOPPING_SELECTED to "购物精选",
        PURIFY_FOLLOW to "关注提醒",
        PURIFY_RESERVE to "直播预约",
        PURIFY_GIFT to "投喂支持",
        PURIFY_BANNER to "滚动横幅",
        PURIFY_TASK to "电池任务",
        PURIFY_GOTO_BUY to "正在去买",
        PURIFY_POPULARITY_TICKET to "隐藏人气票按钮",
        PURIFY_SHOPPING_CART_BTN to "隐藏购物车按钮",
        PURIFY_PLAY_WITH_ME to "隐藏帮我玩按钮",
        PURIFY_PK_WIDGET to "隐藏PK贴片",
    )

    fun isBlockCloudTvPopup(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_BLOCK_CLOUD_TV_POPUP, true)

    fun isBlockMentionGame(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_BLOCK_MENTION_GAME, true)

    fun isBlockPlayerWatermark(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_BLOCK_PLAYER_WATERMARK, false)

    fun isShareToOverflow(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_SHARE_TO_OVERFLOW, true)

    fun getPurifyLivePopups(prefs: SharedPreferences): Set<String> =
        prefs.getStringSet(KEY_PURIFY_LIVE_POPUPS, emptySet()) ?: emptySet()
}