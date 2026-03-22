package com.systemdesign.pluginsystem

import com.systemdesign.pluginsystem.approach_01_interface.*
import com.systemdesign.pluginsystem.approach_02_hooks.*
import com.systemdesign.pluginsystem.approach_03_modular.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PluginSystemTest {

    // Interface Plugin Tests
    class TestPlugin(
        override val id: String,
        override val name: String = "Test",
        override val version: String = "1.0"
    ) : Plugin {
        var initialized = false
        var shutdown = false
        
        override fun initialize() { initialized = true }
        override fun shutdown() { shutdown = true }
    }

    @Test
    fun `interface - registers plugin`() {
        val manager = PluginManager()
        val plugin = TestPlugin("test")
        
        manager.register(plugin)
        
        assertNotNull(manager.getPlugin("test"))
    }

    @Test
    fun `interface - enables and initializes plugin`() {
        val manager = PluginManager()
        val plugin = TestPlugin("test")
        manager.register(plugin)
        
        manager.enable("test")
        
        assertTrue(plugin.initialized)
        assertTrue(manager.isEnabled("test"))
    }

    @Test
    fun `interface - disables and shuts down plugin`() {
        val manager = PluginManager()
        val plugin = TestPlugin("test")
        manager.register(plugin)
        manager.enable("test")
        
        manager.disable("test")
        
        assertTrue(plugin.shutdown)
        assertFalse(manager.isEnabled("test"))
    }

    @Test
    fun `interface - provides services`() {
        val manager = PluginManager()
        
        manager.registerService("logger", "LoggerInstance")
        
        assertEquals("LoggerInstance", manager.getService("logger"))
    }

    // Hook Plugin Tests
    @Test
    fun `hooks - applies filters`() {
        val hooks = HookSystem()
        hooks.addFilter<String>("modify_text", "plugin1") { "$it!" }
        
        val result = hooks.applyFilters("modify_text", "Hello")
        
        assertEquals("Hello!", result)
    }

    @Test
    fun `hooks - chains filters by priority`() {
        val hooks = HookSystem()
        hooks.addFilter<String>("text", "plugin1", priority = 10) { "$it-B" }
        hooks.addFilter<String>("text", "plugin2", priority = 5) { "$it-A" }
        
        val result = hooks.applyFilters("text", "Start")
        
        assertEquals("Start-A-B", result)
    }

    @Test
    fun `hooks - removes plugin filters`() {
        val hooks = HookSystem()
        hooks.addFilter<String>("text", "plugin1") { "$it!" }
        
        hooks.removeFilters("plugin1")
        
        assertFalse(hooks.hasHook("text"))
    }

    @Test
    fun `hooks - manager enables plugins`() {
        val manager = HookablePluginManager()
        var registered = false
        
        val plugin = object : HookablePlugin {
            override val id = "test"
            override fun registerHooks(hooks: HookSystem) {
                registered = true
                hooks.addFilter<String>("text", id) { "$it!" }
            }
        }
        
        manager.register(plugin)
        manager.enable("test")
        
        assertTrue(registered)
        assertEquals("Hello!", manager.applyFilters("text", "Hello"))
    }

    // Modular Plugin Tests
    class TestModularPlugin(
        override val descriptor: PluginDescriptor,
        private val onStartCallback: () -> Unit = {},
        private val onStopCallback: () -> Unit = {}
    ) : ModularPlugin {
        override fun onStart(context: ModuleContext) = onStartCallback()
        override fun onStop() = onStopCallback()
    }

    @Test
    fun `modular - starts plugin`() {
        val manager = ModularPluginManager()
        var started = false
        
        val plugin = TestModularPlugin(
            PluginDescriptor("test", "Test", "1.0"),
            onStartCallback = { started = true }
        )
        
        manager.register(plugin)
        manager.start("test")
        
        assertTrue(started)
        assertEquals(PluginState.STARTED, manager.getState("test"))
    }

    @Test
    fun `modular - resolves dependencies`() {
        val manager = ModularPluginManager()
        var coreStarted = false
        var featureStarted = false
        
        val core = TestModularPlugin(
            PluginDescriptor("core", "Core", "1.0"),
            onStartCallback = { coreStarted = true }
        )
        
        val feature = TestModularPlugin(
            PluginDescriptor("feature", "Feature", "1.0", 
                dependencies = listOf(PluginDependency("core"))),
            onStartCallback = { featureStarted = true }
        )
        
        manager.register(core)
        manager.register(feature)
        manager.start("feature")
        
        assertTrue(coreStarted)
        assertTrue(featureStarted)
    }

    @Test
    fun `modular - fails for missing dependency`() {
        val manager = ModularPluginManager()
        
        val plugin = TestModularPlugin(
            PluginDescriptor("test", "Test", "1.0",
                dependencies = listOf(PluginDependency("missing")))
        )
        
        manager.register(plugin)
        val result = manager.resolve("test")
        
        assertFalse(result)
        assertEquals(PluginState.FAILED, manager.getState("test"))
    }

    @Test
    fun `modular - allows optional missing dependency`() {
        val manager = ModularPluginManager()
        
        val plugin = TestModularPlugin(
            PluginDescriptor("test", "Test", "1.0",
                dependencies = listOf(PluginDependency("optional", optional = true)))
        )
        
        manager.register(plugin)
        val result = manager.resolve("test")
        
        assertTrue(result)
    }

    @Test
    fun `modular - stops dependent plugins`() {
        val manager = ModularPluginManager()
        var featureStopped = false
        
        val core = TestModularPlugin(PluginDescriptor("core", "Core", "1.0"))
        val feature = TestModularPlugin(
            PluginDescriptor("feature", "Feature", "1.0",
                dependencies = listOf(PluginDependency("core"))),
            onStopCallback = { featureStopped = true }
        )
        
        manager.register(core)
        manager.register(feature)
        manager.start("feature")
        
        manager.stop("core")
        
        assertTrue(featureStopped)
        assertEquals(PluginState.STOPPED, manager.getState("feature"))
    }

    @Test
    fun `modular - provides extensions`() {
        val manager = ModularPluginManager()
        
        manager.registerExtension("menu.items", "Item1")
        manager.registerExtension("menu.items", "Item2")
        
        val items = manager.getExtension<String>("menu.items")
        
        assertEquals(2, items.size)
    }
}
