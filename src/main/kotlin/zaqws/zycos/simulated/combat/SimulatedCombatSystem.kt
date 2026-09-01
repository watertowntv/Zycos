package zaqws.zycos.simulated.combat

import zaqws.zycos.simulated.entity.SimulatedEntityFlag
import zaqws.zycos.simulated.entity.SimulatedEntityId
import zaqws.zycos.simulated.entity.SimulatedEntityStore
import zaqws.zycos.simulated.goal.SimulatedGoalSystem
import zaqws.zycos.simulated.math.SimulatedMath
import zaqws.zycos.simulated.math.SimulatedVector3
import zaqws.zycos.simulated.snapshot.SimulatedEvent
import zaqws.zycos.simulated.snapshot.SimulatedEventQueue
import zaqws.zycos.simulated.system.SimulatedSystem
import zaqws.zycos.simulated.system.SimulatedSystemContext
import kotlin.math.sqrt

internal class SimulatedCombatSystem(
    private val entityStore: SimulatedEntityStore,
    private val goalSystem: SimulatedGoalSystem,
    private val eventQueue: SimulatedEventQueue,
    private val relationResolver: SimulatedRelationResolver =
        SimulatedRelationResolver.DEFAULT
) : SimulatedSystem {
    override fun update(
        context: SimulatedSystemContext
    ) {
        updateAttackCooldowns()

        var slot = 0

        while (slot < entityStore.size) {
            if (canAttack(slot)) {
                val attackerEntityId =
                    entityStore.entityIdAt(slot)

                val attackIntent =
                    goalSystem.attackIntent(
                        attackerEntityId
                    )

                if (attackIntent != null) {
                    attemptAttack(
                        attackerSlot = slot,
                        attackerEntityId =
                            attackerEntityId,
                        targetEntityId =
                            attackIntent.targetEntityId,
                        tick =
                            context.tick
                    )
                }
            }

            slot++
        }
    }

    fun damage(
        damage: SimulatedDamage,
        tick: Long
    ): Double {
        require(tick >= 0L)

        val targetSlot =
            entityStore.slotOf(
                damage.targetEntityId
            )

        if (
            targetSlot < 0 ||
            entityStore.hasFlag(
                targetSlot,
                SimulatedEntityFlag.REMOVED
            ) ||
            entityStore.hasFlag(
                targetSlot,
                SimulatedEntityFlag.DEAD
            )
        ) {
            return 0.0
        }

        val appliedDamage =
            entityStore.damage(
                targetSlot,
                damage.amount
            )

        if (appliedDamage <= 0.0) {
            return 0.0
        }

        eventQueue.offer(
            SimulatedEvent.Hurt(
                tick = tick,
                entityId =
                    damage.targetEntityId,
                damage =
                    appliedDamage
            )
        )

        if (
            entityStore.hasFlag(
                targetSlot,
                SimulatedEntityFlag.DEAD
            )
        ) {
            eventQueue.offer(
                SimulatedEvent.Death(
                    tick = tick,
                    entityId =
                        damage.targetEntityId
                )
            )

            goalSystem.clearEntity(
                damage.targetEntityId
            )
        }

        return appliedDamage
    }

    private fun attemptAttack(
        attackerSlot: Int,
        attackerEntityId: SimulatedEntityId,
        targetEntityId: SimulatedEntityId,
        tick: Long
    ) {
        if (
            attackerEntityId ==
            targetEntityId
        ) {
            return
        }

        if (
            entityStore
                .attackCooldownRemainingTicks(
                    attackerSlot
                ) > 0
        ) {
            return
        }

        val targetSlot =
            entityStore.slotOf(
                targetEntityId
            )

        if (!canReceiveAttack(targetSlot)) {
            return
        }

        if (
            relationResolver.resolve(
                entityStore.team(
                    attackerSlot
                ),
                entityStore.team(
                    targetSlot
                )
            ) != SimulatedRelation.ENEMY
        ) {
            return
        }

        val attackerPosition =
            entityStore.position(
                attackerSlot
            )

        val targetPosition =
            entityStore.position(
                targetSlot
            )

        val attackRange =
            entityStore.attackRange(
                attackerSlot
            )

        if (
            attackerPosition.distanceSquared(
                targetPosition
            ) >
            attackRange * attackRange
        ) {
            return
        }

        val attack =
            SimulatedAttack(
                attackerEntityId =
                    attackerEntityId,

                targetEntityId =
                    targetEntityId,

                damage =
                    entityStore.attackDamage(
                        attackerSlot
                    ),

                knockbackStrength =
                    entityStore.knockbackStrength(
                        attackerSlot
                    )
            )

        executeAttack(
            attack = attack,
            targetSlot = targetSlot,
            tick = tick
        )

        entityStore
            .setAttackCooldownRemainingTicks(
                attackerSlot,
                entityStore.attackCooldownTicks(
                    attackerSlot
                )
            )
    }

    private fun executeAttack(
        attack: SimulatedAttack,
        targetSlot: Int,
        tick: Long
    ) {
        eventQueue.offer(
            SimulatedEvent.Attack(
                tick = tick,
                entityId =
                    attack.attackerEntityId,
                targetEntityId =
                    attack.targetEntityId
            )
        )

        val appliedDamage =
            damage(
                SimulatedDamage(
                    targetEntityId =
                        attack.targetEntityId,

                    amount =
                        attack.damage,

                    sourceEntityId =
                        attack.attackerEntityId
                ),
                tick
            )

        if (
            appliedDamage <= 0.0 ||
            attack.knockbackStrength <= 0.0 ||
            entityStore.hasFlag(
                targetSlot,
                SimulatedEntityFlag.DEAD
            )
        ) {
            return
        }

        applyKnockback(
            attackerEntityId =
                attack.attackerEntityId,

            targetEntityId =
                attack.targetEntityId,

            targetSlot =
                targetSlot,

            strength =
                attack.knockbackStrength,

            tick =
                tick
        )
    }

    private fun applyKnockback(
        attackerEntityId: SimulatedEntityId,
        targetEntityId: SimulatedEntityId,
        targetSlot: Int,
        strength: Double,
        tick: Long
    ) {
        val attackerSlot =
            entityStore.slotOf(
                attackerEntityId
            )

        if (attackerSlot < 0) {
            return
        }

        val attackerPosition =
            entityStore.position(
                attackerSlot
            )

        val targetPosition =
            entityStore.position(
                targetSlot
            )

        val differenceX =
            targetPosition.x -
                    attackerPosition.x

        val differenceZ =
            targetPosition.z -
                    attackerPosition.z

        val horizontalLengthSquared =
            differenceX * differenceX +
                    differenceZ * differenceZ

        if (
            horizontalLengthSquared <=
            SimulatedMath.EPSILON_SQUARED
        ) {
            return
        }

        val inverseHorizontalLength =
            1.0 /
                    sqrt(
                        horizontalLengthSquared
                    )

        val knockbackVelocity =
            SimulatedVector3(
                x =
                    differenceX *
                            inverseHorizontalLength *
                            strength,

                y =
                    strength *
                            VERTICAL_KNOCKBACK_MULTIPLIER,

                z =
                    differenceZ *
                            inverseHorizontalLength *
                            strength
            )

        entityStore.addVelocity(
            targetSlot,
            knockbackVelocity
        )

        eventQueue.offer(
            SimulatedEvent.Knockback(
                tick = tick,
                entityId =
                    targetEntityId,
                velocity =
                    knockbackVelocity
            )
        )
    }

    private fun updateAttackCooldowns() {
        var slot = 0

        while (slot < entityStore.size) {
            if (
                entityStore
                    .attackCooldownRemainingTicks(
                        slot
                    ) > 0
            ) {
                entityStore.decreaseAttackCooldown(
                    slot
                )
            }

            slot++
        }
    }

    private fun canAttack(
        slot: Int
    ): Boolean =
        !entityStore.hasFlag(
            slot,
            SimulatedEntityFlag.REMOVED
        ) &&
                !entityStore.hasFlag(
                    slot,
                    SimulatedEntityFlag.DEAD
                )

    private fun canReceiveAttack(
        slot: Int
    ): Boolean =
        slot >= 0 &&
                !entityStore.hasFlag(
                    slot,
                    SimulatedEntityFlag.REMOVED
                ) &&
                !entityStore.hasFlag(
                    slot,
                    SimulatedEntityFlag.DEAD
                )

    companion object {
        private const val
                VERTICAL_KNOCKBACK_MULTIPLIER =
            0.5
    }
}