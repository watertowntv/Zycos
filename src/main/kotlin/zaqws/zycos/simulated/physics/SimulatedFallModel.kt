@file:Suppress("unused")

package zaqws.zycos.simulated.physics

interface SimulatedFallModel {
    companion object {
        private const val MAXIMUM_SEARCH_ITERATIONS = 32
    }

    fun calculateDamage(
        fallDistance: Double
    ): Double

    fun calculateRemainingHealth(
        currentHealth: Double,
        fallDistance: Double
    ): Double {
        require(currentHealth.isFinite())
        require(currentHealth >= 0.0)

        return (
                currentHealth -
                        calculateDamage(
                            fallDistance
                        )
                ).coerceAtLeast(0.0)
    }

    fun canFall(
        currentHealth: Double,
        maximumHealth: Double,
        fallDistance: Double
    ): Boolean

    fun maximumAllowedFallDistance(
        currentHealth: Double,
        maximumHealth: Double,
        maximumSearchDistance: Double
    ): Double {
        require(currentHealth.isFinite())
        require(maximumHealth.isFinite())
        require(maximumSearchDistance.isFinite())

        require(currentHealth >= 0.0)
        require(maximumHealth > 0.0)
        require(currentHealth <= maximumHealth)
        require(maximumSearchDistance >= 0.0)

        if (
            maximumSearchDistance == 0.0
        ) {
            return 0.0
        }

        var minimumDistance = 0.0
        var maximumDistance =
            maximumSearchDistance

        repeat(MAXIMUM_SEARCH_ITERATIONS) {
            val middleDistance =
                (
                        minimumDistance +
                                maximumDistance
                        ) * 0.5

            if (
                canFall(
                    currentHealth,
                    maximumHealth,
                    middleDistance
                )
            ) {
                minimumDistance =
                    middleDistance
            } else {
                maximumDistance =
                    middleDistance
            }
        }

        return minimumDistance
    }
}