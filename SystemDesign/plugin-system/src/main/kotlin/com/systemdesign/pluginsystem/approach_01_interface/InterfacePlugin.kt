/**
 * # Approach 01: Interface-Based Plugin System
 *
 * ## Pattern Used
 * Plugins implement a common interface, registered and discovered at runtime.
 *
 * ## Trade-offs
 * - **Pros:** Type-safe, simple, compile-time checking
 * - **Cons:** All plugins must implement same interface
 */
package com.systemdesign.pluginsystem.approach_01_interface

import java.util.concurrent.ConcurrentHashMap

interface Plugin {
    val id: String
    val name: String
    val version: String
    
    fun initialize()
    fun shutdown()
}

interface PluginContext {
    fun getService(name: String): Any?
    fun registerService(name: String, service: Any)
}

class PluginManager : PluginContext {
    private val plugins = ConcurrentHashMap<String, Plugin>()
    private val services = ConcurrentHashMap<String, Any>()
    private val enabledPlugins = ConcurrentHashMap.newKeySet<String>()

    fun register(plugin: Plugin) {
        plugins[plugin.id] = plugin
    }

    fun unregister(pluginId: String) {
        plugins[pluginId]?.let { plugin ->
            if (enabledPlugins.contains(pluginId)) {
                disable(pluginId)
            }
            plugins.remove(pluginId)
        }
    }

    fun enable(pluginId: String): Boolean {
        val plugin = plugins[pluginId] ?: return false
        if (enabledPlugins.contains(pluginId)) return true
        
        try {
            plugin.initialize()
            enabledPlugins.add(pluginId)
            return true
        } catch (e: Exception) {
            return false
        }
    }

    fun disable(pluginId: String): Boolean {
        val plugin = plugins[pluginId] ?: return false
        if (!enabledPlugins.contains(pluginId)) return true
        
        try {
            plugin.shutdown()
            enabledPlugins.remove(pluginId)
            return true
        } catch (e: Exception) {
            return false
        }
    }

    fun isEnabled(pluginId: String): Boolean = enabledPlugins.contains(pluginId)

    fun getPlugin(pluginId: String): Plugin? = plugins[pluginId]

    fun getAllPlugins(): List<Plugin> = plugins.values.toList()

    fun getEnabledPlugins(): List<Plugin> = plugins.values.filter { enabledPlugins.contains(it.id) }

    override fun getService(name: String): Any? = services[name]

    override fun registerService(name: String, service: Any) {
        services[name] = service
    }

    fun shutdown() {
        enabledPlugins.toList().forEach { disable(it) }
    }
}
