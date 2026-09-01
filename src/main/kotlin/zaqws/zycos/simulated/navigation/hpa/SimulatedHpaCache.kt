package zaqws.zycos.simulated.navigation.hpa

import zaqws.zycos.simulated.map.SimulatedMap
import zaqws.zycos.simulated.navigation.SimulatedTraversalProfile
import zaqws.zycos.simulated.paper.map.SimulatedMapRevision

internal class SimulatedHpaCache(
    private val maximumEntries: Int = DEFAULT_MAXIMUM_ENTRIES
) {
    companion object {
        private const val DEFAULT_MAXIMUM_ENTRIES = 16
        private const val DEFAULT_LOAD_FACTOR = 0.75f
    }

    private data class CacheKey(
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
        traversalProfile: SimulatedTraversalProfile
    ): SimulatedHpaGraph {
        val key = CacheKey(traversalProfile, map.revision)
        cache[key]?.let { return it }

        cache.keys.removeIf { it.mapRevision < map.revision }

        return SimulatedHpaGraph.build(map, traversalProfile).also {
            cache[key] = it
        }
    }
}
