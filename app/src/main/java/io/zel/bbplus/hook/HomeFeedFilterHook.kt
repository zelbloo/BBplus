package io.zel.bbplus.hook

import io.github.libxposed.api.XposedInterface
import io.zel.bbplus.BbplusRuntime
import io.zel.bbplus.BbplusSettings
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.regex.Pattern

class HomeFeedFilterHook(private val runtime: BbplusRuntime) {

    fun startHook() {
        if (!BbplusSettings.isFeedFilterEnabled(runtime.prefs)) {
            runtime.log("HomeFeedFilterHook skipped: feature disabled")
            return
        }
        hookPegasusParser()
        runtime.log("HomeFeedFilterHook installed")
    }

    private fun hookPegasusParser() {
        val parserClass = loadClass("com.bilibili.pegasus.request.h") ?: run {
            runtime.log("HomeFeedFilterHook: parser class not found")
            return
        }
        val convertMethod = findMethod(parserClass, "convert", Any::class.java) ?: run {
            runtime.log("HomeFeedFilterHook: convert method not found")
            return
        }

        runtime.xposed.hook(convertMethod)
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
            .intercept { chain ->
                val result = chain.proceed()
                runCatching {
                    filterFeedItems(result)
                }.onFailure { runtime.log("HomeFeedFilterHook filter failed", it) }
                result
            }
        runtime.log("HomeFeedFilterHook: hooked ${parserClass.name}.convert")
    }

    private fun filterFeedItems(response: Any?) {
        if (response == null) return
        val prefs = runtime.prefs
        val mode = BbplusSettings.getFeedFilterMode(prefs)
        val bypassFollowed = BbplusSettings.isFeedBypassFollowed(prefs)
        val isWhitelist = mode == "whitelist"

        val dataField = findField(response.javaClass, "data") ?: return
        val pegasusResponse = runCatching { dataField.get(response) }.getOrNull() ?: return

        val itemsList = findItemsList(pegasusResponse) ?: return
        val iterator = itemsList.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (shouldRemoveItem(item, prefs, isWhitelist, bypassFollowed)) {
                iterator.remove()
            }
        }
    }

    private fun findItemsList(pegasusResponse: Any): MutableList<Any>? {
        // Try common field names for the items list
        for (fieldName in listOf("items", "items_", "f7149a")) {
            val field = findField(pegasusResponse.javaClass, fieldName) ?: continue
            val value = runCatching { field.get(pegasusResponse) }.getOrNull()
            if (value is MutableList<*>) {
                @Suppress("UNCHECKED_CAST")
                return value as? MutableList<Any>
            }
        }
        // Fallback: find first List-typed field
        var clazz: Class<*>? = pegasusResponse.javaClass
        while (clazz != null && clazz != Any::class.java) {
            for (field in clazz.declaredFields) {
                if (List::class.java.isAssignableFrom(field.type)) {
                    field.isAccessible = true
                    val value = runCatching { field.get(pegasusResponse) }.getOrNull()
                    if (value is MutableList<*>) {
                        @Suppress("UNCHECKED_CAST")
                        return value as? MutableList<Any>
                    }
                }
            }
            clazz = clazz.superclass
        }
        return null
    }

    private fun shouldRemoveItem(item: Any, prefs: android.content.SharedPreferences, isWhitelist: Boolean, bypassFollowed: Boolean): Boolean {
        // Bypass followed creators
        if (bypassFollowed && isFollowed(item)) return false

        val matches = doesItemMatchFilter(item, prefs)
        return if (isWhitelist) !matches else matches
    }

    private fun doesItemMatchFilter(item: Any, prefs: android.content.SharedPreferences): Boolean {
        // Card type filters
        val cardGoto = getFieldAsString(item, "cardGoto", "card_goto")
        val cardType = getFieldAsString(item, "cardType", "card_type")
        val goto = getFieldAsString(item, "goTo", "goto")

        if (BbplusSettings.isFeedFilterAd(prefs)) {
            if (cardGoto in AD_TYPES || cardType in AD_TYPES || goto in AD_TYPES) return true
        }
        if (BbplusSettings.isFeedFilterLive(prefs)) {
            if (cardGoto in LIVE_TYPES || cardType in LIVE_TYPES || goto in LIVE_TYPES) return true
        }
        if (BbplusSettings.isFeedFilterPicture(prefs)) {
            if (cardGoto in PICTURE_TYPES || cardType in PICTURE_TYPES || goto in PICTURE_TYPES) return true
        }
        if (BbplusSettings.isFeedFilterBanner(prefs)) {
            if (cardGoto in BANNER_TYPES || cardType in BANNER_TYPES || goto in BANNER_TYPES) return true
        }

        // Like count filter
        val likeMin = BbplusSettings.getFeedLikeMin(prefs)
        val likeMax = BbplusSettings.getFeedLikeMax(prefs)
        if (likeMin > 0 || likeMax > 0) {
            val likeCount = getLikeCount(item)
            if (likeMin > 0 && likeCount < likeMin) return true
            if (likeMax > 0 && likeCount > likeMax) return true
        }

        // Duration filter
        val durationMin = BbplusSettings.getFeedDurationMin(prefs)
        val durationMax = BbplusSettings.getFeedDurationMax(prefs)
        if (durationMin > 0 || durationMax > 0) {
            val duration = getDuration(item)
            if (durationMin > 0 && duration < durationMin) return true
            if (durationMax > 0 && duration > durationMax) return true
        }

        // Keyword filters
        val regexMode = BbplusSettings.isFeedKeywordRegex(prefs)

        val titleFilter = BbplusSettings.getFeedKeywordTitle(prefs)
        if (titleFilter.isNotEmpty()) {
            val title = getFieldAsString(item, "title", "title")
            if (title.isNotEmpty() && matchesKeywords(title, titleFilter, regexMode)) return true
        }

        val upFilter = BbplusSettings.getFeedKeywordUp(prefs)
        if (upFilter.isNotEmpty()) {
            val upName = getUpName(item)
            if (upName.isNotEmpty() && matchesKeywords(upName, upFilter, regexMode)) return true
        }

        val categoryFilter = BbplusSettings.getFeedKeywordCategory(prefs)
        if (categoryFilter.isNotEmpty()) {
            val category = getFieldFromNested(item, "args", "rname")
            if (category.isNotEmpty() && matchesKeywords(category, categoryFilter, regexMode)) return true
        }

        val reasonFilter = BbplusSettings.getFeedKeywordReason(prefs)
        if (reasonFilter.isNotEmpty()) {
            val reason = getRecommendReason(item)
            if (reason.isNotEmpty() && matchesKeywords(reason, reasonFilter, regexMode)) return true
        }

        return false
    }

    private fun isFollowed(item: Any): Boolean {
        // Check args.isFollow
        val argsIsFollow = getFieldFromNestedAsInt(item, "args", "isFollow")
        if (argsIsFollow == 1) return true
        // Check upArgs.selected
        val upArgsSelected = getFieldFromNestedAsInt(item, "upArgs", "selected")
        return upArgsSelected == 1
    }

    private fun getLikeCount(item: Any): Long {
        val likeButton = getNestedObject(item, "likeButton") ?: return 0
        return getFieldAsLong(likeButton, "count", "count")
    }

    private fun getDuration(item: Any): Int {
        val playerArgs = getNestedObject(item, "playerArgs") ?: return 0
        return getFieldAsInt(playerArgs, "fakeDuration", "duration")
    }

    private fun getUpName(item: Any): String {
        // Try args.upName first
        val fromArgs = getFieldFromNested(item, "args", "upName")
        if (fromArgs.isNotEmpty()) return fromArgs
        // Try upArgs.upName
        return getFieldFromNested(item, "upArgs", "upName")
    }

    private fun getRecommendReason(item: Any): String {
        val rcmdReason = getNestedObject(item, "rcmdReason") ?: return ""
        return getFieldAsString(rcmdReason, "text", "text")
    }

    private fun matchesKeywords(text: String, keywords: Set<String>, regexMode: Boolean): Boolean {
        if (regexMode) {
            return keywords.any { pattern ->
                runCatching { Pattern.matches(pattern, text) }.getOrDefault(false) ||
                    text.contains(pattern)
            }
        }
        return keywords.any { text.contains(it, ignoreCase = true) }
    }

    // ========== Reflection helpers ==========

    private fun loadClass(name: String): Class<*>? =
        runCatching { runtime.classLoader.loadClass(name) }.getOrNull()

    private fun findMethod(cls: Class<*>, name: String, vararg paramTypes: Class<*>): Method? {
        var current: Class<*>? = cls
        while (current != null && current != Any::class.java) {
            runCatching {
                return current.getDeclaredMethod(name, *paramTypes).apply { isAccessible = true }
            }
            current = current.superclass
        }
        return null
    }

    private fun findField(clazz: Class<*>, name: String): Field? {
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            runCatching { return current.getDeclaredField(name).apply { isAccessible = true } }
            current = current.superclass
        }
        return null
    }

    private fun getFieldAsString(obj: Any, fieldName1: String, fieldName2: String): String {
        val field = findField(obj.javaClass, fieldName1) ?: findField(obj.javaClass, fieldName2) ?: return ""
        val value = runCatching { field.get(obj) }.getOrNull() ?: return ""
        return value.toString()
    }

    private fun getFieldAsLong(obj: Any, fieldName1: String, fieldName2: String): Long {
        val field = findField(obj.javaClass, fieldName1) ?: findField(obj.javaClass, fieldName2) ?: return 0L
        val value = runCatching { field.get(obj) }.getOrNull() ?: return 0L
        return when (value) {
            is Number -> value.toLong()
            else -> 0L
        }
    }

    private fun getFieldAsInt(obj: Any, fieldName1: String, fieldName2: String): Int {
        val field = findField(obj.javaClass, fieldName1) ?: findField(obj.javaClass, fieldName2) ?: return 0
        val value = runCatching { field.get(obj) }.getOrNull() ?: return 0
        return when (value) {
            is Number -> value.toInt()
            else -> 0
        }
    }

    private fun getNestedObject(obj: Any, fieldName: String): Any? {
        val field = findField(obj.javaClass, fieldName) ?: return null
        return runCatching { field.get(obj) }.getOrNull()
    }

    private fun getFieldFromNested(obj: Any, parentField: String, childField: String): String {
        val parent = getNestedObject(obj, parentField) ?: return ""
        return getFieldAsString(parent, childField, childField)
    }

    private fun getFieldFromNestedAsInt(obj: Any, parentField: String, childField: String): Int {
        val parent = getNestedObject(obj, parentField) ?: return -1
        return getFieldAsInt(parent, childField, childField)
    }

    private companion object {
        val AD_TYPES = setOf("ad", "cm", "cm_v2", "small_cover_v2_ad", "large_cover_v9")
        val LIVE_TYPES = setOf("live", "live_room")
        val PICTURE_TYPES = setOf("picture", "vertical")
        val BANNER_TYPES = setOf("banner", "banner_v4")
    }
}
