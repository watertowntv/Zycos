package zaqws.zycos.simulated.navigation

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import zaqws.zycos.simulated.entity.SimulatedEntityId
import zaqws.zycos.simulated.map.SimulatedMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal class SimulatedNavigationService(
    private val map: SimulatedMap,
    private val pathfinder: SimulatedLocalPathfinder = SimulatedAStarPathfinder(),
    workerCount: Int,
    maximumQueuedRequests: Int = DEFAULT_MAXIMUM_QUEUED_REQUESTS,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    cacheMaximumEntries: Int = DEFAULT_CACHE_MAXIMUM_ENTRIES
) : AutoCloseable {
    companion object {
        private const val DEFAULT_MAXIMUM_QUEUED_REQUESTS = 8192
        private const val DEFAULT_CACHE_MAXIMUM_ENTRIES = 4096
    }

    private val closed = AtomicBoolean()
    private val nextRequestId = AtomicLong(1L)
    private val latestRequestIds = ConcurrentHashMap<Int, Long>()
    private val results = ConcurrentLinkedQueue<SimulatedPathResult>()
    private val pathCache = SimulatedPathCache(cacheMaximumEntries)
    private val requests = Channel<SimulatedPathRequest>(maximumQueuedRequests)
    private val scope = CoroutineScope(
        SupervisorJob() + dispatcher + CoroutineName("Simulated-Navigation")
    )

    init {
        require(workerCount > 0)
        require(maximumQueuedRequests > 0)
        require(cacheMaximumEntries > 0)

        repeat(workerCount) {
            scope.launch {
                for (request in requests) {
                    processRequest(request)
                }
            }
        }
    }

    fun requestPath(
        entityId: SimulatedEntityId,
        start: NavigationNode,
        target: NavigationNode,
        traversalProfile: SimulatedTraversalProfile,
        maximumDropHeightUnits: Int
    ): Long? {
        require(maximumDropHeightUnits >= 0)
        if (closed.get()) return null

        val requestId = allocateRequestId()
        val request = SimulatedPathRequest(
            requestId = requestId,
            entityId = entityId,
            start = start,
            target = target,
            traversalProfile = traversalProfile,
            maximumDropHeightUnits = maximumDropHeightUnits,
            mapRevision = map.revision
        )

        latestRequestIds[entityId.value] = requestId

        pathCache.get(request)?.let {
            results.offer(it)
            return requestId
        }

        if (requests.trySend(request).isFailure) {
            latestRequestIds.remove(entityId.value, requestId)
            return null
        }

        return requestId
    }

    fun cancel(entityId: SimulatedEntityId) {
        latestRequestIds.remove(entityId.value)
    }

    fun drainResults(
        maximumResults: Int = Int.MAX_VALUE,
        consumer: (SimulatedPathResult) -> Unit
    ): Int {
        require(maximumResults >= 0)

        var processedResults = 0

        while (processedResults < maximumResults) {
            consumer(pollResult() ?: break)
            processedResults++
        }

        return processedResults
    }

    fun invalidateCache() {
        pathCache.invalidateBefore(map.revision)
    }

    private fun pollResult(): SimulatedPathResult? {
        while (true) {
            val result = results.poll() ?: return null

            if (isLatestRequest(result)) {
                latestRequestIds.remove(result.entityId.value, result.requestId)
                return result
            }
        }
    }

    private fun processRequest(request: SimulatedPathRequest) {
        if (!isLatestRequest(request)) return

        if (map.revision != request.mapRevision) {
            offerResultIfCurrent(
                SimulatedPathResult.Invalid(
                    requestId = request.requestId,
                    entityId = request.entityId,
                    mapRevision = request.mapRevision
                )
            )
            return
        }

        pathCache.get(request)?.let {
            offerResultIfCurrent(it)
            return
        }

        val result = pathfinder.findPath(map, request)

        if (map.revision == request.mapRevision) {
            pathCache.put(request, result)
        }

        offerResultIfCurrent(result)
    }

    private fun isLatestRequest(request: SimulatedPathRequest) =
        latestRequestIds[request.entityId.value] == request.requestId

    private fun isLatestRequest(result: SimulatedPathResult) =
        latestRequestIds[result.entityId.value] == result.requestId

    private fun offerResultIfCurrent(result: SimulatedPathResult) {
        if (isLatestRequest(result)) results.offer(result)
    }

    private fun allocateRequestId(): Long {
        val requestId = nextRequestId.getAndIncrement()
        check(requestId > 0L) { "Simulated path request identifier space exhausted" }
        return requestId
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return

        requests.close()
        scope.cancel()
        latestRequestIds.clear()
        results.clear()
        pathCache.clear()
    }
}
