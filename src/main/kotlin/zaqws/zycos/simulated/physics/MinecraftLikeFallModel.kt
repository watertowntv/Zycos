package zaqws.zycos.simulated.physics

import kotlin.math.ceil

class MinecraftLikeFallModel(
    val safeFallDistance: Double =
        DEFAULT_SAFE_FALL_DISTANCE,
    val damagePerBlock: Double =
        DEFAULT_DAMAGE_PER_BLOCK,
    val minimumRemainingHealthRatio: Double =
        DEFAULT_MINIMUM_REMAINING_HEALTH_RATIO
) : SimulatedFallModel {
    companion object {
        const val DEFAULT_SAFE_FALL_DISTANCE =
            3.0

        const val DEFAULT_DAMAGE_PER_BLOCK =
            1.0

        const val DEFAULT_MINIMUM_REMAINING_HEALTH_RATIO =
            0.5
    }

    init {
        require(safeFallDistance.isFinite())
        require(safeFallDistance >= 0.0)

        require(damagePerBlock.isFinite())
        require(damagePerBlock >= 0.0)

        require(minimumRemainingHealthRatio.isFinite())
        require(
            minimumRemainingHealthRatio in
                    0.0..1.0
        )
    }

    override fun calculateDamage(
        fallDistance: Double
    ): Double {
        require(fallDistance.isFinite())
        require(fallDistance >= 0.0)

        val damagingDistance =
            fallDistance -
                    safeFallDistance

        if (damagingDistance <= 0.0) {
            return 0.0
        }

        return ceil(
            damagingDistance
        ) * damagePerBlock
    }

    override fun canFall(
        currentHealth: Double,
        maximumHealth: Double,
        fallDistance: Double
    ): Boolean {
        require(currentHealth.isFinite())
        require(maximumHealth.isFinite())
        require(fallDistance.isFinite())

        require(currentHealth >= 0.0)
        require(maximumHealth > 0.0)
        require(currentHealth <= maximumHealth)
        require(fallDistance >= 0.0)

        val remainingHealth =
            calculateRemainingHealth(
                currentHealth,
                fallDistance
            )

        return remainingHealth >=
                maximumHealth *
                minimumRemainingHealthRatio
    }
}