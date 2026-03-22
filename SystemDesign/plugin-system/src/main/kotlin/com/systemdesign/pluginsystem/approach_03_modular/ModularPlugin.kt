/**
 * # Approach 03: Modular Plugin System
 *
 * ## Pattern Used
 * Dependency-aware plugins with lifecycle management.
 *
 * ## Trade-offs
 * - **Pros:** Handles dependencies, proper lifecycle, version checking
 * - **Cons:** Most complex, dependency resolution overhead
 */
package com.systemdesign.pluginsystem.approach_03_modular

import java.util.concurrent.ConcurrentHashMap

data class PluginDescriptor(
    val id: String,
    val name: String,
    val version: String,
    val dependencies: List<PluginDependency> = emptyList()
)

data class PluginDependency(
    val pluginId: String,
    val minVersion: String? = null,
    val optional: Boolean = false
)

enum class PluginState {
    REGISTERED, RESOLVED, STARTED, STOPPED, FAILED
}

interface ModularPlugin {
    val descriptor: PluginDescriptor
    fun onStart(context: ModuleContext)
    fun onStop()
}

interface ModuleContext {
    fun <T> getExtension(extensionPoint: String): List<T>
    fun <T> registerExtension(extensionPoint: String, extension: T)
}

class ModularPluginManager : ModuleContext {
    private val plugins = ConcurrentHashMap<String, ModularPlugin>()
    private val states = ConcurrentHashMap<String, PluginState>()
    private val extensions = ConcurrentHashMap<String, MutableList<Any>>()

    fun register(plugin: ModularPlugin): Boolean {
        if (plugins.containsKey(plugin.descriptor.id)) return false
        plugins[plugin.descriptor.id] = plugin
        states[plugin.descriptor.id] = PluginState.REGISTERED
        return true
    }

    fun resolve(pluginId: String): Boolean {
        val plugin = plugins[pluginId] ?: return false
        
        for (dep in plugin.descriptor.dependencies) {
            val depPlugin = plugins[dep.pluginId]
            if (depPlugin == null && !dep.optional) {
                states[pluginId] = PluginState.FAILED
                return false
            }
            
            if (depPlugin != null && dep.minVersion != null) {
                if (!isVersionCompatible(depPlugin.descriptor.version, dep.minVersion)) {
                    if (!dep.optional) {
                        states[pluginId] = PluginState.FAILED
                        return false
                    }
                }
            }
        }
        
        states[pluginId] = PluginState.RESOLVED
        return true
    }

    fun start(pluginId: String): Boolean {
        val plugin = plugins[pluginId] ?: return false
        val state = states[pluginId]
        
        if (state != PluginState.RESOLVED && state != PluginState.STOPPED) {
            if (!resolve(pluginId)) return false
        }
        
        for (dep in plugin.descriptor.dependencies) {
            if (!dep.optional && states[dep.pluginId] != PluginState.STARTED) {
                if (!start(dep.pluginId)) return false
            }
        }
        
        try {
            plugin.onStart(this)
            states[pluginId] = PluginState.STARTED
            return true
        } catch (e: Exception) {
            states[pluginId] = PluginState.FAILED
            return false
        }
    }

    fun stop(pluginId: String): Boolean {
        val plugin = plugins[pluginId] ?: return false
        if (states[pluginId] != PluginState.STARTED) return true
        
        val dependents = plugins.values.filter { p ->
            p.descriptor.dependencies.any { it.pluginId == pluginId && !it.optional }
        }
        
        dependents.forEach { stop(it.descriptor.id) }
        
        try {
            plugin.onStop()
            states[pluginId] = PluginState.STOPPED
            return true
        } catch (e: Exception) {
            return false
        }
    }

    fun getState(pluginId: String): PluginState? = states[pluginId]

    fun getPlugin(pluginId: String): ModularPlugin? = plugins[pluginId]

    fun getAllPlugins(): List<ModularPlugin> = plugins.values.toList()

    @Suppress("UNCHECKED_CAST")
    override fun <T> getExtension(extensionPoint: String): List<T> {
        return extensions[extensionPoint]?.map { it as T } ?: emptyList()
    }

    override fun <T> registerExtension(extensionPoint: String, extension: T) {
        extensions.getOrPut(extensionPoint) { mutableListOf() }.add(extension as Any)
    }

    private fun isVersionCompatible(actual: String, required: String): Boolean {
        val actualParts = actual.split(".").map { it.toIntOrNull() ?: 0 }
        val requiredParts = required.split(".").map { it.toIntOrNull() ?: 0 }
        
        for (i in 0 until maxOf(actualParts.size, requiredParts.size)) {
            val a = actualParts.getOrElse(i) { 0 }
            val r = requiredParts.getOrElse(i) { 0 }
            if (a > r) return true
            if (a < r) return false
        }
        return true
    }

    fun shutdown() {
        plugins.keys.toList().forEach { stop(it) }
        extensions.clear()
    }
}
