package com.deskcontrol

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.UnfetchedProduct

class SupporterBillingManager(
    context: Context,
    private val listener: Listener
) : PurchasesUpdatedListener {

    interface Listener {
        fun onBillingStateChanged(state: State)
        fun onBillingMessage(messageRes: Int)
    }

    data class State(
        val connected: Boolean = false,
        val productLoading: Boolean = false,
        val productReady: Boolean = false,
        val owned: Boolean = false,
        val price: String? = null,
        val pending: Boolean = false
    )

    private val appContext = context.applicationContext
    private var productDetails: ProductDetails? = null
    private var connecting = false
    private var closed = false
    private var state = State(owned = PlayEntitlements.hasSupporterPack(appContext))
    private val billingClient = BillingClient.newBuilder(appContext)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .build()
        )
        .enableAutoServiceReconnection()
        .build()

    fun start() {
        if (closed) return
        if (billingClient.isReady) {
            connecting = false
            if (!state.connected) update(state.copy(connected = true))
            refresh()
            return
        }
        if (connecting) {
            DiagnosticsLog.add("Billing connect skipped: connection already in progress")
            return
        }
        connecting = true
        DiagnosticsLog.add("Billing connect start")
        billingClient.startConnection(
            object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (closed) return
                    connecting = false
                    logResult("setup", result)
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                        update(state.copy(connected = true))
                        refresh()
                    } else {
                        productDetails = null
                        update(
                            state.copy(
                                connected = false,
                                productLoading = false,
                                productReady = false,
                                price = null
                            )
                        )
                        notifyMessage(messageForBillingFailure(result.responseCode))
                    }
                }

                override fun onBillingServiceDisconnected() {
                    if (closed) return
                    connecting = false
                    productDetails = null
                    DiagnosticsLog.add("Billing service disconnected; automatic reconnect enabled")
                    update(
                        state.copy(
                            connected = false,
                            productLoading = false,
                            productReady = false,
                            price = null
                        )
                    )
                }
            }
        )
    }

    fun refresh(showRestoreErrors: Boolean = false) {
        if (closed) return
        if (!billingClient.isReady) {
            DiagnosticsLog.add(
                "Billing refresh deferred: client not ready userInitiated=$showRestoreErrors"
            )
            start()
            return
        }
        queryProduct()
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        ) { result, purchases ->
            if (closed) return@queryPurchasesAsync
            logResult("queryPurchases", result)
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                processPurchases(purchases)
            } else {
                // Offline or unavailable Play services must never revoke the durable local
                // entitlement. Only an authoritative successful query is allowed to reconcile it.
                DiagnosticsLog.add(
                    "Billing purchases unavailable: keeping cached entitlement owned=${state.owned}"
                )
                if (showRestoreErrors) {
                    notifyMessage(messageForBillingFailure(result.responseCode))
                }
            }
        }
    }

    fun launchPurchase(activity: Activity) {
        if (!billingClient.isReady) {
            DiagnosticsLog.add("Billing launch blocked: client not ready")
            notifyMessage(R.string.play_billing_service_unavailable)
            start()
            return
        }
        val details = productDetails
        val offer = details?.oneTimePurchaseOfferDetailsList?.firstOrNull()
        val offerToken = offer?.offerToken
        if (details == null || offerToken.isNullOrBlank()) {
            DiagnosticsLog.add(
                "Billing launch blocked: productReady=${state.productReady} " +
                    "details=${details != null} offerToken=${!offerToken.isNullOrBlank()}"
            )
            notifyMessage(R.string.play_product_still_loading)
            queryProduct()
            return
        }
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .setOfferToken(offerToken)
            .build()
        val result = billingClient.launchBillingFlow(
            activity,
            BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(listOf(productParams))
                .build()
        )
        logResult("launchBillingFlow", result)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            handlePurchaseFailure(result.responseCode)
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        if (closed) return
        logResult("purchasesUpdated", result)
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> processPurchases(purchases.orEmpty())
            BillingClient.BillingResponseCode.USER_CANCELED -> Unit
            else -> handlePurchaseFailure(result.responseCode)
        }
    }

    fun close() {
        closed = true
        connecting = false
        DiagnosticsLog.add("Billing client closed")
        billingClient.endConnection()
    }

    private fun queryProduct() {
        if (!billingClient.isReady) {
            DiagnosticsLog.add("Billing product query deferred: client not ready")
            start()
            return
        }
        productDetails = null
        update(
            state.copy(
                connected = true,
                productLoading = true,
                productReady = false,
                price = null
            )
        )
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(PRODUCT_ID)
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        billingClient.queryProductDetailsAsync(
            QueryProductDetailsParams.newBuilder()
                .setProductList(listOf(product))
                .build()
        ) { result, detailsResult ->
            if (closed) return@queryProductDetailsAsync
            logResult("queryProductDetails", result)
            DiagnosticsLog.add(
                "Billing product result: fetched=${detailsResult.productDetailsList.size} " +
                    "unfetched=${detailsResult.unfetchedProductList.joinToString { item ->
                        "${item.productId}:${item.statusCode}"
                    }}"
            )
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                update(
                    state.copy(
                        productLoading = false,
                        productReady = false,
                        price = null
                    )
                )
                notifyMessage(messageForBillingFailure(result.responseCode))
                return@queryProductDetailsAsync
            }

            val details = detailsResult.productDetailsList
                .firstOrNull { it.productId == PRODUCT_ID }
            if (details == null) {
                update(
                    state.copy(
                        productLoading = false,
                        productReady = false,
                        price = null
                    )
                )
                val status = detailsResult.unfetchedProductList
                    .firstOrNull { it.productId == PRODUCT_ID }
                    ?.statusCode
                notifyMessage(messageForUnfetchedProduct(status))
                return@queryProductDetailsAsync
            }

            val offer = details.oneTimePurchaseOfferDetailsList?.firstOrNull()
            val ready = !offer?.offerToken.isNullOrBlank()
            productDetails = if (ready) details else null
            DiagnosticsLog.add(
                "Billing product matched: offers=" +
                    "${details.oneTimePurchaseOfferDetailsList?.size ?: 0} ready=$ready"
            )
            update(
                state.copy(
                    productLoading = false,
                    productReady = ready,
                    price = offer?.formattedPrice
                )
            )
            if (!ready) notifyMessage(R.string.play_product_offer_missing)
        }
    }

    private fun processPurchases(purchases: List<Purchase>) {
        val matching = purchases.firstOrNull { PRODUCT_ID in it.products }
        val purchased = matching?.purchaseState == Purchase.PurchaseState.PURCHASED
        val pending = matching?.purchaseState == Purchase.PurchaseState.PENDING
        val wasPending = state.pending
        DiagnosticsLog.add(
            "Billing purchases processed: count=${purchases.size} match=${matching != null} " +
                "state=${matching?.purchaseState ?: "none"} " +
                "acknowledged=${matching?.isAcknowledged ?: false}"
        )
        PlayEntitlements.setSupporterPack(appContext, purchased)
        if (!purchased &&
            !PlayEntitlements.isReviewMode(appContext) &&
            LauncherIconManager.selected(appContext) != LauncherIconManager.Icon.DEFAULT
        ) {
            val resetResult = LauncherIconManager.apply(
                appContext,
                LauncherIconManager.Icon.DEFAULT
            )
            DiagnosticsLog.add("Billing entitlement revoked: resetIcon=$resetResult")
        }
        update(
            state.copy(
                owned = purchased,
                pending = pending
            )
        )
        if (pending && !wasPending) notifyMessage(R.string.play_purchase_pending)
        if (purchased && matching != null && !matching.isAcknowledged) {
            billingClient.acknowledgePurchase(
                AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(matching.purchaseToken)
                    .build()
            ) { result ->
                if (closed) return@acknowledgePurchase
                logResult("acknowledgePurchase", result)
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    notifyMessage(R.string.play_acknowledge_failed)
                }
            }
        }
    }

    private fun handlePurchaseFailure(responseCode: Int) {
        when (responseCode) {
            BillingClient.BillingResponseCode.ITEM_UNAVAILABLE ->
                notifyMessage(R.string.play_purchase_item_unavailable)
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                notifyMessage(R.string.play_purchase_already_owned)
                refresh(showRestoreErrors = true)
            }
            BillingClient.BillingResponseCode.BILLING_UNAVAILABLE,
            BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED,
            BillingClient.BillingResponseCode.DEVELOPER_ERROR,
            BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
            BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
            BillingClient.BillingResponseCode.NETWORK_ERROR,
            BillingClient.BillingResponseCode.ERROR ->
                notifyMessage(messageForBillingFailure(responseCode))
            else -> notifyMessage(R.string.play_purchase_failed)
        }
    }

    private fun messageForBillingFailure(responseCode: Int): Int = when (responseCode) {
        BillingClient.BillingResponseCode.BILLING_UNAVAILABLE ->
            R.string.play_billing_account_unavailable
        BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED ->
            R.string.play_billing_feature_not_supported
        BillingClient.BillingResponseCode.DEVELOPER_ERROR ->
            R.string.play_billing_configuration_error
        BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
        BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
        BillingClient.BillingResponseCode.NETWORK_ERROR,
        BillingClient.BillingResponseCode.ERROR ->
            R.string.play_billing_service_unavailable
        else -> R.string.play_billing_unknown_error
    }

    private fun messageForUnfetchedProduct(statusCode: Int?): Int = when (statusCode) {
        UnfetchedProduct.StatusCode.INVALID_PRODUCT_ID_FORMAT ->
            R.string.play_product_id_invalid
        UnfetchedProduct.StatusCode.PRODUCT_NOT_FOUND ->
            R.string.play_product_not_found
        UnfetchedProduct.StatusCode.NO_ELIGIBLE_OFFER ->
            R.string.play_product_no_eligible_offer
        else -> R.string.play_product_details_unknown
    }

    private fun logResult(operation: String, result: BillingResult) {
        DiagnosticsLog.add(
            "Billing $operation: code=${result.responseCode} message=${result.debugMessage}"
        )
    }

    private fun notifyMessage(messageRes: Int) {
        listener.onBillingMessage(messageRes)
    }

    private fun update(next: State) {
        state = next
        listener.onBillingStateChanged(next)
    }

    companion object {
        const val PRODUCT_ID = "supporter_icon_pack"
    }
}
