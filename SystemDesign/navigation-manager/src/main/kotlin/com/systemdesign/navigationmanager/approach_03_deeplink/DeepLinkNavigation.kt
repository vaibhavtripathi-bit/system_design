/**
 * # Approach 03: Deep Link Navigation
 *
 * ## Pattern Used
 * URL-based routing with pattern matching.
 *
 * ## Trade-offs
 * - **Pros:** External linking, web-like URLs, flexible
 * - **Cons:** String parsing, pattern complexity
 */
package com.systemdesign.navigationmanager.approach_03_deeplink

import kotlinx.coroutines.flow.*
import java.util.Stack
import java.util.regex.Pattern

data class DeepLinkPattern(
    val pattern: String,
    val destinationId: String
) {
    private val paramNames = mutableListOf<String>()
    private val regex: Pattern

    init {
        val regexPattern = pattern.replace(Regex("\\{(\\w+)\\}")) { match ->
            paramNames.add(match.groupValues[1])
            "([^/]+)"
        }
        regex = Pattern.compile("^$regexPattern$")
    }

    fun match(url: String): Map<String, String>? {
        val matcher = regex.matcher(url)
        if (!matcher.matches()) return null
        
        return paramNames.mapIndexed { index, name ->
            name to matcher.group(index + 1)
        }.toMap()
    }
}

class DeepLinkRouter {
    private val patterns = mutableListOf<DeepLinkPattern>()

    fun register(pattern: String, destinationId: String) {
        patterns.add(DeepLinkPattern(pattern, destinationId))
    }

    fun resolve(url: String): Pair<String, Map<String, String>>? {
        for (pattern in patterns) {
            val params = pattern.match(url)
            if (params != null) {
                return pattern.destinationId to params
            }
        }
        return null
    }
}

data class DeepLinkEntry(
    val url: String,
    val destinationId: String,
    val params: Map<String, String>
)

class DeepLinkNavigator(private val router: DeepLinkRouter) {
    private val backStack = Stack<DeepLinkEntry>()
    private val _currentEntry = MutableStateFlow<DeepLinkEntry?>(null)
    val currentEntry: StateFlow<DeepLinkEntry?> = _currentEntry

    fun navigate(url: String): Boolean {
        val resolved = router.resolve(url) ?: return false
        val (destinationId, params) = resolved
        
        val entry = DeepLinkEntry(url, destinationId, params)
        backStack.push(entry)
        _currentEntry.value = entry
        return true
    }

    fun goBack(): Boolean {
        if (backStack.size <= 1) return false
        backStack.pop()
        _currentEntry.value = if (backStack.isNotEmpty()) backStack.peek() else null
        return true
    }

    fun handleDeepLink(url: String, clearStack: Boolean = false): Boolean {
        if (clearStack) {
            backStack.clear()
        }
        return navigate(url)
    }

    fun getCurrentUrl(): String? = _currentEntry.value?.url

    fun getCurrentParams(): Map<String, String> = _currentEntry.value?.params ?: emptyMap()

    fun canGoBack(): Boolean = backStack.size > 1

    fun getBackStack(): List<DeepLinkEntry> = backStack.toList()

    fun clear() {
        backStack.clear()
        _currentEntry.value = null
    }
}

fun deepLinkRouter(block: DeepLinkRouter.() -> Unit): DeepLinkRouter {
    return DeepLinkRouter().apply(block)
}
