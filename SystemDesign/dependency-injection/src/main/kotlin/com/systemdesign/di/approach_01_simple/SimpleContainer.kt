/**
 * # Approach 01: Simple DI Container
 *
 * ## Pattern Used
 * Manual registration with factory functions. No reflection, fully explicit.
 *
 * ## Trade-offs
 * - **Pros:** Fast, no reflection, compile-time safety, easy to debug
 * - **Cons:** Verbose registration, manual dependency wiring
 *
 * ## When to Prefer
 * - Simple apps with few dependencies
 * - When reflection is undesirable
 * - Performance-critical scenarios
 */
package com.systemdesign.di.approach_01_simple

import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

class SimpleContainer {
    @PublishedApi
    internal val factories = ConcurrentHashMap<KClass<*>, () -> Any>()
    @PublishedApi
    internal val singletons = ConcurrentHashMap<KClass<*>, Any>()
    private val scopes = ConcurrentHashMap<String, MutableMap<KClass<*>, Any>>()

    inline fun <reified T : Any> register(noinline factory: () -> T) {
        factories[T::class] = factory
    }

    inline fun <reified T : Any> registerSingleton(noinline factory: () -> T) {
        factories[T::class] = {
            singletons.getOrPut(T::class) { factory() }
        }
    }

    inline fun <reified T : Any> registerInstance(instance: T) {
        singletons[T::class] = instance
        factories[T::class] = { singletons[T::class]!! }
    }

    inline fun <reified T : Any> get(): T {
        return resolve(T::class)
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> resolve(klass: KClass<T>): T {
        val factory = factories[klass]
            ?: throw IllegalArgumentException("No registration for ${klass.simpleName}")
        return factory() as T
    }

    inline fun <reified T : Any> getOrNull(): T? {
        return try { get<T>() } catch (e: Exception) { null }
    }

    fun createScope(name: String): Scope = Scope(name, this)

    fun clear() {
        factories.clear()
        singletons.clear()
        scopes.clear()
    }

    class Scope(private val name: String, private val parent: SimpleContainer) {
        private val scopedInstances = ConcurrentHashMap<KClass<*>, Any>()

        inline fun <reified T : Any> get(): T = resolve(T::class)

        @Suppress("UNCHECKED_CAST")
        fun <T : Any> resolve(klass: KClass<T>): T {
            return scopedInstances.getOrPut(klass) {
                parent.resolve(klass)
            } as T
        }

        fun close() {
            scopedInstances.clear()
        }
    }
}

class ContainerBuilder {
    @PublishedApi
    internal val container = SimpleContainer()

    inline fun <reified T : Any> factory(noinline factory: () -> T) = apply {
        container.register(factory)
    }

    inline fun <reified T : Any> singleton(noinline factory: () -> T) = apply {
        container.registerSingleton(factory)
    }

    inline fun <reified T : Any> instance(instance: T) = apply {
        container.registerInstance(instance)
    }

    fun build(): SimpleContainer = container
}

fun container(block: ContainerBuilder.() -> Unit): SimpleContainer {
    return ContainerBuilder().apply(block).build()
}
