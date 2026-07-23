@file:Suppress("unused")

package zaqws.zycos

import com.mojang.datafixers.util.Pair
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import it.unimi.dsi.fastutil.ints.IntArrayList
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.PositionMoveRotation
import org.bukkit.Location
import org.bukkit.craftbukkit.CraftEquipmentSlot
import org.bukkit.craftbukkit.CraftWorld
import org.bukkit.craftbukkit.entity.CraftEntityType
import org.bukkit.craftbukkit.entity.CraftPlayer
import org.bukkit.craftbukkit.inventory.CraftItemStack
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack

object ClientEntityManager : Listener {
    private val entityMap = Int2ObjectOpenHashMap<ClientEntity>()


    internal fun register() {
        Main.plugin.server.pluginManager.registerEvents(
            this, Main.plugin
        )
    }

    internal fun unregister() {
        removeAll()

        HandlerList.unregisterAll(this)
    }


    fun spawn(
        type: org.bukkit.entity.EntityType,
        location: Location,
    ): ClientEntity {
        val craftWorld = location.world as CraftWorld
        val nmsWorld = craftWorld.handle
        val nmsEntityType = CraftEntityType.bukkitToMinecraft(type)

        val nmsEntity = nmsEntityType.create(nmsWorld, EntitySpawnReason.COMMAND)
            ?: throw IllegalArgumentException("$type")

        nmsEntity.setPos(location.x, location.y, location.z)
        nmsEntity.setRot(location.yaw, location.pitch)

        val entity = ClientEntity(nmsEntity)
        entityMap.put(entity.entityId, entity)

        return entity
    }

    fun getEntity(entityId: Int): ClientEntity? = entityMap.get(entityId)
    fun removeEntity(entityId: Int) {
        entityMap.remove(entityId)?.remove()
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val player = event.player

        val iterator = entityMap.values.iterator()
        while (iterator.hasNext()) {
            iterator.next().unregisterViewer(player)
        }
    }

    fun removeAll() {
        val iterator = entityMap.values.iterator()

        while (iterator.hasNext()) {
            iterator.next().destroy()
            iterator.remove()
        }
    }


    class ClientEntity internal constructor(
        @PublishedApi internal val nmsEntity: Entity
    ) {
        private val _viewers = ObjectOpenHashSet<Player>()
        val viewers: Set<Player>
            get() = _viewers.toSet()

        val entityId: Int
            get() = nmsEntity.id

        val bukkitEntity: org.bukkit.entity.Entity
            get() = nmsEntity.bukkitEntity


        fun show(player: Player) {
            if (!_viewers.add(player)) return
            val connection = player.handle.connection

            connection.send(ClientboundAddEntityPacket(
                nmsEntity.id,
                nmsEntity.uuid,
                nmsEntity.x,
                nmsEntity.y,
                nmsEntity.z,
                nmsEntity.xRot,
                nmsEntity.yRot,
                nmsEntity.type,
                0,
                nmsEntity.deltaMovement,
                nmsEntity.yHeadRot.toDouble()
            ))
            sendPacket(player)
        }
        fun show(players: Iterable<Player>) {
            players.forEach(this::show)
        }

        fun hide(player: Player) {
            if (!_viewers.remove(player)) return

            player.handle.connection.send(ClientboundRemoveEntitiesPacket(
                IntArrayList.of(entityId)
            ))
        }
        fun hide(players: Iterable<Player>) {
            players.forEach(this::hide)
        }

        fun teleport(location: Location) {
            nmsEntity.setPos(location.x, location.y, location.z)
            nmsEntity.setRot(location.yaw, location.pitch)

            broadcastPacket(ClientboundTeleportEntityPacket.teleport(
                entityId,
                PositionMoveRotation.of(nmsEntity),
                emptySet(),
                nmsEntity.onGround
            ))
        }

        fun equip(slot: EquipmentSlot, item: ItemStack) {
            val nmsSlot = CraftEquipmentSlot.getNMS(slot)
            val nmsItem = CraftItemStack.asNMSCopy(item) ?: return

            val packet = ClientboundSetEquipmentPacket(
                entityId,
                listOf(Pair(nmsSlot, nmsItem))
            )

            broadcastPacket(packet)
        }

        inline fun <reified E : org.bukkit.entity.Entity> update(crossinline block: E.() -> Unit) {
            val bukkit = nmsEntity.bukkitEntity

            if (bukkit !is E) return
            bukkit.block()

            val dirty = nmsEntity.entityData.packDirty()
            if (dirty != null) {
                broadcastPacket(ClientboundSetEntityDataPacket(entityId, dirty))
            }
        }

        fun remove() {
            destroy()

            entityMap.remove(entityId)
        }

        internal fun destroy() {
            if (_viewers.isEmpty()) return

            val packet = ClientboundRemoveEntitiesPacket(IntArrayList.of(entityId))
            for (player in _viewers) {
                player.handle.connection.send(packet)
            }

            _viewers.clear()
        }

        internal fun unregisterViewer(player: Player) {
            _viewers.remove(player)
        }

        private fun sendPacket(player: Player) {
            val packedData = nmsEntity.entityData.packAll()

            player.handle.connection.send(ClientboundSetEntityDataPacket(entityId, packedData))
        }

        @PublishedApi
        internal fun broadcastPacket(packet: Packet<*>) {
            for (player in _viewers) {
                player.handle.connection.send(packet)
            }
        }
    }


    private val Player.handle: ServerPlayer
        get() = (this as CraftPlayer).handle
}