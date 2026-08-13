package com.deskcontrol

/** Keeps only the newest notice for a state signature and delivers it at most once. */
internal class OneShotNoticeState<T> {
    enum class UpdateAction {
        UNCHANGED,
        QUEUED,
        SUPERSEDED,
        CLEARED
    }

    private var activeSignature: String? = null
    private var pendingNotice: T? = null

    @Synchronized
    fun update(signature: String?, notice: T?): UpdateAction {
        if (signature == activeSignature) {
            if (pendingNotice != null && notice != null) {
                pendingNotice = notice
            }
            return UpdateAction.UNCHANGED
        }

        val previousSignature = activeSignature
        val hadPendingNotice = pendingNotice != null
        activeSignature = signature
        pendingNotice = notice
        return when {
            notice != null && hadPendingNotice -> UpdateAction.SUPERSEDED
            notice != null -> UpdateAction.QUEUED
            previousSignature != null -> UpdateAction.CLEARED
            else -> UpdateAction.UNCHANGED
        }
    }

    @Synchronized
    fun consume(): T? {
        val notice = pendingNotice
        pendingNotice = null
        return notice
    }
}
