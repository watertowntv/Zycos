package zaqws.zycos.simulated.combat

import zaqws.zycos.simulated.SimulatedTarget
import zaqws.zycos.simulated.entity.SimulatedEntityFlag
import zaqws.zycos.simulated.entity.SimulatedEntityId
import zaqws.zycos.simulated.entity.SimulatedEntityStore
import zaqws.zycos.simulated.external.SimulatedExternalAction
import zaqws.zycos.simulated.external.SimulatedExternalActorId
import zaqws.zycos.simulated.external.SimulatedExternalFrame
import zaqws.zycos.simulated.goal.SimulatedGoalSystem
import zaqws.zycos.simulated.goal.SimulatedIntent
import zaqws.zycos.simulated.math.SimulatedMath
import zaqws.zycos.simulated.math.SimulatedVector3
import zaqws.zycos.simulated.snapshot.SimulatedEvent
import zaqws.zycos.simulated.projectile.SimulatedProjectileSource
import zaqws.zycos.simulated.projectile.SimulatedProjectileSpawnData
import zaqws.zycos.simulated.system.SimulatedSystem
import zaqws.zycos.simulated.system.SimulatedSystemContext
import kotlin.math.sqrt

internal class SimulatedCombatSystem(
    private val entityStore: SimulatedEntityStore,
    private val goalSystem: SimulatedGoalSystem,
    private val eventConsumer:
        (SimulatedEvent) -> Unit,
    private val externalFrameProvider:
        () -> SimulatedExternalFrame,
    private val externalActionConsumer:
        (SimulatedExternalAction) -> Unit,
    private val projectileConsumer:
        (SimulatedProjectileSpawnData, Long) -> Unit,
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

                val shootIntent =
                    goalSystem.shootIntent(
                        attackerEntityId
                    )

                if (shootIntent != null) {
                    attemptShoot(
                        attackerSlot = slot,
                        attackerEntityId =
                            attackerEntityId,
                        intent = shootIntent,
                        tick = context.tick
                    )
                } else {
                    val attackIntent =
                        goalSystem.attackIntent(
                            attackerEntityId
                        )

                    if (attackIntent == null) {
                        slot++
                        continue
                    }

                    attemptAttack(
                        attackerSlot = slot,
                        attackerEntityId =
                            attackerEntityId,
                        target =
                            attackIntent.target,
                        tick =
                            context.tick
                    )
                }
            }

            slot++
        }
    }

    private fun attemptShoot(
        attackerSlot: Int,
        attackerEntityId: SimulatedEntityId,
        intent: SimulatedIntent.Shoot,
        tick: Long
    ) {
        if (
            entityStore
                .attackCooldownRemainingTicks(
                    attackerSlot
                ) > 0
        ) {
            return
        }

        val spawned =
            when (val target = intent.target) {
                is SimulatedTarget.Entity ->
                    attemptEntityShot(
                        attackerSlot,
                        attackerEntityId,
                        target.entityId,
                        intent,
                        tick
                    )

                is SimulatedTarget.ExternalActor ->
                    attemptExternalActorShot(
                        attackerSlot,
                        attackerEntityId,
                        target.actorId,
                        intent,
                        tick
                    )
            }

        if (spawned) {
            entityStore
                .setAttackCooldownRemainingTicks(
                    attackerSlot,
                    entityStore.attackCooldownTicks(
                        attackerSlot
                    )
                )
        }
    }

    private fun attemptEntityShot(
        attackerSlot: Int,
        attackerEntityId: SimulatedEntityId,
        targetEntityId: SimulatedEntityId,
        intent: SimulatedIntent.Shoot,
        tick: Long
    ): Boolean {
        if (attackerEntityId == targetEntityId) {
            return false
        }

        val targetSlot =
            entityStore.slotOf(
                targetEntityId
            )

        if (!canReceiveAttack(targetSlot)) {
            return false
        }

        if (
            relationResolver.resolve(
                entityStore.team(attackerSlot),
                entityStore.team(targetSlot)
            ) != SimulatedRelation.ENEMY
        ) {
            return false
        }

        return spawnProjectile(
            attackerSlot = attackerSlot,
            attackerEntityId = attackerEntityId,
            targetPosition =
                entityStore.position(targetSlot),
            targetHeight =
                entityStore.hitbox(targetSlot).height,
            targetEntityId = targetEntityId,
            intent = intent,
            tick = tick
        )
    }

    private fun attemptExternalActorShot(
        attackerSlot: Int,
        attackerEntityId: SimulatedEntityId,
        targetActorId: SimulatedExternalActorId,
        intent: SimulatedIntent.Shoot,
        tick: Long
    ): Boolean {
        val target =
            externalFrameProvider()[
                targetActorId
            ] ?: return false

        if (
            !target.isTargetable ||
            !target.hasCollision
        ) {
            return false
        }

        if (
            relationResolver.resolve(
                entityStore.team(attackerSlot),
                target.team
            ) != SimulatedRelation.ENEMY
        ) {
            return false
        }

        return spawnProjectile(
            attackerSlot = attackerSlot,
            attackerEntityId = attackerEntityId,
            targetPosition = target.position,
            targetHeight = target.hitbox.height,
            targetEntityId = null,
            intent = intent,
            tick = tick
        )
    }

    private fun spawnProjectile(
        attackerSlot: Int,
        attackerEntityId: SimulatedEntityId,
        targetPosition: SimulatedVector3,
        targetHeight: Double,
        targetEntityId: SimulatedEntityId?,
        intent: SimulatedIntent.Shoot,
        tick: Long
    ): Boolean {
        val attackerPosition =
            entityStore.position(
                attackerSlot
            )

        if (
            attackerPosition.distanceSquared(
                targetPosition
            ) >
            intent.maximumDistance *
                    intent.maximumDistance
        ) {
            return false
        }

        val origin =
            attackerPosition.withY(
                attackerPosition.y +
                        entityStore.hitbox(
                            attackerSlot
                        ).height *
                        PROJECTILE_ORIGIN_HEIGHT_MULTIPLIER
            )

        val target =
            targetPosition.withY(
                targetPosition.y +
                        targetHeight *
                        PROJECTILE_TARGET_HEIGHT_MULTIPLIER
            )

        val direction =
            (target - origin)
                .normalized()

        if (
            direction.lengthSquared <=
            SimulatedMath.EPSILON_SQUARED
        ) {
            return false
        }

        projectileConsumer(
            SimulatedProjectileSpawnData(
                position = origin,
                velocity =
                    direction *
                            intent.projectileSpeed,
                source =
                    SimulatedProjectileSource.Entity(
                        attackerEntityId
                    ),
                team =
                    entityStore.team(
                        attackerSlot
                    ),
                definition =
                    intent.projectileDefinition
            ),
            tick
        )

        eventConsumer(
            SimulatedEvent.Attack(
                tick = tick,
                entityId =
                    attackerEntityId,
                targetEntityId =
                    targetEntityId
            )
        )

        return true
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

        eventConsumer(
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
            eventConsumer(
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
        target: SimulatedTarget,
        tick: Long
    ) {
        if (
            entityStore
                .attackCooldownRemainingTicks(
                    attackerSlot
                ) > 0
        ) {
            return
        }

        val attacked =
            when (target) {
                is SimulatedTarget.Entity ->
                    attemptEntityAttack(
                        attackerSlot =
                            attackerSlot,
                        attackerEntityId =
                            attackerEntityId,
                        targetEntityId =
                            target.entityId,
                        tick = tick
                    )

                is SimulatedTarget.ExternalActor ->
                    attemptExternalAttack(
                        attackerSlot =
                            attackerSlot,
                        attackerEntityId =
                            attackerEntityId,
                        targetActorId =
                            target.actorId,
                        tick = tick
                    )
            }

        if (!attacked) {
            return
        }

        entityStore
            .setAttackCooldownRemainingTicks(
                attackerSlot,
                entityStore.attackCooldownTicks(
                    attackerSlot
                )
            )
    }

    private fun attemptEntityAttack(
        attackerSlot: Int,
        attackerEntityId: SimulatedEntityId,
        targetEntityId: SimulatedEntityId,
        tick: Long
    ): Boolean {
        if (
            attackerEntityId ==
            targetEntityId
        ) {
            return false
        }

        val targetSlot =
            entityStore.slotOf(
                targetEntityId
            )

        if (!canReceiveAttack(targetSlot)) {
            return false
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
            return false
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
            return false
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

        return true
    }

    private fun attemptExternalAttack(
        attackerSlot: Int,
        attackerEntityId: SimulatedEntityId,
        targetActorId: SimulatedExternalActorId,
        tick: Long
    ): Boolean {
        val target =
            externalFrameProvider()[
                targetActorId
            ] ?: return false

        if (!target.isDamageable) {
            return false
        }

        if (
            relationResolver.resolve(
                entityStore.team(
                    attackerSlot
                ),
                target.team
            ) != SimulatedRelation.ENEMY
        ) {
            return false
        }

        val attackerPosition =
            entityStore.position(
                attackerSlot
            )

        val attackRange =
            entityStore.attackRange(
                attackerSlot
            )

        if (
            attackerPosition.distanceSquared(
                target.position
            ) >
            attackRange * attackRange
        ) {
            return false
        }

        eventConsumer(
            SimulatedEvent.Attack(
                tick = tick,
                entityId =
                    attackerEntityId,
                targetEntityId = null
            )
        )

        externalActionConsumer(
            SimulatedExternalAction.Damage(
                actorId = targetActorId,
                amount =
                    entityStore.attackDamage(
                        attackerSlot
                    ),
                sourceEntityId =
                    attackerEntityId
            )
        )

        val knockbackStrength =
            entityStore.knockbackStrength(
                attackerSlot
            )

        if (knockbackStrength > 0.0) {
            val differenceX =
                target.position.x -
                        attackerPosition.x

            val differenceZ =
                target.position.z -
                        attackerPosition.z

            val horizontalLengthSquared =
                differenceX * differenceX +
                        differenceZ * differenceZ

            if (
                horizontalLengthSquared >
                SimulatedMath.EPSILON_SQUARED
            ) {
                val inverseHorizontalLength =
                    1.0 /
                            sqrt(
                                horizontalLengthSquared
                            )

                externalActionConsumer(
                    SimulatedExternalAction.Knockback(
                        actorId = targetActorId,
                        velocity =
                            SimulatedVector3(
                                x =
                                    differenceX *
                                            inverseHorizontalLength *
                                            knockbackStrength,
                                y =
                                    knockbackStrength *
                                            VERTICAL_KNOCKBACK_MULTIPLIER,
                                z =
                                    differenceZ *
                                            inverseHorizontalLength *
                                            knockbackStrength
                            ),
                        sourceEntityId =
                            attackerEntityId
                    )
                )
            }
        }

        return true
    }

    private fun executeAttack(
        attack: SimulatedAttack,
        targetSlot: Int,
        tick: Long
    ) {
        eventConsumer(
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

        eventConsumer(
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

        private const val
                PROJECTILE_ORIGIN_HEIGHT_MULTIPLIER =
            0.75

        private const val
                PROJECTILE_TARGET_HEIGHT_MULTIPLIER =
            0.5
    }
}
