@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package zaqws.zycos

import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.util.Vector
import kotlin.math.absoluteValue
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt


object AreaManager {
    @JvmInline
    value class Position(val raw: Long) {
        companion object {
            fun pack(x: Int, y: Int, z: Int) =
                ((x.toLong() and 0x3FFFFFFL) shl 38) or
                        ((z.toLong() and 0x3FFFFFFL) shl 12) or
                        (y.toLong() and 0xFFFL)

            operator fun invoke(x: Int, y: Int, z: Int) = Position(pack(x, y, z))

            fun lerp(start: Position, target: Position, ratio: Double): Position {
                val sX = start.x
                val sY = start.y
                val sZ = start.z

                val tX = target.x
                val tY = target.y
                val tZ = target.z

                return invoke(
                    (sX + (tX - sX) * ratio).toInt(),
                    (sY + (tY - sY) * ratio).toInt(),
                    (sZ + (tZ - sZ) * ratio).toInt()
                )
            }
        }

        val x: Int get() = (raw shr 38).toInt()
        val y: Int get() = (raw shl 52 shr 52).toInt()
        val z: Int get() = (raw shl 26 shr 38).toInt()

        val chunkX: Int get() = x shr Constants.CHUNK_SHIFT
        val chunkZ: Int get() = z shr Constants.CHUNK_SHIFT

        operator fun plus(other: Position) = Position(
            x + other.x,
            y + other.y,
            z + other.z
        )
        operator fun minus(other: Position) = Position(
            x - other.x,
            y - other.y,
            z - other.z
        )
        operator fun plus(n: Int) = Position(
            x + n,
            y + n,
            z + n
        )
        operator fun minus(n: Int) = Position(
            x - n,
            y - n,
            z - n
        )
        operator fun times(n: Int) = Position(
            x * n,
            y * n,
            z * n
        )

        operator fun component1() = x
        operator fun component2() = y
        operator fun component3() = z

        fun abs() = Position(
            x.absoluteValue,
            y.absoluteValue,
            z.absoluteValue
        )
        fun shifted(x: Int, y: Int, z: Int) = Position(
            this.x + x,
            this.y + y,
            this.z + z
        )

        fun lerp(target: Position, ratio: Double) = lerp(this, target, ratio)

        fun toLocation(world: World = overworld) = Location(
            world,
            x.toDouble(),
            y.toDouble(),
            z.toDouble()
        )
        fun toVector() = Vector(
            x.toDouble(),
            y.toDouble(),
            z.toDouble()
        )

        fun distanceSquared(target: Position): Long {
            val dx = (x - target.x).toLong()
            val dy = (y - target.y).toLong()
            val dz = (z - target.z).toLong()

            return dx * dx + dy * dy + dz * dz
        }

        fun distanceSquared2D(target: Position): Long {
            val dx = (x - target.x).toLong()
            val dz = (z - target.z).toLong()

            return dx * dx + dz * dz
        }

        fun distance(target: Position) = sqrt(distanceSquared(target).toDouble())
        fun distance2D(target: Position) = sqrt(distanceSquared2D(target).toDouble())

        override fun toString() = "Position($x, $y, $z)"
    }

    class Area(
        val start: Position,
        val end: Position
    ) {
        val boundingBoxStart = Position(
            min(start.x, end.x),
            min(start.y, end.y),
            min(start.z, end.z)
        )
        val boundingBoxEnd = Position(
            max(start.x, end.x),
            max(start.y, end.y),
            max(start.z, end.z)
        )
        val center: Position = Position(
            (boundingBoxStart.x + boundingBoxEnd.x) / 2,
            (boundingBoxStart.y + boundingBoxEnd.y) / 2,
            (boundingBoxStart.z + boundingBoxEnd.z) / 2
        )

        constructor(
            startX: Int,
            startY: Int,
            startZ: Int,
            endX: Int,
            endY: Int,
            endZ: Int
        ) : this(
            Position(startX, startY, startZ),
            Position(endX, endY, endZ)
        )

        constructor(vararg values: Int) : this(
            if (values.size == 6) values[0] else throw IllegalArgumentException("Values must be 6 Ints"),
            values[1], values[2], values[3], values[4], values[5]
        )

        operator fun contains(position: Position): Boolean {
            val x = position.x in boundingBoxStart.x..boundingBoxEnd.x
            val y = position.y in boundingBoxStart.y..boundingBoxEnd.y
            val z = position.z in boundingBoxStart.z..boundingBoxEnd.z

            return x && y && z
        }

        fun random() = Position(
            randomRange(boundingBoxStart.x, boundingBoxEnd.x),
            randomRange(boundingBoxStart.y, boundingBoxEnd.y),
            randomRange(boundingBoxStart.z, boundingBoxEnd.z)
        )

        fun intersects(other: Area): Boolean {
            val x = boundingBoxStart.x <= other.boundingBoxEnd.x &&
                    boundingBoxEnd.x >= other.boundingBoxStart.x
            val y = boundingBoxStart.y <= other.boundingBoxEnd.y &&
                    boundingBoxEnd.y >= other.boundingBoxStart.y
            val z = boundingBoxStart.z <= other.boundingBoxEnd.z &&
                    boundingBoxEnd.z >= other.boundingBoxStart.z

            return x && y && z
        }

        val players: Sequence<Player>
            get() = onlinePlayers.asSequence().filter {
                it.location.toPosition() in this
            }
        val size: Long
            get() {
                val x = boundingBoxEnd.x - boundingBoxStart.x + 1
                val y = boundingBoxEnd.y - boundingBoxStart.y + 1
                val z = boundingBoxEnd.z - boundingBoxStart.z + 1

                return x.toLong() * y.toLong() * z.toLong()
            }

        fun clone() = Area(start, end)

        fun query() = AreaQuery { callback ->
            val rangeX = boundingBoxStart.x..boundingBoxEnd.x
            val rangeY = boundingBoxStart.y..boundingBoxEnd.y
            val rangeZ = boundingBoxStart.z..boundingBoxEnd.z

            val chunkRangeX = (rangeX.first shr Constants.CHUNK_SHIFT)..(rangeX.last shr Constants.CHUNK_SHIFT)
            val chunkRangeZ = (rangeZ.first shr Constants.CHUNK_SHIFT)..(rangeZ.last shr Constants.CHUNK_SHIFT)

            for(chunkX in chunkRangeX){
                val chunkStartX = chunkX shl Constants.CHUNK_SHIFT
                val startX = max(rangeX.first, chunkStartX)
                val endX = min(rangeX.last, chunkStartX + 15)

                for(chunkZ in chunkRangeZ){
                    val chunkStartZ = chunkZ shl Constants.CHUNK_SHIFT
                    val startZ = max(rangeZ.first, chunkStartZ)
                    val endZ = min(rangeZ.last, chunkStartZ + 15)

                    for(y in rangeY){
                        for(z in startZ..endZ){
                            for(x in startX..endX)
                                callback(x, y, z)
                        }
                    }
                }
            }
        }
    }


    fun interface CoordinateStep {
        fun execute(callback: (x: Int, y: Int, z: Int) -> Unit)
    }

    @JvmInline
    value class AreaQuery(val step: CoordinateStep) {
        inline fun filter(
            crossinline predicate: (x: Int, y: Int, z: Int) -> Boolean
        ) = AreaQuery { callback ->
            step.execute { x, y, z ->
                if (predicate(x, y, z))
                    callback(x, y, z)
            }
        }

        inline fun map(
            crossinline transform: (x: Int, y: Int, z: Int) -> Position
        ) = AreaQuery { callback ->
            step.execute { x, y, z ->
                val mapped = transform(x, y, z)

                callback(mapped.x, mapped.y, mapped.z)
            }
        }

        inline fun forEach(
            crossinline callback: (x: Int, y: Int, z: Int) -> Unit
        ) {
            step.execute { x, y, z ->
                callback(x, y, z)
            }
        }

        fun toArray(): LongArray {
            var capacity = 1024
            var array = LongArray(capacity)
            var size = 0

            step.execute { x, y, z ->
                if (size == capacity) {
                    capacity *= 2
                    array = array.copyOf(capacity)
                }

                array[size++] = Position(x, y, z).raw
            }

            return if (size == capacity) array
            else array.copyOf(size)
        }
    }
}