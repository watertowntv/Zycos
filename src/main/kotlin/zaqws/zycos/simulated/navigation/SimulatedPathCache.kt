package zaqws.zycos.simulated.navigation

import zaqws.zycos.simulated.paper.map.SimulatedMapRevision

internal class SimulatedPathCache(
    private val maximumEntries: Int =
        DEFAULT_MAXIMUM_ENTRIES
) {
    companion object {
        private const val DEFAULT_MAXIMUM_ENTRIES = 4096
        private const val DEFAULT_LOAD_FACTOR = 0.75f
    }

    private data class CacheKey(
        val start: NavigationNode,
        val target: NavigationNode,
        val traversalProfile:
        SimulatedTraversalProfile,
        val maximumDropHeightUnits: Int,
        val mapRevision:
        SimulatedMapRevision
    )

    private sealed interface CacheValue {
        data class Success(
            val path: SimulatedPath,
            val totalCost: Double
        ) : CacheValue

        data object Unreachable :
            CacheValue
    }

    private val cache =
        object :
            LinkedHashMap<
                    CacheKey,
                    CacheValue
                    >(
                maximumEntries,
                DEFAULT_LOAD_FACTOR,
                true
            ) {
            override fun removeEldestEntry(
                eldest:
                MutableMap.MutableEntry<
                        CacheKey,
                        CacheValue
                        >?
            ): Boolean =
                size >
                        maximumEntries
        }

    init {
        require(maximumEntries > 0)
    }

    @Synchronized
    fun get(
        request: SimulatedPathRequest
    ): SimulatedPathResult? {
        val value =
            cache[
                keyOf(request)
            ] ?: return null

        return when (value) {
            is CacheValue.Success ->
                SimulatedPathResult.Success(
                    requestId =
                        request.requestId,

                    entityId =
                        request.entityId,

                    mapRevision =
                        request.mapRevision,

                    path =
                        value.path,

                    totalCost =
                        value.totalCost
                )

            CacheValue.Unreachable ->
                SimulatedPathResult.Unreachable(
                    requestId =
                        request.requestId,

                    entityId =
                        request.entityId,

                    mapRevision =
                        request.mapRevision
                )
        }
    }

    @Synchronized
    fun put(
        request: SimulatedPathRequest,
        result: SimulatedPathResult
    ) {
        if (
            result.mapRevision !=
            request.mapRevision
        ) {
            return
        }

        val value =
            when (result) {
                is SimulatedPathResult.Success ->
                    CacheValue.Success(
                        path =
                            result.path,

                        totalCost =
                            result.totalCost
                    )

                is SimulatedPathResult.Unreachable ->
                    CacheValue.Unreachable

                is SimulatedPathResult.Invalid ->
                    return
            }

        cache[
            keyOf(request)
        ] = value
    }

    @Synchronized
    fun invalidateBefore(
        revision: SimulatedMapRevision
    ) {
        val iterator =
            cache.keys.iterator()

        while (iterator.hasNext()) {
            if (
                iterator.next()
                    .mapRevision <
                revision
            ) {
                iterator.remove()
            }
        }
    }

    @Synchronized
    fun clear() {
        cache.clear()
    }

    @Synchronized
    fun size(): Int =
        cache.size

    private fun keyOf(
        request: SimulatedPathRequest
    ) = CacheKey(
        start =
            request.start,

        target =
            request.target,

        traversalProfile =
            request.traversalProfile,

        maximumDropHeightUnits =
            request.maximumDropHeightUnits,

        mapRevision =
            request.mapRevision
    )
}