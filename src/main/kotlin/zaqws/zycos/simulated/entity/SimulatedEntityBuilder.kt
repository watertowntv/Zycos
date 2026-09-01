@file:Suppress("unused")

package zaqws.zycos.simulated.entity

import zaqws.zycos.simulated.goal.SimulatedGoalSet
import zaqws.zycos.simulated.math.SimulatedVector3

class SimulatedEntityBuilder internal constructor() {
    private var position = SimulatedVector3.ZERO
    private var velocity = SimulatedVector3.ZERO

    private var yaw = 0.0f
    private var pitch = 0.0f

    private var hitbox = SimulatedHitbox.DEFAULT
    private var attributes = SimulatedAttributes.DEFAULT

    private var team = SimulatedTeam.NONE
    private var presentationId = SimulatedPresentationId.NONE
    private var flags = SimulatedEntityFlags.NONE

    private var goalSet: SimulatedGoalSet? = null

    fun position(position: SimulatedVector3) {
        require(position.isFinite)

        this.position = position
    }

    fun position(
        x: Double,
        y: Double,
        z: Double
    ) {
        position(
            SimulatedVector3(
                x,
                y,
                z
            )
        )
    }

    fun velocity(velocity: SimulatedVector3) {
        require(velocity.isFinite)

        this.velocity = velocity
    }

    fun velocity(
        x: Double,
        y: Double,
        z: Double
    ) {
        velocity(
            SimulatedVector3(
                x,
                y,
                z
            )
        )
    }

    fun rotation(
        yaw: Float,
        pitch: Float = 0.0f
    ) {
        require(yaw.isFinite())
        require(pitch.isFinite())

        this.yaw = yaw
        this.pitch = pitch
    }

    fun hitbox(hitbox: SimulatedHitbox) {
        this.hitbox = hitbox
    }

    fun hitbox(
        width: Double,
        height: Double
    ) {
        hitbox(
            SimulatedHitbox(
                width,
                height
            )
        )
    }

    fun attributes(attributes: SimulatedAttributes) {
        this.attributes = attributes
    }

    fun attributes(
        maximumHealth: Double = this.attributes.maximumHealth,
        movementSpeed: Double = this.attributes.movementSpeed,
        attackDamage: Double = this.attributes.attackDamage,
        attackRange: Double = this.attributes.attackRange,
        attackCooldownTicks: Int = this.attributes.attackCooldownTicks,
        knockbackStrength: Double = this.attributes.knockbackStrength
    ) {
        attributes(
            SimulatedAttributes(
                maximumHealth = maximumHealth,
                movementSpeed = movementSpeed,
                attackDamage = attackDamage,
                attackRange = attackRange,
                attackCooldownTicks = attackCooldownTicks,
                knockbackStrength = knockbackStrength
            )
        )
    }

    fun health(maximumHealth: Double) {
        attributes(
            maximumHealth = maximumHealth
        )
    }

    fun movementSpeed(movementSpeed: Double) {
        attributes(
            movementSpeed = movementSpeed
        )
    }

    fun attackDamage(attackDamage: Double) {
        attributes(
            attackDamage = attackDamage
        )
    }

    fun attackRange(attackRange: Double) {
        attributes(
            attackRange = attackRange
        )
    }

    fun attackCooldownTicks(attackCooldownTicks: Int) {
        attributes(
            attackCooldownTicks = attackCooldownTicks
        )
    }

    fun knockbackStrength(knockbackStrength: Double) {
        attributes(
            knockbackStrength = knockbackStrength
        )
    }

    fun team(team: SimulatedTeam) {
        this.team = team
    }

    fun presentation(presentationId: SimulatedPresentationId) {
        this.presentationId = presentationId
    }

    fun flags(flags: SimulatedEntityFlags) {
        this.flags = flags
    }

    fun flag(
        flag: SimulatedEntityFlag,
        enabled: Boolean = true
    ) {
        flags = flags.with(
            flag,
            enabled
        )
    }

    fun goals(goalSet: SimulatedGoalSet) {
        this.goalSet = goalSet
    }

    internal fun build() = SimulatedEntitySpawnData(
        position = position,
        velocity = velocity,
        yaw = yaw,
        pitch = pitch,
        hitbox = hitbox,
        attributes = attributes,
        team = team,
        presentationId = presentationId,
        flags = flags,
        goalSet = goalSet
    )
}

internal data class SimulatedEntitySpawnData(
    val position: SimulatedVector3,
    val velocity: SimulatedVector3,
    val yaw: Float,
    val pitch: Float,
    val hitbox: SimulatedHitbox,
    val attributes: SimulatedAttributes,
    val team: SimulatedTeam,
    val presentationId: SimulatedPresentationId,
    val flags: SimulatedEntityFlags,
    val goalSet: SimulatedGoalSet?
)