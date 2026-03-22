/**
 * # Approach 02: Graph-Based Navigation
 *
 * ## Pattern Used
 * Directed graph with defined destinations and actions.
 *
 * ## Trade-offs
 * - **Pros:** Type-safe, validation, complex flows supported
 * - **Cons:** More setup, less dynamic
 */
package com.systemdesign.navigationmanager.approach_02_graph

import kotlinx.coroutines.flow.*
import java.util.Stack

data class Destination(
    val id: String,
    val defaultParams: Map<String, Any> = emptyMap()
)

data class NavAction(
    val id: String,
    val destinationId: String
)

class NavGraph {
    private val destinations = mutableMapOf<String, Destination>()
    private val actions = mutableMapOf<String, NavAction>()
    private var startDestinationId: String? = null

    fun addDestination(destination: Destination) {
        destinations[destination.id] = destination
    }

    fun addAction(fromDestinationId: String, action: NavAction) {
        actions["$fromDestinationId:${action.id}"] = action
    }

    fun setStartDestination(destinationId: String) {
        startDestinationId = destinationId
    }

    fun getDestination(id: String): Destination? = destinations[id]

    fun getStartDestination(): Destination? = startDestinationId?.let { destinations[it] }

    fun getAction(fromDestinationId: String, actionId: String): NavAction? {
        return actions["$fromDestinationId:$actionId"]
    }

    fun hasDestination(id: String): Boolean = destinations.containsKey(id)
}

data class NavEntry(
    val destination: Destination,
    val params: Map<String, Any>
)

class GraphNavigator(private val graph: NavGraph) {
    private val backStack = Stack<NavEntry>()
    private val _currentEntry = MutableStateFlow<NavEntry?>(null)
    val currentEntry: StateFlow<NavEntry?> = _currentEntry

    fun start() {
        graph.getStartDestination()?.let { dest ->
            navigateTo(dest.id)
        }
    }

    fun navigateTo(destinationId: String, params: Map<String, Any> = emptyMap()): Boolean {
        val destination = graph.getDestination(destinationId) ?: return false
        val entry = NavEntry(destination, destination.defaultParams + params)
        backStack.push(entry)
        _currentEntry.value = entry
        return true
    }

    fun performAction(actionId: String, params: Map<String, Any> = emptyMap()): Boolean {
        val current = _currentEntry.value ?: return false
        val action = graph.getAction(current.destination.id, actionId) ?: return false
        return navigateTo(action.destinationId, params)
    }

    fun goBack(): Boolean {
        if (backStack.size <= 1) return false
        backStack.pop()
        _currentEntry.value = if (backStack.isNotEmpty()) backStack.peek() else null
        return true
    }

    fun popTo(destinationId: String, inclusive: Boolean = false): Boolean {
        while (backStack.isNotEmpty()) {
            if (backStack.peek().destination.id == destinationId) {
                if (inclusive && backStack.size > 1) backStack.pop()
                _currentEntry.value = if (backStack.isNotEmpty()) backStack.peek() else null
                return true
            }
            backStack.pop()
        }
        return false
    }

    fun canGoBack(): Boolean = backStack.size > 1

    fun getBackStack(): List<NavEntry> = backStack.toList()
}
