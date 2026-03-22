/**
 * # Approach 02: Reflection-Based DI Container
 *
 * ## Pattern Used
 * Uses Kotlin reflection to auto-wire dependencies via constructor injection.
 *
 * ## Trade-offs
 * - **Pros:** Less boilerplate, automatic wiring, flexible
 * - **Cons:** Runtime errors, slower, harder to debug
 *
 * ## When to Prefer
 * - Medium-sized apps
 * - When automatic wiring saves significant time
 */
package com.systemdesign.di.approach_02_reflection

import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.full.primaryConstructor

enum class Lifecycle {
    TRANSIENT,
    SINGLETON,
    SCOPED
}

data class Registration(
    val factory: (() -> Any)? = null,
    val implClass: KClass<*>? = null,
    val lifecycle: Lifecycle = Lifecycle.TRANSIENT
)

class ReflectionContainer {
    @PublishedApi
    internal val registrations = ConcurrentHashMap<KClass<*>, Registration>()
    @PublishedApi
    internal val singletons = ConcurrentHashMap<KClass<*>, Any>()

    inline fun <reified T : Any> register(lifecycle: Lifecycle = Lifecycle.TRANSIENT) {
        register(T::class, T::class, lifecycle)
    }

    inline fun <reified TInterface : Any, reified TImpl : TInterface> bind(
        lifecycle: Lifecycle = Lifecycle.TRANSIENT
    ) {
        register(TInterface::class, TImpl::class, lifecycle)
    }

    fun <T : Any> register(
        interfaceClass: KClass<T>,
        implClass: KClass<out T>,
        lifecycle: Lifecycle = Lifecycle.TRANSIENT
    ) {
        registrations[interfaceClass] = Registration(implClass = implClass, lifecycle = lifecycle)
    }

    inline fun <reified T : Any> registerFactory(
        lifecycle: Lifecycle = Lifecycle.TRANSIENT,
        noinline factory: () -> T
    ) {
        registrations[T::class] = Registration(factory = factory, lifecycle = lifecycle)
    }

    inline fun <reified T : Any> registerInstance(instance: T) {
        singletons[T::class] = instance
        registrations[T::class] = Registration(lifecycle = Lifecycle.SINGLETON)
    }

    inline fun <reified T : Any> get(): T = resolve(T::class)

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> resolve(klass: KClass<T>): T {
        val registration = registrations[klass]
            ?: throw IllegalArgumentException("No registration for ${klass.simpleName}")

        return when (registration.lifecycle) {
            Lifecycle.SINGLETON -> singletons.getOrPut(klass) {
                createInstance(registration, klass)
            } as T
            Lifecycle.TRANSIENT, Lifecycle.SCOPED -> createInstance(registration, klass) as T
        }
    }

    @PublishedApi
    internal fun createInstance(registration: Registration, klass: KClass<*>): Any {
        registration.factory?.let { return it() }

        val implClass = registration.implClass ?: klass
        val constructor = implClass.primaryConstructor
            ?: throw IllegalArgumentException("No primary constructor for ${implClass.simpleName}")

        val args = constructor.parameters.associateWith { param: KParameter ->
            resolveParameter(param)
        }

        return constructor.callBy(args)
    }

    private fun resolveParameter(param: KParameter): Any {
        val paramClass = param.type.classifier as? KClass<*>
            ?: throw IllegalArgumentException("Cannot resolve parameter ${param.name}")

        return if (registrations.containsKey(paramClass)) {
            resolve(paramClass)
        } else {
            throw IllegalArgumentException("No registration for ${paramClass.simpleName}")
        }
    }

    fun createScope(): Scope = Scope(this)

    fun clear() {
        registrations.clear()
        singletons.clear()
    }

    class Scope(private val parent: ReflectionContainer) {
        private val scopedInstances = ConcurrentHashMap<KClass<*>, Any>()

        inline fun <reified T : Any> get(): T = resolve(T::class)

        @Suppress("UNCHECKED_CAST")
        fun <T : Any> resolve(klass: KClass<T>): T {
            val registration = parent.registrations[klass]
                ?: return parent.resolve(klass)

            return when (registration.lifecycle) {
                Lifecycle.SCOPED -> scopedInstances.getOrPut(klass) {
                    parent.createInstance(registration, klass)
                } as T
                else -> parent.resolve(klass)
            }
        }

        fun close() {
            scopedInstances.clear()
        }
    }
}
