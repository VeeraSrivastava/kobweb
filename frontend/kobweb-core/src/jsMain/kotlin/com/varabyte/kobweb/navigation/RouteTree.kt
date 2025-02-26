package com.varabyte.kobweb.navigation

import androidx.compose.runtime.*
import com.varabyte.kobweb.core.Page
import com.varabyte.kobweb.core.PageContext
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Text

typealias PageMethod = @Composable () -> Unit
/**
 * Typealias for a composable method which takes an error code as its first and only argument (e.g. 404).
 *
 * Use [Router.setErrorHandler] to override with your own custom handler.
 */
typealias ErrorPageMethod = @Composable (Int) -> Unit

/**
 * The default error page logic used by Kobweb.
 */
@Page
@Composable
private fun ErrorPage(errorCode: Int) {
    Div {
        Text("Error code: $errorCode")
    }
}

internal class PageData(
    val pageMethod: PageMethod,
    val pageContext: PageContext,
)

/**
 * A tree data structure that represents a parsed route, such as `/example/path` or `/{dynamic}/path`
 */
internal class RouteTree {
    sealed class Node(val name: String, var method: PageMethod?) {
        private val children = mutableListOf<Node>()

        protected open fun matches(name: String): Boolean { return this.name == name }

        fun createChild(routePart: String, method: PageMethod?): Node {
            val node = if (routePart.startsWith('{') && routePart.endsWith('}')) {
                DynamicNode(routePart.substring(1, routePart.length - 1), method)
            } else {
                StaticNode(routePart, method)
            }
            children.add(node)
            return node
        }

        fun findChild(routePart: String): Node? = children.firstOrNull { it.matches(routePart) }
    }

    class RootNode : Node("", null)
    class StaticNode(name: String, method: PageMethod?) : Node(name, method)
    class DynamicNode(name: String, method: PageMethod?) : Node(name, method) {
        override fun matches(name: String) = true // Dynamic nodes eat all possible inputs
    }

    private class ResolvedEntry(val node: Node, val routePart: String)

    private val root = RootNode()

    var errorHandler: ErrorPageMethod = { errorCode -> ErrorPage(errorCode) }

    private fun resolve(route: String): List<ResolvedEntry>? {
        val routeParts = route.split('/')

        val resolved = mutableListOf<ResolvedEntry>()
        var currNode: Node = root
        require(routeParts[0] == root.name) // Will be true if incoming route starts with '/'

        for (i in 1 until routeParts.size) {
            val routePart = routeParts[i]
            currNode = currNode.findChild(routePart) ?: return null
            resolved.add(ResolvedEntry(currNode, routePart))
        }

        return resolved
    }

    /**
     * Register [route] with this tree, or return false if it was already added.
     */
    fun register(route: String, method: PageMethod): Boolean {
        if (resolve(route) != null) return false

        val routeParts = route.split('/')

        var currNode: Node = root
        require(routeParts[0] == root.name) // Will be true if incoming route starts with '/'
        for (i in 1 until routeParts.size) {
            val routePart = routeParts[i]
            currNode = currNode.findChild(routePart)
                ?: currNode.createChild(routePart, method.takeIf { i == routeParts.lastIndex })
        }

        return true
    }

    fun createPageData(router: Router, route: String, query: String?): PageData {
        val resolvedEntries = resolve(route)
        val pageMethod: PageMethod = resolvedEntries?.last()?.node?.method ?: @Composable { errorHandler(404) }

        val ctx = PageContext(router)

        resolvedEntries?.forEach { resolvedEntry ->
            if (resolvedEntry.node is DynamicNode) {
                ctx.mutableParams[resolvedEntry.node.name] = resolvedEntry.routePart
            }
        }

        query?.split("&")?.forEach { param ->
            val (key, value) = param.split('=', limit = 2)
            ctx.mutableParams[key] = value
        }

        return PageData(pageMethod, ctx)
    }
}