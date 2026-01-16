package com.deskcontrol

object SessionStore {
    @Volatile
    var lastLaunchFailure: String? = null

    @Volatile
    var lastInjectionResult: String? = null

    fun clear() {
        lastLaunchFailure = null
        lastInjectionResult = null
    }
}
