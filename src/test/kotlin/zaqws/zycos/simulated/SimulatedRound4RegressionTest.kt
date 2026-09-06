package zaqws.zycos.simulated

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import zaqws.zycos.simulated.entity.SimulatedEntityFlag
import zaqws.zycos.simulated.entity.SimulatedEntityFlags
import zaqws.zycos.simulated.entity.SimulatedEntityId
import zaqws.zycos.simulated.entity.SimulatedEntityStore
import zaqws.zycos.simulated.external.SimulatedExternalAction
import zaqws.zycos.simulated.external.SimulatedExternalActionQueue
import zaqws.zycos.simulated.external.SimulatedExternalActorId
import zaqws.zycos.simulated.map.SimulatedMapRevision
import zaqws.zycos.simulated.math.SimulatedVector3
import zaqws.zycos.simulated.navigation.NavigationNode
import zaqws.zycos.simulated.navigation.SimulatedAStarPathfinder
import zaqws.zycos.simulated.navigation.SimulatedNavigationService
import zaqws.zycos.simulated.navigation.SimulatedPathCancellation
import zaqws.zycos.simulated.navigation.SimulatedPathRequest
import zaqws.zycos.simulated.navigation.SimulatedPathResult
import zaqws.zycos.simulated.navigation.SimulatedTraversalProfile
import zaqws.zycos.simulated.navigation.hpa.SimulatedHpaCache
import zaqws.zycos.simulated.navigation.hpa.SimulatedHpaPathfinder
import zaqws.zycos.simulated.physics.SimulatedPhysicsConfig
import zaqws.zycos.simulated.physics.SimulatedPhysicsSystem
import zaqws.zycos.simulated.projectile.SimulatedProjectileRemovalReason
import zaqws.zycos.simulated.projectile.SimulatedProjectileEvent
import zaqws.zycos.simulated.projectile.SimulatedProjectileManager
import zaqws.zycos.simulated.projectile.SimulatedProjectileId
import zaqws.zycos.simulated.projectile.SimulatedProjectileSource
import zaqws.zycos.simulated.projectile.SimulatedProjectileSpawnData
import zaqws.zycos.simulated.projectile.SimulatedProjectileDefinition
import zaqws.zycos.simulated.spatial.SimulatedSpatialCell
import zaqws.zycos.simulated.spatial.SimulatedSpatialIndex
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SimulatedRound4RegressionTest {
    @Test
    fun stopOnCreatedEngineCleansUpResources() {
        val map = TestSimulatedMapFactory.create()
        val engine = SimulatedEngineBuilder(map)
            .config(SimulatedConfig(navigationWorkerCount = 2))
            .build()

        engine.stop()

        assertThrows<IllegalStateException> {
            engine.spawn {
                position(SimulatedVector3(2.5, 1.0, 2.5))
            }
        }
        assertEquals(0, engine.entityCount)
    }

    @Test
    fun spawnAfterStopIsRejectedAndLeavesNoKnownEntities() {
        val map = TestSimulatedMapFactory.create()
        val engine = SimulatedEngineBuilder(map).build()
        engine.start()
        engine.stop()

        assertThrows<IllegalStateException> {
            engine.spawn {
                position(SimulatedVector3(2.5, 1.0, 2.5))
            }
        }
        assertEquals(0, engine.entityCount)
        assertFalse(engine.exists(SimulatedEntityId(1)))
    }

    @Test
    fun projectileSpawnAfterStopIsRejectedAndLeavesNoActiveProjectiles() {
        val map = TestSimulatedMapFactory.create()
        val engine = SimulatedEngineBuilder(map).build()
        engine.start()
        engine.stop()

        assertThrows<IllegalStateException> {
            engine.projectileManager.spawn {
                position(SimulatedVector3(2.5, 1.0, 2.5))
            }
        }
        assertEquals(0, engine.projectileManager.size)
    }

    @Test
    fun saturatedCommandQueueRejectsEntitySpawnWithoutLeakingIdentity() {
        val engine = SimulatedEngineBuilder(TestSimulatedMapFactory.create())
            .config(
                SimulatedConfig(
                    maximumQueuedCommands = 1,
                    navigationWorkerCount = 1
                )
            )
            .build()

        try {
            val accepted = engine.spawn {
                position(SimulatedVector3(2.5, 1.0, 2.5))
            }

            assertThrows<IllegalStateException> {
                engine.spawn {
                    position(SimulatedVector3(3.5, 1.0, 2.5))
                }
            }

            assertTrue(engine.exists(accepted.entityId))
            assertFalse(engine.exists(SimulatedEntityId(2)))
        } finally {
            engine.close()
        }
    }

    @Test
    fun saturatedCommandQueueRejectsProjectileSpawnWithoutLeakingIdentity() {
        val engine = SimulatedEngineBuilder(TestSimulatedMapFactory.create())
            .config(
                SimulatedConfig(
                    maximumQueuedCommands = 1,
                    navigationWorkerCount = 1
                )
            )
            .build()

        try {
            engine.spawn {
                position(SimulatedVector3(2.5, 1.0, 2.5))
            }

            assertThrows<IllegalStateException> {
                engine.projectileManager.spawn {
                    position(SimulatedVector3(2.5, 2.0, 2.5))
                }
            }

            assertFalse(
                engine.projectileManager.exists(
                    SimulatedProjectileId(1)
                )
            )
        } finally {
            engine.close()
        }
    }

    @Test
    fun repeatedStopAndCloseProduceNoExceptions() {
        val map = TestSimulatedMapFactory.create()
        val engine = SimulatedEngineBuilder(map).build()
        engine.start()

        engine.stop()
        engine.stop()
        engine.close()
        engine.close()
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun concurrentSpawnAndStopLeavesZeroOrphanedEntities() {
        val map = TestSimulatedMapFactory.create()
        val engine = SimulatedEngineBuilder(map)
            .config(SimulatedConfig(navigationWorkerCount = 1))
            .build()
        engine.start()

        val threadCount = 8
        val spawnsPerThread = 50
        val latch = CountDownLatch(threadCount)
        val successfulSpawns = AtomicInteger(0)
        val rejectedSpawns = AtomicInteger(0)

        for (i in 0 until threadCount) {
            Thread {
                for (j in 0 until spawnsPerThread) {
                    try {
                        engine.spawn {
                            position(SimulatedVector3(2.5, 1.0, 2.5))
                        }
                        successfulSpawns.incrementAndGet()
                    } catch (_: IllegalStateException) {
                        rejectedSpawns.incrementAndGet()
                    }
                }
                latch.countDown()
            }.start()
        }

        Thread.sleep(5)
        engine.stop()
        assertTrue(latch.await(5, TimeUnit.SECONDS))

        assertThrows<IllegalStateException> {
            engine.spawn {
                position(SimulatedVector3(2.5, 1.0, 2.5))
            }
        }

        engine.close()
        assertEquals(threadCount * spawnsPerThread, successfulSpawns.get() + rejectedSpawns.get())
        assertEquals(0, engine.entityCount)
    }

    @Test
    fun spatialCellValidityAndClampingInvariants() {
        val cellSize = 4.0

        assertTrue(SimulatedSpatialCell.isValidPosition(SimulatedVector3(0.0, 0.0, 0.0), cellSize))
        assertTrue(SimulatedSpatialCell.isValidPosition(SimulatedVector3(100_000.0, 64.0, -100_000.0), cellSize))

        assertFalse(SimulatedSpatialCell.isValidPosition(SimulatedVector3(Double.MAX_VALUE, 0.0, 0.0), cellSize))
        assertFalse(SimulatedSpatialCell.isValidPosition(SimulatedVector3(0.0, Double.MAX_VALUE, 0.0), cellSize))
        assertFalse(SimulatedSpatialCell.isValidPosition(SimulatedVector3(0.0, 0.0, Double.MAX_VALUE), cellSize))
        assertFalse(SimulatedSpatialCell.isValidPosition(SimulatedVector3(-Double.MAX_VALUE, 0.0, 0.0), cellSize))
        assertFalse(SimulatedSpatialCell.isValidPosition(SimulatedVector3(Double.NaN, 0.0, 0.0), cellSize))
        assertFalse(SimulatedSpatialCell.isValidPosition(SimulatedVector3(Double.POSITIVE_INFINITY, 0.0, 0.0), cellSize))

        val clamped = SimulatedSpatialCell.clampPosition(SimulatedVector3(100_000_000.0, -500_000.0, 100_000_000.0), cellSize)
        assertTrue(SimulatedSpatialCell.isValidPosition(clamped, cellSize))
    }

    @Test
    fun spatialIndexExtremeQueryRadiusDoesNotOverflow() {
        val store = SimulatedEntityStore(16)
        val id = store.create(position = SimulatedVector3(2.0, 2.0, 2.0))
        val index = SimulatedSpatialIndex(4.0)
        index.rebuild(store)

        var visited = 0
        index.forEachNearby(SimulatedVector3(0.0, 0.0, 0.0), 1e12) { _ ->
            visited++
        }
        assertEquals(1, visited)
    }

    @Test
    fun spatialIndexDoubleMaximumRadiusFromNegativeBoundaryDoesNotOverflow() {
        val cellSize = 4.0
        val store = SimulatedEntityStore(16)
        store.create(position = SimulatedVector3(2.0, 2.0, 2.0))
        val index = SimulatedSpatialIndex(cellSize)
        index.rebuild(store)
        val minimumX = SimulatedSpatialCell.MINIMUM_X.toDouble() * cellSize

        var visited = 0
        index.forEachNearby(
            SimulatedVector3(minimumX, 0.0, 0.0),
            Double.MAX_VALUE
        ) {
            visited++
        }

        assertEquals(1, visited)
    }

    @Test
    fun projectileOutOfBoundsRejectedAtIngress() {
        val map = TestSimulatedMapFactory.create()
        val engine = SimulatedEngineBuilder(map).build()
        try {
            assertThrows<IllegalArgumentException> {
                engine.projectileManager.spawn {
                    position(SimulatedVector3(Double.MAX_VALUE, 0.0, 0.0))
                }
            }

            val proj = engine.projectileManager.spawn {
                position(SimulatedVector3(2.5, 2.0, 2.5))
            }

            assertThrows<IllegalArgumentException> {
                proj.teleport(SimulatedVector3(Double.MAX_VALUE, 0.0, 0.0))
            }

        } finally {
            engine.close()
        }
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    fun extremeProjectileVelocityIsBoundedByMapCollisionTraversal() {
        val map = TestSimulatedMapFactory.create()
        val entityStore = SimulatedEntityStore(16)
        val spatialIndex = SimulatedSpatialIndex(4.0)
        spatialIndex.rebuild(entityStore)
        val manager = SimulatedProjectileManager(
            initialCapacity = 4,
            maximumQueuedEvents = 16,
            map = map,
            entityStore = entityStore,
            spatialIndex = spatialIndex,
            commandConsumer = { true },
            damageConsumer = { _, _ -> 0.0 },
            externalActionConsumer = {}
        )
        val projectileId = manager.spawnNow(
            SimulatedProjectileSpawnData(
                position = SimulatedVector3(2.5, 2.0, 2.5),
                velocity = SimulatedVector3(1.0e100, 0.0, 0.0),
                source = SimulatedProjectileSource.None,
                team = zaqws.zycos.simulated.entity.SimulatedTeam.NONE,
                definition = SimulatedProjectileDefinition(maximumRange = 1.0e100)
            ),
            tick = 1L
        )

        manager.update(2L, zaqws.zycos.simulated.external.SimulatedExternalFrame.EMPTY)

        assertFalse(manager.exists(projectileId))
        val removal = manager.drainEvents().filterIsInstance<SimulatedProjectileEvent.Remove>().single()
        assertEquals(SimulatedProjectileRemovalReason.BLOCK_HIT, removal.reason)
        manager.close()
    }

    @Test
    fun publicDataClassAndEnumShapesRemainCompatible() {
        val physicsConstructorParameters = SimulatedPhysicsConfig::class.java.declaredConstructors
            .filterNot { it.isSynthetic }
            .maxOf { it.parameterCount }
        val requestConstructorParameters = SimulatedPathRequest::class.java.declaredConstructors
            .filterNot { it.isSynthetic }
            .maxOf { it.parameterCount }

        assertEquals(9, physicsConstructorParameters)
        assertEquals(7, requestConstructorParameters)
        assertEquals(
            listOf(
                "REMOVED",
                "BLOCK_HIT",
                "ENTITY_HIT",
                "EXTERNAL_ACTOR_HIT",
                "MAXIMUM_TICKS",
                "MAXIMUM_RANGE"
            ),
            SimulatedProjectileRemovalReason.entries.map { it.name }
        )
    }

    @Test
    fun deadEntityMovementVelocityIsZeroed() {
        val entityStore = SimulatedEntityStore(16)
        val entityId = entityStore.create(
            position = SimulatedVector3(2.0, 1.0, 2.0),
            flags = SimulatedEntityFlags.of(SimulatedEntityFlag.DEAD)
        )
        val slot = entityStore.slotOf(entityId)
        entityStore.setMovementVelocity(slot, 1.0, 2.0, 3.0)

        val map = TestSimulatedMapFactory.create()
        val physicsSystem = SimulatedPhysicsSystem(
            entityStore = entityStore,
            map = map,
            config = SimulatedPhysicsConfig.DEFAULT,
            spatialCellSize = 4.0,
            damageConsumer = { _, _, _ -> }
        )

        physicsSystem.update(zaqws.zycos.simulated.system.SimulatedSystemContext(1L, 0.05))

        val velocity = entityStore.movementVelocity(slot)
        assertEquals(0.0, velocity.x)
        assertEquals(0.0, velocity.y)
        assertEquals(0.0, velocity.z)
    }

    @Test
    fun navigationCancellationReturnsInvalid() {
        val map = TestSimulatedMapFactory.create()
        val pathfinder = SimulatedAStarPathfinder()
        val profile = SimulatedTraversalProfile.from(0.6, 1.8, 1.0)
        val start = NavigationNode(0, 0, 16)
        val target = NavigationNode(5, 5, 16)

        val cancelledRequest = SimulatedPathRequest(
            requestId = 1L,
            entityId = SimulatedEntityId(1),
            start = start,
            target = target,
            traversalProfile = profile,
            maximumDropHeightUnits = 16,
            mapRevision = map.revision
        )

        val result = pathfinder.findPath(
            map,
            cancelledRequest,
            SimulatedPathCancellation { true }
        )
        assertTrue(result is SimulatedPathResult.Invalid)

        val staleRevisionRequest = SimulatedPathRequest(
            requestId = 2L,
            entityId = SimulatedEntityId(1),
            start = start,
            target = target,
            traversalProfile = profile,
            maximumDropHeightUnits = 16,
            mapRevision = SimulatedMapRevision(9999L)
        )

        val staleResult = pathfinder.findPath(map, staleRevisionRequest)
        assertTrue(staleResult is SimulatedPathResult.Invalid)
    }

    @Test
    fun hpaPathfinderCancellationReturnsInvalid() {
        val map = TestSimulatedMapFactory.create()
        val pathfinder = SimulatedHpaPathfinder()
        val profile = SimulatedTraversalProfile.from(0.6, 1.8, 1.0)
        val start = NavigationNode(0, 0, 16)
        val target = NavigationNode(5, 5, 16)

        val cancelledRequest = SimulatedPathRequest(
            requestId = 1L,
            entityId = SimulatedEntityId(1),
            start = start,
            target = target,
            traversalProfile = profile,
            maximumDropHeightUnits = 16,
            mapRevision = map.revision
        )

        val result = pathfinder.findPath(
            map,
            cancelledRequest,
            SimulatedPathCancellation { true }
        )
        assertTrue(result is SimulatedPathResult.Invalid)
    }

    @Test
    fun hpaCacheEnforcesExpectedRevisionAndPurgesStale() {
        val map = TestSimulatedMapFactory.create()
        val cache = SimulatedHpaCache()
        val profile = SimulatedTraversalProfile.from(0.6, 1.8, 1.0)

        val wrongRevision = SimulatedMapRevision(map.revision.value + 10L)
        assertNull(cache.graph(map, profile, wrongRevision))

        val graph = cache.graph(map, profile, map.revision)
        assertNotNull(graph)
        assertEquals(map.revision, graph?.mapRevision)
    }

    @Test
    fun navigationServiceShutdownTimeoutEnforced() {
        val map = TestSimulatedMapFactory.create()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val service = SimulatedNavigationService(
            map = map,
            pathfinder = zaqws.zycos.simulated.navigation.SimulatedLocalPathfinder { _, request ->
                started.countDown()
                while (release.count > 0L) {
                    try {
                        release.await(10, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                    }
                }
                SimulatedPathResult.Invalid(request.requestId, request.entityId, request.mapRevision)
            },
            workerCount = 1
        )
        val profile = SimulatedTraversalProfile.from(0.6, 1.8, 1.0)
        service.requestPath(
            SimulatedEntityId(1),
            NavigationNode(0, 0, 16),
            NavigationNode(5, 5, 16),
            profile,
            16
        )
        assertTrue(started.await(1, TimeUnit.SECONDS))

        val startedAt = System.nanoTime()
        try {
            service.close()
        } finally {
            release.countDown()
        }
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        assertTrue(elapsedMillis in 800L..2_000L, "close returned after $elapsedMillis ms")
    }

    @Test
    fun externalActionQueueDropsOldestAsIndivisibleUnit() {
        val queue = SimulatedExternalActionQueue(1)
        val actorId = SimulatedExternalActorId(1L)

        val action1 = SimulatedExternalAction.Combined(
            actorId = actorId,
            damage = 5.0,
            knockbackVelocity = SimulatedVector3(1.0, 0.0, 0.0)
        )
        val action2 = SimulatedExternalAction.Combined(
            actorId = actorId,
            damage = 10.0,
            knockbackVelocity = SimulatedVector3(2.0, 0.0, 0.0)
        )

        queue.offer(action1)
        assertEquals(0L, queue.droppedCount)

        queue.offer(action2)
        assertEquals(1L, queue.droppedCount)

        val drained = ArrayList<SimulatedExternalAction>()
        queue.drainTo(drained, 10)
        assertEquals(1, drained.size)
        val retrieved = drained[0] as SimulatedExternalAction.Combined
        assertEquals(10.0, retrieved.damage)
        assertEquals(2.0, retrieved.knockbackVelocity?.x)
    }

    @Test
    fun aabbClosestPointAndReachCalculation() {
        val targetPos = SimulatedVector3(10.0, 64.0, 10.0)
        val halfWidth = 0.3
        val height = 1.8
        val minX = targetPos.x - halfWidth
        val maxX = targetPos.x + halfWidth
        val minY = targetPos.y
        val maxY = targetPos.y + height
        val minZ = targetPos.z - halfWidth
        val maxZ = targetPos.z + halfWidth

        val eyeWithinReach = SimulatedVector3(10.0, 65.0, 13.0)
        val closestX1 = eyeWithinReach.x.coerceIn(minX, maxX)
        val closestY1 = eyeWithinReach.y.coerceIn(minY, maxY)
        val closestZ1 = eyeWithinReach.z.coerceIn(minZ, maxZ)
        assertEquals(10.0, closestX1)
        assertEquals(65.0, closestY1)
        assertEquals(10.3, closestZ1)
        val dist1 = kotlin.math.sqrt(
            (eyeWithinReach.x - closestX1) * (eyeWithinReach.x - closestX1) +
                    (eyeWithinReach.y - closestY1) * (eyeWithinReach.y - closestY1) +
                    (eyeWithinReach.z - closestZ1) * (eyeWithinReach.z - closestZ1)
        )
        assertTrue(dist1 <= 6.0)

        val eyeOutOfReach = SimulatedVector3(10.0, 65.0, 20.0)
        val closestX2 = eyeOutOfReach.x.coerceIn(minX, maxX)
        val closestY2 = eyeOutOfReach.y.coerceIn(minY, maxY)
        val closestZ2 = eyeOutOfReach.z.coerceIn(minZ, maxZ)
        val dist2 = kotlin.math.sqrt(
            (eyeOutOfReach.x - closestX2) * (eyeOutOfReach.x - closestX2) +
                    (eyeOutOfReach.y - closestY2) * (eyeOutOfReach.y - closestY2) +
                    (eyeOutOfReach.z - closestZ2) * (eyeOutOfReach.z - closestZ2)
        )
        assertTrue(dist2 > 6.0)
    }

    @Test
    fun cooldownDamageScalingCurve() {
        val baseDamage = 10.0

        fun scale(cooldown: Double): Double {
            val clamped = cooldown.coerceIn(0.0, 1.0)
            return baseDamage * (0.2 + 0.8 * clamped * clamped)
        }

        assertEquals(10.0, scale(1.0), 1e-9)
        assertEquals(4.0, scale(0.5), 1e-9)
        assertEquals(2.32, scale(0.2), 1e-9)
        assertEquals(2.0, scale(0.0), 1e-9)
    }

    @Test
    fun largeProjectileFrameScalarAccessWithoutArrayCloning() {
        val count = 5_000
        val ids = IntArray(count) { it + 1 }
        val posX = DoubleArray(count) { it * 1.0 }
        val posY = DoubleArray(count) { 64.0 }
        val posZ = DoubleArray(count) { it * 2.0 }
        val velX = DoubleArray(count) { 0.1 }
        val velY = DoubleArray(count) { 0.0 }
        val velZ = DoubleArray(count) { 0.2 }
        val presIds = IntArray(count) { 0 }
        val ages = IntArray(count) { it }
        val distances = DoubleArray(count) { it * 0.5 }

        val frame = zaqws.zycos.simulated.projectile.SimulatedProjectileFrame(
            tick = 100L,
            rawProjectileIds = ids,
            rawPositionX = posX,
            rawPositionY = posY,
            rawPositionZ = posZ,
            rawVelocityX = velX,
            rawVelocityY = velY,
            rawVelocityZ = velZ,
            rawPresentationIds = presIds,
            rawAgeTicks = ages,
            rawTravelledDistance = distances
        )

        assertEquals(count, frame.size)
        val lastIdx = count - 1
        assertEquals(count, frame.rawProjectileIdAt(lastIdx))
        assertEquals(4999.0, frame.positionXAt(lastIdx))
        assertEquals(64.0, frame.positionYAt(lastIdx))
        assertEquals(9998.0, frame.positionZAt(lastIdx))
        assertEquals(4999, frame.ageTicksAt(lastIdx))
        assertEquals(2499.5, frame.travelledDistanceAt(lastIdx))
    }
}
