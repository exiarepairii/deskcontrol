package com.deskcontrol

internal class ContinuousGestureIdleCallbacks {
    private val callbacks = ArrayDeque<() -> Unit>()

    fun add(callback: () -> Unit) {
        callbacks.addLast(callback)
    }

    fun dispatch() {
        if (callbacks.isEmpty()) return
        val pending = callbacks.toList()
        callbacks.clear()
        pending.forEach { it() }
    }

    val isEmpty: Boolean
        get() = callbacks.isEmpty()
}
