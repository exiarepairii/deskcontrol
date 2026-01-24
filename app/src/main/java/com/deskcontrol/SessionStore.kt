package com.deskcontrol

object SessionStore {
    @Volatile
    var lastLaunchFailure: String? = null

    @Volatile
    var lastInjectionResult: String? = null

    @Volatile
    var lastBackWarmupUptime: Long = 0L

    @Volatile
    var lastBackFailure: String? = null

    @Volatile
    var lastLaunchedPackage: String? = null

    fun clear() {
        lastLaunchFailure = null
        lastInjectionResult = null
        lastBackWarmupUptime = 0L
        lastBackFailure = null
        lastLaunchedPackage = null
    }
}
