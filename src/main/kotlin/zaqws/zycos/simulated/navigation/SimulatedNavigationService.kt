@file:Suppress("unused")

package zaqws.zycos.simulated.navigation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import zaqws.zycos.simulated.entity.SimulatedEntityId
import zaqws.zycos.simulated.map.SimulatedMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal class SimulatedNavigationService(
    private val map: SimulatedMap,
    private val pathfinder:
    SimulatedLocalPathfinder =
        SimulatedAStarPathfinder(),
    workerCount: Int,
    maximumQueuedRequests: Int =
        DEFAULT_MAXIMUM_QUEUED_REQUESTS,
    dispatcher: CoroutineDispatcher =
        Dispatchers.Default,
    cacheMaximumEntries: Int =
        DEFAULT_CACHE_MAXIMUM_ENTRIES
) : AutoCloseable {
    companion object {
        private const val DEFAULT_MAXIMUM_QUEUED_REQUESTS = 8192
        private const val DEFAULT_CACHE_MAXIMUM_ENTRIES = 4096
    }

    private val closed =
        AtomicBoolean(false)

    private val nextRequestId =
        AtomicLong(1L)

    private val pendingRequestCount =
        AtomicInteger(0)

    private val latestRequestIds =
        ConcurrentHashMap<Int, Long>()

    private val resultQueue =
        ConcurrentLinkedQueue<
                SimulatedPathResult
                >()

    private val pathCache =
        SimulatedPathCache(
            cacheMaximumEntries
        )

    private val requests =
        Channel<SimulatedPathRequest>(
            capacity =
                maximumQueuedRequests
        )

    private val serviceJob =
        SupervisorJob()

    private val scope =
        CoroutineScope(
            serviceJob +
                    dispatcher +
                    CoroutineName(
                        "Simulated-Navigation"
                    )
        )

    private val workers =
        ArrayList<Job>(
            workerCount
        )

    init {
        require(workerCount > 0)
        require(maximumQueuedRequests > 0)
        require(cacheMaximumEntries > 0)

        repeat(workerCount) {
            workers.add(
                scope.launch {
                    workerLoop()
                }
            )
        }
    }

    val pendingRequests: Int
        get() =
            pendingRequestCount.get()

    val isClosed: Boolean
        get() =
            closed.get()

    fun requestPath(
        entityId: SimulatedEntityId,
        start: NavigationNode,
        target: NavigationNode,
        traversalProfile:
        SimulatedTraversalProfile,
        maximumDropHeightUnits: Int
    ): Long? {
        require(maximumDropHeightUnits >= 0)

        if (closed.get()) {
            return null
        }

        val requestId =
            allocateRequestId()

        val request =
            SimulatedPathRequest(
                requestId =
                    requestId,

                entityId =
                    entityId,

                start =
                    start,

                target =
                    target,

                traversalProfile =
                    traversalProfile,

                maximumDropHeightUnits =
                    maximumDropHeightUnits,

                mapRevision =
                    map.revision
            )

        latestRequestIds[
            entityId.value
        ] = requestId

        val cachedResult =
            pathCache.get(
                request
            )

        if (cachedResult != null) {
            resultQueue.offer(
                cachedResult
            )

            return requestId
        }

        val result =
            requests.trySend(
                request
            )

        if (result.isFailure) {
            latestRequestIds.remove(
                entityId.value,
                requestId
            )

            return null
        }

        pendingRequestCount.incrementAndGet()

        return requestId
    }

    fun cancel(
        entityId: SimulatedEntityId
    ) {
        latestRequestIds.remove(
            entityId.value
        )
    }

    fun isLatestRequest(
        entityId: SimulatedEntityId,
        requestId: Long
    ): Boolean =
        latestRequestIds[
            entityId.value
        ] == requestId

    fun pollResult():
            SimulatedPathResult? {
        while (true) {
            val result =
                resultQueue.poll()
                    ?: return null

            if (
                isLatestRequest(
                    result.entityId,
                    result.requestId
                )
            ) {
                latestRequestIds.remove(
                    result.entityId.value,
                    result.requestId
                )

                return result
            }
        }
    }

    fun drainResults(
        maximumResults: Int = Int.MAX_VALUE,
        consumer:
            (SimulatedPathResult) -> Unit
    ): Int {
        require(maximumResults >= 0)

        var processedResults = 0

        while (
            processedResults <
            maximumResults
        ) {
            val result =
                pollResult()
                    ?: break

            consumer(result)
            processedResults++
        }

        return processedResults
    }

    fun invalidateCache() {
        pathCache.invalidateBefore(
            map.revision
        )
    }

    private suspend fun workerLoop() {
        try {
            for (request in requests) {
                try {
                    processRequest(
                        request
                    )
                } finally {
                    pendingRequestCount
                        .decrementAndGet()
                }
            }
        } catch (
            exception: CancellationException
        ) {
            throw exception
        }
    }

    private fun processRequest(
        request: SimulatedPathRequest
    ) {
        if (
            !isLatestRequest(
                request.entityId,
                request.requestId
            )
        ) {
            return
        }

        if (
            map.revision !=
            request.mapRevision
        ) {
            offerResultIfCurrent(
                SimulatedPathResult.Invalid(
                    requestId =
                        request.requestId,

                    entityId =
                        request.entityId,

                    mapRevision =
                        request.mapRevision
                )
            )

            return
        }

        val cachedResult =
            pathCache.get(
                request
            )

        if (cachedResult != null) {
            offerResultIfCurrent(
                cachedResult
            )

            return
        }

        val result =
            pathfinder.findPath(
                map,
                request
            )

        if (
            map.revision ==
            request.mapRevision
        ) {
            pathCache.put(
                request,
                result
            )
        }

        offerResultIfCurrent(
            result
        )
    }

    private fun offerResultIfCurrent(
        result: SimulatedPathResult
    ) {
        if (
            !isLatestRequest(
                result.entityId,
                result.requestId
            )
        ) {
            return
        }

        resultQueue.offer(
            result
        )
    }

    private fun allocateRequestId(): Long {
        val requestId =
            nextRequestId.getAndIncrement()

        check(requestId > 0L) {
            "Simulated path request identifier space exhausted"
        }

        return requestId
    }

    override fun close() {
        if (
            !closed.compareAndSet(
                false,
                true
            )
        ) {
            return
        }

        requests.close()
        scope.cancel()

        latestRequestIds.clear()
        resultQueue.clear()

        pathCache.clear()
    }
}