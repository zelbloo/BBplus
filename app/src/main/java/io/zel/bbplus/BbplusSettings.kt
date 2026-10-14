package io.zel.bbplus

import android.content.SharedPreferences

object BbplusSettings {
    const val PREFS_NAME = "bbplus_prefs"

    const val KEY_BLOCK_CLOUD_TV_POPUP = "block_cloud_tv_popup"
    const val KEY_BLOCK_MENTION_GAME = "block_mention_game"
    const val KEY_BLOCK_DYNAMIC_GAME_CARD = "block_dynamic_game_card"
    const val KEY_BLOCK_PLAYER_WATERMARK = "block_player_watermark"
    const val KEY_SHARE_TO_OVERFLOW = "share_to_overflow"
    const val KEY_LIVE_SENSOR_ROTATION = "live_sensor_rotation"
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

    fun isBlockDynamicGameCard(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_BLOCK_DYNAMIC_GAME_CARD, true)

    fun isBlockPlayerWatermark(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_BLOCK_PLAYER_WATERMARK, false)

    fun isShareToOverflow(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_SHARE_TO_OVERFLOW, true)

    fun isLiveSensorRotation(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_LIVE_SENSOR_ROTATION, true)

    fun getPurifyLivePopups(prefs: SharedPreferences): Set<String> =
        prefs.getStringSet(KEY_PURIFY_LIVE_POPUPS, emptySet()) ?: emptySet()

    // ==================== Home Feed Filter ====================

    const val KEY_FEED_FILTER_ENABLED = "feed_filter_enabled"
    const val KEY_FEED_FILTER_MODE = "feed_filter_mode" // "blacklist" or "whitelist"
    const val KEY_FEED_BYPASS_FOLLOWED = "feed_bypass_followed"

    const val KEY_FEED_FILTER_AD = "feed_filter_ad"
    const val KEY_FEED_FILTER_LIVE = "feed_filter_live"
    const val KEY_FEED_FILTER_PICTURE = "feed_filter_picture"
    const val KEY_FEED_FILTER_BANNER = "feed_filter_banner"

    const val KEY_FEED_LIKE_MIN = "feed_like_min"
    const val KEY_FEED_LIKE_MAX = "feed_like_max"
    const val KEY_FEED_DURATION_MIN = "feed_duration_min"
    const val KEY_FEED_DURATION_MAX = "feed_duration_max"

    const val KEY_FEED_KEYWORD_TITLE = "feed_keyword_title"
    const val KEY_FEED_KEYWORD_UP = "feed_keyword_up"
    const val KEY_FEED_KEYWORD_CATEGORY = "feed_keyword_category"
    const val KEY_FEED_KEYWORD_REASON = "feed_keyword_reason"
    const val KEY_FEED_KEYWORD_REGEX = "feed_keyword_regex"

    // ==================== Home Feed Size ====================

    const val KEY_FEED_SIZE_ENHANCE = "feed_size_enhance"
    const val KEY_FEED_MAX_CARD_COUNT = "feed_max_card_count"
    const val KEY_FEED_AUTO_LOAD_MORE = "feed_auto_load_more"
    const val KEY_FEED_TARGET_COUNT = "feed_target_count"

    // ==================== Accessors ====================

    fun isFeedFilterEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_FEED_FILTER_ENABLED, false)

    fun getFeedFilterMode(prefs: SharedPreferences): String =
        prefs.getString(KEY_FEED_FILTER_MODE, "blacklist") ?: "blacklist"

    fun isFeedBypassFollowed(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_FEED_BYPASS_FOLLOWED, true)

    fun isFeedFilterAd(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_FEED_FILTER_AD, true)

    fun isFeedFilterLive(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_FEED_FILTER_LIVE, false)

    fun isFeedFilterPicture(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_FEED_FILTER_PICTURE, false)

    fun isFeedFilterBanner(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_FEED_FILTER_BANNER, false)

    fun getFeedLikeMin(prefs: SharedPreferences): Long =
        prefs.getLong(KEY_FEED_LIKE_MIN, 0L)

    fun getFeedLikeMax(prefs: SharedPreferences): Long =
        prefs.getLong(KEY_FEED_LIKE_MAX, 0L)

    fun getFeedDurationMin(prefs: SharedPreferences): Int =
        prefs.getInt(KEY_FEED_DURATION_MIN, 0)

    fun getFeedDurationMax(prefs: SharedPreferences): Int =
        prefs.getInt(KEY_FEED_DURATION_MAX, 0)

    fun getFeedKeywordTitle(prefs: SharedPreferences): Set<String> =
        prefs.getStringSet(KEY_FEED_KEYWORD_TITLE, emptySet()) ?: emptySet()

    fun getFeedKeywordUp(prefs: SharedPreferences): Set<String> =
        prefs.getStringSet(KEY_FEED_KEYWORD_UP, emptySet()) ?: emptySet()

    fun getFeedKeywordCategory(prefs: SharedPreferences): Set<String> =
        prefs.getStringSet(KEY_FEED_KEYWORD_CATEGORY, emptySet()) ?: emptySet()

    fun getFeedKeywordReason(prefs: SharedPreferences): Set<String> =
        prefs.getStringSet(KEY_FEED_KEYWORD_REASON, emptySet()) ?: emptySet()

    fun isFeedKeywordRegex(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_FEED_KEYWORD_REGEX, false)

    fun isFeedSizeEnhance(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_FEED_SIZE_ENHANCE, true)

    fun getFeedMaxCardCount(prefs: SharedPreferences): Int =
        prefs.getInt(KEY_FEED_MAX_CARD_COUNT, 1000)

    fun isFeedAutoLoadMore(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_FEED_AUTO_LOAD_MORE, true)

    fun getFeedTargetCount(prefs: SharedPreferences): Int =
        prefs.getInt(KEY_FEED_TARGET_COUNT, 30)
}