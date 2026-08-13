package com.deskcontrol

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.media.MediaRouter
import android.os.Build
import android.os.Process
import android.os.UserManager
import android.provider.Settings
import java.security.MessageDigest
import java.util.Locale

object DiagnosticsEnvironment {
    private val globalSettingKeys = listOf(
        "development_settings_enabled",
        "adb_enabled",
        "adb_wifi_enabled",
        "wifi_display_on",
        "wifi_display_certification_on",
        "overlay_display_devices",
        "force_resizable_activities",
        "enable_freeform_support",
        "override_desktop_experience_features",
        "override_desktop_mode_features",
        "force_desktop_mode_on_external_displays",
        "enable_non_resizable_multi_window",
        "disable_screen_share_protections_for_apps_and_notifications"
    )
    private val relevantPackages = listOf(
        "com.milink.service",
        "com.xiaomi.miplay_client",
        "com.xiaomi.mi_connect_service",
        "com.xiaomi.mirror",
        "com.miui.screenrecorder",
        "com.miui.securitycenter",
        "moe.shizuku.privileged.api"
    )

    fun snapshot(context: Context): List<String> {
        val packageManager = context.packageManager
        return buildList {
            add(appLine(context, packageManager))
            add(buildLine())
            add(buildVersionLine())
            add(runtimeLine(context, packageManager))
            add(featureLine(packageManager))
            add(mediaRouteLine(context))
            add(usbLine(context))
            add(
                "GlobalSettings: " + globalSettingKeys.joinToString(" ") { key ->
                    "$key=${globalSetting(context, key)}"
                }
            )
            relevantPackages.forEach { packageName ->
                add(packageLine(packageManager, packageName))
            }
            addAll(FlavorDiagnostics.snapshot(context))
        }
    }

    private fun appLine(context: Context, packageManager: PackageManager): String {
        val packageInfo = getPackageInfo(
            packageManager,
            context.packageName,
            PackageManager.GET_SIGNING_CERTIFICATES
        )
        val applicationInfo = context.applicationInfo
        val source = runCatching { packageManager.getInstallSourceInfo(context.packageName) }
            .fold(
                onSuccess = {
                    "installing=${clean(it.installingPackageName)};" +
                        "initiating=${clean(it.initiatingPackageName)};" +
                        "originating=${clean(it.originatingPackageName)}"
                },
                onFailure = { "unavailable:${it.javaClass.simpleName}" }
            )
        val signer = packageInfo?.signingInfo?.let { signingInfo ->
            val signatures = if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
            signatures.firstOrNull()?.toByteArray()?.let(::sha256)
        } ?: "unavailable"
        return "EnvironmentApp: package=${context.packageName} " +
            "versionName=${clean(packageInfo?.versionName)} versionCode=${packageInfo?.longVersionCode ?: -1} " +
            "minSdk=${applicationInfo.minSdkVersion} targetSdk=${applicationInfo.targetSdkVersion} " +
            "uid=${Process.myUid()} user=${Process.myUid() / 100000} " +
            "debuggable=${applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0} " +
            "signerSha256=$signer installSource=[$source]"
    }

    private fun buildLine(): String {
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            "${clean(Build.SOC_MANUFACTURER)}/${clean(Build.SOC_MODEL)}"
        } else {
            "unsupported_api"
        }
        return "EnvironmentBuild: manufacturer=${clean(Build.MANUFACTURER)} brand=${clean(Build.BRAND)} " +
            "model=${clean(Build.MODEL)} device=${clean(Build.DEVICE)} product=${clean(Build.PRODUCT)} " +
            "board=${clean(Build.BOARD)} hardware=${clean(Build.HARDWARE)} soc=$soc " +
            "type=${clean(Build.TYPE)} tags=${clean(Build.TAGS)} abis=${Build.SUPPORTED_ABIS.joinToString(",")}"
    }

    private fun buildVersionLine(): String {
        return "EnvironmentVersion: sdk=${Build.VERSION.SDK_INT} " +
            "release=${clean(Build.VERSION.RELEASE)} codename=${clean(Build.VERSION.CODENAME)} " +
            "incremental=${clean(Build.VERSION.INCREMENTAL)} securityPatch=${clean(Build.VERSION.SECURITY_PATCH)} " +
            "baseOs=${clean(Build.VERSION.BASE_OS)} buildId=${clean(Build.ID)} " +
            "display=${clean(Build.DISPLAY)} fingerprint=${clean(Build.FINGERPRINT)}"
    }

    private fun runtimeLine(context: Context, packageManager: PackageManager): String {
        val userManager = context.getSystemService(UserManager::class.java)
        return "EnvironmentRuntime: managedProfile=${runCatching { userManager?.isManagedProfile }.getOrNull()} " +
            "systemUser=${runCatching { userManager?.isSystemUser }.getOrNull()} " +
            "instantApp=${packageManager.isInstantApp}"
    }

    private fun featureLine(packageManager: PackageManager): String {
        return "EnvironmentFeatures: secondaryDisplays=" +
            packageManager.hasSystemFeature(PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS) +
            " freeform=" +
            packageManager.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT) +
            " pictureInPicture=" +
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    @Suppress("DEPRECATION")
    private fun mediaRouteLine(context: Context): String {
        return runCatching {
            val router = context.getSystemService(MediaRouter::class.java)
            val route = router?.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_VIDEO)
            val presentation = route?.presentationDisplay
            "MediaRoute: selectedLiveVideoName=${clean(route?.name)} " +
                "description=${clean(route?.description)} enabled=${route?.isEnabled} " +
                "connecting=${route?.isConnecting} presentationDisplayId=${presentation?.displayId ?: "none"} " +
                "presentationDisplayName=${clean(presentation?.name)}"
        }.getOrElse { "MediaRoute: unavailable:${it.javaClass.simpleName}" }
    }

    private fun usbLine(context: Context): String {
        return runCatching {
            val devices = context.getSystemService(UsbManager::class.java)
                ?.deviceList
                ?.values
                .orEmpty()
                .sortedWith(compareBy({ it.vendorId }, { it.productId }, { it.deviceClass }))
            val summary = devices.joinToString(";") {
                "vid=0x${it.vendorId.toString(16).padStart(4, '0')}," +
                    "pid=0x${it.productId.toString(16).padStart(4, '0')}," +
                    "class=${it.deviceClass}/${it.deviceSubclass}/${it.deviceProtocol}"
            }.ifEmpty { "none" }
            "UsbDevices: count=${devices.size} devices=[$summary]"
        }.getOrElse { "UsbDevices: unavailable:${it.javaClass.simpleName}" }
    }

    private fun globalSetting(context: Context, key: String): String {
        return runCatching { Settings.Global.getString(context.contentResolver, key) }
            .fold(
                onSuccess = { clean(it).replace(' ', '_') },
                onFailure = { "unavailable:${it.javaClass.simpleName}" }
            )
    }

    private fun packageLine(packageManager: PackageManager, packageName: String): String {
        val packageInfo = getPackageInfo(packageManager, packageName, PackageManager.GET_META_DATA)
            ?: return "RelatedPackage: package=$packageName visibleInstalled=false"
        val applicationInfo = packageInfo.applicationInfo
        val metadata = if (packageName == "com.milink.service") {
            val opensdkVersion = applicationInfo?.metaData?.let {
                bundleValue(it, "com.xiaomi.miplay.opensdk.version")
            }
            val opensdkMinSdk = applicationInfo?.metaData?.let {
                bundleValue(it, "com.xiaomi.miplay.opensdk.minSdk")
            }
            " openSdkVersion=${clean(opensdkVersion)} openSdkMinSdk=${clean(opensdkMinSdk)}"
        } else {
            ""
        }
        val enabledSetting = runCatching {
            packageManager.getApplicationEnabledSetting(packageName)
        }.fold({ it.toString() }, { "unavailable:${it.javaClass.simpleName}" })
        return "RelatedPackage: package=$packageName visibleInstalled=true " +
            "versionName=${clean(packageInfo.versionName)} versionCode=${packageInfo.longVersionCode} " +
            "enabled=${applicationInfo?.enabled} enabledSetting=$enabledSetting " +
            "targetSdk=${applicationInfo?.targetSdkVersion ?: -1} " +
            "system=${applicationInfo?.flags?.and(ApplicationInfo.FLAG_SYSTEM) != 0} " +
            "updatedSystem=${applicationInfo?.flags?.and(ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0} " +
            "lastUpdateTime=${packageInfo.lastUpdateTime}$metadata"
    }

    private fun getPackageInfo(
        packageManager: PackageManager,
        packageName: String,
        flags: Int
    ): PackageInfo? {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(flags.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, flags)
            }
        }.getOrNull()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }

    private fun bundleValue(bundle: android.os.Bundle, key: String): String? {
        if (!bundle.containsKey(key)) return null
        bundle.getString(key)?.let { return it }
        return bundle.getInt(key).toString()
    }

    private fun clean(value: Any?): String = value
        ?.toString()
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.ifEmpty { "unset" }
        ?.take(200)
        ?: "unset"
}
