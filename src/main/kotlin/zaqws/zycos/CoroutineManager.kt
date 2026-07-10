@file:Suppress("unused")

package zaqws.zycos

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext


object CoroutineManager {
    private class PluginContext(plugin: Plugin) {
        val mainDispatcher = PaperDispatcher(plugin, async = false)
        val asyncDispatcher = PaperDispatcher(plugin, async = true)
        val mainScope = CoroutineScope(SupervisorJob() + mainDispatcher)
        val asyncScope = CoroutineScope(SupervisorJob() + asyncDispatcher)
    }

    private val contextMap = ConcurrentHashMap<Plugin, PluginContext>()

    val Plugin.scope: CoroutineScope
        get() = contextMap.getOrPut(this) {
            PluginContext(this)
        }.mainScope

    val Plugin.asyncScope: CoroutineScope
        get() = contextMap.getOrPut(this) {
            PluginContext(this)
        }.asyncScope

    val Plugin.mainDispatcher: CoroutineDispatcher
        get() = contextMap.getOrPut(this) {
            PluginContext(this)
        }.mainDispatcher

    val Plugin.asyncDispatcher: CoroutineDispatcher
        get() = contextMap.getOrPut(this) {
            PluginContext(this)
        }.asyncDispatcher

    fun cancel(plugin: Plugin) {
        contextMap.remove(plugin)?.let {
            it.mainScope.cancel()
            it.asyncScope.cancel()
        }
    }

    class PaperDispatcher(
        private val plugin: Plugin,
        private val async: Boolean = false
    ) : CoroutineDispatcher() {
        override fun isDispatchNeeded(context: CoroutineContext) =
            if (async) true else !Bukkit.isPrimaryThread()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (async) plugin.server.scheduler.runTaskAsynchronously(plugin, block)
            else plugin.server.scheduler.runTask(plugin, block)
        }
    }
}