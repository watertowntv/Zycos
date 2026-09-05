package zaqws.zycos.simulated.combat

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import zaqws.zycos.simulated.SimulatedEngineBuilder
import zaqws.zycos.simulated.TestSimulatedMapFactory
import zaqws.zycos.simulated.entity.SimulatedAttributes
import zaqws.zycos.simulated.entity.SimulatedTeam
import zaqws.zycos.simulated.goal.SimulatedGoal
import zaqws.zycos.simulated.goal.SimulatedGoalContext
import zaqws.zycos.simulated.goal.SimulatedGoalRuntime
import zaqws.zycos.simulated.goal.SimulatedGoalSet
import zaqws.zycos.simulated.goal.SimulatedIntent
import zaqws.zycos.simulated.goal.builtin.SimulatedMeleeGoal
import zaqws.zycos.simulated.goal.builtin.SimulatedNearestTargetGoal
import zaqws.zycos.simulated.math.SimulatedVector3
import zaqws.zycos.simulated.snapshot.SimulatedEvent
import java.util.concurrent.TimeUnit

class SimulatedKnockbackRetentionTest {
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `knockback applied in combat is not overwritten before physics`() {
        val map = TestSimulatedMapFactory.create(chunkCountX = 2)
        val engine = SimulatedEngineBuilder(map).build()

        try {
            val attacker = engine.spawn {
                position(2.0, 1.0, 2.5)
                team(SimulatedTeam(1))
                attackRange(2.0)
                attackCooldownTicks(2)
                attributes(
                    SimulatedAttributes(
                        attackDamage = 2.0,
                        attackRange = 2.0,
                        knockbackStrength = 2.0
                    )
                )
                goals(
                    SimulatedGoalSet.build {
                        goal(SimulatedNearestTargetGoal(searchRadius = 16.0))
                        goal(SimulatedMeleeGoal())
                    }
                )
            }

            val victim = engine.spawn {
                position(3.0, 1.0, 2.5)
                team(SimulatedTeam(2))
                movementSpeed(0.1)
                goals(
                    SimulatedGoalSet.build {
                        goal(object : SimulatedGoal {
                            override fun evaluate(
                                context: SimulatedGoalContext,
                                runtime: SimulatedGoalRuntime,
                                intents: MutableCollection<SimulatedIntent>
                            ) {
                                intents.add(SimulatedIntent.MoveTo(SimulatedVector3(1.0, 1.0, 2.5), 0.0))
                            }
                        })
                    }
                )
            }

            val events = ArrayList<SimulatedEvent>()
            engine.start()

            var victimKnockedBack = false
            val deadline = System.currentTimeMillis() + 5000
            while (System.currentTimeMillis() < deadline) {
                engine.drainEvents(events)
                val knockbacks = events.filterIsInstance<SimulatedEvent.Knockback>()
                if (knockbacks.any { it.entityId == victim.entityId }) {
                    val pos = victim.snapshot()?.position
                    if (pos != null && pos.x > 3.0) {
                        victimKnockedBack = true
                        break
                    }
                }
                Thread.sleep(20)
            }

            assertTrue(victimKnockedBack, "Victim should be knocked back in +X direction past 3.0")
        } finally {
            engine.close()
        }
    }
}
