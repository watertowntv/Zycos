@file:Suppress("unused")

package zaqws.zycos

import com.destroystokyo.paper.event.player.PlayerUseUnknownEntityEvent
import com.mojang.datafixers.util.Pair
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import it.unimi.dsi.fastutil.ints.IntArrayList
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.LivingEntity
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
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Vector


object ClientEntityManager : Listener {
    private val entityMap = Int2ObjectOpenHashMap<ClientEntity>()

    private var interactionHandler: InteractionHandler? = null


    internal fun register() {
        Main.plugin.server.pluginManager.registerEvents(
            this, Main.plugin
        )
    }

    internal fun unregister() {
        interactionHandler = null
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
        entityMap.remove(entityId)?.destroy()
    }

    fun setInteractionHandler(handler: InteractionHandler?) {
        interactionHandler = handler
    }


    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val player = event.player

        entityMap.values.forEach { entity ->
            entity.unregisterViewer(player)
        }
    }

    @EventHandler
    fun onPlayerChangedWorld(event: PlayerChangedWorldEvent) {
        val player = event.player

        entityMap.values.forEach { entity ->
            entity.unregisterViewer(player)
        }
    }

    @EventHandler
    fun onPlayerUseUnknownEntity(event: PlayerUseUnknownEntityEvent) {
        val entity = entityMap.get(event.entityId) ?: return

        if (event.player !in entity.viewers) return

        val interaction = if (event.isAttack) {
            Interaction.Attack
        } else {
            Interaction.Interact(
                hand = event.hand,
                clickedRelativePosition = event.clickedRelativePosition
            )
        }

        interactionHandler?.handle(
            event.player,
            entity,
            interaction
        )
    }

    fun removeAll() {
        entityMap.values.forEach { entity ->
            entity.destroy()
        }

        entityMap.clear()
    }


    fun interface InteractionHandler {
        fun handle(
            player: Player,
            entity: ClientEntity,
            interaction: Interaction
        )
    }

    sealed interface Interaction {
        data object Attack : Interaction

        data class Interact(
            val hand: EquipmentSlot,
            val clickedRelativePosition: Vector?
        ) : Interaction
    }


    class ClientEntity internal constructor(
        @PublishedApi internal val nmsEntity: Entity
    ) {
        val viewers: Set<Player>
            field = ObjectOpenHashSet<Player>()

        val entityId: Int
            get() = nmsEntity.id

        val bukkitEntity: org.bukkit.entity.Entity
            get() = nmsEntity.bukkitEntity


        fun show(player: Player) {
            if (!viewers.add(player)) return

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
            if (!viewers.remove(player)) return

            player.handle.connection.send(
                ClientboundRemoveEntitiesPacket(
                    IntArrayList.of(entityId)
                )
            )
        }

        fun hide(players: Iterable<Player>) {
            players.forEach(this::hide)
        }

        fun teleport(location: Location) {
            nmsEntity.setPos(
                location.x,
                location.y,
                location.z
            )

            nmsEntity.setRot(
                location.yaw,
                location.pitch
            )

            nmsEntity.yHeadRot = location.yaw

            broadcastPacket(
                ClientboundTeleportEntityPacket.teleport(
                    entityId,
                    PositionMoveRotation.of(nmsEntity),
                    emptySet(),
                    nmsEntity.onGround
                )
            )

            if (nmsEntity is LivingEntity) {
                val headYawByte =
                    (
                            location.yaw *
                                    256.0f /
                                    360.0f
                            ).toInt().toByte()

                broadcastPacket(
                    ClientboundRotateHeadPacket(
                        nmsEntity,
                        headYawByte
                    )
                )
            }
        }

        fun equip(
            slot: EquipmentSlot,
            item: ItemStack
        ) {
            val nmsSlot =
                CraftEquipmentSlot.getNMS(slot)

            val nmsItem =
                CraftItemStack.asNMSCopy(item)
                    ?: return

            val packet =
                ClientboundSetEquipmentPacket(
                    entityId,
                    listOf(
                        Pair(
                            nmsSlot,
                            nmsItem
                        )
                    )
                )

            broadcastPacket(packet)
        }

        inline fun <
                reified E : org.bukkit.entity.Entity
                > update(
            crossinline block: E.() -> Unit
        ) {
            val bukkit =
                nmsEntity.bukkitEntity

            if (bukkit !is E) return

            bukkit.block()

            val dirty =
                nmsEntity.entityData.packDirty()

            if (dirty != null) {
                broadcastPacket(
                    ClientboundSetEntityDataPacket(
                        entityId,
                        dirty
                    )
                )
            }
        }

        fun remove() {
            destroy()

            entityMap.remove(entityId)
        }

        internal fun destroy() {
            if (viewers.isEmpty()) return

            val packet =
                ClientboundRemoveEntitiesPacket(
                    IntArrayList.of(entityId)
                )

            viewers.forEach { player ->
                player.handle.connection.send(packet)
            }

            viewers.clear()
        }

        internal fun unregisterViewer(player: Player) {
            viewers.remove(player)
        }

        private fun sendPacket(player: Player) {
            val packedData =
                nmsEntity.entityData.packAll()

            player.handle.connection.send(
                ClientboundSetEntityDataPacket(
                    entityId,
                    packedData
                )
            )
        }

        @PublishedApi
        internal fun broadcastPacket(packet: Packet<*>) {
            viewers.forEach { player ->
                player.handle.connection.send(packet)
            }
        }
    }


    private val Player.handle: ServerPlayer
        get() = (this as CraftPlayer).handle
}
