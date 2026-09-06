@file:Suppress("unused")

package zaqws.zycos.mob

import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import org.bukkit.Location
import org.bukkit.entity.LivingEntity
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import kotlin.math.ceil

class MobTargetManager(
    plugin: JavaPlugin
) : Listener {
    companion object {
        private const val CELL_SHIFT = 4
        private const val UPDATE_PERIOD_TICKS = 2L

        private fun getCellKey(cellX: Int, cellZ: Int): Long =
            (cellX.toLong() shl 32) or (cellZ.toLong() and 0xFFFFFFFFL)
    }

    private val worldGrids = HashMap<UUID, WorldGrid>()
    private val registeredEntities = HashMap<UUID, RegisteredEntity>()
    private val updateTask: BukkitTask

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)

        updateTask = plugin.server.scheduler.runTaskTimer(
            plugin,
            this::update,
            UPDATE_PERIOD_TICKS,
            UPDATE_PERIOD_TICKS
        )
    }

    fun unregister() {
        updateTask.cancel()

        worldGrids.clear()
        registeredEntities.clear()

        HandlerList.unregisterAll(this)
    }


    private fun update() {
        val iterator = registeredEntities.values.iterator()

        while (iterator.hasNext()) {
            val registeredEntity = iterator.next()
            val entity = registeredEntity.entity

            val worldGrid = worldGrids[registeredEntity.worldId]
            val oldEntities = worldGrid?.cells?.get(registeredEntity.cellKey)

            if (!entity.isValid || entity.isDead) {
                oldEntities?.remove(entity)

                if (oldEntities?.isEmpty() == true)
                    worldGrid.cells.remove(registeredEntity.cellKey)

                iterator.remove()
                continue
            }

            val worldId = entity.world.uid
            val newCellKey = getCellKey(
                entity.location.blockX shr CELL_SHIFT,
                entity.location.blockZ shr CELL_SHIFT
            )

            if (worldId == registeredEntity.worldId && newCellKey == registeredEntity.cellKey) {
                continue
            }

            oldEntities?.remove(entity)
            if (oldEntities?.isEmpty() == true) {
                worldGrid.cells.remove(registeredEntity.cellKey)
            }

            val newWorldGrid = worldGrids.getOrPut(worldId) { WorldGrid() }
            newWorldGrid.cells.getOrPut(newCellKey) {
                ArrayList(16)
            }.add(entity)

            registeredEntity.worldId = worldId
            registeredEntity.cellKey = newCellKey
        }

        worldGrids.entries.removeIf {
            it.value.cells.isEmpty()
        }
    }


    fun register(entity: LivingEntity): Boolean {
        if (!entity.isValid || entity.isDead) return false
        if (entity.uniqueId in registeredEntities) return false

        val cellKey = getCellKey(
            entity.location.blockX shr CELL_SHIFT,
            entity.location.blockZ shr CELL_SHIFT
        )

        val worldId = entity.world.uid
        val worldGrid = worldGrids.getOrPut(worldId) { WorldGrid() }

        worldGrid.cells.getOrPut(cellKey) {
            ArrayList(16)
        }.add(entity)

        registeredEntities[entity.uniqueId] = RegisteredEntity(
            entity,
            worldId,
            cellKey
        )

        return true
    }

    fun unregister(entity: LivingEntity) = unregister(entity.uniqueId)
    fun isRegistered(entity: LivingEntity) = entity.uniqueId in registeredEntities

    fun findNearest(
        origin: Location,
        range: Double,
        predicate: (LivingEntity) -> Boolean
    ): LivingEntity? {
        require(range.isFinite() && range >= 0.0)

        val world = origin.world ?: return null
        val worldGrid = worldGrids[world.uid] ?: return null

        val originX = origin.x
        val originY = origin.y
        val originZ = origin.z

        val rangeSquared = range * range
        val cellRadius = ceil(range / (1 shl CELL_SHIFT)).toInt()

        val centerCellX = origin.blockX shr CELL_SHIFT
        val centerCellZ = origin.blockZ shr CELL_SHIFT

        var nearestEntity: LivingEntity? = null
        var nearestDistanceSquared = rangeSquared

        for (cellX in centerCellX - cellRadius..centerCellX + cellRadius)
            for (cellZ in centerCellZ - cellRadius..centerCellZ + cellRadius) {
                val entities = worldGrid.cells[getCellKey(cellX, cellZ)] ?: continue

                for (index in entities.indices) {
                    val candidate = entities[index]

                    if (!candidate.isValid || candidate.isDead) continue
                    if (candidate.world !== world) continue
                    if (!predicate(candidate)) continue

                    val location = candidate.location

                    val deltaX = location.x - originX
                    val deltaY = location.y - originY
                    val deltaZ = location.z - originZ

                    val distanceSquared = deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ
                    if (distanceSquared > nearestDistanceSquared) continue

                    nearestDistanceSquared = distanceSquared
                    nearestEntity = candidate
                }
            }

        return nearestEntity
    }

    fun findNearest(
        origin: LivingEntity,
        range: Double,
        predicate: (LivingEntity) -> Boolean
    ): LivingEntity? = findNearest(origin.location, range) {
        it !== origin && predicate(it)
    }

    fun findAll(
        origin: Location,
        range: Double,
        predicate: (LivingEntity) -> Boolean
    ): List<LivingEntity> {
        require(range.isFinite() && range >= 0.0)

        val world = origin.world ?: return emptyList()
        val worldGrid = worldGrids[world.uid] ?: return emptyList()

        val originX = origin.x
        val originY = origin.y
        val originZ = origin.z

        val rangeSquared = range * range
        val cellRadius = ceil(range / (1 shl CELL_SHIFT)).toInt()

        val centerCellX = origin.blockX shr CELL_SHIFT
        val centerCellZ = origin.blockZ shr CELL_SHIFT

        val result = ArrayList<LivingEntity>()

        for (cellX in centerCellX - cellRadius..centerCellX + cellRadius)
            for (cellZ in centerCellZ - cellRadius..centerCellZ + cellRadius) {
                val entities = worldGrid.cells[getCellKey(cellX, cellZ)] ?: continue

                for (index in entities.indices) {
                    val candidate = entities[index]

                    if (!candidate.isValid || candidate.isDead) continue
                    if (candidate.world !== world) continue
                    if (!predicate(candidate)) continue

                    val location = candidate.location

                    val deltaX = location.x - originX
                    val deltaY = location.y - originY
                    val deltaZ = location.z - originZ

                    val distanceSquared = deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ
                    if (distanceSquared <= rangeSquared) {
                        result.add(candidate)
                    }
                }
            }

        return result
    }

    private fun unregister(entityId: UUID): Boolean {
        val registeredEntity = registeredEntities.remove(entityId) ?: return false
        val worldGrid = worldGrids[registeredEntity.worldId] ?: return true
        val entities = worldGrid.cells[registeredEntity.cellKey] ?: return true

        entities.remove(registeredEntity.entity)

        if (entities.isEmpty()) {
            worldGrid.cells.remove(registeredEntity.cellKey)
        }

        if (worldGrid.cells.isEmpty()) {
            worldGrids.remove(registeredEntity.worldId)
        }

        return true
    }


    @EventHandler
    private fun onEntityRemove(event: EntityRemoveFromWorldEvent) {
        val entity = event.entity as? LivingEntity ?: return

        unregister(entity.uniqueId)
    }


    private data class RegisteredEntity(
        val entity: LivingEntity,
        var worldId: UUID,
        var cellKey: Long
    )

    private class WorldGrid {
        val cells = Long2ObjectOpenHashMap<ArrayList<LivingEntity>>()
    }
}