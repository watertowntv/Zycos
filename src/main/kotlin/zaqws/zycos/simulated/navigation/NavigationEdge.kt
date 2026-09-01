@file:Suppress("unused")

package zaqws.zycos.simulated.navigation

import kotlin.math.abs
import kotlin.math.sqrt

data class NavigationEdge(
    val target: NavigationNode,
    val movement: Movement,
    val cost: Double,
    val verticalDifferenceUnits: Int
) {
    companion object {
        const val STRAIGHT_COST = 1.0

        val DIAGONAL_COST: Double =
            sqrt(2.0)

        fun between(
            source: NavigationNode,
            target: NavigationNode
        ): NavigationEdge {
            val differenceX =
                abs(target.x - source.x)

            val differenceZ =
                abs(target.z - source.z)

            require(
                differenceX <= 1 &&
                        differenceZ <= 1 &&
                        differenceX + differenceZ > 0
            )

            val verticalDifferenceUnits =
                source.verticalDifferenceUnits(
                    target
                )

            val horizontalCost =
                if (
                    differenceX == 1 &&
                    differenceZ == 1
                ) {
                    DIAGONAL_COST
                } else {
                    STRAIGHT_COST
                }

            val movement =
                when {
                    verticalDifferenceUnits > 0 ->
                        Movement.STEP_UP

                    verticalDifferenceUnits < 0 ->
                        Movement.DROP

                    differenceX == 1 &&
                            differenceZ == 1 ->
                        Movement.DIAGONAL

                    else ->
                        Movement.WALK
                }

            return NavigationEdge(
                target = target,
                movement = movement,
                cost = horizontalCost,
                verticalDifferenceUnits =
                    verticalDifferenceUnits
            )
        }
    }

    init {
        require(cost.isFinite())
        require(cost >= 0.0)
    }

    val isAscending: Boolean
        get() =
            verticalDifferenceUnits > 0

    val isDescending: Boolean
        get() =
            verticalDifferenceUnits < 0

    val dropHeightUnits: Int
        get() =
            if (verticalDifferenceUnits < 0) {
                -verticalDifferenceUnits
            } else {
                0
            }

    enum class Movement {
        WALK,
        DIAGONAL,
        STEP_UP,
        DROP
    }
}