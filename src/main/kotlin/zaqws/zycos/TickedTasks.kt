@file:Suppress("unused")

package zaqws.zycos

import org.bukkit.scheduler.BukkitRunnable
import zaqws.zycos.Main.Companion.plugin
import java.util.ArrayDeque
import java.util.Queue


class ForEachTicked {
    private var onCompleteBlock: (() -> Unit)? = null
    private var onTickBlock: (() -> Unit)? = null
    private var isFinished = false

    @Deprecated("Use ChunkSnapshot with Coroutine")
    fun onComplete(block: () -> Unit): ForEachTicked {
        this.onCompleteBlock = block

        if (isFinished) block()

        return this
    }

    @Deprecated("Use ChunkSnapshot with Coroutine")
    fun onTick(block: () -> Unit): ForEachTicked {
        this.onTickBlock = block

        return this
    }

    internal fun finish() {
        isFinished = true

        onCompleteBlock?.invoke()
    }

    internal fun tick() {
        onTickBlock?.invoke()
    }
}

@Deprecated("Use ChunkSnapshot with Coroutine")
fun <T> Iterable<T>.forEachTicked(
    thresholdMs: Long = 10,
    action: (T) -> Unit
): ForEachTicked {
    val task = ForEachTicked()
    val iterator = this.iterator()

    object : BukkitRunnable() {
        private val threshold = thresholdMs * 1_000_000L

        override fun run() {
            val startTime = System.nanoTime()

            while (iterator.hasNext()) {
                if (System.nanoTime() - startTime >= threshold) {
                    task.tick()
                    return
                }

                action(iterator.next())
            }

            task.finish()
            this.cancel()
        }
    }.runTaskTimer(plugin, 0L, 1L)

    return task
}

fun interface LongConsumer {
    fun accept(value: Long)
}

@Deprecated("Use ChunkSnapshot with Coroutine")
fun LongArray.forEachTicked(
    thresholdMs: Long = 10,
    action: LongConsumer
): ForEachTicked {
    val task = ForEachTicked()
    var index = 0
    val size = this.size

    object : BukkitRunnable() {
        private val threshold = thresholdMs * 1_000_000L

        override fun run() {
            val startTime = System.nanoTime()

            while (index < size) {
                if (System.nanoTime() - startTime >= threshold) {
                    task.tick()
                    return
                }

                action.accept(this@forEachTicked[index++])
            }

            task.finish()
            this.cancel()
        }
    }.runTaskTimer(plugin, 0L, 1L)

    return task
}

class RunnableTask {
    private val queue: Queue<(() -> Unit) -> Unit> = ArrayDeque()
    private var isRunning = false
    private var isAborted = false


    fun later(tick: Int = 1, callback: () -> Unit): RunnableTask {
        queue.add { next ->
            object : BukkitRunnable() {
                override fun run() {
                    if (isAborted) return

                    try {
                        callback()
                    } catch (e: Exception) {
                        e.printStackTrace()
                        abort()
                        return
                    }

                    next()
                }
            }.runTaskLater(plugin, tick.toLong())
        }

        return executeNext()
    }

    fun loop(
        amount: Int,
        tick: Int,
        delay: Int,
        callback: (i: Int, runnable: BukkitRunnable) -> Unit
    ): RunnableTask {
        if (amount <= 0) return this

        queue.add { next ->
            object : BukkitRunnable() {
                private var i = 0

                override fun run() {
                    if (isAborted) {
                        this.cancel()
                        return
                    }
                    if (++i > amount) {
                        this.cancel()
                        next()
                        return
                    }

                    try {
                        callback(i - 1, this)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        this.cancel()
                        abort()

                        return
                    }

                    if (!this.isCancelled) return
                    abort()
                }
            }.runTaskTimer(plugin, delay.toLong(), tick.toLong())
        }

        return executeNext()
    }

    fun then(callback: () -> Unit): RunnableTask {
        queue.add { next ->
            if (isAborted) return@add

            try {
                callback()
            } catch (e: Exception) {
                e.printStackTrace()
                abort()
                return@add
            }

            next()
        }

        return executeNext()
    }

    fun sync(callback: () -> Unit): RunnableTask {
        queue.add { next ->
            object : BukkitRunnable() {
                override fun run() {
                    if (isAborted) return

                    try {
                        callback()
                    } catch (e: Exception) {
                        e.printStackTrace()
                        abort()
                        return
                    }

                    next()
                }
            }.runTask(plugin)
        }

        return executeNext()
    }

    fun abort() {
        isAborted = true
        isRunning = false

        queue.clear()
    }

    private fun executeNext(): RunnableTask {
        if (!isRunning && queue.isNotEmpty()) {
            isRunning = true

            runNext()
        }

        return this
    }

    private fun runNext() {
        if (isAborted) return

        val task = queue.poll()

        if (task != null) task(this::runNext)
        else isRunning = false
    }
}


fun later(tick: Int = 1, callback: () -> Unit) =
    RunnableTask().later(tick, callback)

fun loop(amount: Int, tick: Int = 1, delay: Int = 0, callback: (i: Int, BukkitRunnable) -> Unit) =
    RunnableTask().loop(amount, tick, delay, callback)

fun then(callback: () -> Unit) =
    RunnableTask().then(callback)

fun sync(callback: () -> Unit) =
    RunnableTask().sync(callback)
