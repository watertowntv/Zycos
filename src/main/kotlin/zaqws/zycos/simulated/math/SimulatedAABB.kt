package zaqws.zycos.simulated.math

data class SimulatedAABB(
    val minimumX: Double,
    val minimumY: Double,
    val minimumZ: Double,
    val maximumX: Double,
    val maximumY: Double,
    val maximumZ: Double
) {
    init {
        require(
            minimumX.isFinite() &&
                    minimumY.isFinite() &&
                    minimumZ.isFinite() &&
                    maximumX.isFinite() &&
                    maximumY.isFinite() &&
                    maximumZ.isFinite()
        )

        require(minimumX <= maximumX)
        require(minimumY <= maximumY)
        require(minimumZ <= maximumZ)
    }

    val width: Double
        get() = maximumX - minimumX

    val height: Double
        get() = maximumY - minimumY

    val depth: Double
        get() = maximumZ - minimumZ

    val center: SimulatedVector3
        get() = SimulatedVector3(
            (minimumX + maximumX) * 0.5,
            (minimumY + maximumY) * 0.5,
            (minimumZ + maximumZ) * 0.5
        )

    fun intersects(other: SimulatedAABB): Boolean =
        maximumX > other.minimumX &&
                minimumX < other.maximumX &&
                maximumY > other.minimumY &&
                minimumY < other.maximumY &&
                maximumZ > other.minimumZ &&
                minimumZ < other.maximumZ

    fun contains(position: SimulatedVector3): Boolean =
        position.x in minimumX..maximumX &&
                position.y in minimumY..maximumY &&
                position.z in minimumZ..maximumZ

    fun moved(offset: SimulatedVector3) = moved(
        offset.x,
        offset.y,
        offset.z
    )

    fun moved(
        offsetX: Double,
        offsetY: Double,
        offsetZ: Double
    ) = SimulatedAABB(
        minimumX + offsetX,
        minimumY + offsetY,
        minimumZ + offsetZ,
        maximumX + offsetX,
        maximumY + offsetY,
        maximumZ + offsetZ
    )

    fun expanded(amount: Double): SimulatedAABB {
        require(amount >= 0.0)

        return SimulatedAABB(
            minimumX - amount,
            minimumY - amount,
            minimumZ - amount,
            maximumX + amount,
            maximumY + amount,
            maximumZ + amount
        )
    }

    fun expanded(
        amountX: Double,
        amountY: Double,
        amountZ: Double
    ): SimulatedAABB {
        require(amountX >= 0.0)
        require(amountY >= 0.0)
        require(amountZ >= 0.0)

        return SimulatedAABB(
            minimumX - amountX,
            minimumY - amountY,
            minimumZ - amountZ,
            maximumX + amountX,
            maximumY + amountY,
            maximumZ + amountZ
        )
    }

    fun contracted(amount: Double): SimulatedAABB {
        require(amount >= 0.0)
        require(width >= amount * 2.0)
        require(height >= amount * 2.0)
        require(depth >= amount * 2.0)

        return SimulatedAABB(
            minimumX + amount,
            minimumY + amount,
            minimumZ + amount,
            maximumX - amount,
            maximumY - amount,
            maximumZ - amount
        )
    }

    fun overlapX(other: SimulatedAABB) =
        minOf(maximumX, other.maximumX) -
                maxOf(minimumX, other.minimumX)

    fun overlapY(other: SimulatedAABB) =
        minOf(maximumY, other.maximumY) -
                maxOf(minimumY, other.minimumY)

    fun overlapZ(other: SimulatedAABB) =
        minOf(maximumZ, other.maximumZ) -
                maxOf(minimumZ, other.minimumZ)

    companion object {
        fun fromBottomCenter(
            position: SimulatedVector3,
            width: Double,
            height: Double
        ): SimulatedAABB {
            require(width > 0.0)
            require(height > 0.0)
            require(width.isFinite())
            require(height.isFinite())

            val halfWidth = width * 0.5

            return SimulatedAABB(
                position.x - halfWidth,
                position.y,
                position.z - halfWidth,
                position.x + halfWidth,
                position.y + height,
                position.z + halfWidth
            )
        }
    }
}
