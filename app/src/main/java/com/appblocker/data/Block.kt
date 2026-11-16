package com.appblocker.data

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * One "block": a group of apps that share the same hold-to-open rule.
 *
 * Opening any app in [packages] shows the black hold screen. Holding the button
 * for [holdSeconds] without letting go unlocks every app in the block for
 * [sessionMinutes]; when that runs out the user is sent to the home screen.
 */
@Serializable
data class Block(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val enabled: Boolean = true,
    /** Android package names, e.g. "com.instagram.android". */
    val packages: Set<String> = emptySet(),
    val holdSeconds: Int = DEFAULT_HOLD_SECONDS,
    val sessionMinutes: Int = DEFAULT_SESSION_MINUTES,
) {
    companion object {
        const val DEFAULT_HOLD_SECONDS = 120
        const val DEFAULT_SESSION_MINUTES = 10
    }
}
