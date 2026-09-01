@file:Suppress("unused")

package zaqws.zycos.simulated.navigation.hpa

import zaqws.zycos.simulated.map.SimulatedMap
import zaqws.zycos.simulated.paper.map.SimulatedMapRevision
import zaqws.zycos.simulated.navigation.SimulatedTraversalProfile

class SimulatedHpaCache(
    private val maximumEntries: Int =
        DEFAULT_MAXIMUM_ENTRIES
) {
    private data class CacheKey(
        val traversalProfile:
        SimulatedTraversalProfile,
        val mapRevision:
        SimulatedMapRevision
    )

    private val cache =
        object :
            LinkedHashMap<
                    CacheKey,
                    SimulatedHpaGraph
                    >(
                maximumEntries,
                DEFAULT_LOAD_FACTOR,
                true
            ) {
            override fun removeEldestEntry(
                eldest:
                MutableMap.MutableEntry<
                        CacheKey,
                        SimulatedHpaGraph
                        >?
            ): Boolean =
                size >
                        maximumEntries
        }

    init {
        require(maximumEntries > 0)
    }

    @Synchronized
    fun graph(
        map: SimulatedMap,
        traversalProfile:
        SimulatedTraversalProfile
    ): SimulatedHpaGraph {
        val key =
            CacheKey(
                traversalProfile =
                    traversalProfile,

                mapRevision =
                    map.revision
            )

        val existingGraph =
            cache[
                key
            ]

        if (existingGraph != null) {
            return existingGraph
        }

        removeOldRevisions(
            map.revision
        )

        val graph =
            SimulatedHpaGraph.build(
                map,
                traversalProfile
            )

        cache[
            key
        ] = graph

        return graph
    }

    @Synchronized
    fun invalidateBefore(
        revision: SimulatedMapRevision
    ) {
        removeOldRevisions(
            revision
        )
    }

    @Synchronized
    fun invalidate(
        traversalProfile:
        SimulatedTraversalProfile
    ) {
        val iterator =
            cache.keys.iterator()

        while (iterator.hasNext()) {
            if (
                iterator.next()
                    .traversalProfile ==
                traversalProfile
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

    private fun removeOldRevisions(
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

    companion object {
        private const val DEFAULT_MAXIMUM_ENTRIES =
            16

        private const val DEFAULT_LOAD_FACTOR =
            0.75f
    }
}