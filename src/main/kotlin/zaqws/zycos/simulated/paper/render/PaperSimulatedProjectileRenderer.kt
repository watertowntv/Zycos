@file:Suppress("unused")

package zaqws.zycos.simulated.paper.render

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import zaqws.zycos.ClientEntityManager
import zaqws.zycos.simulated.SimulatedEngine
import zaqws.zycos.simulated.entity.SimulatedPresentationId
import zaqws.zycos.simulated.projectile.SimulatedProjectileFrame
import zaqws.zycos.simulated.projectile.SimulatedProjectileId
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.sqrt

class PaperSimulatedProjectileRenderer(
    private val plugin: JavaPlugin,
    private val world: World,
    private val engine: SimulatedEngine,
    private val presentationRegistry: SimulatedPresentationRegistry,
    val showRadius: Double = DEFAULT_SHOW_RADIUS,
    val hideRadius: Double = DEFAULT_HIDE_RADIUS
) : AutoCloseable {
    private data class RenderedProjectile(
        val clientEntity: ClientEntityManager.ClientEntity,
        val presentationId: SimulatedPresentationId
    )

    private val renderedProjectiles =
        Int2ObjectOpenHashMap<RenderedProjectile>()

    private val currentProjectileIds =
        IntOpenHashSet()

    private val activePlayers =
        ArrayList<Player>()

    private val closed =
        AtomicBoolean(false)

    private var scheduledTask:
            BukkitTask? = null

    init {
        require(showRadius.isFinite())
        require(hideRadius.isFinite())
        require(showRadius >= 0.0)
        require(hideRadius >= showRadius)
    }

    val renderedProjectileCount: Int
        get() = renderedProjectiles.size

    val isRunning: Boolean
        get() = scheduledTask != null

    val isClosed: Boolean
        get() = closed.get()

    fun start(): PaperSimulatedProjectileRenderer {
        check(!closed.get()) {
            "PaperSimulatedProjectileRenderer is closed"
        }

        if (scheduledTask != null) {
            return this
        }

        scheduledTask =
            plugin.server.scheduler.runTaskTimer(
                plugin,
                Runnable(::update),
                1L,
                1L
            )

        return this
    }

    fun stop() {
        scheduledTask?.cancel()
        scheduledTask = null
        removeAll()
    }

    fun update() {
        check(!closed.get()) {
            "PaperSimulatedProjectileRenderer is closed"
        }

        check(plugin.server.isPrimaryThread) {
            "PaperSimulatedProjectileRenderer.update must be called from the server thread"
        }

        collectActivePlayers()

        val frame =
            engine.projectileManager
                .latestFrame

        currentProjectileIds.clear()

        var index = 0

        while (index < frame.size) {
            val projectileIdValue =
                frame.projectileIds[index]

            currentProjectileIds.add(
                projectileIdValue
            )

            updateProjectile(
                frame,
                index,
                projectileIdValue
            )

            index++
        }

        removeStaleProjectiles()
    }

    fun clientEntity(
        projectileId: SimulatedProjectileId
    ): ClientEntityManager.ClientEntity? =
        renderedProjectiles[
            projectileId.value
        ]?.clientEntity

    fun isRendered(
        projectileId: SimulatedProjectileId
    ): Boolean =
        renderedProjectiles.containsKey(
            projectileId.value
        )

    fun remove(
        projectileId: SimulatedProjectileId
    ) {
        removeRenderedProjectile(
            projectileId.value
        )
    }

    fun removeAll() {
        val iterator =
            renderedProjectiles
                .values
                .iterator()

        while (iterator.hasNext()) {
            iterator.next()
                .clientEntity
                .remove()
        }

        renderedProjectiles.clear()
        currentProjectileIds.clear()
        activePlayers.clear()
    }

    private fun updateProjectile(
        frame: SimulatedProjectileFrame,
        index: Int,
        projectileIdValue: Int
    ) {
        val presentationId =
            frame.presentationIdAt(index)

        if (
            presentationId ==
            SimulatedPresentationId.NONE
        ) {
            removeRenderedProjectile(
                projectileIdValue
            )

            return
        }

        val presentation =
            presentationRegistry[
                presentationId
            ]

        if (presentation == null) {
            removeRenderedProjectile(
                projectileIdValue
            )

            return
        }

        val positionX = frame.positionX[index]
        val positionY = frame.positionY[index]
        val positionZ = frame.positionZ[index]

        var renderedProjectile =
            renderedProjectiles[
                projectileIdValue
            ]

        if (
            renderedProjectile != null &&
            renderedProjectile.presentationId !=
            presentationId
        ) {
            renderedProjectile.clientEntity.remove()
            renderedProjectiles.remove(
                projectileIdValue
            )
            renderedProjectile = null
        }

        if (
            renderedProjectile == null &&
            hasPotentialViewer(
                positionX,
                positionY,
                positionZ
            )
        ) {
            renderedProjectile =
                createRenderedProjectile(
                    frame,
                    index,
                    presentationId,
                    presentation
                )

            renderedProjectiles.put(
                projectileIdValue,
                renderedProjectile
            )
        }

        if (renderedProjectile == null) {
            return
        }

        renderedProjectile.clientEntity.teleport(
            location(
                frame,
                index
            )
        )

        updateViewers(
            renderedProjectile.clientEntity,
            positionX,
            positionY,
            positionZ
        )

        if (
            renderedProjectile.clientEntity
                .viewers.isEmpty()
        ) {
            renderedProjectile.clientEntity.remove()
            renderedProjectiles.remove(
                projectileIdValue
            )
        }
    }

    private fun createRenderedProjectile(
        frame: SimulatedProjectileFrame,
        index: Int,
        presentationId: SimulatedPresentationId,
        presentation: SimulatedPresentation
    ): RenderedProjectile {
        val clientEntity =
            ClientEntityManager.spawn(
                presentation.entityType,
                location(
                    frame,
                    index
                )
            )

        presentation.onCreate(
            clientEntity
        )

        return RenderedProjectile(
            clientEntity,
            presentationId
        )
    }

    private fun collectActivePlayers() {
        activePlayers.clear()

        for (player in plugin.server.onlinePlayers) {
            if (
                player.isOnline &&
                player.world == world
            ) {
                activePlayers.add(player)
            }
        }
    }

    private fun hasPotentialViewer(
        positionX: Double,
        positionY: Double,
        positionZ: Double
    ): Boolean {
        val showRadiusSquared =
            showRadius * showRadius

        for (player in activePlayers) {
            if (
                distanceSquared(
                    player,
                    positionX,
                    positionY,
                    positionZ
                ) <= showRadiusSquared
            ) {
                return true
            }
        }

        return false
    }

    private fun updateViewers(
        clientEntity: ClientEntityManager.ClientEntity,
        positionX: Double,
        positionY: Double,
        positionZ: Double
    ) {
        val showRadiusSquared =
            showRadius * showRadius

        val hideRadiusSquared =
            hideRadius * hideRadius

        for (player in activePlayers) {
            val distanceSquared =
                distanceSquared(
                    player,
                    positionX,
                    positionY,
                    positionZ
                )

            if (player in clientEntity.viewers) {
                if (
                    distanceSquared >
                    hideRadiusSquared
                ) {
                    clientEntity.hide(player)
                }
            } else if (
                distanceSquared <=
                showRadiusSquared
            ) {
                clientEntity.show(player)
            }
        }

        val viewers =
            clientEntity.viewers
                .toList()

        for (player in viewers) {
            if (
                !player.isOnline ||
                player.world != world
            ) {
                clientEntity.hide(player)
            }
        }
    }

    private fun distanceSquared(
        player: Player,
        positionX: Double,
        positionY: Double,
        positionZ: Double
    ): Double {
        val location = player.location
        val differenceX = location.x - positionX
        val differenceY = location.y - positionY
        val differenceZ = location.z - positionZ

        return differenceX * differenceX +
                differenceY * differenceY +
                differenceZ * differenceZ
    }

    private fun location(
        frame: SimulatedProjectileFrame,
        index: Int
    ): Location {
        val velocityX = frame.velocityX[index]
        val velocityY = frame.velocityY[index]
        val velocityZ = frame.velocityZ[index]

        val horizontalLength =
            sqrt(
                velocityX * velocityX +
                        velocityZ * velocityZ
            )

        val yaw =
            if (
                horizontalLength == 0.0 &&
                velocityY == 0.0
            ) {
                0.0f
            } else {
                (
                        atan2(
                            -velocityX,
                            velocityZ
                        ) * 180.0 / PI
                        ).toFloat()
            }

        val pitch =
            if (
                horizontalLength == 0.0 &&
                velocityY == 0.0
            ) {
                0.0f
            } else {
                (
                        -atan2(
                            velocityY,
                            horizontalLength
                        ) * 180.0 / PI
                        ).toFloat()
            }

        return Location(
            world,
            frame.positionX[index],
            frame.positionY[index],
            frame.positionZ[index],
            yaw,
            pitch
        )
    }

    private fun removeStaleProjectiles() {
        val iterator =
            renderedProjectiles
                .int2ObjectEntrySet()
                .fastIterator()

        while (iterator.hasNext()) {
            val entry = iterator.next()

            if (
                currentProjectileIds.contains(
                    entry.intKey
                )
            ) {
                continue
            }

            entry.value.clientEntity.remove()
            iterator.remove()
        }
    }

    private fun removeRenderedProjectile(
        projectileIdValue: Int
    ) {
        val renderedProjectile =
            renderedProjectiles.remove(
                projectileIdValue
            ) ?: return

        renderedProjectile.clientEntity.remove()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }

        scheduledTask?.cancel()
        scheduledTask = null
        removeAll()
    }

    companion object {
        const val DEFAULT_SHOW_RADIUS = 96.0
        const val DEFAULT_HIDE_RADIUS = 106.0
    }
}
