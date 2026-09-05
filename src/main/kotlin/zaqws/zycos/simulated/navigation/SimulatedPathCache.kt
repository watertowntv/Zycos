package zaqws.zycos.simulated.navigation

import zaqws.zycos.simulated.map.SimulatedMap
import zaqws.zycos.simulated.map.SimulatedMapRevision
import java.lang.ref.WeakReference

internal class SimulatedMapReferenceKey(map: SimulatedMap) {
    private val weakRef = WeakReference(map)
    private val hash = System.identityHashCode(map)

    val map: SimulatedMap?
        get() = weakRef.get()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SimulatedMapReferenceKey) return false
        val my = weakRef.get() ?: return false
        val target = other.weakRef.get() ?: return false
        return my === target
    }

    override fun hashCode(): Int = hash
}

internal class SimulatedPathCache(
    private val maximumEntries: Int = DEFAULT_MAXIMUM_ENTRIES
) {
    companion object {
        private const val DEFAULT_MAXIMUM_ENTRIES = 4096
        private const val DEFAULT_LOAD_FACTOR = 0.75f
    }

    private data class CacheKey(
        val mapKey: SimulatedMapReferenceKey,
        val start: NavigationNode,
        val target: NavigationNode,
        val traversalProfile: SimulatedTraversalProfile,
        val maximumDropHeightUnits: Int,
        val mapRevision: SimulatedMapRevision
    )

    private sealed interface CacheValue {
        data class Success(
            val path: SimulatedPath,
            val totalCost: Double
        ) : CacheValue

        data object Unreachable : CacheValue
    }

    private val cache = object : LinkedHashMap<CacheKey, CacheValue>(
        maximumEntries,
        DEFAULT_LOAD_FACTOR,
        true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<CacheKey, CacheValue>?
        ) = size > maximumEntries
    }

    init {
        require(maximumEntries > 0)
    }

    @Synchronized
    fun get(map: SimulatedMap, request: SimulatedPathRequest): SimulatedPathResult? =
        when (val value = cache[keyOf(map, request)] ?: return null) {
            is CacheValue.Success -> SimulatedPathResult.Success(
                requestId = request.requestId,
                entityId = request.entityId,
                mapRevision = request.mapRevision,
                path = value.path,
                totalCost = value.totalCost
            )

            CacheValue.Unreachable -> SimulatedPathResult.Unreachable(
                requestId = request.requestId,
                entityId = request.entityId,
                mapRevision = request.mapRevision
            )
        }

    @Synchronized
    fun put(map: SimulatedMap, request: SimulatedPathRequest, result: SimulatedPathResult) {
        if (result.mapRevision != request.mapRevision) return

        val value = when (result) {
            is SimulatedPathResult.Success ->
                CacheValue.Success(result.path, result.totalCost)

            is SimulatedPathResult.Unreachable -> CacheValue.Unreachable
            is SimulatedPathResult.Invalid -> return
        }

        cache[keyOf(map, request)] = value
    }

    @Synchronized
    fun invalidateBefore(map: SimulatedMap, revision: SimulatedMapRevision) {
        val key = SimulatedMapReferenceKey(map)
        cache.keys.removeIf { it.mapKey == key && it.mapRevision < revision }
    }

    @Synchronized
    fun clear() {
        cache.clear()
    }

    private fun keyOf(map: SimulatedMap, request: SimulatedPathRequest) = CacheKey(
        mapKey = SimulatedMapReferenceKey(map),
        start = request.start,
        target = request.target,
        traversalProfile = request.traversalProfile,
        maximumDropHeightUnits = request.maximumDropHeightUnits,
        mapRevision = request.mapRevision
    )
}
