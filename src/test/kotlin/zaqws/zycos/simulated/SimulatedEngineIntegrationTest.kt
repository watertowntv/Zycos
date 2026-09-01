package zaqws.zycos.simulated

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import zaqws.zycos.simulated.entity.SimulatedEntity
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
import java.util.concurrent.TimeUnit

class SimulatedEngineIntegrationTest {
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
                        it is SimulatedExternalAction.Damage &&
                                it.actorId == actorId
                    }
                }
            )
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
