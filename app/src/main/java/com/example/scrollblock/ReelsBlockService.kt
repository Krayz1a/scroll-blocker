package com.example.scrollblock

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.os.Build
import android.widget.ImageView
import androidx.core.content.ContextCompat



class ReelsBlockService : AccessibilityService() {
    companion object {
        private const val TAG               = "ScrollBlockSvc"
        private const val IG_PKG            = "com.instagram.android"
        private const val TIKTOK_PKG        = "com.zhiliaoapp.musically"
        private const val YT_PKG            = "com.google.android.youtube"
        private const val SYSUI_PKG         = "com.android.systemui"

        // YouTube Shorts resource IDs (StackOverflow) :contentReference[oaicite:1]{index=1}
        private const val YT_SHORTS_ROOT_ID = "com.google.android.youtube:id/reel_watch_fragment_root"
        private const val YT_SHORTS_LIST_ID = "com.google.android.youtube:id/reel_recycler"
    }

    private lateinit var wm: WindowManager
    private var overlay: View? = null
    private var blocking = false
    private val ownPkg by lazy { applicationContext.packageName }

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes =
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                        AccessibilityEvent.TYPE_VIEW_SCROLLED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = (
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                            AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
                    )
            notificationTimeout = 0
        }
        Log.d(TAG, "Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val leftTargets = pkg !in listOf(IG_PKG, TIKTOK_PKG) &&
                    !pkg.contains("youtube", ignoreCase = true) &&
                    pkg != ownPkg && pkg != SYSUI_PKG
            if (blocking && leftTargets) {
                stopBlocking("Launcher / other app")
            }
        }

        // 1) ignore System UI & our own overlay
        if (pkg == SYSUI_PKG || pkg == ownPkg) return

        // 2) if blocking and we leave all target apps, stop immediately
        if (blocking && pkg !in listOf(IG_PKG, TIKTOK_PKG, YT_PKG)) {
            stopBlocking("Left all target apps")
            return
        }

        when (pkg) {
            // ────────── Instagram Reels ──────────
            IG_PKG -> {
                if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                    event.className?.toString()?.endsWith("InstagramMainActivity") == true &&
                    blocking
                ) {
                    stopBlocking("Back to feed activity")
                    return    //  ← we’re done for this event
                }
                val root = rootInActiveWindow ?: return
                val inReels = containsViewPager(root)
                if (inReels && !blocking) {
                    startBlocking("Entered IG Reels")
                } else if (!inReels && blocking) {
                    stopBlocking("Left IG Reels")
                }
            }

            // ────────── TikTok ──────────
            TIKTOK_PKG -> {
                if (!blocking) {
                    startBlocking("Entered TikTok")
                }
            }

            // ────────── YouTube Shorts ──────────
            YT_PKG -> {
                val root = rootInActiveWindow ?: return

                // First try the known Shorts IDs
                val hasRoot =
                    root.findAccessibilityNodeInfosByViewId(YT_SHORTS_ROOT_ID).isNotEmpty()
                val hasList =
                    root.findAccessibilityNodeInfosByViewId(YT_SHORTS_LIST_ID).isNotEmpty()

                // Fallback: any className containing “Shorts”
                val className = event.className?.toString().orEmpty()
                val hasShortsTag = className.contains("Shorts", ignoreCase = true)

                val inShorts = hasRoot || hasList || hasShortsTag

                if (inShorts && !blocking) {
                    startBlocking("Entered YouTube Shorts")
                } else if (!inShorts && blocking) {
                    stopBlocking("Left YouTube Shorts")
                }
            }
        }
    }

    // instant unblock on Back‐gesture
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (blocking &&
            event.keyCode == KeyEvent.KEYCODE_BACK &&
            event.action == KeyEvent.ACTION_UP
        ) {
            stopBlocking("Back gesture exit")
        }
        return false
    }

    override fun onInterrupt() {
        stopBlocking("Service interrupted")
    }

    /** Recursively detects any ViewPager (including ViewPager2 in Reels). */
    private fun containsViewPager(node: AccessibilityNodeInfo): Boolean {
        node.className?.let {
            if (it.toString().contains("ViewPager", ignoreCase = true)) {
                return true
            }
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                if (containsViewPager(child)) return true
            }
        }
        return false
    }

    /** Installs a full-screen, touch-through white overlay. */
    private fun startBlocking(reason: String = "unknown") {
        if (overlay != null) return
        blocking = true

        // create an ImageView instead of a plain View
        val img = ImageView(this).apply {
            // load your drawable
            setImageDrawable(
                ContextCompat.getDrawable(this@ReelsBlockService, R.drawable.background)
            )
            // fill the screen
            scaleType = ImageView.ScaleType.CENTER_CROP
            // if you want the image centered with aspect‐ratio:
            // scaleType = ImageView.ScaleType.FIT_CENTER
        }
        overlay = img

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        wm.addView(
            overlay,
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
        )
        Log.d(TAG, "🔒 Blocking ON: $reason")
    }


    /** Removes the overlay immediately. */
    private fun stopBlocking(reason: String = "unknown") {
        overlay?.let { wm.removeView(it) }
        overlay = null
        blocking = false
        Log.d(TAG, "🔓 Blocking OFF: $reason")
    }
}
