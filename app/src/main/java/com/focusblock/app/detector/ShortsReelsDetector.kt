package com.focusblock.app.detector

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

/**
 * Result data class specifying the type of distraction detected.
 */
data class DetectionResult(
    val isDistraction: Boolean,
    val isTabSelected: Boolean = false,
    val tabType: String = ""
)

/**
 * Universal, high-precision detector for Short-form video feeds across:
 * - Instagram (Reels tab & Fullscreen Clips Viewer only; Stories, Feed, and DMs guaranteed safe)
 * - Snapchat (Spotlight tab & Fullscreen Spotlight viewer only; Camera, Chats, Stories safe)
 * - YouTube & YouTube ReVanced (Shorts tab & Fullscreen Shorts player only; Normal videos & Home safe)
 * - Facebook & FB Lite (Reels tab & Shorts viewer only; Normal posts & feed safe)
 */
class ShortsReelsDetector {

    companion object {
        const val PACKAGE_YOUTUBE = "com.google.android.youtube"
        const val PACKAGE_YOUTUBE_REVANCED = "app.revanced.android.youtube"
        const val PACKAGE_INSTAGRAM = "com.instagram.android"
        const val PACKAGE_SNAPCHAT = "com.snapchat.android"
        const val PACKAGE_FACEBOOK = "com.facebook.katana"
        const val PACKAGE_FACEBOOK_LITE = "com.facebook.lite"

        val SUPPORTED_PACKAGES = setOf(
            PACKAGE_YOUTUBE,
            PACKAGE_YOUTUBE_REVANCED,
            PACKAGE_INSTAGRAM,
            PACKAGE_SNAPCHAT,
            PACKAGE_FACEBOOK,
            PACKAGE_FACEBOOK_LITE
        )

        private const val MAX_NODES_TO_SCAN = 800
        private const val MAX_SCAN_DEPTH = 35

        // ==================== INSTAGRAM SIGNATURES ====================
        // View IDs that strictly appear ONLY in the fullscreen Reels/Clips viewer
        private val IG_CLIPS_VIEWER_IDS = listOf(
            "com.instagram.android:id/clips_viewer_view_pager",
            "com.instagram.android:id/clips_video_container",
            "com.instagram.android:id/clips_viewer_root",
            "com.instagram.android:id/clips_viewer_media_info_layout",
            "com.instagram.android:id/clips_ufi_container",
            "com.instagram.android:id/clips_viewer_fragment",
            "com.instagram.android:id/clips_item_container",
            "com.instagram.android:id/clips_swipe_refresh_layout"
        )

        private val IG_CLIPS_ID_KEYWORDS = setOf(
            "clips_viewer_view_pager",
            "clips_video_container",
            "clips_viewer_root",
            "clips_viewer_media_info_layout",
            "clips_ufi_container",
            "clips_viewer_fragment",
            "clips_item_container",
            "clips_swipe_refresh_layout",
            "clips_action_sheet"
        )

        // Strict whitelist for Instagram: Stories, Direct Messages, Main Feed Header/Search
        private val IG_WHITELIST_KEYWORDS = setOf(
            "story_viewer_fragment_container",
            "story_viewer_container",
            "reel_viewer_progress_bar",
            "stories_tray",
            "reel_viewer_avatar",
            "story_comment_composer_container",
            "reel_viewer_toolbar",
            "direct_thread_feed",
            "direct_inbox",
            "direct_thread_fragment",
            "message_composer_container",
            "direct_expiring_media_viewer"
        )

        // ==================== SNAPCHAT SIGNATURES ====================
        private val SNAP_SPOTLIGHT_FULL_IDS = listOf(
            "com.snapchat.android:id/spotlight_carousel",
            "com.snapchat.android:id/spotlight_fullscreen",
            "com.snapchat.android:id/spotlight_player",
            "com.snapchat.android:id/spotlight_story_player",
            "com.snapchat.android:id/spotlight_swipe_layer",
            "com.snapchat.android:id/spotlight_container",
            "com.snapchat.android:id/spotlight_video",
            "com.snapchat.android:id/spotlight_overlay",
            "com.snapchat.android:id/spotlight_fragment",
            "com.snapchat.android:id/spotlight_unified_carousel",
            "com.snapchat.android:id/spotlight_tab"
        )

        private val SNAP_SPOTLIGHT_ID_KEYWORDS = setOf(
            "spotlight_carousel",
            "spotlight_fullscreen",
            "spotlight_player",
            "spotlight_story_player",
            "spotlight_swipe_layer",
            "spotlight_container",
            "spotlight_video",
            "spotlight_overlay",
            "spotlight_fragment",
            "spotlight_unified_carousel",
            "spotlight_tab"
        )

        private val SNAP_WHITELIST_KEYWORDS = setOf(
            "chat_fragment",
            "friend_feed",
            "message_input",
            "camera_fragment",
            "camera_preview",
            "capture_button",
            "map_fragment",
            "map_view"
        )

        // ==================== YOUTUBE SIGNATURES ====================
        private val YT_SHORTS_FULL_IDS = listOf(
            "com.google.android.youtube:id/reel_recycler",
            "com.google.android.youtube:id/reel_player_page_view",
            "com.google.android.youtube:id/reel_watch_fragment_container",
            "com.google.android.youtube:id/reel_watch_fragment_root",
            "com.google.android.youtube:id/reel_watch_pager",
            "com.google.android.youtube:id/reel_video_player",
            "com.google.android.youtube:id/reel_content_root",
            "com.google.android.youtube:id/reel_player_view_stub",
            "com.google.android.youtube:id/shorts_container",
            "com.google.android.youtube:id/shorts_player"
        )

        private val YT_SHORTS_ID_KEYWORDS = setOf(
            "reel_recycler",
            "reel_player_page_view",
            "reel_watch_fragment_container",
            "reel_watch_fragment_root",
            "reel_watch_pager",
            "reel_video_player",
            "reel_content_root",
            "reel_player_view_stub",
            "shorts_container",
            "shorts_player"
        )

        private val YT_NORMAL_ID_KEYWORDS = setOf(
            "time_bar",
            "youtube_controls_overlay",
            "watch_player",
            "player_fragment_container",
            "player_view",
            "results",
            "search_results_editor",
            "search_edit_text",
            "home_page"
        )

        // ==================== FACEBOOK SIGNATURES ====================
        private val FB_REELS_ID_KEYWORDS = setOf(
            "fb_shorts_container",
            "fb_shorts_viewer_fragment",
            "fb_shorts_viewer_page",
            "reels_tab",
            "fb_shorts_video_container"
        )
    }

    /**
     * Evaluates whether the active window is displaying a Short, Reel, or Spotlight feed.
     */
    fun detectDistraction(
        packageName: String?,
        currentActivity: String?,
        rootNode: AccessibilityNodeInfo?,
        event: AccessibilityEvent?
    ): DetectionResult {
        if (packageName == null || rootNode == null) return DetectionResult(false)

        return when (packageName) {
            PACKAGE_INSTAGRAM -> evaluateInstagram(currentActivity, rootNode, event)
            PACKAGE_SNAPCHAT -> evaluateSnapchat(currentActivity, rootNode, event)
            PACKAGE_YOUTUBE, PACKAGE_YOUTUBE_REVANCED -> evaluateYouTube(currentActivity, rootNode, event)
            PACKAGE_FACEBOOK, PACKAGE_FACEBOOK_LITE -> evaluateFacebook(currentActivity, rootNode, event)
            else -> DetectionResult(false)
        }
    }

    // ==================== INSTAGRAM EVALUATION ====================
    private fun evaluateInstagram(
        currentActivity: String?,
        rootNode: AccessibilityNodeInfo,
        event: AccessibilityEvent?
    ): DetectionResult {
        // Step 1: NEVER block Stories (ReelViewerActivity in Instagram is Stories!)
        if (currentActivity != null && currentActivity.contains("ReelViewerActivity", ignoreCase = true)) {
            return DetectionResult(false)
        }

        // Fullscreen clips viewer activity
        if (currentActivity != null && currentActivity.contains("ClipsViewerActivity", ignoreCase = true)) {
            return DetectionResult(isDistraction = true, isTabSelected = false)
        }

        // Step 2: Check event for direct Reels tab selection
        if (event != null) {
            val eventDesc = event.contentDescription?.toString() ?: ""
            val eventText = event.text.joinToString(" ")
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_SELECTED ||
                event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED
            ) {
                if (eventDesc.equals("Reels", ignoreCase = true) ||
                    eventDesc.startsWith("Reels,", ignoreCase = true) ||
                    eventDesc.contains("Reels tab", ignoreCase = true) ||
                    eventText.equals("Reels", ignoreCase = true)
                ) {
                    return DetectionResult(isDistraction = true, isTabSelected = true, tabType = "reels")
                }
            }
        }

        // Step 3: Fast Native View ID Lookups for Clips Viewer
        for (fullId in IG_CLIPS_VIEWER_IDS) {
            try {
                val nodes = rootNode.findAccessibilityNodeInfosByViewId(fullId)
                if (!nodes.isNullOrEmpty()) {
                    return DetectionResult(isDistraction = true, isTabSelected = false)
                }
            } catch (_: Exception) {
            }
        }

        // Step 4: BFS Hierarchy Inspection
        var isReelsTabSelected = false
        var isClipsViewerFound = false
        var isWhitelistedState = false

        traverseHierarchy(rootNode) { node ->
            val viewId = node.viewIdResourceName
            val desc = node.contentDescription?.toString() ?: ""
            val text = node.text?.toString() ?: ""

            if (viewId != null) {
                val cleanId = viewId.substringAfter(":id/").lowercase()

                // If in Story viewer or DM conversation, whitelist immediately
                if (IG_WHITELIST_KEYWORDS.any { cleanId.contains(it) }) {
                    isWhitelistedState = true
                    return@traverseHierarchy false
                }

                if (IG_CLIPS_ID_KEYWORDS.any { cleanId.contains(it) }) {
                    isClipsViewerFound = true
                }
            }

            // Bottom Navigation: ONLY trigger if Reels tab is actively SELECTED
            if (desc.isNotEmpty()) {
                val cleanDesc = desc.trim()
                if ((cleanDesc.equals("Reels", ignoreCase = true) ||
                     cleanDesc.startsWith("Reels,", ignoreCase = true) ||
                     cleanDesc.contains("Reels, tab", ignoreCase = true) ||
                     cleanDesc.contains("Reels tab", ignoreCase = true)) &&
                    node.isSelected
                ) {
                    isReelsTabSelected = true
                }
            }

            if (text.isNotEmpty() && text.equals("Reels", ignoreCase = true) && node.isSelected) {
                isReelsTabSelected = true
            }

            true
        }

        if (isWhitelistedState) {
            return DetectionResult(false)
        }

        if (isReelsTabSelected) {
            return DetectionResult(isDistraction = true, isTabSelected = true, tabType = "reels")
        }

        if (isClipsViewerFound) {
            return DetectionResult(isDistraction = true, isTabSelected = false)
        }

        return DetectionResult(false)
    }

    // ==================== SNAPCHAT EVALUATION ====================
    private fun evaluateSnapchat(
        currentActivity: String?,
        rootNode: AccessibilityNodeInfo,
        event: AccessibilityEvent?
    ): DetectionResult {
        if (currentActivity != null && currentActivity.contains("Spotlight", ignoreCase = true)) {
            return DetectionResult(isDistraction = true, isTabSelected = false)
        }

        if (event != null) {
            val eventDesc = event.contentDescription?.toString() ?: ""
            val eventText = event.text.joinToString(" ")
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_SELECTED ||
                event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED
            ) {
                if (eventDesc.equals("Spotlight", ignoreCase = true) ||
                    eventDesc.startsWith("Spotlight,", ignoreCase = true) ||
                    eventText.equals("Spotlight", ignoreCase = true)
                ) {
                    return DetectionResult(isDistraction = true, isTabSelected = true, tabType = "spotlight")
                }
            }
        }

        for (fullId in SNAP_SPOTLIGHT_FULL_IDS) {
            try {
                val nodes = rootNode.findAccessibilityNodeInfosByViewId(fullId)
                if (!nodes.isNullOrEmpty()) {
                    return DetectionResult(isDistraction = true, isTabSelected = false)
                }
            } catch (_: Exception) {
            }
        }

        var isSpotlightTabSelected = false
        var isSpotlightViewerFound = false
        var isWhitelistedState = false

        traverseHierarchy(rootNode) { node ->
            val viewId = node.viewIdResourceName
            val desc = node.contentDescription?.toString() ?: ""
            val text = node.text?.toString() ?: ""

            if (viewId != null) {
                val cleanId = viewId.substringAfter(":id/").lowercase()

                if (SNAP_WHITELIST_KEYWORDS.any { cleanId.contains(it) }) {
                    isWhitelistedState = true
                    return@traverseHierarchy false
                }

                if (SNAP_SPOTLIGHT_ID_KEYWORDS.any { cleanId.contains(it) }) {
                    isSpotlightViewerFound = true
                }
            }

            if (desc.isNotEmpty()) {
                val cleanDesc = desc.trim()
                if ((cleanDesc.equals("Spotlight", ignoreCase = true) ||
                     cleanDesc.startsWith("Spotlight,", ignoreCase = true) ||
                     cleanDesc.contains("Spotlight, tab", ignoreCase = true) ||
                     cleanDesc.contains("Spotlight tab", ignoreCase = true)) &&
                    node.isSelected
                ) {
                    isSpotlightTabSelected = true
                }
            }

            if (text.isNotEmpty() && text.equals("Spotlight", ignoreCase = true) && node.isSelected) {
                isSpotlightTabSelected = true
            }

            true
        }

        if (isWhitelistedState) {
            return DetectionResult(false)
        }

        if (isSpotlightTabSelected) {
            return DetectionResult(isDistraction = true, isTabSelected = true, tabType = "spotlight")
        }

        if (isSpotlightViewerFound) {
            return DetectionResult(isDistraction = true, isTabSelected = false)
        }

        return DetectionResult(false)
    }

    // ==================== YOUTUBE EVALUATION ====================
    private fun evaluateYouTube(
        currentActivity: String?,
        rootNode: AccessibilityNodeInfo,
        event: AccessibilityEvent?
    ): DetectionResult {
        if (currentActivity != null &&
            (currentActivity.contains("ReelWatchActivity", ignoreCase = true) ||
             currentActivity.contains("ReelPlayerActivity", ignoreCase = true))
        ) {
            return DetectionResult(isDistraction = true, isTabSelected = false)
        }

        if (event != null) {
            val eventText = event.text.joinToString(" ")
            val eventDesc = event.contentDescription?.toString() ?: ""
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_SELECTED ||
                event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED
            ) {
                if (eventDesc.equals("Shorts", ignoreCase = true) ||
                    eventText.equals("Shorts", ignoreCase = true)
                ) {
                    return DetectionResult(isDistraction = true, isTabSelected = true, tabType = "shorts")
                }
            }
        }

        for (fullId in YT_SHORTS_FULL_IDS) {
            try {
                val nodes = rootNode.findAccessibilityNodeInfosByViewId(fullId)
                if (!nodes.isNullOrEmpty()) {
                    return DetectionResult(isDistraction = true, isTabSelected = false)
                }
            } catch (_: Exception) {
            }
        }

        var isShortsTabSelected = false
        var isShortsFound = false
        var isWhitelistedState = false

        traverseHierarchy(rootNode) { node ->
            val viewId = node.viewIdResourceName
            val desc = node.contentDescription?.toString() ?: ""
            val text = node.text?.toString() ?: ""

            if (viewId != null) {
                val cleanId = viewId.substringAfter(":id/").lowercase()

                if (YT_NORMAL_ID_KEYWORDS.any { cleanId.contains(it) }) {
                    isWhitelistedState = true
                    return@traverseHierarchy false
                }

                if (YT_SHORTS_ID_KEYWORDS.any { cleanId.contains(it) }) {
                    isShortsFound = true
                }
            }

            if (desc.isNotEmpty()) {
                val cleanDesc = desc.trim()
                if ((cleanDesc.equals("Shorts", ignoreCase = true) ||
                     cleanDesc.startsWith("Shorts,", ignoreCase = true) ||
                     (cleanDesc.endsWith(", tab", ignoreCase = true) && cleanDesc.contains("Shorts", ignoreCase = true))) &&
                    node.isSelected
                ) {
                    isShortsTabSelected = true
                }
            }

            if (text.isNotEmpty() && text.equals("Shorts", ignoreCase = true) && node.isSelected) {
                isShortsTabSelected = true
            }

            true
        }

        if (isWhitelistedState) {
            return DetectionResult(false)
        }

        if (isShortsTabSelected) {
            return DetectionResult(isDistraction = true, isTabSelected = true, tabType = "shorts")
        }

        if (isShortsFound) {
            return DetectionResult(isDistraction = true, isTabSelected = false)
        }

        return DetectionResult(false)
    }

    // ==================== FACEBOOK EVALUATION ====================
    private fun evaluateFacebook(
        currentActivity: String?,
        rootNode: AccessibilityNodeInfo,
        event: AccessibilityEvent?
    ): DetectionResult {
        if (currentActivity != null &&
            (currentActivity.contains("Reel", ignoreCase = true) ||
             currentActivity.contains("FbShorts", ignoreCase = true))
        ) {
            return DetectionResult(isDistraction = true, isTabSelected = false)
        }

        var isFbShortsDetected = false
        var isReelsTabSelected = false

        traverseHierarchy(rootNode) { node ->
            val viewId = node.viewIdResourceName
            val desc = node.contentDescription?.toString() ?: ""

            if (viewId != null) {
                val cleanId = viewId.substringAfter(":id/").lowercase()
                if (FB_REELS_ID_KEYWORDS.any { cleanId.contains(it) }) {
                    isFbShortsDetected = true
                }
            }

            if (desc.isNotEmpty()) {
                if ((desc.equals("Reels", ignoreCase = true) || desc.contains("Reels, tab", ignoreCase = true)) &&
                    node.isSelected
                ) {
                    isReelsTabSelected = true
                }
            }

            true
        }

        if (isReelsTabSelected) {
            return DetectionResult(isDistraction = true, isTabSelected = true, tabType = "reels")
        }

        if (isFbShortsDetected) {
            return DetectionResult(isDistraction = true, isTabSelected = false)
        }

        return DetectionResult(false)
    }

    /**
     * High-speed BFS tree traversal with cycle detection, depth limits, and node count caps.
     */
    private inline fun traverseHierarchy(
        root: AccessibilityNodeInfo,
        crossinline onNode: (AccessibilityNodeInfo) -> Boolean
    ) {
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var scannedCount = 0

        while (queue.isNotEmpty() && scannedCount < MAX_NODES_TO_SCAN) {
            val (current, depth) = queue.poll() ?: break
            scannedCount++

            val shouldContinue = try {
                onNode(current)
            } catch (e: Exception) {
                true
            }

            if (!shouldContinue) {
                break
            }

            if (depth < MAX_SCAN_DEPTH) {
                val childCount = try {
                    current.childCount
                } catch (e: Exception) {
                    0
                }

                for (i in 0 until childCount) {
                    val child = try {
                        current.getChild(i)
                    } catch (e: Exception) {
                        null
                    }
                    if (child != null) {
                        queue.add(child to depth + 1)
                    }
                }
            }
        }
    }
}
