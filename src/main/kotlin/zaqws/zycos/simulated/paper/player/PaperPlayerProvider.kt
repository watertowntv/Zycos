@file:Suppress("unused")

package zaqws.zycos.simulated.paper.player

import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import zaqws.zycos.onGround
import zaqws.zycos.simulated.entity.SimulatedHitbox
import zaqws.zycos.simulated.external.SimulatedExternalActorFlag
import zaqws.zycos.simulated.external.SimulatedExternalActorFlags
import zaqws.zycos.simulated.external.SimulatedExternalActorId
import zaqws.zycos.simulated.external.SimulatedExternalActorProvider
import zaqws.zycos.simulated.external.SimulatedExternalActorSnapshot
import zaqws.zycos.simulated.external.SimulatedExternalFrame
import zaqws.zycos.simulated.math.SimulatedVector3
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class PaperPlayerProvider(
    private val plugin: JavaPlugin,
    private val teamResolver: PaperPlayerTeamResolver =
        PaperPlayerTeamResolver.NONE
) : SimulatedExternalActorProvider {
    private val playerIds =
        HashMap<UUID, SimulatedExternalActorId>()

    private val playerUniqueIds =
        HashMap<SimulatedExternalActorId, UUID>()

    private val nextActorId =
        AtomicLong(1L)

    override fun capture(
        sequence: Long
    ): SimulatedExternalFrame {
        require(sequence >= 0L)

        check(plugin.server.isPrimaryThread) {
            "PaperPlayerProvider.capture must be called from the server thread"
        }

        val onlinePlayers =
            plugin.server.onlinePlayers

        if (onlinePlayers.isEmpty()) {
            removeOfflineMappings(
                emptySet()
            )

            return SimulatedExternalFrame(
                sequence = sequence,
                actors = emptyList()
            )
        }

        val actors =
            ArrayList<SimulatedExternalActorSnapshot>(
                onlinePlayers.size
            )

        val onlineUniqueIds =
            HashSet<UUID>(
                onlinePlayers.size
            )

        for (player in onlinePlayers) {
            onlineUniqueIds.add(
                player.uniqueId
            )

            actors.add(
                capturePlayer(player)
            )
        }

        removeOfflineMappings(
            onlineUniqueIds
        )

        return SimulatedExternalFrame(
            sequence = sequence,
            actors = actors
        )
    }

    fun actorId(
        player: Player
    ): SimulatedExternalActorId =
        actorId(
            player.uniqueId
        )

    fun actorId(
        uniqueId: UUID
    ): SimulatedExternalActorId =
        playerIds.getOrPut(
            uniqueId
        ) {
            allocateActorId().also {
                playerUniqueIds[it] =
                    uniqueId
            }
        }

    fun player(
        actorId: SimulatedExternalActorId
    ): Player? {
        val uniqueId =
            playerUniqueIds[
                actorId
            ] ?: return null

        return plugin.server.getPlayer(
            uniqueId
        )
    }

    fun playerUniqueId(
        actorId: SimulatedExternalActorId
    ): UUID? =
        playerUniqueIds[
            actorId
        ]

    private fun capturePlayer(
        player: Player
    ): SimulatedExternalActorSnapshot {
        val location =
            player.location

        val velocity =
            player.velocity

        val boundingBox =
            player.boundingBox

        val width =
            maxOf(
                boundingBox.widthX,
                boundingBox.widthZ
            )

        val height =
            boundingBox.height

        val maximumHealth =
            player.getAttribute(
                Attribute.MAX_HEALTH
            )?.value
                ?.coerceAtLeast(
                    MINIMUM_MAXIMUM_HEALTH
                )
                ?: player.health
                    .coerceAtLeast(
                        MINIMUM_MAXIMUM_HEALTH
                    )

        val health =
            player.health.coerceIn(
                0.0,
                maximumHealth
            )

        var flags =
            SimulatedExternalActorFlags.NONE

        if (!player.isDead) {
            flags +=
                SimulatedExternalActorFlag.ALIVE
        }

        if (
            !player.isDead &&
            player.gameMode !=
            org.bukkit.GameMode.SPECTATOR
        ) {
            flags +=
                SimulatedExternalActorFlag.TARGETABLE

            flags +=
                SimulatedExternalActorFlag.DAMAGEABLE

            flags +=
                SimulatedExternalActorFlag.COLLIDABLE
        }

        if (player.onGround) {
            flags +=
                SimulatedExternalActorFlag.ON_GROUND
        }

        return SimulatedExternalActorSnapshot(
            actorId =
                actorId(player),

            position =
                SimulatedVector3(
                    location.x,
                    location.y,
                    location.z
                ),

            velocity =
                SimulatedVector3(
                    velocity.x,
                    velocity.y,
                    velocity.z
                ),

            hitbox =
                SimulatedHitbox(
                    width =
                        width.coerceAtLeast(
                            MINIMUM_HITBOX_SIZE
                        ),

                    height =
                        height.coerceAtLeast(
                            MINIMUM_HITBOX_SIZE
                        )
                ),

            health =
                health,

            maximumHealth =
                maximumHealth,

            team =
                teamResolver.resolve(
                    player
                ),

            flags =
                flags
        )
    }

    private fun removeOfflineMappings(
        onlineUniqueIds: Set<UUID>
    ) {
        val iterator =
            playerIds.entries.iterator()

        while (iterator.hasNext()) {
            val entry =
                iterator.next()

            if (
                entry.key in
                onlineUniqueIds
            ) {
                continue
            }

            playerUniqueIds.remove(
                entry.value
            )

            iterator.remove()
        }
    }

    private fun allocateActorId():
            SimulatedExternalActorId {
        val value =
            nextActorId.getAndIncrement()

        check(value > 0L) {
            "Simulated external actor identifier space exhausted"
        }

        return SimulatedExternalActorId(
            value
        )
    }

    companion object {
        private const val MINIMUM_HITBOX_SIZE =
            1.0e-6

        private const val MINIMUM_MAXIMUM_HEALTH =
            1.0e-6
    }
}