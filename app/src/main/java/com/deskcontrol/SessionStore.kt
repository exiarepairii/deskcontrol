package com.deskcontrol

object SessionStore {
    @Volatile
    var lastLaunchFailure: String? = null

    @Volatile
    var lastInjectionResult: String? = null

    @Volatile
    var lastBackWarmupUptime: Long = 0L

    fun clear() {
        lastLaunchFailure = null
        lastInjectionResult = null
        lastBackWarmupUptime = 0L
    }
}
