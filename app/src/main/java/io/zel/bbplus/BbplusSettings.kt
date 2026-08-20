package io.zel.bbplus

import android.content.SharedPreferences

object BbplusSettings {
    const val PREFS_NAME = "bbplus_prefs"

    const val KEY_BLOCK_CLOUD_TV_POPUP = "block_cloud_tv_popup"
    const val KEY_BLOCK_MENTION_GAME = "block_mention_game"
    const val KEY_BLOCK_PLAYER_WATERMARK = "block_player_watermark"
    const val KEY_SHARE_TO_OVERFLOW = "share_to_overflow"

    fun isBlockCloudTvPopup(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_BLOCK_CLOUD_TV_POPUP, true)

    fun isBlockMentionGame(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_BLOCK_MENTION_GAME, true)

    fun isBlockPlayerWatermark(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_BLOCK_PLAYER_WATERMARK, true)

    fun isShareToOverflow(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_SHARE_TO_OVERFLOW, true)
}