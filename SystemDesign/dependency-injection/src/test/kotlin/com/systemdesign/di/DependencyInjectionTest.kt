package com.systemdesign.di

import com.systemdesign.di.approach_01_simple.SimpleContainer
import com.systemdesign.di.approach_01_simple.container
import com.systemdesign.di.approach_02_reflection.Lifecycle
import com.systemdesign.di.approach_02_reflection.ReflectionContainer
import com.systemdesign.di.approach_03_dsl.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach

// Test interfaces and classes
interface Repository {
    fun getData(): String
}

class InMemoryRepository : Repository {
    override fun getData() = "data"
}

interface Service {
    fun process(): String
}

class ServiceImpl(private val repo: Repository) : Service {
    override fun process() = "processed: ${repo.getData()}"
}

class SimpleService {
    fun doWork() = "work done"
}

class DependencyInjectionTest {

    // Simple Container Tests
    @Test
    fun `simple - registers and resolves factory`() {
        val container = SimpleContainer()
        container.register<SimpleService> { SimpleService() }
        
        val service1 = container.get<SimpleService>()
        val service2 = container.get<SimpleService>()
        
        assertNotSame(service1, service2)
    }

    @Test
    fun `simple - registers and resolves singleton`() {
        val container = SimpleContainer()
        container.registerSingleton<SimpleService> { SimpleService() }
        
        val service1 = container.get<SimpleService>()
        val service2 = container.get<SimpleService>()
        
        assertSame(service1, service2)
    }

    @Test
    fun `simple - registers instance`() {
        val container = SimpleContainer()
        val instance = SimpleService()
        container.registerInstance(instance)
        
        val resolved = container.get<SimpleService>()
        assertSame(instance, resolved)
    }

    @Test
    fun `simple - resolves with dependencies`() {
        val container = SimpleContainer()
        container.register<Repository> { InMemoryRepository() }
        container.register<Service> { ServiceImpl(container.get()) }
        
        val service = container.get<Service>()
        assertEquals("processed: data", service.process())
    }

    @Test
    fun `simple - throws for unregistered type`() {
        val container = SimpleContainer()
        
        assertThrows(IllegalArgumentException::class.java) {
            container.get<SimpleService>()
        }
    }

    @Test
    fun `simple - builder pattern works`() {
        val container = container {
            singleton<Repository> { InMemoryRepository() }
            factory<Service> { ServiceImpl(build().get()) }
        }
        
        val service = container.get<Service>()
        assertEquals("processed: data", service.process())
    }

    @Test
    fun `simple - scoped instances`() {
        val container = SimpleContainer()
        container.register<SimpleService> { SimpleService() }
        
        val scope1 = container.createScope("scope1")
        val scope2 = container.createScope("scope2")
        
        val s1a = scope1.get<SimpleService>()
        val s1b = scope1.get<SimpleService>()
        val s2a = scope2.get<SimpleService>()
        
        assertSame(s1a, s1b)
        assertNotSame(s1a, s2a)
    }

    // Reflection Container Tests
    @Test
    fun `reflection - auto-wires dependencies`() {
        val container = ReflectionContainer()
        container.bind<Repository, InMemoryRepository>()
        container.register<ServiceImpl>()
        
        val service = container.get<ServiceImpl>()
        assertEquals("processed: data", service.process())
    }

    @Test
    fun `reflection - singleton lifecycle`() {
        val container = ReflectionContainer()
        container.register<SimpleService>(Lifecycle.SINGLETON)
        
        val s1 = container.get<SimpleService>()
        val s2 = container.get<SimpleService>()
        
        assertSame(s1, s2)
    }

    @Test
    fun `reflection - transient lifecycle`() {
        val container = ReflectionContainer()
        container.register<SimpleService>(Lifecycle.TRANSIENT)
        
        val s1 = container.get<SimpleService>()
        val s2 = container.get<SimpleService>()
        
        assertNotSame(s1, s2)
    }

    @Test
    fun `reflection - registers instance`() {
        val container = ReflectionContainer()
        val instance = SimpleService()
        container.registerInstance(instance)
        
        val resolved = container.get<SimpleService>()
        assertSame(instance, resolved)
    }

    @Test
    fun `reflection - scoped lifecycle`() {
        val container = ReflectionContainer()
        container.register<SimpleService>(Lifecycle.SCOPED)
        
        val scope1 = container.createScope()
        val scope2 = container.createScope()
        
        val s1a = scope1.get<SimpleService>()
        val s1b = scope1.get<SimpleService>()
        val s2a = scope2.get<SimpleService>()
        
        assertSame(s1a, s1b)
        assertNotSame(s1a, s2a)
    }

    // DSL Container Tests
    @Test
    fun `dsl - module definition works`() {
        val appModule = module("app") {
            single<Repository> { InMemoryRepository() }
            factory<Service> { ServiceImpl(get()) }
        }
        
        val container = startDi(appModule)
        
        val service = container.get<Service>()
        assertEquals("processed: data", service.process())
        
        container.close()
    }

    @Test
    fun `dsl - single returns same instance`() {
        val appModule = module {
            single<SimpleService> { SimpleService() }
        }
        
        val container = startDi(appModule)
        
        val s1 = container.get<SimpleService>()
        val s2 = container.get<SimpleService>()
        
        assertSame(s1, s2)
        container.close()
    }

    @Test
    fun `dsl - factory returns new instance`() {
        val appModule = module {
            factory<SimpleService> { SimpleService() }
        }
        
        val container = startDi(appModule)
        
        val s1 = container.get<SimpleService>()
        val s2 = container.get<SimpleService>()
        
        assertNotSame(s1, s2)
        container.close()
    }

    @Test
    fun `dsl - multiple modules`() {
        val dataModule = module("data") {
            single<Repository> { InMemoryRepository() }
        }
        
        val serviceModule = module("service") {
            factory<Service> { ServiceImpl(get()) }
        }
        
        val container = startDi(dataModule, serviceModule)
        
        val service = container.get<Service>()
        assertEquals("processed: data", service.process())
        container.close()
    }

    @Test
    fun `dsl - scoped instances`() {
        val appModule = module {
            scoped<SimpleService> { SimpleService() }
        }
        
        val container = startDi(appModule)
        val scope1 = container.createScope()
        val scope2 = container.createScope()
        
        val s1a = scope1.get<SimpleService>()
        val s1b = scope1.get<SimpleService>()
        val s2a = scope2.get<SimpleService>()
        
        assertSame(s1a, s1b)
        assertNotSame(s1a, s2a)
        
        scope1.close()
        scope2.close()
        container.close()
    }

    @Test
    fun `dsl - unload module removes definitions`() {
        val appModule = module {
            single<SimpleService> { SimpleService() }
        }
        
        val container = startDi(appModule)
        
        assertNotNull(container.get<SimpleService>())
        
        container.unloadModules(appModule)
        
        assertThrows(IllegalArgumentException::class.java) {
            container.get<SimpleService>()
        }
        
        container.close()
    }

    @Test
    fun `dsl - global Di object works`() {
        val appModule = module {
            single<SimpleService> { SimpleService() }
        }
        
        Di.start(appModule)
        
        val service = Di.get<SimpleService>()
        assertNotNull(service)
        
        Di.stop()
    }
}
