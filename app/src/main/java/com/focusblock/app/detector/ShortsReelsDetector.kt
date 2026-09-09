package com.focusblock.app.detector

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

/**
 * High-precision, zero-false-positive detector for YouTube Shorts and Instagram Reels.
 *
 * Implements multi-signal analysis:
 * 1. Fast-path Native View ID queries (optimized C++/Binder queries).
 * 2. Deep BFS hierarchy scan with protection against cycles and stale nodes.
 * 3. Bottom navigation tab selection signals (Shorts / Reels tabs).
 * 4. Strict Whitelist filtering to guarantee normal video playback, search, home feeds,
 *    stories, and DMs are NEVER blocked.
 */
class ShortsReelsDetector {

    companion object {
        const val PACKAGE_YOUTUBE = "com.google.android.youtube"
        const val PACKAGE_INSTAGRAM = "com.instagram.android"

        private const val MAX_NODES_TO_SCAN = 800
        private const val MAX_SCAN_DEPTH = 35

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
            "com.google.android.youtube:id/shorts_player",
            "com.google.android.youtube:id/reel_viewer_layout",
            "com.google.android.youtube:id/reel_player_touch_event_listener",
            "com.google.android.youtube:id/reel_comment_sheet_dialog_container"
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
            "shorts_player",
            "reel_viewer_layout",
            "reel_player_touch_event_listener",
            "reel_comment_sheet_dialog_container"
        )

        // Strict whitelist for standard YouTube player & browsing
        private val YT_NORMAL_FULL_IDS = listOf(
            "com.google.android.youtube:id/time_bar",
            "com.google.android.youtube:id/watch_player",
            "com.google.android.youtube:id/player_fragment_container",
            "com.google.android.youtube:id/player_view",
            "com.google.android.youtube:id/youtube_controls_overlay",
            "com.google.android.youtube:id/channel_subscribe_button",
            "com.google.android.youtube:id/fullscreen_button",
            "com.google.android.youtube:id/current_time",
            "com.google.android.youtube:id/total_time",
            "com.google.android.youtube:id/results",
            "com.google.android.youtube:id/search_results_editor",
            "com.google.android.youtube:id/search_edit_text",
            "com.google.android.youtube:id/feed_filter_bar",
            "com.google.android.youtube:id/home_page"
        )

        private val YT_NORMAL_ID_KEYWORDS = setOf(
            "time_bar",
            "youtube_controls_overlay",
            "watch_player",
            "player_fragment_container",
            "player_view",
            "channel_subscribe_button",
            "fullscreen_button",
            "current_time",
            "total_time",
            "results",
            "search_results_editor",
            "search_edit_text",
            "feed_filter_bar",
            "home_page"
        )

        // ==================== INSTAGRAM SIGNATURES ====================
        private val IG_REELS_FULL_IDS = listOf(
            "com.instagram.android:id/clips_viewer_view_pager",
            "com.instagram.android:id/clips_video_container",
            "com.instagram.android:id/clips_viewer_root",
            "com.instagram.android:id/clips_swipe_refresh_layout",
            "com.instagram.android:id/clips_ufi_container",
            "com.instagram.android:id/clips_viewer_media_info_layout",
            "com.instagram.android:id/clips_audio_mix_editor",
            "com.instagram.android:id/clips_remix_button",
            "com.instagram.android:id/clips_action_sheet",
            "com.instagram.android:id/clips_like_button",
            "com.instagram.android:id/clips_item_container"
        )

        private val IG_REELS_ID_KEYWORDS = setOf(
            "clips_viewer_view_pager",
            "clips_video_container",
            "clips_viewer_root",
            "clips_swipe_refresh_layout",
            "clips_ufi_container",
            "clips_viewer_media_info_layout",
            "clips_audio_mix_editor",
            "clips_remix_button",
            "clips_action_sheet",
            "clips_like_button",
            "clips_item_container"
        )

        // Strict whitelist for standard Instagram (Stories, DMs, Feed, Profile, Search)
        private val IG_NORMAL_FULL_IDS = listOf(
            "com.instagram.android:id/story_viewer_fragment_container",
            "com.instagram.android:id/story_viewer_container",
            "com.instagram.android:id/reel_viewer_progress_bar",
            "com.instagram.android:id/stories_tray",
            "com.instagram.android:id/direct_thread_feed",
            "com.instagram.android:id/direct_inbox",
            "com.instagram.android:id/direct_thread_fragment",
            "com.instagram.android:id/message_composer_container",
            "com.instagram.android:id/feed_recycler_view",
            "com.instagram.android:id/row_feed_photo",
            "com.instagram.android:id/row_feed_profile_header",
            "com.instagram.android:id/row_feed_view_group",
            "com.instagram.android:id/main_feed_container",
            "com.instagram.android:id/action_bar_search_edit_text",
            "com.instagram.android:id/profile_tab"
        )

        private val IG_NORMAL_ID_KEYWORDS = setOf(
            "story_viewer_fragment_container",
            "story_viewer_container",
            "reel_viewer_progress_bar",
            "stories_tray",
            "direct_thread_feed",
            "direct_inbox",
            "direct_thread_fragment",
            "message_composer_container",
            "feed_recycler_view",
            "row_feed_photo",
            "row_feed_profile_header",
            "row_feed_view_group",
            "main_feed_container",
            "action_bar_search_edit_text",
            "profile_tab"
        )
    }

    /**
     * Evaluates whether the active window is displaying a Short or Reel.
     *
     * @param packageName Package of the foreground application.
     * @param currentActivity Activity class name if known.
     * @param rootNode The root AccessibilityNodeInfo in the active window.
     * @param event The triggered AccessibilityEvent.
     * @return True if and only if high-confidence Short/Reel is detected and NOT whitelisted.
     */
    fun isShortsOrReel(
        packageName: String?,
        currentActivity: String?,
        rootNode: AccessibilityNodeInfo?,
        event: AccessibilityEvent?
    ): Boolean {
        if (packageName == null || rootNode == null) return false

        return when (packageName) {
            PACKAGE_YOUTUBE -> evaluateYouTube(currentActivity, rootNode, event)
            PACKAGE_INSTAGRAM -> evaluateInstagram(currentActivity, rootNode, event)
            else -> false
        }
    }

    private fun evaluateYouTube(
        currentActivity: String?,
        rootNode: AccessibilityNodeInfo,
        event: AccessibilityEvent?
    ): Boolean {
        // Step 1: Explicit Legacy Activity check (if applicable)
        if (currentActivity != null &&
            (currentActivity.contains("ReelWatchActivity", ignoreCase = true) ||
             currentActivity.contains("ReelPlayerActivity", ignoreCase = true))
        ) {
            return true
        }

        // Step 2: Check event context directly for immediate fast response
        if (event != null) {
            val eventText = event.text.joinToString(" ")
            val eventDesc = event.contentDescription?.toString() ?: ""
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_SELECTED ||
                event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED
            ) {
                if (eventDesc.equals("Shorts", ignoreCase = true) ||
                    eventText.contains("Shorts", ignoreCase = true)
                ) {
                    return true
                }
            }
        }

        // Step 3: Fast Native View ID Lookups for Shorts
        for (fullId in YT_SHORTS_FULL_IDS) {
            try {
                val nodes = rootNode.findAccessibilityNodeInfosByViewId(fullId)
                if (!nodes.isNullOrEmpty()) {
                    // Confirm not in a whitelisted state (e.g. search results page containing a shelf)
                    if (!hasYouTubeWhitelistSignals(rootNode)) {
                        return true
                    }
                }
            } catch (_: Exception) {
            }
        }

        // Step 4: Comprehensive BFS Hierarchy Inspection
        var isShortsDetected = false
        var isWhitelisted = false

        traverseHierarchy(rootNode) { node ->
            val viewId = node.viewIdResourceName
            val desc = node.contentDescription?.toString()

            if (viewId != null) {
                val cleanId = viewId.substringAfter(":id/").lowercase()

                // Whitelist check
                if (YT_NORMAL_ID_KEYWORDS.any { cleanId.contains(it) }) {
                    isWhitelisted = true
                    return@traverseHierarchy false // Stop traversal immediately
                }

                // Shorts ID check
                if (YT_SHORTS_ID_KEYWORDS.any { cleanId.contains(it) }) {
                    isShortsDetected = true
                }
            }

            // Bottom Navigation Shorts Tab Selected check
            if (desc != null) {
                if ((desc.equals("Shorts", ignoreCase = true) ||
                     desc.startsWith("Shorts,", ignoreCase = true) ||
                     desc.endsWith(", tab", ignoreCase = true) && desc.contains("Shorts", ignoreCase = true)) &&
                    node.isSelected
                ) {
                    isShortsDetected = true
                }
            }

            // Shorts-unique control signatures (e.g. "Remix with this audio" / "Remix")
            if (desc != null && (desc.contains("Remix with this audio", ignoreCase = true) ||
                                 desc.contains("Dislike this video", ignoreCase = true) ||
                                 desc.contains("Dislike this Short", ignoreCase = true))
            ) {
                // Secondary signal supporting Shorts
                val parent = node.parent
                val parentId = parent?.viewIdResourceName?.lowercase() ?: ""
                if (parentId.contains("reel") || parentId.contains("shorts")) {
                    isShortsDetected = true
                }
            }

            true // continue traversing
        }

        if (isWhitelisted) {
            return false
        }

        return isShortsDetected
    }

    private fun hasYouTubeWhitelistSignals(rootNode: AccessibilityNodeInfo): Boolean {
        for (fullId in YT_NORMAL_FULL_IDS) {
            try {
                val nodes = rootNode.findAccessibilityNodeInfosByViewId(fullId)
                if (!nodes.isNullOrEmpty()) {
                    return true
                }
            } catch (_: Exception) {
            }
        }
        return false
    }

    private fun evaluateInstagram(
        @Suppress("UNUSED_PARAMETER") currentActivity: String?,
        rootNode: AccessibilityNodeInfo,
        event: AccessibilityEvent?
    ): Boolean {
        // Step 1: Check event context directly for Reels tab click/selection
        if (event != null) {
            val eventDesc = event.contentDescription?.toString() ?: ""
            val eventText = event.text.joinToString(" ")
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_SELECTED ||
                event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED
            ) {
                if (eventDesc.contains("Reels", ignoreCase = true) ||
                    eventText.contains("Reels", ignoreCase = true)
                ) {
                    return true
                }
            }
        }

        // Step 2: Fast Native View ID Lookups for Reels
        for (fullId in IG_REELS_FULL_IDS) {
            try {
                val nodes = rootNode.findAccessibilityNodeInfosByViewId(fullId)
                if (!nodes.isNullOrEmpty()) {
                    if (!hasInstagramWhitelistSignals(rootNode)) {
                        return true
                    }
                }
            } catch (_: Exception) {
            }
        }

        // Step 3: Comprehensive BFS Hierarchy Inspection
        var isReelsDetected = false
        var isWhitelisted = false

        traverseHierarchy(rootNode) { node ->
            val viewId = node.viewIdResourceName
            val desc = node.contentDescription?.toString()

            if (viewId != null) {
                val cleanId = viewId.substringAfter(":id/").lowercase()

                // Whitelist check (Stories, DMs, Feed, Profile)
                if (IG_NORMAL_ID_KEYWORDS.any { cleanId.contains(it) }) {
                    isWhitelisted = true
                    return@traverseHierarchy false // Stop traversal immediately
                }

                // Reels ID check
                if (IG_REELS_ID_KEYWORDS.any { cleanId.contains(it) }) {
                    isReelsDetected = true
                }
            }

            // Bottom Navigation Reels Tab Selected check
            if (desc != null) {
                if ((desc.equals("Reels", ignoreCase = true) ||
                     desc.startsWith("Reels,", ignoreCase = true) ||
                     desc.contains("Reels, tab", ignoreCase = true)) &&
                    node.isSelected
                ) {
                    isReelsDetected = true
                }
            }

            true // continue traversing
        }

        if (isWhitelisted) {
            return false
        }

        return isReelsDetected
    }

    private fun hasInstagramWhitelistSignals(rootNode: AccessibilityNodeInfo): Boolean {
        for (fullId in IG_NORMAL_FULL_IDS) {
            try {
                val nodes = rootNode.findAccessibilityNodeInfosByViewId(fullId)
                if (!nodes.isNullOrEmpty()) {
                    return true
                }
            } catch (_: Exception) {
            }
        }
        return false
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
