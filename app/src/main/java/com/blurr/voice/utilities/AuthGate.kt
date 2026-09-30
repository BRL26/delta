package com.blurr.voice.utilities

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.google.firebase.auth.FirebaseAuth

/**
 * Single source of truth for "is this app allowed past the login screen".
 *
 * This fork runs in LOCAL mode by default: there is no Firebase Auth session
 * to check, and requiring a Google sign-in against a project the user may not
 * control is a poor fit for a personal build. In LOCAL mode we simply provision
 * a local profile on first launch and let the user straight in.
 *
 * Set [KEY_MODE] to "firebase" to restore the original upstream behaviour.
 */
object AuthGate {

    private const val PREFS_NAME = "auth_gate"
    private const val KEY_MODE = "mode"
    private const val LOCAL_PROFILE_EMAIL = "local@blurr.invalid"

    const val MODE_LOCAL = "local"
    const val MODE_FIREBASE = "firebase"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isLocalMode(context: Context): Boolean =
        prefs(context).getString(KEY_MODE, MODE_LOCAL) != MODE_FIREBASE

    fun setMode(context: Context, mode: String) {
        prefs(context).edit { putString(KEY_MODE, mode) }
    }

    /**
     * True when the user may enter the app. In local mode this ensures a
     * profile exists (creating a placeholder if needed) and returns true.
     */
    fun isSignedIn(context: Context): Boolean {
        if (isLocalMode(context)) {
            ensureLocalProfile(context)
            return true
        }
        return try {
            FirebaseAuth.getInstance().currentUser != null
        } catch (e: Exception) {
            Logger.e("AuthGate", "Firebase auth check failed", e)
            false
        }
    }

    /**
     * The signed-in user's email, or a local placeholder in local mode.
     * Callers that used FirebaseAuth.currentUser?.email should use this.
     */
    fun currentEmail(context: Context): String? {
        if (isLocalMode(context)) {
            ensureLocalProfile(context)
            return UserProfileManager(context).getEmail() ?: LOCAL_PROFILE_EMAIL
        }
        return try {
            FirebaseAuth.getInstance().currentUser?.email
        } catch (e: Exception) {
            null
        }
    }

    fun currentUid(context: Context): String? {
        if (isLocalMode(context)) {
            ensureLocalProfile(context)
            return "local_user"
        }
        return try {
            FirebaseAuth.getInstance().currentUser?.uid
        } catch (e: Exception) {
            null
        }
    }

    /** Creates a placeholder profile so profile-gated screens stay satisfied. */
    private fun ensureLocalProfile(context: Context) {
        val manager = UserProfileManager(context)
        if (!manager.isProfileComplete()) {
            Logger.d("AuthGate", "Local mode: provisioning placeholder profile")
            manager.saveProfile("Local User", LOCAL_PROFILE_EMAIL)
        }
    }

    /** Clears the local session state. Firebase sign-out is handled separately. */
    fun signOut(context: Context) {
        if (isLocalMode(context)) {
            UserProfileManager(context).clearProfile()
            ensureLocalProfile(context)
        }
    }
}
