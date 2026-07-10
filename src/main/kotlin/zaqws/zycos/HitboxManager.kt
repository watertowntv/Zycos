@file:Suppress("unused")

package zaqws.zycos

import org.bukkit.Bukkit
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.GameMode
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.util.RayTraceResult
import org.bukkit.util.Vector
import java.util.UUID
import java.util.function.Predicate
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.floor
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap

class HitboxManager(
    plugin: JavaPlugin,
    private val maxHistoryTicks: Int = DEFAULT_MAX_HISTORY_TICKS
) {
    companion object {
        private const val DEFAULT_MAX_HISTORY_TICKS = 10
        private const val TASK_DELAY = 0L
        private const val TASK_PERIOD = 1L
        private const val EPSILON = 1e-8
        private const val MILLISECONDS_PER_TICK = 50.0
        private const val MAX_TICK_DIFFERENCE = 2L
        private const val CHUNK_SHIFT = 4
    }

    private var currentTick = 0L
    private val historyMap = Object2ObjectOpenHashMap<UUID, PlayerHistory>()

    init {
        plugin.server.scheduler.runTaskTimer(
            plugin,
            this::update,
            TASK_DELAY,
            TASK_PERIOD
        )
    }

    private fun update() {
        val tick = currentTick++

        for (entry in historyMap.object2ObjectEntrySet()) {
            val player = Bukkit.getPlayer(entry.key) ?: continue

            if (player.gameMode == GameMode.SPECTATOR || !player.isValid) continue

            val history = entry.value
            history.record(tick, player)
        }
    }

    fun clear() {
        historyMap.clear()
    }


    fun addPlayer(player: Player) {
        historyMap.getOrPut(player.uniqueId) {
            PlayerHistory(maxHistoryTicks)
        }
    }
    fun addPlayers(players: Iterable<Player>) {
        players.forEach(this::addPlayer)
    }

    fun removePlayer(player: Player) {
        historyMap.remove(player.uniqueId)
    }
    fun removePlayers(players: Iterable<Player>) {
        players.forEach(this::removePlayer)
    }


    fun hasPlayer(player: Player): Boolean {
        return historyMap.containsKey(player.uniqueId)
    }


    fun rayTraceBacktrack(
        shooter: Player,
        start: Location,
        direction: Vector,
        maxDistance: Double,
        fluidCollisionMode: FluidCollisionMode,
        ignorePassable: Boolean,
        raySize: Double,
        entityFilter: Predicate<Entity>?
    ): RayTraceResult? {
        val world = start.world
        val blockHit = world.rayTraceBlocks(
            start,
            direction,
            maxDistance,
            fluidCollisionMode,
            ignorePassable
        )

        val limitDistance = if (blockHit != null) {
            val hitPosition = blockHit.hitPosition

            val lengthX = (hitPosition.x - start.x) * (hitPosition.x - start.x)
            val lengthY = (hitPosition.y - start.y) * (hitPosition.y - start.y)
            val lengthZ = (hitPosition.z - start.z) * (hitPosition.z - start.z)
            
            sqrt(lengthX + lengthY + lengthZ)
        } else maxDistance

        val directionX = direction.x
        val directionY = direction.y
        val directionZ = direction.z
        
        val lengthSquared = directionX * directionX + directionY * directionY + directionZ * directionZ

        val normalizedDirectionX: Double
        val normalizedDirectionY: Double
        val normalizedDirectionZ: Double

        if (abs(lengthSquared - 1.0) < EPSILON) {
            normalizedDirectionX = directionX
            normalizedDirectionY = directionY
            normalizedDirectionZ = directionZ
        } else {
            val length = sqrt(lengthSquared)
            if (length < EPSILON) return blockHit

            val inverseLength = 1.0 / length
            normalizedDirectionX = directionX * inverseLength
            normalizedDirectionY = directionY * inverseLength
            normalizedDirectionZ = directionZ * inverseLength
        }

        val startX = start.x
        val startY = start.y
        val startZ = start.z

        val endX = startX + normalizedDirectionX * limitDistance
        val endZ = startZ + normalizedDirectionZ * limitDistance

        val minimumX = min(startX, endX)
        val maximumX = max(startX, endX)
        val minimumZ = min(startZ, endZ)
        val maximumZ = max(startZ, endZ)

        val minimumChunkX = (floor(minimumX).toInt() shr CHUNK_SHIFT) - 1
        val maximumChunkX = (floor(maximumX).toInt() shr CHUNK_SHIFT) + 1
        val minimumChunkZ = (floor(minimumZ).toInt() shr CHUNK_SHIFT) - 1
        val maximumChunkZ = (floor(maximumZ).toInt() shr CHUNK_SHIFT) + 1

        val pingMilliseconds = shooter.ping
        val pingTicks = (pingMilliseconds / MILLISECONDS_PER_TICK).roundToInt().coerceIn(0, maxHistoryTicks)
        val targetTick = currentTick - pingTicks

        var closestPlayer: Player? = null
        var closestDistance = limitDistance
        
        var hitX = 0.0
        var hitY = 0.0
        var hitZ = 0.0

        for (player in world.players) {
            if (player === shooter) continue
            if (player.gameMode == GameMode.SPECTATOR || !player.isValid) continue
            if (entityFilter != null && !entityFilter.test(player)) continue

            val history = historyMap[player.uniqueId]
            val historicalHitbox = history?.getAtTick(targetTick)

            val playerMinimumX: Double
            val playerMinimumY: Double
            val playerMinimumZ: Double
            val playerMaximumX: Double
            val playerMaximumY: Double
            val playerMaximumZ: Double

            if (historicalHitbox != null) {
                playerMinimumX = historicalHitbox.minimumX
                playerMinimumY = historicalHitbox.minimumY
                playerMinimumZ = historicalHitbox.minimumZ
                playerMaximumX = historicalHitbox.maximumX
                playerMaximumY = historicalHitbox.maximumY
                playerMaximumZ = historicalHitbox.maximumZ
            } else {
                val x = player.x
                val y = player.y
                val z = player.z

                val halfWidth = player.width / 2.0
                val height = player.height
                
                playerMinimumX = x - halfWidth
                playerMinimumY = y
                playerMinimumZ = z - halfWidth
                playerMaximumX = x + halfWidth
                playerMaximumY = y + height
                playerMaximumZ = z + halfWidth
            }

            val playerCenterX = (playerMinimumX + playerMaximumX) / 2.0
            val playerCenterZ = (playerMinimumZ + playerMaximumZ) / 2.0
            
            val playerChunkX = floor(playerCenterX).toInt() shr CHUNK_SHIFT
            val playerChunkZ = floor(playerCenterZ).toInt() shr CHUNK_SHIFT

            if (playerChunkX !in minimumChunkX..maximumChunkX ||
                playerChunkZ !in minimumChunkZ..maximumChunkZ) continue

            val intersectionDistance = rayIntersectsAABB(
                startX, startY, startZ,
                normalizedDirectionX, normalizedDirectionY, normalizedDirectionZ,
                playerMinimumX, playerMinimumY, playerMinimumZ,
                playerMaximumX, playerMaximumY, playerMaximumZ,
                raySize,
                closestDistance
            )

            if (intersectionDistance != null && intersectionDistance < closestDistance) {
                closestDistance = intersectionDistance
                closestPlayer = player
                
                hitX = startX + normalizedDirectionX * intersectionDistance
                hitY = startY + normalizedDirectionY * intersectionDistance
                hitZ = startZ + normalizedDirectionZ * intersectionDistance
            }
        }

        val nonPlayerHit = world.rayTraceEntities(
            start,
            direction,
            closestDistance,
            raySize
        ) { entity ->
            entity !is Player && (entityFilter == null || entityFilter.test(entity))
        }

        if (nonPlayerHit != null) return nonPlayerHit
        if (closestPlayer != null) return RayTraceResult(
            Vector(hitX, hitY, hitZ),
            closestPlayer,
            null
        )

        return blockHit
    }

    private fun rayIntersectsAABB(
        startX: Double,
        startY: Double,
        startZ: Double,
        directionX: Double,
        directionY: Double,
        directionZ: Double,
        minimumX: Double,
        minimumY: Double,
        minimumZ: Double,
        maximumX: Double,
        maximumY: Double,
        maximumZ: Double,
        raySize: Double,
        maximumDistance: Double
    ): Double? {
        val inflatedMinimumX = minimumX - raySize
        val inflatedMinimumY = minimumY - raySize
        val inflatedMinimumZ = minimumZ - raySize
        
        val inflatedMaximumX = maximumX + raySize
        val inflatedMaximumY = maximumY + raySize
        val inflatedMaximumZ = maximumZ + raySize

        var currentMinimumDistance = 0.0
        var currentMaximumDistance = maximumDistance

        if (!intersectAxis(
            directionX,
            startX,
            inflatedMinimumX,
            inflatedMaximumX,
            currentMinimumDistance,
            currentMaximumDistance
        ) { newMin, newMax ->
            currentMinimumDistance = newMin
            currentMaximumDistance = newMax
        }) return null

        if (!intersectAxis(
            directionY,
            startY,
            inflatedMinimumY,
            inflatedMaximumY,
            currentMinimumDistance,
            currentMaximumDistance
        ) { newMin, newMax ->
            currentMinimumDistance = newMin
            currentMaximumDistance = newMax
        }) return null

        if (!intersectAxis(
            directionZ,
            startZ,
            inflatedMinimumZ,
            inflatedMaximumZ,
            currentMinimumDistance,
            currentMaximumDistance
        ) { newMin, newMax ->
            currentMinimumDistance = newMin
        }) return null

        return currentMinimumDistance
    }

    private inline fun intersectAxis(
        direction: Double,
        start: Double,
        inflatedMinimum: Double,
        inflatedMaximum: Double,
        currentMin: Double,
        currentMax: Double,
        onUpdate: (newMin: Double, newMax: Double) -> Unit
    ): Boolean {
        if (abs(direction) < EPSILON) {
            return start in inflatedMinimum..inflatedMaximum
        }

        val inverseDirection = 1.0 / direction
        var firstIntersectionDistance = (inflatedMinimum - start) * inverseDirection
        var secondIntersectionDistance = (inflatedMaximum - start) * inverseDirection

        if (firstIntersectionDistance > secondIntersectionDistance) {
            val tempDistance = firstIntersectionDistance

            firstIntersectionDistance = secondIntersectionDistance
            secondIntersectionDistance = tempDistance
        }

        val newMin = max(currentMin, firstIntersectionDistance)
        val newMax = min(currentMax, secondIntersectionDistance)

        if (newMin > newMax) return false
        onUpdate(newMin, newMax)

        return true
    }


    private class HistoricalHitbox {
        var serverTick: Long = 0L
        var minimumX: Double = 0.0
        var minimumY: Double = 0.0
        var minimumZ: Double = 0.0
        var maximumX: Double = 0.0
        var maximumY: Double = 0.0
        var maximumZ: Double = 0.0

        fun update(tick: Long, minX: Double, minY: Double, minZ: Double, maxX: Double, maxY: Double, maxZ: Double) {
            this.serverTick = tick
            this.minimumX = minX
            this.minimumY = minY
            this.minimumZ = minZ
            this.maximumX = maxX
            this.maximumY = maxY
            this.maximumZ = maxZ
        }
    }

    private class PlayerHistory(
        private val maxHistoryTicks: Int
    ) {
        private val history = Array(maxHistoryTicks) { HistoricalHitbox() }
        private var writeIndex = 0

        fun record(tick: Long, player: Player) {
            val x = player.x
            val y = player.y
            val z = player.z

            val halfWidth = player.width / 2.0
            val height = player.height

            history[writeIndex].update(
                tick,
                x - halfWidth,
                y,
                z - halfWidth,
                x + halfWidth,
                y + height,
                z + halfWidth
            )
            writeIndex = (writeIndex + 1) % maxHistoryTicks
        }

        fun getAtTick(targetTick: Long): HistoricalHitbox? {
            val newestEntry = history[(writeIndex - 1 + maxHistoryTicks) % maxHistoryTicks]
            val newestTick = newestEntry.serverTick

            val oldestEntry = history[writeIndex]
            val oldestTick = if (oldestEntry.serverTick == 0L) history[0].serverTick else oldestEntry.serverTick

            if (targetTick !in oldestTick..newestTick) return null

            var index = (targetTick % maxHistoryTicks).toInt()
            if (index < 0) index += maxHistoryTicks

            val entry = history[index]
            return if (entry.serverTick == targetTick) entry else null
        }
    }
}
