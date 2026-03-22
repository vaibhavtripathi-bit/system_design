/**
 * # Approach 01: Simple Feature Flags
 *
 * ## Pattern Used
 * Basic boolean flags with optional user targeting.
 *
 * ## Trade-offs
 * - **Pros:** Simple, low overhead, easy to understand
 * - **Cons:** No gradual rollout, binary on/off only
 *
 * ## When to Prefer
 * - Simple feature toggles
 * - Kill switches
 */
package com.systemdesign.featureflag.approach_01_simple

import java.util.concurrent.ConcurrentHashMap

data class FlagContext(
    val userId: String? = null,
    val attributes: Map<String, Any> = emptyMap()
)

class SimpleFeatureFlags {
    private val flags = ConcurrentHashMap<String, Boolean>()
    private val userOverrides = ConcurrentHashMap<String, ConcurrentHashMap<String, Boolean>>()

    fun setFlag(name: String, enabled: Boolean) {
        flags[name] = enabled
    }

    fun setUserOverride(flagName: String, userId: String, enabled: Boolean) {
        userOverrides.getOrPut(flagName) { ConcurrentHashMap() }[userId] = enabled
    }

    fun isEnabled(name: String, context: FlagContext = FlagContext()): Boolean {
        context.userId?.let { userId ->
            userOverrides[name]?.get(userId)?.let { return it }
        }
        return flags[name] ?: false
    }

    fun removeFlag(name: String) {
        flags.remove(name)
        userOverrides.remove(name)
    }

    fun clear() {
        flags.clear()
        userOverrides.clear()
    }

    fun getAllFlags(): Map<String, Boolean> = flags.toMap()
}
