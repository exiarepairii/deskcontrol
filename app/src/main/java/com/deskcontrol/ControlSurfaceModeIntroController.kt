package com.deskcontrol

import android.content.Intent
import android.graphics.Rect
import android.graphics.RectF
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

class ControlSurfaceModeIntroController(
    private val activity: AppCompatActivity,
    private val root: ViewGroup,
    private val currentMode: ControlSurfaceMode,
    private val switchTarget: View,
    private val controlArea: View,
    private val onActivationRequested: () -> Unit,
    private val onVisibilityChanged: (Boolean) -> Unit,
    private val onFinished: () -> Unit
) {
    private var overlay: View? = null
    private var selectedMode = currentMode

    fun showChoiceIfNeeded() {
        if (!SettingsStore.touchpadIntroShown) {
            showChoice()
        }
    }

    fun replay() {
        showChoice()
    }

    private fun showChoice() {
        if (overlay != null) return
        selectedMode = currentMode
        onVisibilityChanged(true)
        val intro = LayoutInflater.from(activity).inflate(
            R.layout.view_control_surface_mode_intro,
            root,
            false
        )
        overlay = intro
        root.addView(intro)

        val content = intro.findViewById<View>(R.id.controlModeIntroContent)
        val touchButton = intro.findViewById<MaterialButton>(R.id.btnIntroTouch)
        val motionButton = intro.findViewById<MaterialButton>(R.id.btnIntroMotion)
        val tutorial = intro.findViewById<TextView>(R.id.controlModeTutorial)
        val confirm = intro.findViewById<MaterialButton>(R.id.btnIntroConfirm)

        fun select(mode: ControlSurfaceMode) {
            selectedMode = mode
            touchButton.isChecked = mode == ControlSurfaceMode.TOUCHPAD
            motionButton.isChecked = mode == ControlSurfaceMode.RAY_MOUSE
            tutorial.setText(
                if (mode == ControlSurfaceMode.TOUCHPAD) {
                    R.string.control_mode_intro_touch_tutorial
                } else {
                    R.string.control_mode_intro_motion_tutorial
                }
            )
            if (tutorial.visibility != View.VISIBLE) {
                tutorial.visibility = View.VISIBLE
                confirm.visibility = View.VISIBLE
                tutorial.alpha = 0f
                confirm.alpha = 0f
                tutorial.translationY = activity.dp(8).toFloat()
                confirm.translationY = activity.dp(8).toFloat()
                tutorial.animate().alpha(1f).translationY(0f).setDuration(180L).start()
                confirm.animate().alpha(1f).translationY(0f).setDuration(180L).start()
            }
        }

        touchButton.setOnClickListener { select(ControlSurfaceMode.TOUCHPAD) }
        motionButton.setOnClickListener { select(ControlSurfaceMode.RAY_MOUSE) }
        confirm.setOnClickListener {
            confirm.isEnabled = false
            SettingsStore.setTouchpadIntroShown(activity)
            SettingsStore.setRayMouseIntroShown(activity)
            SettingsStore.setLastControlSurface(activity, selectedMode)
            animateIntoSwitch(content, intro)
        }
    }

    fun showSwitchCoachmark() {
        if (overlay != null) return
        onVisibilityChanged(true)
        val coachmark = LayoutInflater.from(activity).inflate(
            R.layout.view_control_surface_switch_coachmark,
            root,
            false
        )
        overlay = coachmark
        root.addView(coachmark)
        coachmark.setOnClickListener {
            replaceOverlay(coachmark, ::showActivationPrompt)
        }
        coachmark.post {
            val spotlight = coachmark.findViewById<TutorialSpotlightView>(
                R.id.controlModeSwitchSpotlight
            )
            spotlight.setSpotlight(localRect(switchTarget, coachmark, activity.dp(6)), activity.dp(14).toFloat())

            val targetRect = Rect()
            switchTarget.getGlobalVisibleRect(targetRect)
            val coachmarkRect = Rect()
            coachmark.getGlobalVisibleRect(coachmarkRect)
            val callout = coachmark.findViewById<View>(R.id.controlModeSwitchCallout)
            val desiredCenter = targetRect.centerX() - coachmarkRect.left
            val layoutParams = callout.layoutParams as FrameLayout.LayoutParams
            layoutParams.topMargin =
                targetRect.bottom - coachmarkRect.top + activity.dp(8)
            callout.layoutParams = layoutParams
            callout.x = (desiredCenter - callout.width / 2f).coerceIn(
                activity.dp(16).toFloat(),
                (root.width - callout.width - activity.dp(16)).toFloat()
            )
            callout.alpha = 0f
            callout.animate().alpha(1f).setDuration(220L).start()
        }
    }

    fun destroy() {
        overlay?.animate()?.cancel()
        overlay = null
    }

    private fun showActivationPrompt() {
        val prompt = LayoutInflater.from(activity).inflate(
            R.layout.view_control_surface_activation_prompt,
            root,
            false
        )
        overlay = prompt
        root.addView(prompt)
        var tapInsideControlArea = false
        prompt.setOnClickListener {
            if (tapInsideControlArea) {
                showActivationExplanation(prompt)
            }
        }
        prompt.setOnTouchListener { view, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                val targetRect = Rect()
                controlArea.getGlobalVisibleRect(targetRect)
                tapInsideControlArea = targetRect.contains(
                    event.rawX.toInt(),
                    event.rawY.toInt()
                )
                if (tapInsideControlArea) {
                    view.performClick()
                }
            }
            true
        }
        prompt.post {
            val spotlight = prompt.findViewById<TutorialSpotlightView>(
                R.id.controlModeActivationSpotlight
            )
            spotlight.setSpotlight(localRect(controlArea, prompt, activity.dp(5)), activity.dp(20).toFloat())
            positionAboveControlArea(
                prompt.findViewById(R.id.controlModeActivationPrompt),
                prompt
            )
        }
    }

    private fun showActivationExplanation(prompt: View) {
        onActivationRequested()
        controlArea.isActivated = true
        val explanation = LayoutInflater.from(activity).inflate(
            R.layout.view_control_surface_activation_explanation,
            root,
            false
        )
        root.addView(explanation)
        overlay = explanation
        root.removeView(prompt)
        explanation.isClickable = false
        explanation.postDelayed({
            if (overlay === explanation) {
                explanation.isClickable = true
                explanation.setOnClickListener { finishTutorial(explanation) }
            }
        }, EXPLANATION_INPUT_DELAY_MS)
        explanation.post {
            val spotlight = explanation.findViewById<TutorialSpotlightView>(
                R.id.controlModeActiveSpotlight
            )
            spotlight.setSpotlight(
                localRect(controlArea, explanation, activity.dp(5)),
                activity.dp(20).toFloat()
            )
            positionInsideControlArea(
                explanation.findViewById(R.id.controlModeActiveExplanation),
                explanation
            )
        }
    }

    private fun animateIntoSwitch(content: View, intro: View) {
        val targetRect = Rect()
        switchTarget.getGlobalVisibleRect(targetRect)
        val contentRect = Rect()
        content.getGlobalVisibleRect(contentRect)
        val deltaX = targetRect.centerX() - contentRect.centerX()
        val deltaY = targetRect.centerY() - contentRect.centerY()
        val scaleX = (targetRect.width().toFloat() / contentRect.width().coerceAtLeast(1))
            .coerceIn(0.12f, 0.35f)
        val scaleY = (targetRect.height().toFloat() / contentRect.height().coerceAtLeast(1))
            .coerceIn(0.08f, 0.25f)

        intro.findViewById<View>(R.id.controlModeIntroScrim).animate()
            .alpha(0f)
            .setDuration(320L)
            .start()
        content.animate()
            .translationX(deltaX.toFloat())
            .translationY(deltaY.toFloat())
            .scaleX(scaleX)
            .scaleY(scaleY)
            .alpha(0f)
            .setDuration(420L)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                root.removeView(intro)
                overlay = null
                if (selectedMode != currentMode) {
                    val target = when (selectedMode) {
                        ControlSurfaceMode.TOUCHPAD -> TouchpadActivity::class.java
                        ControlSurfaceMode.RAY_MOUSE -> RayMouseActivity::class.java
                    }
                    activity.startActivity(
                        Intent(activity, target)
                            .putExtra(EXTRA_SHOW_SWITCH_COACHMARK, true)
                    )
                    activity.finish()
                } else {
                    showSwitchCoachmark()
                }
            }
            .start()
    }

    private fun replaceOverlay(current: View, next: () -> Unit) {
        current.animate()
            .alpha(0f)
            .setDuration(140L)
            .withEndAction {
                root.removeView(current)
                overlay = null
                next()
            }
            .start()
    }

    private fun finishTutorial(current: View) {
        current.animate()
            .alpha(0f)
            .setDuration(160L)
            .withEndAction {
                root.removeView(current)
                overlay = null
                onVisibilityChanged(false)
                onFinished()
            }
            .start()
    }

    private fun positionAboveControlArea(callout: View, container: View) {
        val targetRect = Rect()
        controlArea.getGlobalVisibleRect(targetRect)
        val containerRect = Rect()
        container.getGlobalVisibleRect(containerRect)
        callout.y = (
            targetRect.top - containerRect.top - callout.height - activity.dp(18)
            ).coerceAtLeast(activity.dp(16)).toFloat()
        callout.alpha = 0f
        callout.animate().alpha(1f).setDuration(200L).start()
    }

    private fun positionInsideControlArea(callout: View, container: View) {
        val targetRect = Rect()
        controlArea.getGlobalVisibleRect(targetRect)
        val containerRect = Rect()
        container.getGlobalVisibleRect(containerRect)
        callout.y = (
            targetRect.centerY() - containerRect.top - callout.height / 2
            ).toFloat()
        callout.alpha = 0f
        callout.animate().alpha(1f).setDuration(200L).start()
    }

    private fun localRect(target: View, container: View, padding: Int): RectF {
        val targetRect = Rect()
        target.getGlobalVisibleRect(targetRect)
        val containerRect = Rect()
        container.getGlobalVisibleRect(containerRect)
        return RectF(
            (targetRect.left - containerRect.left - padding).toFloat(),
            (targetRect.top - containerRect.top - padding).toFloat(),
            (targetRect.right - containerRect.left + padding).toFloat(),
            (targetRect.bottom - containerRect.top + padding).toFloat()
        )
    }

    private fun AppCompatActivity.dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_SHOW_SWITCH_COACHMARK = "show_control_surface_switch_coachmark"
        private const val EXPLANATION_INPUT_DELAY_MS = 250L
    }
}
