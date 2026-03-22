package com.systemdesign.featureflag

import com.systemdesign.featureflag.approach_01_simple.*
import com.systemdesign.featureflag.approach_02_percentage.*
import com.systemdesign.featureflag.approach_03_rules.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach

class FeatureFlagTest {

    // Simple Feature Flags Tests
    @Test
    fun `simple - returns false for unknown flag`() {
        val flags = SimpleFeatureFlags()
        assertFalse(flags.isEnabled("unknown"))
    }

    @Test
    fun `simple - returns flag value`() {
        val flags = SimpleFeatureFlags()
        flags.setFlag("feature1", true)
        
        assertTrue(flags.isEnabled("feature1"))
    }

    @Test
    fun `simple - user override takes precedence`() {
        val flags = SimpleFeatureFlags()
        flags.setFlag("feature1", false)
        flags.setUserOverride("feature1", "user1", true)
        
        assertFalse(flags.isEnabled("feature1"))
        assertTrue(flags.isEnabled("feature1", FlagContext(userId = "user1")))
        assertFalse(flags.isEnabled("feature1", FlagContext(userId = "user2")))
    }

    @Test
    fun `simple - remove flag clears overrides`() {
        val flags = SimpleFeatureFlags()
        flags.setFlag("feature1", true)
        flags.setUserOverride("feature1", "user1", true)
        flags.removeFlag("feature1")
        
        assertFalse(flags.isEnabled("feature1"))
        assertFalse(flags.isEnabled("feature1", FlagContext(userId = "user1")))
    }

    // Percentage Rollout Tests
    @Test
    fun `percentage - 0 percent disables for all`() {
        val rollout = PercentageRollout()
        rollout.setRollout("feature1", RolloutConfig(percentage = 0))
        
        val results = (1..100).map { rollout.isEnabled("feature1", "user$it") }
        
        assertTrue(results.all { !it })
    }

    @Test
    fun `percentage - 100 percent enables for all`() {
        val rollout = PercentageRollout()
        rollout.setRollout("feature1", RolloutConfig(percentage = 100))
        
        val results = (1..100).map { rollout.isEnabled("feature1", "user$it") }
        
        assertTrue(results.all { it })
    }

    @Test
    fun `percentage - deterministic for same user`() {
        val rollout = PercentageRollout()
        rollout.setRollout("feature1", RolloutConfig(percentage = 50))
        
        val result1 = rollout.isEnabled("feature1", "user1")
        val result2 = rollout.isEnabled("feature1", "user1")
        
        assertEquals(result1, result2)
    }

    @Test
    fun `percentage - blocked user is always disabled`() {
        val rollout = PercentageRollout()
        rollout.setRollout("feature1", RolloutConfig(
            percentage = 100,
            blockedUsers = setOf("blocked-user")
        ))
        
        assertFalse(rollout.isEnabled("feature1", "blocked-user"))
    }

    @Test
    fun `percentage - allowed user is always enabled`() {
        val rollout = PercentageRollout()
        rollout.setRollout("feature1", RolloutConfig(
            percentage = 0,
            allowedUsers = setOf("vip-user")
        ))
        
        assertTrue(rollout.isEnabled("feature1", "vip-user"))
    }

    // Rule-Based Feature Flags Tests
    @Test
    fun `rules - returns default value when no rules match`() {
        val flags = RuleBasedFlags()
        flags.setFlag(FeatureFlag("feature1", defaultValue = true))
        
        assertTrue(flags.isEnabled("feature1"))
    }

    @Test
    fun `rules - equals condition works`() {
        val flags = RuleBasedFlags()
        flags.setFlag(FeatureFlag(
            name = "feature1",
            defaultValue = false,
            rules = listOf(Rule("beta-users", Condition.Equals("userType", "beta")))
        ))
        
        assertFalse(flags.isEnabled("feature1", mapOf("userType" to "normal")))
        assertTrue(flags.isEnabled("feature1", mapOf("userType" to "beta")))
    }

    @Test
    fun `rules - greater than condition works`() {
        val flags = RuleBasedFlags()
        flags.setFlag(FeatureFlag(
            name = "feature1",
            defaultValue = false,
            rules = listOf(Rule("high-level", Condition.GreaterThan("level", 10)))
        ))
        
        assertFalse(flags.isEnabled("feature1", mapOf("level" to 5)))
        assertTrue(flags.isEnabled("feature1", mapOf("level" to 15)))
    }

    @Test
    fun `rules - and condition works`() {
        val flags = RuleBasedFlags()
        flags.setFlag(FeatureFlag(
            name = "feature1",
            defaultValue = false,
            rules = listOf(Rule("premium-beta", Condition.And(listOf(
                Condition.Equals("isPremium", true),
                Condition.Equals("isBeta", true)
            ))))
        ))
        
        assertFalse(flags.isEnabled("feature1", mapOf("isPremium" to true, "isBeta" to false)))
        assertTrue(flags.isEnabled("feature1", mapOf("isPremium" to true, "isBeta" to true)))
    }

    @Test
    fun `rules - or condition works`() {
        val flags = RuleBasedFlags()
        flags.setFlag(FeatureFlag(
            name = "feature1",
            defaultValue = false,
            rules = listOf(Rule("special", Condition.Or(listOf(
                Condition.Equals("isAdmin", true),
                Condition.Equals("isBeta", true)
            ))))
        ))
        
        assertTrue(flags.isEnabled("feature1", mapOf("isAdmin" to true, "isBeta" to false)))
        assertTrue(flags.isEnabled("feature1", mapOf("isAdmin" to false, "isBeta" to true)))
        assertFalse(flags.isEnabled("feature1", mapOf("isAdmin" to false, "isBeta" to false)))
    }

    @Test
    fun `rules - DSL works`() {
        val flags = RuleBasedFlags()
        
        val flag = feature("premium-feature") {
            defaultValue = false
            rule("premium-users") { "subscription" eq "premium" }
            rule("high-spenders", priority = 1) { "totalSpent" gt 1000 }
        }
        
        flags.setFlag(flag)
        
        assertTrue(flags.isEnabled("premium-feature", mapOf("subscription" to "premium")))
        assertTrue(flags.isEnabled("premium-feature", mapOf("totalSpent" to 1500)))
        assertFalse(flags.isEnabled("premium-feature", mapOf("subscription" to "free")))
    }

    @Test
    fun `rules - disabled flag returns false`() {
        val flags = RuleBasedFlags()
        flags.setFlag(FeatureFlag(
            name = "feature1",
            defaultValue = true,
            enabled = false
        ))
        
        assertFalse(flags.isEnabled("feature1"))
    }
}
