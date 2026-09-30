package com.blurr.voice

import android.app.Activity
import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.util.Log

/**
 * A transparent, one-shot activity that surfaces the real lock screen.
 *
 * [KeyguardManager.requestDismissKeyguard] requires an Activity, but the agent
 * is a service, so this tiny activity is the bridge between the two: it starts,
 * asks Android to show the keyguard (PIN / password / fingerprint), and calls
 * [finish] as soon as the user unlocks or dismisses the request.
 *
 * The activity itself is invisible - a translucent theme with no UI - so the
 * only thing the user sees is the phone's own unlock prompt.
 */
class KeyguardDismissActivity : Activity() {

    private val TAG = "KeyguardDismissActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // requestDismissKeyguard does not exist before O; nothing to show.
            Log.w(TAG, "Keyguard dismiss requires API 26+, finishing.")
            finish()
            return
        }

        // Safety net: if the keyguard is dismissed without a callback (e.g. it
        // was already gone, or the device has no secure lock), never leave an
        // invisible activity sitting in the task.
        window.decorView.postDelayed({ finish() }, 30_000)

        val keyguardManager = getSystemService(KeyguardManager::class.java)
        try {
            keyguardManager.requestDismissKeyguard(
                this,
                object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() {
                        Log.d(TAG, "Keyguard dismissed; finishing.")
                        finish()
                    }

                    override fun onDismissCancelled() {
                        Log.d(TAG, "Keyguard dismiss cancelled by user; finishing.")
                        finish()
                    }

                    override fun onDismissError() {
                        Log.e(TAG, "Keyguard dismiss error; finishing.")
                        finish()
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "requestDismissKeyguard failed", e)
            finish()
        }
    }
}