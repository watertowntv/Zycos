@file:Suppress("unused")

package zaqws.zycos.simulated.spatial

import zaqws.zycos.simulated.entity.SimulatedEntityFlag
import zaqws.zycos.simulated.entity.SimulatedEntityStore
import zaqws.zycos.simulated.math.SimulatedVector3

internal class SimulatedInterestIndex(
    private val fullSimulationRadius: Double
) {
    companion object {
        private const val MINIMUM_CAPACITY = 4
    }

    private var playerPositionX =
        DoubleArray(0)

    private var playerPositionY =
        DoubleArray(0)

    private var playerPositionZ =
        DoubleArray(0)

    private var playerCount = 0

    init {
        require(fullSimulationRadius.isFinite())
        require(fullSimulationRadius >= 0.0)
    }

    val size: Int
        get() = playerCount

    fun rebuild(
        playerPositions: List<SimulatedVector3>
    ) {
        ensureCapacity(
            playerPositions.size
        )

        var index = 0

        while (
            index <
            playerPositions.size
        ) {
            val position =
                playerPositions[index]

            require(position.isFinite)

            playerPositionX[index] =
                position.x

            playerPositionY[index] =
                position.y

            playerPositionZ[index] =
                position.z

            index++
        }

        playerCount =
            playerPositions.size
    }

    fun clear() {
        playerCount = 0
    }

    fun updateEntitySimulationFlags(
        entityStore: SimulatedEntityStore
    ) {
        val radiusSquared =
            fullSimulationRadius *
                    fullSimulationRadius

        var slot = 0

        while (slot < entityStore.size) {
            val fullSimulation =
                playerCount != 0 && isWithinAnyPlayer(
                    entityStore.position(slot),
                    radiusSquared
                )

            entityStore.setFlag(
                slot,
                SimulatedEntityFlag.FULL_SIMULATION,
                fullSimulation
            )

            slot++
        }
    }

    fun isWithinFullSimulationRange(
        position: SimulatedVector3
    ): Boolean {
        require(position.isFinite)

        return playerCount != 0 && isWithinAnyPlayer(
            position,
            fullSimulationRadius *
                    fullSimulationRadius
        )
    }

    fun nearestPlayerDistanceSquared(
        position: SimulatedVector3
    ): Double {
        require(position.isFinite)

        if (playerCount == 0) {
            return Double.POSITIVE_INFINITY
        }

        var nearestDistanceSquared =
            Double.POSITIVE_INFINITY

        var index = 0

        while (index < playerCount) {
            val differenceX =
                position.x -
                        playerPositionX[index]

            val differenceY =
                position.y -
                        playerPositionY[index]

            val differenceZ =
                position.z -
                        playerPositionZ[index]

            val distanceSquared =
                differenceX * differenceX +
                        differenceY * differenceY +
                        differenceZ * differenceZ

            if (
                distanceSquared <
                nearestDistanceSquared
            ) {
                nearestDistanceSquared =
                    distanceSquared
            }

            index++
        }

        return nearestDistanceSquared
    }

    private fun isWithinAnyPlayer(
        position: SimulatedVector3,
        radiusSquared: Double
    ): Boolean {
        var index = 0

        while (index < playerCount) {
            val differenceX =
                position.x -
                        playerPositionX[index]

            val differenceY =
                position.y -
                        playerPositionY[index]

            val differenceZ =
                position.z -
                        playerPositionZ[index]

            val distanceSquared =
                differenceX * differenceX +
                        differenceY * differenceY +
                        differenceZ * differenceZ

            if (
                distanceSquared <=
                radiusSquared
            ) {
                return true
            }

            index++
        }

        return false
    }

    private fun ensureCapacity(
        requiredCapacity: Int
    ) {
        if (
            requiredCapacity <=
            playerPositionX.size
        ) {
            return
        }

        val newCapacity =
            maxOf(
                requiredCapacity,
                maxOf(
                    MINIMUM_CAPACITY,
                    playerPositionX.size * 2
                )
            )

        playerPositionX =
            playerPositionX.copyOf(
                newCapacity
            )

        playerPositionY =
            playerPositionY.copyOf(
                newCapacity
            )

        playerPositionZ =
            playerPositionZ.copyOf(
                newCapacity
            )
    }
}