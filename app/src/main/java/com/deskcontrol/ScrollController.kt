package com.deskcontrol

import android.view.MotionEvent

interface ScrollController {
    fun enter(service: ControlAccessibilityService, event: MotionEvent): Boolean
    fun update(event: MotionEvent)
    fun exit()
}

