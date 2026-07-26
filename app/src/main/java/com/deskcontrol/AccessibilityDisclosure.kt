package com.deskcontrol

import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * Required prominent disclosure shown immediately before requesting AccessibilityService access.
 * It is intentionally separate from the privacy policy and other onboarding copy.
 */
object AccessibilityDisclosure {
    fun showIfNeeded(activity: AppCompatActivity, onAccepted: () -> Unit) {
        val preferences = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val acceptedVersion = preferences.getInt(KEY_ACCEPTED_VERSION, 0)
        if (acceptedVersion >= CURRENT_VERSION) {
            onAccepted()
            return
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.accessibility_disclosure_title)
            .setMessage(R.string.accessibility_disclosure_message)
            .setNegativeButton(R.string.accessibility_disclosure_decline, null)
            .setPositiveButton(R.string.accessibility_disclosure_accept) { _, _ ->
                preferences.edit()
                    .putInt(KEY_ACCEPTED_VERSION, CURRENT_VERSION)
                    .commit()
                onAccepted()
            }
            .show()
    }

    private const val PREFS = "accessibility_disclosure"
    private const val KEY_ACCEPTED_VERSION = "accepted_version"
    private const val CURRENT_VERSION = 2
}
