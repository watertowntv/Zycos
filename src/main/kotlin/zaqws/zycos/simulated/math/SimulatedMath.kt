package zaqws.zycos.simulated.math

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round

object SimulatedMath {
    const val EPSILON = 1.0e-9
    const val EPSILON_SQUARED = EPSILON * EPSILON

    fun approximatelyZero(value: Double) =
        value < EPSILON && value > -EPSILON

    fun approximatelyEqual(
        first: Double,
        second: Double,
        epsilon: Double = EPSILON
    ): Boolean {
        require(epsilon >= 0.0)

        return kotlin.math.abs(first - second) <= epsilon
    }

    fun square(value: Double) = value * value

    fun floorToInt(value: Double): Int {
        require(value.isFinite())

        val floored = floor(value)
        require(floored >= Int.MIN_VALUE.toDouble())
        require(floored <= Int.MAX_VALUE.toDouble())

        return floored.toInt()
    }

    fun ceilToInt(value: Double): Int {
        require(value.isFinite())

        val ceiled = ceil(value)
        require(ceiled >= Int.MIN_VALUE.toDouble())
        require(ceiled <= Int.MAX_VALUE.toDouble())

        return ceiled.toInt()
    }

    fun quantizeUp(
        value: Double,
        unit: Double
    ): Double {
        require(value.isFinite())
        require(unit.isFinite())
        require(unit > 0.0)

        return ceil(value / unit) * unit
    }

    fun quantizeDown(
        value: Double,
        unit: Double
    ): Double {
        require(value.isFinite())
        require(unit.isFinite())
        require(unit > 0.0)

        return floor(value / unit) * unit
    }

    fun quantizeNearest(
        value: Double,
        unit: Double
    ): Double {
        require(value.isFinite())
        require(unit.isFinite())
        require(unit > 0.0)

        return round(value / unit) * unit
    }

    fun inverseLerp(
        minimum: Double,
        maximum: Double,
        value: Double
    ): Double {
        if (approximatelyEqual(minimum, maximum)) return 0.0

        return (value - minimum) / (maximum - minimum)
    }

    fun lerp(
        start: Double,
        end: Double,
        ratio: Double
    ) = start + (end - start) * ratio
}
