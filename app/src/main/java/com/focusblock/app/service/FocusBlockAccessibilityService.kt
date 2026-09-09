package com.focusblock.app.service

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.focusblock.app.data.PreferencesHelper
import com.focusblock.app.detector.ShortsReelsDetector

/**
 * High-performance Accessibility Service for FocusBlock.
 *
 * Monitors YouTube and Instagram in real time, immediately navigating away from
 * Shorts and Reels using global back action with smart debouncing and fallback handling.
 */
class FocusBlockAccessibilityService : AccessibilityService() {

    private lateinit var preferencesHelper: PreferencesHelper
    private lateinit var detector: ShortsReelsDetector

    private val handler = Handler(Looper.getMainLooper())
    private var lastEvaluationTime = 0L
    private var lastBlockActionTime = 0L

    private val debounceDelayMs = 60L
    private val blockCooldownMs = 350L

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

        // Only evaluate target apps
        if (eventPackage == ShortsReelsDetector.PACKAGE_YOUTUBE ||
            eventPackage == ShortsReelsDetector.PACKAGE_INSTAGRAM
        ) {
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
     * Evaluates active window content and executes immediate blocking navigation if a Short/Reel is detected.
     */
    private fun evaluateCurrentScreen(event: AccessibilityEvent?) {
        if (!preferencesHelper.isProtectionEnabled) return

        val pkg = currentForegroundPackage
        if (pkg != ShortsReelsDetector.PACKAGE_YOUTUBE && pkg != ShortsReelsDetector.PACKAGE_INSTAGRAM) {
            return
        }

        val rootNode = try {
            rootInActiveWindow
        } catch (e: Exception) {
            null
        }

        val isTargetDistraction = detector.isShortsOrReel(
            packageName = pkg,
            currentActivity = currentActivityName,
            rootNode = rootNode,
            event = event
        )

        if (isTargetDistraction) {
            executeBlockAction(pkg, rootNode)
        }
    }

    /**
     * Immediately triggers Back navigation with cooldown protection and scheduled follow-up re-checks.
     */
    private fun executeBlockAction(pkg: String, rootNode: AccessibilityNodeInfo?) {
        val now = System.currentTimeMillis()
        if (now - lastBlockActionTime < blockCooldownMs) {
            // Already taking action within cooldown window
            return
        }
        lastBlockActionTime = now

        // Step 1: Perform Global Back to exit the Short/Reel viewer
        performGlobalAction(GLOBAL_ACTION_BACK)

        // Step 2: Fallback for YouTube Shorts tab - if stuck on tab, attempt navigating to Home tab
        if (pkg == ShortsReelsDetector.PACKAGE_YOUTUBE && rootNode != null) {
            tryFallbackToYouTubeHome(rootNode)
        }

        // Step 3: Schedule follow-up re-checks to confirm the distraction was dismissed
        handler.postDelayed({ evaluateCurrentScreen(null) }, 180)
        handler.postDelayed({ evaluateCurrentScreen(null) }, 480)
    }

    /**
     * If user navigated into the YouTube Shorts tab, locate and click the Home tab as a clean fallback.
     */
    private fun tryFallbackToYouTubeHome(rootNode: AccessibilityNodeInfo) {
        try {
            // Look for Home tab in pivot bar
            val nodes = rootNode.findAccessibilityNodeInfosByText("Home")
            if (!nodes.isNullOrEmpty()) {
                for (node in nodes) {
                    if (node.isClickable) {
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        return
                    }
                    val parent = node.parent
                    if (parent != null && parent.isClickable) {
                        parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        return
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    override fun onInterrupt() {
        // Accessibility service interrupted
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
