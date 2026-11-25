package com.badshah222.rani.ai.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class AwarenessInfo(
    val packageName: String,
    val appLabel: String,
    val sinceMs: Long,
    val history: List<Pair<String, Long>> = emptyList(),
)

/**
 * Stonic-style Awareness: which app/window is in front and for how long.
 * Fed by [RaniAccessibilityService] window-state events (on-device only).
 * Nothing leaves the phone. The monitor only records while [enabled] is true;
 * the top-bar toggle (default ON, Stonic-style) and the admin panel's remote
 * config can switch it off at any time.
 */
object AwarenessMonitor {
    @Volatile var enabled = true

    private val _current = MutableStateFlow<AwarenessInfo?>(null)
    val current: StateFlow<AwarenessInfo?> = _current

    fun onWindow(packageName: String, appLabel: String) {
        if (!enabled) return
        val now = System.currentTimeMillis()
        val prev = _current.value
        if (prev?.packageName == packageName) return
        val hist = buildList {
            prev?.let { add(it.packageName to it.sinceMs) }
            addAll(prev?.history ?: emptyList())
        }.take(9)
        _current.value = AwarenessInfo(packageName, appLabel, now, hist)
    }

    fun minutesOnCurrentApp(): Long {
        val c = _current.value ?: return 0
        return (System.currentTimeMillis() - c.sinceMs) / 60000
    }

    fun stop() {
        _current.value = null
    }
}
