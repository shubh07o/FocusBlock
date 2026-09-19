package com.focusblock.app.service

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.focusblock.app.data.PreferencesHelper
import com.focusblock.app.detector.DetectionResult
import com.focusblock.app.detector.ShortsReelsDetector

/**
 * High-performance Accessibility Service for FocusBlock.
 *
 * Exclusively blocks Reels, Spotlight, and Shorts without closing the host app.
 * - When on Reels/Spotlight/Shorts bottom tab: switches to Home/Chat tab cleanly.
 * - When in fullscreen overlay player: issues Back to dismiss overlay without exiting app.
 * - Stories, Main Feeds, and DMs remain completely untouched and usable.
 */
class FocusBlockAccessibilityService : AccessibilityService() {

    private lateinit var preferencesHelper: PreferencesHelper
    private lateinit var detector: ShortsReelsDetector

    private val handler = Handler(Looper.getMainLooper())
    private var lastEvaluationTime = 0L
    private var lastBlockActionTime = 0L

    private val debounceDelayMs = 40L
    private val blockCooldownMs = 300L

    private var currentForegroundPackage: String? = null
    private var currentActivityName: String? = null

    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == PreferencesHelper.KEY_PROTECTION_ENABLED) {
            if (preferencesHelper.isProtectionEnabled) {
                scheduleEvaluation(forceImmediate = true)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        preferencesHelper = PreferencesHelper.getInstance(this)
        detector = ShortsReelsDetector()
        preferencesHelper.registerOnSharedPreferenceChangeListener(preferenceListener)
        isServiceRunning = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!preferencesHelper.isProtectionEnabled) return

        val eventPackage = event.packageName?.toString()
        if (eventPackage != null) {
            currentForegroundPackage = eventPackage
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val className = event.className?.toString()
            if (className != null && !className.startsWith("android.widget.") && !className.startsWith("android.view.")) {
                currentActivityName = className
            }
        }

        if (eventPackage != null && ShortsReelsDetector.SUPPORTED_PACKAGES.contains(eventPackage)) {
            scheduleEvaluation(event = event)
        }
    }

    /**
     * Debounced screen evaluator.
     */
    private fun scheduleEvaluation(forceImmediate: Boolean = false, event: AccessibilityEvent? = null) {
        val now = System.currentTimeMillis()
        val timeSinceLast = now - lastEvaluationTime

        if (forceImmediate || timeSinceLast >= debounceDelayMs) {
            lastEvaluationTime = now
            evaluateCurrentScreen(event)
        } else {
            handler.removeCallbacks(evaluationRunnable)
            handler.postDelayed(evaluationRunnable, debounceDelayMs - timeSinceLast)
        }
    }

    private val evaluationRunnable = Runnable {
        lastEvaluationTime = System.currentTimeMillis()
        evaluateCurrentScreen(null)
    }

    /**
     * Evaluates active window content and executes non-destructive escape navigation.
     */
    private fun evaluateCurrentScreen(event: AccessibilityEvent?) {
        if (!preferencesHelper.isProtectionEnabled) return

        val pkg = currentForegroundPackage
        if (pkg == null || !ShortsReelsDetector.SUPPORTED_PACKAGES.contains(pkg)) {
            return
        }

        val rootNode = try {
            rootInActiveWindow
        } catch (e: Exception) {
            null
        }

        val detectionResult = detector.detectDistraction(
            packageName = pkg,
            currentActivity = currentActivityName,
            rootNode = rootNode,
            event = event
        )

        if (detectionResult.isDistraction) {
            executeBlockAction(pkg, detectionResult, rootNode)
        }
    }

    /**
     * Executes targeted escape action:
     * - If on a bottom tab: switches to Home/Chat tab without pressing Back (so the app never closes).
     * - If in an overlay viewer: presses Back to return to feed/search.
     */
    private fun executeBlockAction(pkg: String, result: DetectionResult, rootNode: AccessibilityNodeInfo?) {
        val now = System.currentTimeMillis()
        if (now - lastBlockActionTime < blockCooldownMs) {
            return
        }
        lastBlockActionTime = now

        if (result.isTabSelected && rootNode != null) {
            // User is on the dedicated tab -> Switch to safe tab immediately without exiting app
            val tabSwitched = when (pkg) {
                ShortsReelsDetector.PACKAGE_INSTAGRAM -> {
                    tryClickTab(rootNode, listOf("Home", "Feed", "Search", "Profile", "Direct"))
                }
                ShortsReelsDetector.PACKAGE_SNAPCHAT -> {
                    tryClickTab(rootNode, listOf("Chat", "Camera", "Map", "Stories"))
                }
                ShortsReelsDetector.PACKAGE_YOUTUBE, ShortsReelsDetector.PACKAGE_YOUTUBE_REVANCED -> {
                    tryClickTab(rootNode, listOf("Home", "Subscriptions", "You", "Library"))
                }
                else -> false
            }

            if (!tabSwitched) {
                // If tab click didn't find candidate, perform back
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
        } else {
            // Fullscreen overlay opened from feed/search -> Dismiss overlay with Back
            performGlobalAction(GLOBAL_ACTION_BACK)
        }

        // Re-check shortly after navigation
        handler.postDelayed({ evaluateCurrentScreen(null) }, 200)
    }

    /**
     * Finds and clicks an alternative safe bottom tab.
     */
    private fun tryClickTab(rootNode: AccessibilityNodeInfo, candidateNames: List<String>): Boolean {
        try {
            for (name in candidateNames) {
                val nodesByText = rootNode.findAccessibilityNodeInfosByText(name)
                if (!nodesByText.isNullOrEmpty()) {
                    for (node in nodesByText) {
                        if (performClickOnNodeOrParent(node)) {
                            return true
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return false
    }

    private fun performClickOnNodeOrParent(node: AccessibilityNodeInfo?): Boolean {
        var curr = node
        var depth = 0
        while (curr != null && depth < 4) {
            if (curr.isClickable) {
                return curr.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            curr = curr.parent
            depth++
        }
        return false
    }

    override fun onInterrupt() {
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        preferencesHelper.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        handler.removeCallbacksAndMessages(null)
    }

    companion object {
        @Volatile
        var isServiceRunning: Boolean = false
            private set
    }
}
