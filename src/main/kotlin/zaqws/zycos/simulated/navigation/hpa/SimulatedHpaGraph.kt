@file:Suppress("unused")

package zaqws.zycos.simulated.navigation.hpa

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import zaqws.zycos.simulated.map.SimulatedMap
import zaqws.zycos.simulated.map.SimulatedMapRevision
import zaqws.zycos.simulated.navigation.NavigationNode
import zaqws.zycos.simulated.navigation.SimulatedTraversalProfile
import java.util.concurrent.atomic.AtomicInteger

class SimulatedHpaGraph private constructor(
    val mapRevision: SimulatedMapRevision,
    val traversalProfile: SimulatedTraversalProfile,
    val clusters:
    Long2ObjectOpenHashMap<SimulatedHpaCluster>,
    private val portals:
    Int2ObjectOpenHashMap<SimulatedHpaPortal>
) {
    val clusterCount: Int
        get() =
            clusters.size

    val portalCount: Int
        get() =
            portals.size

    fun cluster(
        chunkX: Int,
        chunkZ: Int
    ): SimulatedHpaCluster? =
        clusters[
            clusterId(
                chunkX,
                chunkZ
            )
        ]

    fun cluster(
        clusterId: Long
    ): SimulatedHpaCluster? =
        clusters[
            clusterId
        ]

    fun portal(
        portalId: Int
    ): SimulatedHpaPortal? =
        portals[
            portalId
        ]

    fun clusterFor(
        node: NavigationNode
    ): SimulatedHpaCluster? =
        cluster(
            node.chunkX,
            node.chunkZ
        )

    inline fun forEachCluster(
        action: (
            SimulatedHpaCluster
        ) -> Unit
    ) {
        val iterator =
            clusters.values.iterator()

        while (iterator.hasNext()) {
            action(
                iterator.next()
            )
        }
    }

    companion object {
        private const val MAXIMUM_BUILD_RETRIES = 3

        fun build(
            map: SimulatedMap,
            traversalProfile:
            SimulatedTraversalProfile
        ): SimulatedHpaGraph? {
            var attempt = 0
            while (attempt < MAXIMUM_BUILD_RETRIES) {
                if (Thread.currentThread().isInterrupted) {
                    return null
                }
                attempt++

                val expectedMapRevision =
                    map.revision

            val clusterPortals =
                Long2ObjectOpenHashMap<
                        MutableList<
                                SimulatedHpaPortal
                                >
                        >()

            val portals =
                Int2ObjectOpenHashMap<
                        SimulatedHpaPortal
                        >()

            val nextPortalId =
                AtomicInteger(1)

            var chunkZ =
                map.bounds.minimumChunkZ

            while (
                chunkZ <=
                map.bounds.maximumChunkZ
            ) {
                var chunkX =
                    map.bounds.minimumChunkX

                while (
                    chunkX <=
                    map.bounds.maximumChunkX
                ) {
                    if (
                        map.walkSurfaceChunk(
                            chunkX,
                            chunkZ
                        ) != null
                    ) {
                        createHorizontalBoundaryPortals(
                            map = map,
                            traversalProfile =
                                traversalProfile,
                            firstChunkX =
                                chunkX,
                            firstChunkZ =
                                chunkZ,
                            secondChunkX =
                                chunkX + 1,
                            secondChunkZ =
                                chunkZ,
                            nextPortalId =
                                nextPortalId,
                            clusterPortals =
                                clusterPortals,
                            portals =
                                portals
                        )

                        createVerticalBoundaryPortals(
                            map = map,
                            traversalProfile =
                                traversalProfile,
                            firstChunkX =
                                chunkX,
                            firstChunkZ =
                                chunkZ,
                            secondChunkX =
                                chunkX,
                            secondChunkZ =
                                chunkZ + 1,
                            nextPortalId =
                                nextPortalId,
                            clusterPortals =
                                clusterPortals,
                            portals =
                                portals
                        )
                    }

                    chunkX++
                }

                chunkZ++
            }

            val clusters =
                Long2ObjectOpenHashMap<
                        SimulatedHpaCluster
                        >()

            chunkZ =
                map.bounds.minimumChunkZ

            while (
                chunkZ <=
                map.bounds.maximumChunkZ
            ) {
                var chunkX =
                    map.bounds.minimumChunkX

                while (
                    chunkX <=
                    map.bounds.maximumChunkX
                ) {
                    val chunkRevision =
                        map.chunkRevision(
                            chunkX,
                            chunkZ
                        )

                    if (
                        chunkRevision != null
                    ) {
                        val clusterId =
                            clusterId(
                                chunkX,
                                chunkZ
                            )

                        clusters.put(
                            clusterId,
                            SimulatedHpaCluster(
                                clusterId =
                                    clusterId,

                                chunkX =
                                    chunkX,

                                chunkZ =
                                    chunkZ,

                                revision =
                                    chunkRevision,

                                portals =
                                    clusterPortals[
                                        clusterId
                                    ] ?: emptyList()
                            )
                        )
                    }

                    chunkX++
                }

                chunkZ++
            }

            if (
                map.revision ==
                expectedMapRevision
            ) {
                return SimulatedHpaGraph(
                    mapRevision =
                        expectedMapRevision,

                    traversalProfile =
                        traversalProfile,

                    clusters =
                        clusters,

                    portals =
                        portals
                )
            }
        }

        return null
    }

        internal fun clusterId(
            chunkX: Int,
            chunkZ: Int
        ): Long =
            (chunkX.toLong() shl Int.SIZE_BITS) or
                    (
                            chunkZ.toLong() and
                                    0xFFFF_FFFFL
                            )

        private fun createHorizontalBoundaryPortals(
            map: SimulatedMap,
            traversalProfile:
            SimulatedTraversalProfile,
            firstChunkX: Int,
            firstChunkZ: Int,
            secondChunkX: Int,
            secondChunkZ: Int,
            nextPortalId: AtomicInteger,
            clusterPortals:
            Long2ObjectOpenHashMap<
                    MutableList<
                            SimulatedHpaPortal
                            >
                    >,
            portals:
            Int2ObjectOpenHashMap<
                    SimulatedHpaPortal
                    >
        ) {
            if (
                !map.bounds.containsChunk(
                    secondChunkX,
                    secondChunkZ
                )
            ) {
                return
            }

            if (
                map.walkSurfaceChunk(
                    secondChunkX,
                    secondChunkZ
                ) == null
            ) {
                return
            }

            val firstWorldX =
                firstChunkX *
                        CHUNK_SIZE +
                        CHUNK_SIZE -
                        1

            val secondWorldX =
                secondChunkX *
                        CHUNK_SIZE

            val minimumWorldZ =
                maxOf(
                    map.bounds.minimumZ,
                    firstChunkZ *
                            CHUNK_SIZE
                )

            val maximumWorldZ =
                minOf(
                    map.bounds.maximumZ,
                    firstChunkZ *
                            CHUNK_SIZE +
                            CHUNK_SIZE -
                            1
                )

            val candidates =
                ArrayList<PortalCandidate>()

            var worldZ =
                minimumWorldZ

            while (
                worldZ <=
                maximumWorldZ
            ) {
                collectPortalCandidatesBetweenColumns(
                    map = map,
                    traversalProfile =
                        traversalProfile,
                    firstX =
                        firstWorldX,
                    firstZ =
                        worldZ,
                    secondX =
                        secondWorldX,
                    secondZ =
                        worldZ,
                    destination =
                        candidates
                )

                worldZ++
            }

            addCompressedPortals(
                candidates = candidates,
                firstChunkX = firstChunkX,
                firstChunkZ = firstChunkZ,
                secondChunkX = secondChunkX,
                secondChunkZ = secondChunkZ,
                nextPortalId = nextPortalId,
                clusterPortals = clusterPortals,
                portals = portals
            )
        }

        private fun createVerticalBoundaryPortals(
            map: SimulatedMap,
            traversalProfile:
            SimulatedTraversalProfile,
            firstChunkX: Int,
            firstChunkZ: Int,
            secondChunkX: Int,
            secondChunkZ: Int,
            nextPortalId: AtomicInteger,
            clusterPortals:
            Long2ObjectOpenHashMap<
                    MutableList<
                            SimulatedHpaPortal
                            >
                    >,
            portals:
            Int2ObjectOpenHashMap<
                    SimulatedHpaPortal
                    >
        ) {
            if (
                !map.bounds.containsChunk(
                    secondChunkX,
                    secondChunkZ
                )
            ) {
                return
            }

            if (
                map.walkSurfaceChunk(
                    secondChunkX,
                    secondChunkZ
                ) == null
            ) {
                return
            }

            val firstWorldZ =
                firstChunkZ *
                        CHUNK_SIZE +
                        CHUNK_SIZE -
                        1

            val secondWorldZ =
                secondChunkZ *
                        CHUNK_SIZE

            val minimumWorldX =
                maxOf(
                    map.bounds.minimumX,
                    firstChunkX *
                            CHUNK_SIZE
                )

            val maximumWorldX =
                minOf(
                    map.bounds.maximumX,
                    firstChunkX *
                            CHUNK_SIZE +
                            CHUNK_SIZE -
                            1
                )

            val candidates =
                ArrayList<PortalCandidate>()

            var worldX =
                minimumWorldX

            while (
                worldX <=
                maximumWorldX
            ) {
                collectPortalCandidatesBetweenColumns(
                    map = map,
                    traversalProfile =
                        traversalProfile,
                    firstX =
                        worldX,
                    firstZ =
                        firstWorldZ,
                    secondX =
                        worldX,
                    secondZ =
                        secondWorldZ,
                    destination =
                        candidates
                )

                worldX++
            }

            addCompressedPortals(
                candidates = candidates,
                firstChunkX = firstChunkX,
                firstChunkZ = firstChunkZ,
                secondChunkX = secondChunkX,
                secondChunkZ = secondChunkZ,
                nextPortalId = nextPortalId,
                clusterPortals = clusterPortals,
                portals = portals
            )
        }

        private fun collectPortalCandidatesBetweenColumns(
            map: SimulatedMap,
            traversalProfile:
            SimulatedTraversalProfile,
            firstX: Int,
            firstZ: Int,
            secondX: Int,
            secondZ: Int,
            destination:
                MutableCollection<PortalCandidate>
        ) {
            val firstSurfaceCount =
                map.surfaceCountAt(
                    firstX,
                    firstZ
                )

            val secondSurfaceCount =
                map.surfaceCountAt(
                    secondX,
                    secondZ
                )

            if (
                firstSurfaceCount == 0 ||
                secondSurfaceCount == 0
            ) {
                return
            }

            var firstSurfaceIndex = 0

            while (
                firstSurfaceIndex <
                firstSurfaceCount
            ) {
                val firstSurface =
                    map.surfaceAt(
                        firstX,
                        firstZ,
                        firstSurfaceIndex
                    )

                firstSurfaceIndex++

                if (
                    firstSurface == null ||
                    firstSurface.clearanceUnits <
                    traversalProfile.heightUnits ||
                    firstSurface.waterDepthUnits >
                    map.config.maximumWalkableWaterDepthUnits
                ) {
                    continue
                }

                var secondSurfaceIndex = 0

                while (
                    secondSurfaceIndex <
                    secondSurfaceCount
                ) {
                    val secondSurface =
                        map.surfaceAt(
                            secondX,
                            secondZ,
                            secondSurfaceIndex
                        )

                    secondSurfaceIndex++

                    if (
                        secondSurface == null ||
                        secondSurface.clearanceUnits <
                        traversalProfile.heightUnits ||
                        secondSurface.waterDepthUnits >
                        map.config.maximumWalkableWaterDepthUnits
                    ) {
                        continue
                    }

                    val firstNode =
                        NavigationNode(
                            x =
                                firstX,

                            z =
                                firstZ,

                            floorHeightUnits =
                                firstSurface.floorHeightUnits
                        )

                    val secondNode =
                        NavigationNode(
                            x =
                                secondX,

                            z =
                                secondZ,

                            floorHeightUnits =
                                secondSurface.floorHeightUnits
                        )

                    if (
                        !canOccupy(
                            map,
                            firstNode,
                            traversalProfile
                        ) ||
                        !canOccupy(
                            map,
                            secondNode,
                            traversalProfile
                        )
                    ) {
                        continue
                    }

                    destination.add(
                        PortalCandidate(
                        firstNode =
                            firstNode,
                        secondNode =
                            secondNode
                        )
                    )
                }
            }
        }

        private fun addCompressedPortals(
            candidates: List<PortalCandidate>,
            firstChunkX: Int,
            firstChunkZ: Int,
            secondChunkX: Int,
            secondChunkZ: Int,
            nextPortalId: AtomicInteger,
            clusterPortals:
            Long2ObjectOpenHashMap<
                    MutableList<
                            SimulatedHpaPortal
                            >
                    >,
            portals:
            Int2ObjectOpenHashMap<
                    SimulatedHpaPortal
                    >
        ) {
            val candidatesByHeight =
                LinkedHashMap<
                        Long,
                        MutableList<PortalCandidate>
                        >()

            for (candidate in candidates) {
                val heightKey =
                    (
                            candidate.firstNode
                                .floorHeightUnits
                                .toLong() shl
                                    Int.SIZE_BITS
                            ) or
                            (
                                    candidate.secondNode
                                        .floorHeightUnits
                                        .toLong() and
                                            0xFFFF_FFFFL
                                    )

                candidatesByHeight
                    .computeIfAbsent(
                        heightKey
                    ) {
                        ArrayList()
                    }
                    .add(candidate)
            }

            for (
            heightCandidates in
            candidatesByHeight.values
            ) {
                var segmentStart = 0
                var index = 1

                while (
                    index <=
                    heightCandidates.size
                ) {
                    val continuesSegment =
                        index <
                        heightCandidates.size &&
                                areAdjacent(
                                    heightCandidates[
                                        index - 1
                                    ],
                                    heightCandidates[index]
                                )

                    if (continuesSegment) {
                        index++
                        continue
                    }

                    val representative =
                        heightCandidates[
                            (
                                    segmentStart +
                                            index - 1
                                    ) ushr 1
                        ]

                    addPortal(
                        firstChunkX = firstChunkX,
                        firstChunkZ = firstChunkZ,
                        secondChunkX = secondChunkX,
                        secondChunkZ = secondChunkZ,
                        firstNode =
                            representative.firstNode,
                        secondNode =
                            representative.secondNode,
                        nextPortalId = nextPortalId,
                        clusterPortals = clusterPortals,
                        portals = portals
                    )

                    segmentStart = index
                    index++
                }
            }
        }

        private fun areAdjacent(
            first: PortalCandidate,
            second: PortalCandidate
        ): Boolean =
            kotlin.math.abs(
                first.firstNode.x -
                        second.firstNode.x
            ) +
                    kotlin.math.abs(
                        first.firstNode.z -
                                second.firstNode.z
                    ) == 1 &&
                    kotlin.math.abs(
                        first.secondNode.x -
                                second.secondNode.x
                    ) +
                    kotlin.math.abs(
                        first.secondNode.z -
                                second.secondNode.z
                    ) == 1

        private fun addPortal(
            firstChunkX: Int,
            firstChunkZ: Int,
            secondChunkX: Int,
            secondChunkZ: Int,
            firstNode: NavigationNode,
            secondNode: NavigationNode,
            nextPortalId: AtomicInteger,
            clusterPortals:
            Long2ObjectOpenHashMap<
                    MutableList<
                            SimulatedHpaPortal
                            >
                    >,
            portals:
            Int2ObjectOpenHashMap<
                    SimulatedHpaPortal
                    >
        ) {
            val portalId =
                nextPortalId
                    .getAndIncrement()

            check(portalId > 0) {
                "HPA portal identifier space exhausted"
            }

            val firstClusterId =
                clusterId(
                    firstChunkX,
                    firstChunkZ
                )

            val secondClusterId =
                clusterId(
                    secondChunkX,
                    secondChunkZ
                )

            val portal =
                SimulatedHpaPortal(
                    portalId =
                        portalId,

                    firstClusterId =
                        firstClusterId,

                    secondClusterId =
                        secondClusterId,

                    firstNode =
                        firstNode,

                    secondNode =
                        secondNode
                )

            portals.put(
                portalId,
                portal
            )

            clusterPortals
                .computeIfAbsent(
                    firstClusterId
                ) {
                    ArrayList()
                }
                .add(
                    portal
                )

            clusterPortals
                .computeIfAbsent(
                    secondClusterId
                ) {
                    ArrayList()
                }
                .add(
                    portal
                )
        }

        private fun canOccupy(
            map: SimulatedMap,
            node: NavigationNode,
            traversalProfile:
            SimulatedTraversalProfile
        ): Boolean =
            !map.hasCollision(
                zaqws.zycos.simulated.math.SimulatedAABB
                    .fromBottomCenter(
                        position =
                            node.toPosition(),

                        width =
                            traversalProfile.width,

                        height =
                            traversalProfile.height
                    )
            )

        private data class PortalCandidate(
            val firstNode: NavigationNode,
            val secondNode: NavigationNode
        )

        private const val CHUNK_SIZE =
            16
    }
}
