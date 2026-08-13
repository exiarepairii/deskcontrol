package com.deskcontrol

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

object FlavorDiagnostics {
    @Suppress("UNUSED_PARAMETER")
    fun snapshot(context: Context): List<String> {
        val binderAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!binderAlive) {
            return listOf("DistributionState: flavor=direct shizukuBinderAlive=false")
        }
        val permission = runCatching {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                "granted"
            } else {
                "denied"
            }
        }.getOrElse { "unavailable:${it.javaClass.simpleName}" }
        val serverUid = runCatching { Shizuku.getUid() }
            .fold({ it.toString() }, { "unavailable:${it.javaClass.simpleName}" })
        val serverVersion = runCatching { Shizuku.getVersion() }
            .fold({ it.toString() }, { "unavailable:${it.javaClass.simpleName}" })
        return listOf(
            "DistributionState: flavor=direct shizukuBinderAlive=true permission=$permission " +
                "serverUid=$serverUid serverVersion=$serverVersion"
        )
    }
}
