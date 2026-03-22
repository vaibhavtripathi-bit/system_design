package com.systemdesign.navigationmanager

import com.systemdesign.navigationmanager.approach_01_stack.*
import com.systemdesign.navigationmanager.approach_02_graph.*
import com.systemdesign.navigationmanager.approach_03_deeplink.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NavigationTest {

    // Stack Navigation Tests
    @Test
    fun `stack - navigates to route`() {
        val navigator = StackNavigator()
        
        navigator.navigate("/home")
        
        assertEquals("/home", navigator.getCurrentRoute()?.path)
    }

    @Test
    fun `stack - goes back`() {
        val navigator = StackNavigator()
        navigator.navigate("/home")
        navigator.navigate("/details")
        
        navigator.goBack()
        
        assertEquals("/home", navigator.getCurrentRoute()?.path)
    }

    @Test
    fun `stack - cannot go back from single screen`() {
        val navigator = StackNavigator()
        navigator.navigate("/home")
        
        val result = navigator.goBack()
        
        assertFalse(result)
    }

    @Test
    fun `stack - replaces current route`() {
        val navigator = StackNavigator()
        navigator.navigate("/home")
        navigator.navigate("/details")
        
        navigator.replace("/settings")
        
        assertEquals("/settings", navigator.getCurrentRoute()?.path)
        assertEquals(2, navigator.getBackStack().size)
    }

    @Test
    fun `stack - pops to root`() {
        val navigator = StackNavigator()
        navigator.navigate("/home")
        navigator.navigate("/a")
        navigator.navigate("/b")
        navigator.navigate("/c")
        
        navigator.popToRoot()
        
        assertEquals("/home", navigator.getCurrentRoute()?.path)
        assertEquals(1, navigator.getBackStack().size)
    }

    @Test
    fun `stack - passes params`() {
        val navigator = StackNavigator()
        
        navigator.navigate("/user", mapOf("id" to 123))
        
        assertEquals(123, navigator.getCurrentRoute()?.params?.get("id"))
    }

    // Graph Navigation Tests
    @Test
    fun `graph - starts at start destination`() {
        val graph = NavGraph()
        graph.addDestination(Destination("home"))
        graph.addDestination(Destination("details"))
        graph.setStartDestination("home")
        
        val navigator = GraphNavigator(graph)
        navigator.start()
        
        assertEquals("home", navigator.currentEntry.value?.destination?.id)
    }

    @Test
    fun `graph - navigates by destination id`() {
        val graph = NavGraph()
        graph.addDestination(Destination("home"))
        graph.addDestination(Destination("details"))
        
        val navigator = GraphNavigator(graph)
        navigator.navigateTo("home")
        navigator.navigateTo("details")
        
        assertEquals("details", navigator.currentEntry.value?.destination?.id)
    }

    @Test
    fun `graph - performs action`() {
        val graph = NavGraph()
        graph.addDestination(Destination("home"))
        graph.addDestination(Destination("details"))
        graph.addAction("home", NavAction("viewDetails", "details"))
        
        val navigator = GraphNavigator(graph)
        navigator.navigateTo("home")
        navigator.performAction("viewDetails")
        
        assertEquals("details", navigator.currentEntry.value?.destination?.id)
    }

    @Test
    fun `graph - fails for unknown destination`() {
        val graph = NavGraph()
        val navigator = GraphNavigator(graph)
        
        val result = navigator.navigateTo("unknown")
        
        assertFalse(result)
    }

    // Deep Link Navigation Tests
    @Test
    fun `deeplink - resolves simple pattern`() {
        val router = deepLinkRouter {
            register("/home", "home")
            register("/user/{id}", "userDetail")
        }
        val navigator = DeepLinkNavigator(router)
        
        navigator.navigate("/home")
        
        assertEquals("home", navigator.currentEntry.value?.destinationId)
    }

    @Test
    fun `deeplink - extracts params`() {
        val router = deepLinkRouter {
            register("/user/{id}", "userDetail")
        }
        val navigator = DeepLinkNavigator(router)
        
        navigator.navigate("/user/123")
        
        assertEquals("123", navigator.getCurrentParams()["id"])
    }

    @Test
    fun `deeplink - extracts multiple params`() {
        val router = deepLinkRouter {
            register("/user/{userId}/post/{postId}", "postDetail")
        }
        val navigator = DeepLinkNavigator(router)
        
        navigator.navigate("/user/abc/post/xyz")
        
        assertEquals("abc", navigator.getCurrentParams()["userId"])
        assertEquals("xyz", navigator.getCurrentParams()["postId"])
    }

    @Test
    fun `deeplink - handles clear stack`() {
        val router = deepLinkRouter {
            register("/home", "home")
            register("/reset", "reset")
        }
        val navigator = DeepLinkNavigator(router)
        
        navigator.navigate("/home")
        navigator.handleDeepLink("/reset", clearStack = true)
        
        assertEquals(1, navigator.getBackStack().size)
    }

    @Test
    fun `deeplink - returns false for unmatched url`() {
        val router = deepLinkRouter {
            register("/home", "home")
        }
        val navigator = DeepLinkNavigator(router)
        
        val result = navigator.navigate("/unknown")
        
        assertFalse(result)
    }
}
