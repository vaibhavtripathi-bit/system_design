/**
 * # Approach 01: Stack-Based Navigation
 *
 * ## Pattern Used
 * Traditional stack navigation with push/pop operations.
 *
 * ## Trade-offs
 * - **Pros:** Simple, predictable, familiar pattern
 * - **Cons:** Limited for complex navigation graphs
 */
package com.systemdesign.navigationmanager.approach_01_stack

import kotlinx.coroutines.flow.*
import java.util.Stack

data class Route(
    val path: String,
    val params: Map<String, Any> = emptyMap()
)

data class NavigationState(
    val currentRoute: Route?,
    val canGoBack: Boolean,
    val stackSize: Int
)

class StackNavigator {
    private val stack = Stack<Route>()
    private val _state = MutableStateFlow(NavigationState(null, false, 0))
    val state: StateFlow<NavigationState> = _state

    fun navigate(path: String, params: Map<String, Any> = emptyMap()) {
        val route = Route(path, params)
        stack.push(route)
        updateState()
    }

    fun goBack(): Boolean {
        if (stack.size <= 1) return false
        stack.pop()
        updateState()
        return true
    }

    fun popToRoot() {
        while (stack.size > 1) {
            stack.pop()
        }
        updateState()
    }

    fun replace(path: String, params: Map<String, Any> = emptyMap()) {
        if (stack.isNotEmpty()) stack.pop()
        navigate(path, params)
    }

    fun getCurrentRoute(): Route? = if (stack.isNotEmpty()) stack.peek() else null

    fun getBackStack(): List<Route> = stack.toList()

    private fun updateState() {
        _state.value = NavigationState(
            currentRoute = if (stack.isNotEmpty()) stack.peek() else null,
            canGoBack = stack.size > 1,
            stackSize = stack.size
        )
    }

    fun clear() {
        stack.clear()
        updateState()
    }
}
