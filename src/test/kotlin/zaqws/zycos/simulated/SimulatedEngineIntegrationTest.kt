package zaqws.zycos.simulated

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import zaqws.zycos.simulated.entity.SimulatedEntity
import zaqws.zycos.simulated.entity.SimulatedEntityFlag
import zaqws.zycos.simulated.entity.SimulatedEntityFlags
import zaqws.zycos.simulated.entity.SimulatedEntityStore
import zaqws.zycos.simulated.entity.SimulatedHitbox
import zaqws.zycos.simulated.entity.SimulatedTeam
import zaqws.zycos.simulated.external.SimulatedExternalAction
import zaqws.zycos.simulated.external.SimulatedExternalActorId
import zaqws.zycos.simulated.external.SimulatedExternalActorSnapshot
import zaqws.zycos.simulated.external.SimulatedExternalFrame
import zaqws.zycos.simulated.goal.SimulatedGoalSet
import zaqws.zycos.simulated.goal.builtin.SimulatedMeleeGoal
import zaqws.zycos.simulated.goal.builtin.SimulatedNearestTargetGoal
import zaqws.zycos.simulated.math.SimulatedVector3
import zaqws.zycos.simulated.navigation.NavigationNode
import zaqws.zycos.simulated.navigation.SimulatedPath
import zaqws.zycos.simulated.navigation.SimulatedPathFollower
import zaqws.zycos.simulated.physics.SimulatedPhysicsConfig
import zaqws.zycos.simulated.snapshot.SimulatedEvent
import zaqws.zycos.simulated.system.SimulatedSystemContext
import java.util.concurrent.TimeUnit

class SimulatedEngineIntegrationTest {
    @Test
    fun `path follower emits jump event when climbing`() {
        val entityStore = SimulatedEntityStore()
        val entityId = entityStore.create(
            position = SimulatedVector3(0.5, 1.0, 0.5),
            flags = SimulatedEntityFlags.of(SimulatedEntityFlag.ON_GROUND)
        )
        val events = ArrayList<SimulatedEvent>()
        val pathFollower = SimulatedPathFollower(
            entityStore = entityStore,
            eventConsumer = events::add
        )

        pathFollower.setPath(
            entityId,
            SimulatedPath(
                listOf(
                    NavigationNode(0, 0, 16),
                    NavigationNode(1, 0, 32)
                )
            )
        )
        pathFollower.update(SimulatedSystemContext(7L, 0.05))
        pathFollower.update(SimulatedSystemContext(8L, 0.05))

        assertEquals(listOf(SimulatedEvent.Jump(7L, entityId)), events)
        assertEquals(
            SimulatedPhysicsConfig.DEFAULT.jumpVelocity,
            entityStore.velocity(entityStore.slotOf(entityId)).y
        )
        assertFalse(
            entityStore.hasFlag(
                entityStore.slotOf(entityId),
                SimulatedEntityFlag.ON_GROUND
            )
        )
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `entity without movement goal retains horizontal velocity`() {
        val engine = SimulatedEngineBuilder(TestSimulatedMapFactory.create())
            .config(SimulatedConfig(navigationWorkerCount = 1))
            .build()

        try {
            val entity = engine.spawn {
                position(2.5, 1.0, 2.5)
                velocity(0.2, 0.0, 0.0)
            }

            engine.start()

            assertTrue(
                waitUntil {
                    engine.tick >= 3L &&
                            (entity.snapshot()?.position?.x ?: 0.0) > 2.5
                }
            )
        } finally {
            engine.close()
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `default engine moves enemies into range and resolves combat`() {
        val engine =
            SimulatedEngineBuilder(
                TestSimulatedMapFactory.create()
            ).config(
                SimulatedConfig(
                    navigationWorkerCount = 1,
                    maximumPathRequestsPerTick = 16,
                    minimumRepathIntervalTicks = 1
                )
            ).build()

        try {
            val first =
                spawnFighter(
                    engine = engine,
                    position =
                        SimulatedVector3(
                            2.5,
                            1.0,
                            2.5
                        ),
                    team = SimulatedTeam(1)
                )

            val second =
                spawnFighter(
                    engine = engine,
                    position =
                        SimulatedVector3(
                            6.5,
                            1.0,
                            2.5
                        ),
                    team = SimulatedTeam(2)
                )

            engine.start()

            assertTrue(
                waitUntil {
                    val firstHealth =
                        first.snapshot()?.health

                    val secondHealth =
                        second.snapshot()?.health

                    firstHealth != null &&
                            secondHealth != null &&
                            (
                                    firstHealth < 20.0 ||
                                            secondHealth < 20.0
                                    )
                }
            )
        } finally {
            engine.close()
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `external actor frame participates in targeting and combat`() {
        val engine =
            SimulatedEngineBuilder(
                TestSimulatedMapFactory.create()
            ).config(
                SimulatedConfig(
                    navigationWorkerCount = 1,
                    maximumPathRequestsPerTick = 16
                )
            ).build()

        try {
            spawnFighter(
                engine = engine,
                position =
                    SimulatedVector3(
                        2.5,
                        1.0,
                        2.5
                    ),
                team = SimulatedTeam(1),
                attackRange = 2.0
            )

            val actorId =
                SimulatedExternalActorId(1L)

            engine.submitExternalFrame(
                SimulatedExternalFrame(
                    sequence = 1L,
                    actors =
                        listOf(
                            SimulatedExternalActorSnapshot(
                                actorId = actorId,
                                position =
                                    SimulatedVector3(
                                        3.5,
                                        1.0,
                                        2.5
                                    ),
                                velocity =
                                    SimulatedVector3.ZERO,
                                hitbox =
                                    SimulatedHitbox.DEFAULT,
                                health = 20.0,
                                maximumHealth = 20.0,
                                team = SimulatedTeam(2)
                            )
                        )
                )
            )

            engine.start()

            val actions =
                ArrayList<SimulatedExternalAction>()

            assertTrue(
                waitUntil {
                    actions.addAll(
                        engine.drainExternalActions()
                    )

                    actions.any {
                        ((it is SimulatedExternalAction.Damage && it.actorId == actorId) ||
                                (it is SimulatedExternalAction.Combined && it.actorId == actorId))
                    }
                }
            )
        } finally {
            engine.close()
        }
    }

    @Test
    fun stoppedEngineRejectsUnusableWork() {
        val engine = SimulatedEngineBuilder(TestSimulatedMapFactory.create())
            .config(SimulatedConfig())
            .build()
        engine.start()
        engine.stop()

        assertThrows<IllegalStateException> {
            engine.spawn {
                position(SimulatedVector3.ZERO)
            }
        }

        assertThrows<IllegalStateException> {
            engine.submitExternalFrame(
                SimulatedExternalFrame(
                    sequence = 1L,
                    actors = emptyList()
                )
            )
        }

        engine.close()
    }

    @Test
    fun spatialBoundsEnforcedOnSpawnAndTeleport() {
        val engine = SimulatedEngineBuilder(TestSimulatedMapFactory.create())
            .config(SimulatedConfig(spatialCellSize = 0.5))
            .build()
        try {
            assertThrows<IllegalArgumentException> {
                engine.spawn {
                    position(SimulatedVector3(20_000_000.0, 64.0, 20_000_000.0))
                }
            }

            val entity = engine.spawn {
                position(SimulatedVector3(500_000.0, 64.0, 500_000.0))
            }

            assertThrows<IllegalArgumentException> {
                engine.teleport(
                    entity.entityId,
                    SimulatedVector3(20_000_000.0, 64.0, 20_000_000.0),
                    null,
                    null
                )
            }
        } finally {
            engine.close()
        }
    }

    private fun spawnFighter(
        engine: SimulatedEngine,
        position: SimulatedVector3,
        team: SimulatedTeam,
        attackRange: Double = 1.25
    ): SimulatedEntity =
        engine.spawn {
            position(position)
            team(team)
            movementSpeed(0.25)
            attackRange(attackRange)
            attackCooldownTicks(2)
            goals(
                SimulatedGoalSet.build {
                    goal(
                        SimulatedNearestTargetGoal(
                            searchRadius = 32.0
                        )
                    )

                    goal(
                        SimulatedMeleeGoal()
                    )
                }
            )
        }

    private fun waitUntil(
        timeoutMilliseconds: Long = 7_000L,
        condition: () -> Boolean
    ): Boolean {
        val deadline =
            System.nanoTime() +
                    TimeUnit.MILLISECONDS.toNanos(
                        timeoutMilliseconds
                    )

        while (System.nanoTime() < deadline) {
            if (condition()) {
                return true
            }

            Thread.sleep(10L)
        }

        return condition()
    }
}
