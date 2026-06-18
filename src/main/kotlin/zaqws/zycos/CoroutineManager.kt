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
    private val scopeMap = ConcurrentHashMap<Plugin, CoroutineScope>()

    val Plugin.scope: CoroutineScope
        get() = scopeMap.getOrPut(this) {
            CoroutineScope(SupervisorJob() + PaperDispatcher(this))
        }

    fun cancel(plugin: Plugin) {
        scopeMap.remove(plugin)?.cancel()
    }

    class PaperDispatcher(
        private val plugin: Plugin
    ) : CoroutineDispatcher() {
        override fun isDispatchNeeded(context: CoroutineContext) =
            !Bukkit.isPrimaryThread()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            Main.plugin.server.scheduler.runTask(Main.plugin, block)
        }
    }
}