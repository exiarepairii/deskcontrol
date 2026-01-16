package com.deskcontrol

import android.view.Surface

object CoordinateMapper {
    data class Point(val x: Float, val y: Float)

    fun mapForRotation(x: Float, y: Float, info: DisplaySessionManager.ExternalDisplayInfo): Point {
        return when (info.rotation) {
            Surface.ROTATION_90 -> Point(info.height - y, x)
            Surface.ROTATION_180 -> Point(info.width - x, info.height - y)
            Surface.ROTATION_270 -> Point(y, info.width - x)
            else -> Point(x, y)
        }
    }
}
