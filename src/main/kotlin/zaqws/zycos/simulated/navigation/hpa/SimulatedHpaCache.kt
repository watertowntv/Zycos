package zaqws.zycos.simulated.navigation.hpa

import zaqws.zycos.simulated.map.SimulatedMap
import zaqws.zycos.simulated.map.SimulatedMapRevision
import zaqws.zycos.simulated.navigation.SimulatedMapReferenceKey
import zaqws.zycos.simulated.navigation.SimulatedPathCancellation
import zaqws.zycos.simulated.navigation.SimulatedTraversalProfile

internal class SimulatedHpaCache(
    private val maximumEntries: Int = DEFAULT_MAXIMUM_ENTRIES
) {
    companion object {
        private const val DEFAULT_MAXIMUM_ENTRIES = 16
        private const val DEFAULT_LOAD_FACTOR = 0.75f
    }

    private data class CacheKey(
        val mapKey: SimulatedMapReferenceKey,
        val traversalProfile: SimulatedTraversalProfile,
        val mapRevision: SimulatedMapRevision
    )

    private val cache = object : LinkedHashMap<CacheKey, SimulatedHpaGraph>(
        maximumEntries,
        DEFAULT_LOAD_FACTOR,
        true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<CacheKey, SimulatedHpaGraph>?
        ) = size > maximumEntries
    }

    init {
        require(maximumEntries > 0)
    }

    @Synchronized
    fun graph(
        map: SimulatedMap,
        traversalProfile: SimulatedTraversalProfile,
        expectedRevision: SimulatedMapRevision = map.revision,
        cancellation: SimulatedPathCancellation = SimulatedPathCancellation.NEVER
    ): SimulatedHpaGraph? {
        if (map.revision != expectedRevision) return null
        if (cancellation.isCancelled()) return null

        val mapKey = SimulatedMapReferenceKey(map)
        val key = CacheKey(mapKey, traversalProfile, expectedRevision)
        cache[key]?.let { return it }

        cache.keys.removeIf { it.mapKey.map == null }
        cache.keys.removeIf { it.mapKey == mapKey && it.mapRevision < expectedRevision }

        val built = SimulatedHpaGraph.build(map, traversalProfile, cancellation) ?: return null
        if (built.mapRevision != expectedRevision || map.revision != expectedRevision || cancellation.isCancelled()) {
            return null
        }
        val insertionKey = CacheKey(mapKey, traversalProfile, built.mapRevision)
        cache[insertionKey] = built
        return built
    }
}
