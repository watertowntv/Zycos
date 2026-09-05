package zaqws.zycos.simulated.paper.interaction

import org.bukkit.Bukkit
import org.bukkit.FluidCollisionMode
import org.bukkit.entity.Player
import org.bukkit.util.Vector
import zaqws.zycos.simulated.SimulatedEngine
import zaqws.zycos.simulated.entity.SimulatedEntityId
import zaqws.zycos.simulated.paper.player.PaperPlayerProvider

class ClientEntityInteractionHandler @JvmOverloads constructor(
    private val engine: SimulatedEngine,
    private val playerProvider: PaperPlayerProvider,
    private val simulatedEntityResolver:
        (clientEntityId: Int) -> SimulatedEntityId?,
    private val interactionConsumer:
        (SimulatedInteraction) -> Unit = {},
    val maximumReachDistance: Double = DEFAULT_MAXIMUM_REACH_DISTANCE
) {
    companion object {
        const val DEFAULT_MAXIMUM_REACH_DISTANCE = 6.0
    }

    init {
        require(maximumReachDistance.isFinite())
        require(maximumReachDistance > 0.0)
    }

    fun attack(
        player: Player,
        clientEntityId: Int,
        damage: Double
    ): Boolean {
        check(Bukkit.isPrimaryThread()) { "Interaction must occur on the main thread" }
        require(damage.isFinite())
        require(damage >= 0.0)

        val targetEntityId =
            resolveTarget(
                clientEntityId
            ) ?: return false

        if (!validateInteraction(player, targetEntityId)) {
            return false
        }

        val actorId =
            playerProvider.actorId(
                player
            )

        val interaction =
            SimulatedInteraction.Attack(
                actorId = actorId,
                targetEntityId = targetEntityId,
                damage = damage
            )

        engine.getEntity(
            targetEntityId
        )?.damage(
            damage
        ) ?: return false

        interactionConsumer(
            interaction
        )

        return true
    }

    fun interact(
        player: Player,
        clientEntityId: Int,
        hand: SimulatedInteraction.Hand
    ): Boolean {
        check(Bukkit.isPrimaryThread()) { "Interaction must occur on the main thread" }

        val targetEntityId =
            resolveTarget(
                clientEntityId
            ) ?: return false

        if (!validateInteraction(player, targetEntityId)) {
            return false
        }

        val actorId =
            playerProvider.actorId(
                player
            )

        interactionConsumer(
            SimulatedInteraction.Interact(
                actorId = actorId,
                targetEntityId = targetEntityId,
                hand = hand
            )
        )

        return true
    }

    fun resolveTarget(
        clientEntityId: Int
    ): SimulatedEntityId? {
        val simulatedEntityId =
            simulatedEntityResolver(
                clientEntityId
            ) ?: return null

        if (
            !engine.exists(
                simulatedEntityId
            )
        ) {
            return null
        }

        return simulatedEntityId
    }

    private fun validateInteraction(
        player: Player,
        targetEntityId: SimulatedEntityId
    ): Boolean {
        if (playerProvider.world != null && player.world != playerProvider.world) {
            return false
        }

        val snapshot = engine.snapshot(targetEntityId) ?: return false

        val targetPosition = snapshot.position
        val hitbox = snapshot.hitbox
        val eyeLoc = player.eyeLocation

        val minX = targetPosition.x - hitbox.halfWidth
        val maxX = targetPosition.x + hitbox.halfWidth
        val minY = targetPosition.y
        val maxY = targetPosition.y + hitbox.height
        val minZ = targetPosition.z - hitbox.halfWidth
        val maxZ = targetPosition.z + hitbox.halfWidth

        val closestX = eyeLoc.x.coerceIn(minX, maxX)
        val closestY = eyeLoc.y.coerceIn(minY, maxY)
        val closestZ = eyeLoc.z.coerceIn(minZ, maxZ)

        val dx = eyeLoc.x - closestX
        val dy = eyeLoc.y - closestY
        val dz = eyeLoc.z - closestZ
        val distanceSquared = dx * dx + dy * dy + dz * dz

        if (distanceSquared > maximumReachDistance * maximumReachDistance) {
            return false
        }

        val targetVec = Vector(closestX, closestY, closestZ)
        val direction = targetVec.subtract(eyeLoc.toVector())
        val distance = direction.length()
        if (distance > 1e-4) {
            val maxDistance = (distance - 1e-3).coerceAtLeast(0.0)
            if (maxDistance > 1e-4) {
                direction.normalize()
                val rayTrace = player.world.rayTraceBlocks(
                    eyeLoc,
                    direction,
                    maxDistance,
                    FluidCollisionMode.NEVER,
                    true
                )
                if (rayTrace != null && rayTrace.hitBlock != null) {
                    return false
                }
            }
        }

        return true
    }
}