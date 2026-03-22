/**
 * # Approach 02: Hook-Based Plugin System
 *
 * ## Pattern Used
 * Event-driven with extension points (hooks) for plugins to intercept.
 *
 * ## Trade-offs
 * - **Pros:** Flexible, plugins can modify behavior, decoupled
 * - **Cons:** Harder to debug, execution order matters
 */
package com.systemdesign.pluginsystem.approach_02_hooks

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

typealias HookCallback<T> = (T) -> T

data class HookRegistration(
    val pluginId: String,
    val priority: Int = 0
)

class HookSystem {
    private val hooks = ConcurrentHashMap<String, CopyOnWriteArrayList<Pair<HookRegistration, HookCallback<*>>>>()

    @Suppress("UNCHECKED_CAST")
    fun <T> addFilter(hookName: String, pluginId: String, priority: Int = 0, callback: HookCallback<T>) {
        val list = hooks.getOrPut(hookName) { CopyOnWriteArrayList() }
        list.add(HookRegistration(pluginId, priority) to callback)
        list.sortBy { it.first.priority }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> applyFilters(hookName: String, value: T): T {
        val callbacks = hooks[hookName] ?: return value
        var result = value
        
        for ((_, callback) in callbacks) {
            result = (callback as HookCallback<T>)(result)
        }
        
        return result
    }

    fun removeFilters(pluginId: String) {
        hooks.values.forEach { list ->
            list.removeIf { it.first.pluginId == pluginId }
        }
    }

    fun hasHook(hookName: String): Boolean = hooks.containsKey(hookName) && hooks[hookName]!!.isNotEmpty()

    fun clear() = hooks.clear()
}

class HookablePluginManager {
    private val hooks = HookSystem()
    private val plugins = ConcurrentHashMap<String, HookablePlugin>()
    private val enabled = ConcurrentHashMap.newKeySet<String>()

    fun register(plugin: HookablePlugin) {
        plugins[plugin.id] = plugin
    }

    fun enable(pluginId: String): Boolean {
        val plugin = plugins[pluginId] ?: return false
        if (enabled.contains(pluginId)) return true
        
        plugin.registerHooks(hooks)
        enabled.add(pluginId)
        return true
    }

    fun disable(pluginId: String): Boolean {
        if (!enabled.contains(pluginId)) return true
        hooks.removeFilters(pluginId)
        enabled.remove(pluginId)
        return true
    }

    fun <T> applyFilters(hookName: String, value: T): T = hooks.applyFilters(hookName, value)

    fun shutdown() {
        enabled.forEach { disable(it) }
        plugins.clear()
    }
}

interface HookablePlugin {
    val id: String
    fun registerHooks(hooks: HookSystem)
}
