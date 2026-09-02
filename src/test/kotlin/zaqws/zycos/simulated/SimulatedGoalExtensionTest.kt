package zaqws.zycos.simulated

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import zaqws.zycos.simulated.entity.SimulatedTeam
import zaqws.zycos.simulated.entity.SimulatedHitbox
import zaqws.zycos.simulated.external.SimulatedExternalAction
import zaqws.zycos.simulated.external.SimulatedExternalActorId
import zaqws.zycos.simulated.external.SimulatedExternalActorSnapshot
import zaqws.zycos.simulated.external.SimulatedExternalFrame
import zaqws.zycos.simulated.goal.SimulatedGoal
import zaqws.zycos.simulated.goal.SimulatedGoalContext
import zaqws.zycos.simulated.goal.SimulatedGoalRuntime
import zaqws.zycos.simulated.goal.SimulatedGoalSet
import zaqws.zycos.simulated.goal.SimulatedIntent
import zaqws.zycos.simulated.math.SimulatedVector3
import zaqws.zycos.simulated.projectile.SimulatedProjectileDefinition
import zaqws.zycos.simulated.projectile.SimulatedProjectileEvent
import zaqws.zycos.simulated.signal.SimulatedSignal
import java.util.concurrent.TimeUnit

class SimulatedGoalExtensionTest {
    private data class PulseSignal(
        val nearbyTargets: Int
    ) : SimulatedSignal

    private class OnceRuntime(
        var executed: Boolean = false
    ) : SimulatedGoalRuntime

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `goal actions control motion area effects projectiles and signals`() {
        val engine =
            SimulatedEngineBuilder(
                TestSimulatedMapFactory.create()
            ).config(
                SimulatedConfig(
                    navigationWorkerCount = 1,
                    gravityPerTick = 0.0,
                    airDrag = 1.0,
                    groundFriction = 1.0
                )
            ).build()

        try {
            val enemy = engine.spawn {
                position(4.5, 1.0, 2.5)
                team(SimulatedTeam(2))
            }

            val externalActorId = SimulatedExternalActorId(1L)

            engine.submitExternalFrame(
                SimulatedExternalFrame(
                    sequence = 1L,
                    actors =
                        listOf(
                            SimulatedExternalActorSnapshot(
                                actorId = externalActorId,
                                position = SimulatedVector3(2.5, 1.0, 4.5),
                                velocity = SimulatedVector3.ZERO,
                                hitbox = SimulatedHitbox.DEFAULT,
                                health = 20.0,
                                maximumHealth = 20.0,
                                team = SimulatedTeam(2)
                            )
                        )
                )
            )

            val source = engine.spawn {
                position(2.5, 1.0, 2.5)
                team(SimulatedTeam(1))
                goals(
                    SimulatedGoalSet.build {
                        goal(
                            object : SimulatedGoal {
                                override fun createRuntime(): SimulatedGoalRuntime =
                                    OnceRuntime()

                                override fun evaluate(
                                    context: SimulatedGoalContext,
                                    runtime: SimulatedGoalRuntime,
                                    intents: MutableCollection<SimulatedIntent>
                                ) {
                                    runtime as OnceRuntime
                                    if (runtime.executed) return

                                    val targets =
                                        context.targetsWithin(2.0) {
                                            it.isTargetable &&
                                                    it.team != context.team
                                        }

                                    context.setVelocity(
                                        SimulatedVector3(0.1, 0.0, 0.0),
                                        priority = 1
                                    )
                                    context.setVelocity(
                                        SimulatedVector3(0.0, 0.8, 0.0),
                                        priority = 10
                                    )
                                    context.addVelocity(
                                        SimulatedVector3(0.2, 0.0, 0.0)
                                    )
                                    context.teleport(
                                        SimulatedVector3(3.0, 1.0, 2.5),
                                        priority = 10
                                    )
                                    context.heal(2.0)

                                    for (target in targets) {
                                        context.damage(target.target, 3.0)
                                        context.knockback(
                                            target.target,
                                            SimulatedVector3(0.0, 0.4, 0.0)
                                        )
                                    }

                                    context.spawnProjectile(
                                        position = context.position,
                                        velocity = SimulatedVector3(0.0, 1.0, 0.0),
                                        definition =
                                            SimulatedProjectileDefinition(
                                                maximumTicks = 20,
                                                maximumRange = 20.0,
                                                damage = 0.0
                                            )
                                    )
                                    context.emit(PulseSignal(targets.size))
                                    runtime.executed = true
                                }
                            }
                        )
                    }
                )
            }

            source.damage(5.0)

            engine.start()

            val completed =
                waitUntil {
                    engine.tick >= 2L &&
                            source.snapshot()?.position?.let {
                                it.x > 3.0 && it.y > 1.0
                            } == true &&
                            source.snapshot()?.health == 17.0 &&
                            enemy.snapshot()?.health == 17.0
                }

            assertTrue(
                completed,
                "tick=${engine.tick}, source=${source.snapshot()}, enemy=${enemy.snapshot()}, failure=${engine.failure}"
            )

            assertTrue(
                engine.projectileManager
                    .drainEvents()
                    .any {
                        it is SimulatedProjectileEvent.Spawn
                    }
            )

            val actions = engine.drainExternalActions()
            assertTrue(
                actions.any {
                    it is SimulatedExternalAction.Damage &&
                            it.actorId == externalActorId &&
                            it.amount == 3.0
                }
            )
            assertTrue(
                actions.any {
                    it is SimulatedExternalAction.Knockback &&
                            it.actorId == externalActorId &&
                            it.velocity.y == 0.4
                }
            )

            val signal = engine.drainSignals().single()
            assertEquals(source.entityId, signal.entityId)
            assertEquals(
                SimulatedVector3(3.0, 1.0, 2.5),
                signal.position
            )
            assertEquals(2, (signal.signal as PulseSignal).nearbyTargets)
        } finally {
            engine.close()
        }
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
            if (condition()) return true
            Thread.sleep(10L)
        }

        return condition()
    }
}
