package com.deskcontrol

import android.animation.ObjectAnimator
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors

class PlaySupportActivity : AppCompatActivity(), SupporterBillingManager.Listener {
    private lateinit var billing: SupporterBillingManager
    private lateinit var status: TextView
    private lateinit var purchase: MaterialButton
    private lateinit var restore: MaterialButton
    private lateinit var defaultIcon: MaterialCardView
    private lateinit var whiteIcon: MaterialCardView
    private lateinit var goldIcon: MaterialCardView
    private lateinit var iconHint: TextView
    private lateinit var reviewDemo: MaterialButton
    private var titleTapCount = 0
    private var lastTitleTapAt = 0L
    private var purchaseNudge: ObjectAnimator? = null
    private var latestBillingState = SupporterBillingManager.State()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_play_support)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        applyEdgeToEdgePadding(findViewById(R.id.playSupportRoot))

        val toolbar = findViewById<MaterialToolbar>(R.id.playSupportToolbar)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setOnClickListener { handleReviewTap() }

        status = findViewById(R.id.playSupportStatus)
        purchase = findViewById(R.id.playSupportPurchase)
        restore = findViewById(R.id.playSupportRestore)
        defaultIcon = findViewById(R.id.playIconDefault)
        whiteIcon = findViewById(R.id.playIconWhite)
        goldIcon = findViewById(R.id.playIconGold)
        iconHint = findViewById(R.id.playIconHint)
        reviewDemo = findViewById(R.id.playReviewDemo)

        billing = SupporterBillingManager(this, this)
        purchase.setOnClickListener { billing.launchPurchase(this) }
        restore.setOnClickListener { billing.refresh(showRestoreErrors = true) }
        reviewDemo.setOnClickListener {
            startActivity(android.content.Intent(this, PlayReviewDemoActivity::class.java))
        }
        defaultIcon.setOnClickListener { selectIcon(LauncherIconManager.Icon.DEFAULT) }
        whiteIcon.setOnClickListener { selectIcon(LauncherIconManager.Icon.WHITE) }
        goldIcon.setOnClickListener { selectIcon(LauncherIconManager.Icon.GOLD) }
        refreshIconUi()
    }

    override fun onResume() {
        super.onResume()
        refreshIconUi()
        if (::billing.isInitialized) billing.refresh()
    }

    override fun onDestroy() {
        purchaseNudge?.cancel()
        if (::billing.isInitialized) billing.close()
        super.onDestroy()
    }

    override fun onBillingStateChanged(state: SupporterBillingManager.State) {
        runOnUiThread {
            latestBillingState = state
            renderBillingState()
        }
    }

    override fun onBillingMessage(messageRes: Int) {
        runOnUiThread {
            Toast.makeText(this, messageRes, Toast.LENGTH_LONG).show()
        }
    }

    private fun selectIcon(icon: LauncherIconManager.Icon) {
        if (icon != LauncherIconManager.Icon.DEFAULT &&
            !PlayEntitlements.hasPremiumAccess(this)
        ) {
            nudgePurchaseButton()
            return
        }
        when (LauncherIconManager.apply(this, icon)) {
            LauncherIconManager.ApplyResult.APPLIED ->
                Toast.makeText(this, R.string.play_icon_changed, Toast.LENGTH_LONG).show()
            LauncherIconManager.ApplyResult.LOCKED ->
                nudgePurchaseButton()
            LauncherIconManager.ApplyResult.FAILED ->
                Toast.makeText(this, R.string.play_icon_switch_failed, Toast.LENGTH_SHORT).show()
        }
        refreshIconUi()
    }

    private fun refreshIconUi() {
        val reviewMode = PlayEntitlements.isReviewMode(this)
        val unlocked = PlayEntitlements.hasPremiumAccess(this)
        reviewDemo.visibility =
            if (reviewMode) View.VISIBLE else View.GONE
        val selected = LauncherIconManager.selected(this)
        updateIconCard(defaultIcon, selected == LauncherIconManager.Icon.DEFAULT)
        updateIconCard(whiteIcon, selected == LauncherIconManager.Icon.WHITE)
        updateIconCard(goldIcon, selected == LauncherIconManager.Icon.GOLD)
        iconHint.setText(
            if (reviewMode) {
                R.string.play_icon_hint_review
            } else if (unlocked) {
                R.string.play_icon_hint_unlocked
            } else {
                R.string.play_icon_hint_locked
            }
        )
        defaultIcon.contentDescription = iconContentDescription(
            R.string.play_icon_default,
            selected == LauncherIconManager.Icon.DEFAULT,
            unlocked = true
        )
        whiteIcon.contentDescription = iconContentDescription(
            R.string.play_icon_white,
            selected == LauncherIconManager.Icon.WHITE,
            unlocked
        )
        goldIcon.contentDescription = iconContentDescription(
            R.string.play_icon_gold,
            selected == LauncherIconManager.Icon.GOLD,
            unlocked
        )
    }

    private fun updateIconCard(card: MaterialCardView, selected: Boolean) {
        val accent = MaterialColors.getColor(
            card,
            com.google.android.material.R.attr.colorPrimary
        )
        val neutral = MaterialColors.getColor(
            card,
            com.google.android.material.R.attr.colorOutlineVariant
        )
        card.strokeColor = if (selected) accent else neutral
        card.strokeWidth = dp(if (selected) 2 else 1)
        card.isSelected = selected
    }

    private fun iconContentDescription(
        iconNameRes: Int,
        selected: Boolean,
        unlocked: Boolean
    ): String {
        val iconName = getString(iconNameRes)
        return when {
            selected -> getString(R.string.play_icon_accessibility_selected, iconName)
            unlocked -> getString(R.string.play_icon_accessibility_available, iconName)
            else -> getString(R.string.play_icon_accessibility_locked, iconName)
        }
    }

    private fun nudgePurchaseButton() {
        purchaseNudge?.cancel()
        purchase.translationX = 0f
        val offset = dp(8).toFloat()
        purchaseNudge = ObjectAnimator.ofFloat(
            purchase,
            View.TRANSLATION_X,
            0f,
            -offset,
            offset,
            -offset * 0.65f,
            offset * 0.65f,
            0f
        ).apply {
            duration = 420L
            interpolator = DecelerateInterpolator()
            start()
        }
    }

    private fun renderBillingState() {
        val state = latestBillingState
        val reviewMode = PlayEntitlements.isReviewMode(this)
        status.text = when {
            reviewMode && state.owned ->
                getString(R.string.play_support_review_active_owned)
            reviewMode && state.pending ->
                getString(R.string.play_support_review_active_pending)
            reviewMode ->
                getString(R.string.play_support_review_active_not_owned)
            state.owned -> getString(R.string.play_support_owned)
            state.pending -> getString(R.string.play_purchase_pending)
            !state.connected -> getString(R.string.play_billing_connecting)
            else -> getString(R.string.play_support_not_owned)
        }
        purchase.isEnabled =
            state.connected && state.productReady && !state.owned && !state.pending
        purchase.visibility = if (state.owned) View.GONE else View.VISIBLE
        purchase.text = when {
            state.price != null ->
                getString(R.string.play_support_purchase_with_price, state.price)
            state.productLoading ->
                getString(R.string.play_support_loading_product)
            else -> getString(R.string.play_support_purchase)
        }
        refreshIconUi()
    }

    private fun handleReviewTap() {
        val now = SystemClock.elapsedRealtime()
        titleTapCount = if (now - lastTitleTapAt > REVIEW_TAP_TIMEOUT_MS) 1 else titleTapCount + 1
        lastTitleTapAt = now
        if (titleTapCount >= REVIEW_TAP_COUNT) {
            PlayEntitlements.enableReviewMode(this)
            titleTapCount = 0
            Toast.makeText(this, R.string.play_review_enabled, Toast.LENGTH_LONG).show()
            renderBillingState()
        }
    }

    private companion object {
        const val REVIEW_TAP_COUNT = 7
        const val REVIEW_TAP_TIMEOUT_MS = 2_000L
    }
}
