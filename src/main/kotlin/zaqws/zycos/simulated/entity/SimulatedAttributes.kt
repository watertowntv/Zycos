package zaqws.zycos.simulated.entity

data class SimulatedAttributes(
    val maximumHealth: Double = DEFAULT_MAXIMUM_HEALTH,
    val movementSpeed: Double = DEFAULT_MOVEMENT_SPEED,
    val attackDamage: Double = DEFAULT_ATTACK_DAMAGE,
    val attackRange: Double = DEFAULT_ATTACK_RANGE,
    val attackCooldownTicks: Int = DEFAULT_ATTACK_COOLDOWN_TICKS,
    val knockbackStrength: Double = DEFAULT_KNOCKBACK_STRENGTH
) {
    companion object {
        const val DEFAULT_MAXIMUM_HEALTH = 20.0
        const val DEFAULT_MOVEMENT_SPEED = 0.1
        const val DEFAULT_ATTACK_DAMAGE = 2.0
        const val DEFAULT_ATTACK_RANGE = 2.0
        const val DEFAULT_ATTACK_COOLDOWN_TICKS = 20
        const val DEFAULT_KNOCKBACK_STRENGTH = 0.4

        val DEFAULT = SimulatedAttributes()
    }

    init {
        require(maximumHealth.isFinite())
        require(movementSpeed.isFinite())
        require(attackDamage.isFinite())
        require(attackRange.isFinite())
        require(knockbackStrength.isFinite())

        require(maximumHealth > 0.0)
        require(movementSpeed >= 0.0)
        require(attackDamage >= 0.0)
        require(attackRange >= 0.0)
        require(attackCooldownTicks >= 0)
        require(knockbackStrength >= 0.0)
    }
}