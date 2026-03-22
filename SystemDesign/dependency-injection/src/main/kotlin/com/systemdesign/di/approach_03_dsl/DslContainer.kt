/**
 * # Approach 03: DSL-Based DI Container
 *
 * ## Pattern Used
 * Kotlin DSL with lazy initialization and modules support (similar to Koin).
 *
 * ## Trade-offs
 * - **Pros:** Clean syntax, modular, lazy loading, test-friendly
 * - **Cons:** Learning curve, less IDE support than compile-time DI
 *
 * ## When to Prefer
 * - Large applications with many modules
 * - When clean DSL syntax is valued
 */
package com.systemdesign.di.approach_03_dsl

import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

@DslMarker
annotation class DiDsl

@DiDsl
class Module(val name: String) {
    @PublishedApi
    internal val definitions = mutableListOf<Definition<*>>()

    inline fun <reified T : Any> factory(noinline factory: DefinitionScope.() -> T) {
        definitions.add(Definition(T::class, DefinitionType.FACTORY, factory))
    }

    inline fun <reified T : Any> single(noinline factory: DefinitionScope.() -> T) {
        definitions.add(Definition(T::class, DefinitionType.SINGLE, factory))
    }

    inline fun <reified T : Any> scoped(noinline factory: DefinitionScope.() -> T) {
        definitions.add(Definition(T::class, DefinitionType.SCOPED, factory))
    }
}

enum class DefinitionType { FACTORY, SINGLE, SCOPED }

data class Definition<T : Any>(
    val klass: KClass<T>,
    val type: DefinitionType,
    val factory: DefinitionScope.() -> T
)

@DiDsl
class DefinitionScope(@PublishedApi internal val container: DslContainer) {
    inline fun <reified T : Any> get(): T = container.get()
    inline fun <reified T : Any> getOrNull(): T? = container.getOrNull()
    inline fun <reified T : Any> lazy(): Lazy<T> = kotlin.lazy { get<T>() }
}

class DslContainer {
    @PublishedApi
    internal val definitions = ConcurrentHashMap<KClass<*>, Definition<*>>()
    @PublishedApi
    internal val singletons = ConcurrentHashMap<KClass<*>, Any>()
    private val modules = mutableListOf<Module>()

    fun loadModules(vararg modules: Module) {
        modules.forEach { module ->
            this.modules.add(module)
            module.definitions.forEach { def ->
                definitions[def.klass] = def
            }
        }
    }

    fun unloadModules(vararg modules: Module) {
        modules.forEach { module ->
            this.modules.remove(module)
            module.definitions.forEach { def ->
                definitions.remove(def.klass)
                singletons.remove(def.klass)
            }
        }
    }

    inline fun <reified T : Any> get(): T = resolve(T::class)

    inline fun <reified T : Any> getOrNull(): T? = try { get<T>() } catch (e: Exception) { null }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> resolve(klass: KClass<T>): T {
        val definition = definitions[klass] as? Definition<T>
            ?: throw IllegalArgumentException("No definition for ${klass.simpleName}")

        return when (definition.type) {
            DefinitionType.SINGLE -> singletons.getOrPut(klass) {
                definition.factory(DefinitionScope(this))
            } as T
            DefinitionType.FACTORY -> definition.factory(DefinitionScope(this))
            DefinitionType.SCOPED -> throw IllegalStateException("Scoped must be resolved in a scope")
        }
    }

    fun createScope(): Scope = Scope(this)

    fun close() {
        definitions.clear()
        singletons.clear()
        modules.clear()
    }

    class Scope(private val parent: DslContainer) {
        private val scopedInstances = ConcurrentHashMap<KClass<*>, Any>()

        inline fun <reified T : Any> get(): T = resolve(T::class)

        @Suppress("UNCHECKED_CAST")
        fun <T : Any> resolve(klass: KClass<T>): T {
            val definition = parent.definitions[klass] as? Definition<T>
                ?: throw IllegalArgumentException("No definition for ${klass.simpleName}")

            return when (definition.type) {
                DefinitionType.SCOPED -> scopedInstances.getOrPut(klass) {
                    definition.factory(DefinitionScope(parent))
                } as T
                else -> parent.resolve(klass)
            }
        }

        fun close() {
            scopedInstances.clear()
        }
    }
}

fun module(name: String = "default", block: Module.() -> Unit): Module {
    return Module(name).apply(block)
}

fun startDi(vararg modules: Module): DslContainer {
    return DslContainer().apply { loadModules(*modules) }
}

object Di {
    private var _container: DslContainer? = null
    val container: DslContainer
        get() = _container ?: throw IllegalStateException("DI not started. Call Di.start() first")

    fun start(vararg modules: Module) {
        _container = startDi(*modules)
    }

    fun stop() {
        _container?.close()
        _container = null
    }

    inline fun <reified T : Any> get(): T = container.get()
    inline fun <reified T : Any> getOrNull(): T? = container.getOrNull()
}
