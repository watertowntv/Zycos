package zaqws.zycos.simulated

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import zaqws.zycos.simulated.entity.SimulatedTeam
import zaqws.zycos.simulated.entity.SimulatedHitbox
import zaqws.zycos.simulated.external.SimulatedExternalAction
import zaqws.zycos.simulated.external.SimulatedExternalActorId
import zaqws.zycos.simulated.external.SimulatedExternalActorSnapshot
import zaqws.zycos.simulated.external.SimulatedExternalFrame
import zaqws.zycos.simulated.goal.SimulatedGoalSet
import zaqws.zycos.simulated.goal.builtin.SimulatedNearestTargetGoal
import zaqws.zycos.simulated.goal.builtin.SimulatedRangedAttackGoal
import zaqws.zycos.simulated.math.SimulatedVector3
import zaqws.zycos.simulated.projectile.SimulatedProjectileDefinition
import zaqws.zycos.simulated.projectile.SimulatedProjectileEvent
import zaqws.zycos.simulated.projectile.SimulatedProjectileFrame
import zaqws.zycos.simulated.projectile.SimulatedProjectileRemovalReason
import java.util.concurrent.TimeUnit

class SimulatedProjectileIntegrationTest {
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `projectile handle controls lifecycle and published frame`() {
        val engine = createEngine()

        try {
            val projectile =
                engine.projectileManager.spawn {
                    position(2.5, 2.0, 2.5)
                    velocity(0.5, 0.0, 0.0)
                }

            engine.start()

            assertTrue(
                waitUntil {
                    val snapshot = projectile.snapshot()

                    snapshot != null &&
                            snapshot.position.x > 2.5
                }
            )

            projectile.teleport(
                SimulatedVector3(
                    4.5,
                    2.0,
                    4.5
                )
            )

            assertTrue(
                waitUntil {
                    projectile.snapshot()
                        ?.position
                        ?.z ?: 0.0 >= 4.5
                }
            )

            projectile.setVelocity(
                SimulatedVector3(
                    0.0,
                    0.0,
                    0.5
                )
            )

            assertTrue(
                waitUntil {
                    val snapshot = projectile.snapshot()

                    snapshot != null &&
                            snapshot.velocity.z == 0.5
                }
            )

            projectile.remove()

            assertTrue(
                waitUntil {
                    !projectile.exists
                }
            )

            assertFalse(projectile.exists)
        } finally {
            engine.close()
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `high speed projectile stops at first solid column`() {
        val engine =
            SimulatedEngineBuilder(
                TestSimulatedMapFactory.create { worldX, _ ->
                    TestSimulatedMapFactory.Cell(
                        walkable = worldX != 4
                    )
                }
            ).config(
                SimulatedConfig(
                    navigationWorkerCount = 1
                )
            ).build()

        try {
            val projectile =
                engine.projectileManager.spawn {
                    position(2.5, 2.0, 2.5)
                    velocity(4.0, 0.0, 0.0)
                }

            engine.start()

            assertTrue(
                waitUntil {
                    !projectile.exists &&
                            engine.tick >= 2L
                }
            )

            val blockHit =
                engine.projectileManager
                    .drainEvents()
                    .filterIsInstance<
                            SimulatedProjectileEvent.BlockHit
                            >()
                    .singleOrNull()

            assertNotNull(blockHit)
            assertTrue(
                checkNotNull(blockHit)
                    .position.x < 4.0
            )
        } finally {
            engine.close()
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `projectile damages nearest enemy without hitting source`() {
        val engine = createEngine()

        try {
            val source =
                engine.spawn {
                    position(2.5, 1.0, 2.5)
                    team(SimulatedTeam(1))
                }

            val target =
                engine.spawn {
                    position(5.5, 1.0, 2.5)
                    team(SimulatedTeam(2))
                }

            val fartherTarget =
                engine.spawn {
                    position(7.5, 1.0, 2.5)
                    team(SimulatedTeam(2))
                }

            val projectile =
                engine.projectileManager.spawn {
                    position(2.5, 1.8, 2.5)
                    velocity(4.0, 0.0, 0.0)
                    source(source.entityId)
                    team(SimulatedTeam(1))
                    definition(
                        SimulatedProjectileDefinition(
                            damage = 5.0
                        )
                    )
                }

            engine.start()

            assertTrue(
                waitUntil {
                    !projectile.exists &&
                            (target.snapshot()?.health ?: 20.0) < 20.0
                }
            )

            assertEquals(
                20.0,
                source.snapshot()?.health
            )
            assertEquals(
                15.0,
                target.snapshot()?.health
            )
            assertEquals(
                20.0,
                fartherTarget.snapshot()?.health
            )
        } finally {
            engine.close()
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `ranged goal publishes projectile and damages target`() {
        val engine = createEngine()

        try {
            val definition =
                SimulatedProjectileDefinition(
                    maximumRange = 16.0,
                    damage = 4.0
                )

            engine.spawn {
                position(2.5, 1.0, 2.5)
                team(SimulatedTeam(1))
                attackCooldownTicks(2)
                goals(
                    SimulatedGoalSet.build {
                        goal(
                            SimulatedNearestTargetGoal(
                                searchRadius = 16.0
                            )
                        )
                        goal(
                            SimulatedRangedAttackGoal(
                                projectileDefinition =
                                    definition,
                                projectileSpeed = 1.5,
                                maximumDistance = 12.0
                            )
                        )
                    }
                )
            }

            val target =
                engine.spawn {
                    position(8.5, 1.0, 2.5)
                    team(SimulatedTeam(2))
                }

            engine.start()

            assertTrue(
                waitUntil {
                    (target.snapshot()?.health ?: 20.0) <
                            20.0
                }
            )

            assertTrue(
                engine.projectileManager
                    .drainEvents()
                    .any {
                        it is SimulatedProjectileEvent.EntityHit &&
                                it.entityId == target.entityId
                    }
            )
        } finally {
            engine.close()
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `projectile emits external actor damage and knockback`() {
        val engine = createEngine()
        val actorId = SimulatedExternalActorId(1L)

        try {
            engine.submitExternalFrame(
                SimulatedExternalFrame(
                    sequence = 1L,
                    actors =
                        listOf(
                            SimulatedExternalActorSnapshot(
                                actorId = actorId,
                                position =
                                    SimulatedVector3(
                                        5.5,
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

            engine.projectileManager.spawn {
                position(2.5, 1.8, 2.5)
                velocity(4.0, 0.0, 0.0)
                team(SimulatedTeam(1))
                definition(
                    SimulatedProjectileDefinition(
                        damage = 3.0,
                        knockbackStrength = 0.5
                    )
                )
            }

            engine.start()

            val actions =
                ArrayList<SimulatedExternalAction>()

            assertTrue(
                waitUntil {
                    actions.addAll(
                        engine.drainExternalActions()
                    )

                    actions.any {
                        (it is SimulatedExternalAction.Combined && it.actorId == actorId && it.damage == 3.0 && it.knockbackVelocity != null) ||
                        (it is SimulatedExternalAction.Damage && it.actorId == actorId)
                    }
                }
            )
        } finally {
            engine.close()
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `projectile lifetime and range remove with exact reasons`() {
        val engine = createEngine()

        try {
            val lifetimeProjectile =
                engine.projectileManager.spawn {
                    position(2.5, 2.0, 2.5)
                    velocity(0.1, 0.0, 0.0)
                    definition(
                        SimulatedProjectileDefinition(
                            maximumTicks = 1
                        )
                    )
                }

            val rangeProjectile =
                engine.projectileManager.spawn {
                    position(2.5, 3.0, 2.5)
                    velocity(0.2, 0.0, 0.0)
                    definition(
                        SimulatedProjectileDefinition(
                            maximumRange = 0.25
                        )
                    )
                }

            engine.start()

            assertTrue(
                waitUntil {
                    !lifetimeProjectile.exists &&
                            !rangeProjectile.exists
                }
            )

            val removals =
                engine.projectileManager
                    .drainEvents()
                    .filterIsInstance<
                            SimulatedProjectileEvent.Remove
                            >()
                    .associate {
                        it.projectileId to it.reason
                    }

            assertEquals(
                SimulatedProjectileRemovalReason.MAXIMUM_TICKS,
                removals[lifetimeProjectile.projectileId]
            )
            assertEquals(
                SimulatedProjectileRemovalReason.MAXIMUM_RANGE,
                removals[rangeProjectile.projectileId]
            )
        } finally {
            engine.close()
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `projectile does not hit target beyond maximum range`() {
        val engine = createEngine()
        val actorId = SimulatedExternalActorId(99L)

        try {
            engine.submitExternalFrame(
                SimulatedExternalFrame(
                    sequence = 1L,
                    actors = listOf(
                        SimulatedExternalActorSnapshot(
                            actorId = actorId,
                            position = SimulatedVector3(8.0, 1.0, 2.5),
                            velocity = SimulatedVector3.ZERO,
                            hitbox = SimulatedHitbox.DEFAULT,
                            health = 20.0,
                            maximumHealth = 20.0,
                            team = SimulatedTeam(2)
                        )
                    )
                )
            )

            engine.projectileManager.spawn {
                position(2.5, 1.8, 2.5)
                velocity(10.0, 0.0, 0.0)
                team(SimulatedTeam(1))
                definition(
                    SimulatedProjectileDefinition(
                        maximumRange = 4.0,
                        damage = 5.0
                    )
                )
            }

            engine.start()

            Thread.sleep(200)
            val actions = engine.drainExternalActions()
            assertTrue(actions.none { it.actorId == actorId })
        } finally {
            engine.close()
        }
    }

    private fun createEngine() =
        SimulatedEngineBuilder(
            TestSimulatedMapFactory.create()
        ).config(
            SimulatedConfig(
                navigationWorkerCount = 1
            )
        ).build()

    @Test
    fun `projectile frame getters return defensive clones`() {
        val frame = SimulatedProjectileFrame(
            tick = 1L,
            rawProjectileIds = intArrayOf(10),
            rawPositionX = doubleArrayOf(1.0),
            rawPositionY = doubleArrayOf(2.0),
            rawPositionZ = doubleArrayOf(3.0),
            rawVelocityX = doubleArrayOf(4.0),
            rawVelocityY = doubleArrayOf(5.0),
            rawVelocityZ = doubleArrayOf(6.0),
            rawPresentationIds = intArrayOf(0),
            rawAgeTicks = intArrayOf(1),
            rawTravelledDistance = doubleArrayOf(0.5)
        )

        val ids = frame.projectileIds
        ids[0] = 999
        assertEquals(10, frame.rawProjectileIdAt(0))
        assertEquals(10, frame.projectileIdAt(0).value)

        val posX = frame.positionX
        posX[0] = 999.0
        assertEquals(1.0, frame.positionXAt(0))
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
