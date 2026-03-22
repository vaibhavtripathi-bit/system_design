/**
 * # Approach 03: Rule-Based Feature Flags
 *
 * ## Pattern Used
 * Flexible rule engine with conditions and targeting.
 *
 * ## Trade-offs
 * - **Pros:** Very flexible, complex targeting, multiple conditions
 * - **Cons:** More complex, potential performance impact
 *
 * ## When to Prefer
 * - Complex targeting requirements
 * - Enterprise applications
 */
package com.systemdesign.featureflag.approach_03_rules

import java.util.concurrent.ConcurrentHashMap

sealed class Condition {
    abstract fun evaluate(context: Map<String, Any>): Boolean

    data class Equals(val key: String, val value: Any) : Condition() {
        override fun evaluate(context: Map<String, Any>) = context[key] == value
    }

    data class Contains(val key: String, val value: String) : Condition() {
        override fun evaluate(context: Map<String, Any>) =
            (context[key] as? String)?.contains(value) == true
    }

    data class GreaterThan(val key: String, val value: Number) : Condition() {
        override fun evaluate(context: Map<String, Any>): Boolean {
            val contextValue = context[key] as? Number ?: return false
            return contextValue.toDouble() > value.toDouble()
        }
    }

    data class LessThan(val key: String, val value: Number) : Condition() {
        override fun evaluate(context: Map<String, Any>): Boolean {
            val contextValue = context[key] as? Number ?: return false
            return contextValue.toDouble() < value.toDouble()
        }
    }

    data class InList(val key: String, val values: List<Any>) : Condition() {
        override fun evaluate(context: Map<String, Any>) = context[key] in values
    }

    data class And(val conditions: List<Condition>) : Condition() {
        override fun evaluate(context: Map<String, Any>) =
            conditions.all { it.evaluate(context) }
    }

    data class Or(val conditions: List<Condition>) : Condition() {
        override fun evaluate(context: Map<String, Any>) =
            conditions.any { it.evaluate(context) }
    }

    data class Not(val condition: Condition) : Condition() {
        override fun evaluate(context: Map<String, Any>) = !condition.evaluate(context)
    }
}

data class Rule(
    val name: String,
    val condition: Condition,
    val enabled: Boolean = true,
    val priority: Int = 0
)

data class FeatureFlag(
    val name: String,
    val defaultValue: Boolean = false,
    val rules: List<Rule> = emptyList(),
    val enabled: Boolean = true
)

class RuleBasedFlags {
    private val flags = ConcurrentHashMap<String, FeatureFlag>()

    fun setFlag(flag: FeatureFlag) {
        flags[flag.name] = flag
    }

    fun isEnabled(flagName: String, context: Map<String, Any> = emptyMap()): Boolean {
        val flag = flags[flagName] ?: return false
        if (!flag.enabled) return false

        val matchingRules = flag.rules
            .filter { it.enabled }
            .sortedByDescending { it.priority }

        for (rule in matchingRules) {
            if (rule.condition.evaluate(context)) {
                return true
            }
        }

        return flag.defaultValue
    }

    fun addRule(flagName: String, rule: Rule) {
        flags.computeIfPresent(flagName) { _, flag ->
            flag.copy(rules = flag.rules + rule)
        }
    }

    fun removeRule(flagName: String, ruleName: String) {
        flags.computeIfPresent(flagName) { _, flag ->
            flag.copy(rules = flag.rules.filter { it.name != ruleName })
        }
    }

    fun getFlag(flagName: String): FeatureFlag? = flags[flagName]

    fun removeFlag(flagName: String) = flags.remove(flagName)

    fun clear() = flags.clear()
}

fun feature(name: String, block: FeatureFlagBuilder.() -> Unit): FeatureFlag {
    return FeatureFlagBuilder(name).apply(block).build()
}

class FeatureFlagBuilder(private val name: String) {
    var defaultValue = false
    var enabled = true
    private val rules = mutableListOf<Rule>()

    fun rule(name: String, priority: Int = 0, block: RuleBuilder.() -> Condition) {
        rules.add(Rule(name, RuleBuilder().block(), priority = priority))
    }

    fun build() = FeatureFlag(name, defaultValue, rules, enabled)
}

class RuleBuilder {
    infix fun String.eq(value: Any) = Condition.Equals(this, value)
    infix fun String.contains(value: String) = Condition.Contains(this, value)
    infix fun String.gt(value: Number) = Condition.GreaterThan(this, value)
    infix fun String.lt(value: Number) = Condition.LessThan(this, value)
    infix fun String.inList(values: List<Any>) = Condition.InList(this, values)
    fun and(vararg conditions: Condition) = Condition.And(conditions.toList())
    fun or(vararg conditions: Condition) = Condition.Or(conditions.toList())
    fun not(condition: Condition) = Condition.Not(condition)
}
