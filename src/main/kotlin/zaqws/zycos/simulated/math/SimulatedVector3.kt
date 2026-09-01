package zaqws.zycos.simulated.math

import kotlin.math.sqrt

data class SimulatedVector3(
    val x: Double,
    val y: Double,
    val z: Double
) {
    operator fun plus(other: SimulatedVector3) = SimulatedVector3(
        x + other.x,
        y + other.y,
        z + other.z
    )

    operator fun minus(other: SimulatedVector3) = SimulatedVector3(
        x - other.x,
        y - other.y,
        z - other.z
    )

    operator fun unaryMinus() = SimulatedVector3(
        -x,
        -y,
        -z
    )

    operator fun times(multiplier: Double) = SimulatedVector3(
        x * multiplier,
        y * multiplier,
        z * multiplier
    )

    operator fun div(divisor: Double): SimulatedVector3 {
        require(divisor != 0.0)

        return SimulatedVector3(
            x / divisor,
            y / divisor,
            z / divisor
        )
    }

    val lengthSquared: Double
        get() = x * x + y * y + z * z

    val length: Double
        get() = sqrt(lengthSquared)

    val horizontalLengthSquared: Double
        get() = x * x + z * z

    val horizontalLength: Double
        get() = sqrt(horizontalLengthSquared)

    val isFinite: Boolean
        get() = x.isFinite() && y.isFinite() && z.isFinite()

    fun normalized(): SimulatedVector3 {
        val lengthSquared = lengthSquared
        if (lengthSquared <= SimulatedMath.EPSILON_SQUARED) return ZERO

        val inverseLength = 1.0 / sqrt(lengthSquared)
        return SimulatedVector3(
            x * inverseLength,
            y * inverseLength,
            z * inverseLength
        )
    }

    fun normalizedHorizontal(): SimulatedVector3 {
        val lengthSquared = horizontalLengthSquared
        if (lengthSquared <= SimulatedMath.EPSILON_SQUARED) return ZERO

        val inverseLength = 1.0 / sqrt(lengthSquared)
        return SimulatedVector3(
            x * inverseLength,
            0.0,
            z * inverseLength
        )
    }

    fun dot(other: SimulatedVector3) =
        x * other.x + y * other.y + z * other.z

    fun cross(other: SimulatedVector3) = SimulatedVector3(
        y * other.z - z * other.y,
        z * other.x - x * other.z,
        x * other.y - y * other.x
    )

    fun distanceSquared(other: SimulatedVector3): Double {
        val differenceX = x - other.x
        val differenceY = y - other.y
        val differenceZ = z - other.z

        return differenceX * differenceX +
                differenceY * differenceY +
                differenceZ * differenceZ
    }

    fun distance(other: SimulatedVector3) =
        sqrt(distanceSquared(other))

    fun horizontalDistanceSquared(other: SimulatedVector3): Double {
        val differenceX = x - other.x
        val differenceZ = z - other.z

        return differenceX * differenceX + differenceZ * differenceZ
    }

    fun horizontalDistance(other: SimulatedVector3) =
        sqrt(horizontalDistanceSquared(other))

    fun withX(x: Double) = SimulatedVector3(x, y, z)

    fun withY(y: Double) = SimulatedVector3(x, y, z)

    fun withZ(z: Double) = SimulatedVector3(x, y, z)

    companion object {
        val ZERO = SimulatedVector3(0.0, 0.0, 0.0)
    }
}
