/**
 * # Approach 02: Percentage-Based Rollout
 *
 * ## Pattern Used
 * Gradual rollout based on user ID hash percentage.
 * Deterministic - same user always gets same result.
 *
 * ## Trade-offs
 * - **Pros:** Gradual rollout, A/B testing, consistent user experience
 * - **Cons:** Requires user ID, slightly more complex
 *
 * ## When to Prefer
 * - A/B testing
 * - Gradual feature rollout
 */
package com.systemdesign.featureflag.approach_02_percentage

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

data class RolloutConfig(
    val percentage: Int,
    val enabled: Boolean = true,
    val allowedUsers: Set<String> = emptySet(),
    val blockedUsers: Set<String> = emptySet()
) {
    init {
        require(percentage in 0..100) { "Percentage must be between 0 and 100" }
    }
}

class PercentageRollout {
    private val configs = ConcurrentHashMap<String, RolloutConfig>()

    fun setRollout(flagName: String, config: RolloutConfig) {
        configs[flagName] = config
    }

    fun setPercentage(flagName: String, percentage: Int) {
        configs[flagName] = configs[flagName]?.copy(percentage = percentage)
            ?: RolloutConfig(percentage)
    }

    fun isEnabled(flagName: String, userId: String): Boolean {
        val config = configs[flagName] ?: return false
        
        if (!config.enabled) return false
        if (config.blockedUsers.contains(userId)) return false
        if (config.allowedUsers.contains(userId)) return true
        
        val hash = hashUserId(flagName, userId)
        return hash < config.percentage
    }

    private fun hashUserId(flagName: String, userId: String): Int {
        val input = "$flagName:$userId"
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        val hash = ((digest[0].toInt() and 0xFF) shl 8) or (digest[1].toInt() and 0xFF)
        return (hash % 100 + 100) % 100
    }

    fun getConfig(flagName: String): RolloutConfig? = configs[flagName]

    fun removeFlag(flagName: String) = configs.remove(flagName)

    fun clear() = configs.clear()
}
