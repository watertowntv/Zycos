@file:Suppress("unused")

package zaqws.zycos.simulated.projectile

import zaqws.zycos.simulated.entity.SimulatedEntityId
import zaqws.zycos.simulated.entity.SimulatedPresentationId
import zaqws.zycos.simulated.entity.SimulatedTeam
import zaqws.zycos.simulated.external.SimulatedExternalActorId
import zaqws.zycos.simulated.math.SimulatedVector3
import java.util.concurrent.atomic.AtomicReference

@JvmInline
value class SimulatedProjectileId(
    val value: Int
) {
    init {
        require(value > 0)
    }

    override fun toString() = value.toString()
}

sealed interface SimulatedProjectileSource {
    data object None : SimulatedProjectileSource

    data class Entity(
        val entityId: SimulatedEntityId
    ) : SimulatedProjectileSource

    data class ExternalActor(
        val actorId: SimulatedExternalActorId
    ) : SimulatedProjectileSource
}

enum class SimulatedProjectileTargetPolicy {
    ENEMIES,
    ALL_EXCEPT_SOURCE
}

data class SimulatedProjectileDefinition(
    val radius: Double = DEFAULT_RADIUS,
    val gravityPerTick: Double = DEFAULT_GRAVITY_PER_TICK,
    val drag: Double = DEFAULT_DRAG,
    val maximumTicks: Int = DEFAULT_MAXIMUM_TICKS,
    val maximumRange: Double = DEFAULT_MAXIMUM_RANGE,
    val damage: Double = DEFAULT_DAMAGE,
    val knockbackStrength: Double = DEFAULT_KNOCKBACK_STRENGTH,
    val targetPolicy: SimulatedProjectileTargetPolicy =
        SimulatedProjectileTargetPolicy.ENEMIES,
    val presentationId: SimulatedPresentationId =
        SimulatedPresentationId.NONE
) {
    init {
        require(radius.isFinite() && radius > 0.0)
        require(gravityPerTick.isFinite())
        require(drag.isFinite() && drag in 0.0..1.0)
        require(maximumTicks > 0)
        require(maximumRange.isFinite() && maximumRange > 0.0)
        require(damage.isFinite() && damage >= 0.0)
        require(knockbackStrength.isFinite() && knockbackStrength >= 0.0)
    }

    companion object {
        const val DEFAULT_RADIUS = 0.125
        const val DEFAULT_GRAVITY_PER_TICK = 0.0
        const val DEFAULT_DRAG = 1.0
        const val DEFAULT_MAXIMUM_TICKS = 100
        const val DEFAULT_MAXIMUM_RANGE = 64.0
        const val DEFAULT_DAMAGE = 2.0
        const val DEFAULT_KNOCKBACK_STRENGTH = 0.0

        val DEFAULT = SimulatedProjectileDefinition()
    }
}

class SimulatedProjectileBuilder internal constructor() {
    private var position = SimulatedVector3.ZERO
    private var velocity = SimulatedVector3.ZERO
    private var source: SimulatedProjectileSource =
        SimulatedProjectileSource.None
    private var team = SimulatedTeam.NONE
    private var definition = SimulatedProjectileDefinition.DEFAULT

    fun position(position: SimulatedVector3) {
        require(position.isFinite)
        this.position = position
    }

    fun position(
        x: Double,
        y: Double,
        z: Double
    ) {
        position(
            SimulatedVector3(
                x,
                y,
                z
            )
        )
    }

    fun velocity(velocity: SimulatedVector3) {
        require(velocity.isFinite)
        this.velocity = velocity
    }

    fun velocity(
        x: Double,
        y: Double,
        z: Double
    ) {
        velocity(
            SimulatedVector3(
                x,
                y,
                z
            )
        )
    }

    fun source(source: SimulatedProjectileSource) {
        this.source = source
    }

    fun source(entityId: SimulatedEntityId) {
        source(
            SimulatedProjectileSource.Entity(
                entityId
            )
        )
    }

    fun source(actorId: SimulatedExternalActorId) {
        source(
            SimulatedProjectileSource.ExternalActor(
                actorId
            )
        )
    }

    fun team(team: SimulatedTeam) {
        this.team = team
    }

    fun definition(definition: SimulatedProjectileDefinition) {
        this.definition = definition
    }

    internal fun build() =
        SimulatedProjectileSpawnData(
            position = position,
            velocity = velocity,
            source = source,
            team = team,
            definition = definition
        )
}

internal data class SimulatedProjectileSpawnData(
    val position: SimulatedVector3,
    val velocity: SimulatedVector3,
    val source: SimulatedProjectileSource,
    val team: SimulatedTeam,
    val definition: SimulatedProjectileDefinition
)

data class SimulatedProjectileSnapshot(
    val projectileId: SimulatedProjectileId,
    val position: SimulatedVector3,
    val velocity: SimulatedVector3,
    val source: SimulatedProjectileSource,
    val team: SimulatedTeam,
    val definition: SimulatedProjectileDefinition,
    val ageTicks: Int,
    val travelledDistance: Double
)

class SimulatedProjectile internal constructor(
    val projectileId: SimulatedProjectileId,
    private val controller: SimulatedProjectileController
) {
    val exists: Boolean
        get() = controller.exists(projectileId)

    fun snapshot(): SimulatedProjectileSnapshot? =
        controller.snapshot(projectileId)

    fun remove() {
        controller.remove(projectileId)
    }

    fun teleport(position: SimulatedVector3) {
        require(position.isFinite)
        controller.teleport(
            projectileId,
            position
        )
    }

    fun setVelocity(velocity: SimulatedVector3) {
        require(velocity.isFinite)
        controller.setVelocity(
            projectileId,
            velocity
        )
    }

    fun addVelocity(velocity: SimulatedVector3) {
        require(velocity.isFinite)
        controller.addVelocity(
            projectileId,
            velocity
        )
    }

    override fun equals(other: Any?): Boolean =
        other is SimulatedProjectile &&
                other.projectileId == projectileId &&
                other.controller === controller

    override fun hashCode(): Int =
        31 * System.identityHashCode(controller) +
                projectileId.hashCode()

    override fun toString() =
        "SimulatedProjectile(projectileId=$projectileId)"
}

internal interface SimulatedProjectileController {
    fun exists(projectileId: SimulatedProjectileId): Boolean

    fun snapshot(
        projectileId: SimulatedProjectileId
    ): SimulatedProjectileSnapshot?

    fun remove(projectileId: SimulatedProjectileId)

    fun teleport(
        projectileId: SimulatedProjectileId,
        position: SimulatedVector3
    )

    fun setVelocity(
        projectileId: SimulatedProjectileId,
        velocity: SimulatedVector3
    )

    fun addVelocity(
        projectileId: SimulatedProjectileId,
        velocity: SimulatedVector3
    )
}

enum class SimulatedProjectileRemovalReason {
    REMOVED,
    BLOCK_HIT,
    ENTITY_HIT,
    EXTERNAL_ACTOR_HIT,
    MAXIMUM_TICKS,
    MAXIMUM_RANGE
}

sealed interface SimulatedProjectileEvent {
    val tick: Long
    val projectileId: SimulatedProjectileId

    data class Spawn(
        override val tick: Long,
        override val projectileId: SimulatedProjectileId
    ) : SimulatedProjectileEvent

    data class BlockHit(
        override val tick: Long,
        override val projectileId: SimulatedProjectileId,
        val position: SimulatedVector3
    ) : SimulatedProjectileEvent

    data class EntityHit(
        override val tick: Long,
        override val projectileId: SimulatedProjectileId,
        val entityId: SimulatedEntityId,
        val position: SimulatedVector3
    ) : SimulatedProjectileEvent

    data class ExternalActorHit(
        override val tick: Long,
        override val projectileId: SimulatedProjectileId,
        val actorId: SimulatedExternalActorId,
        val position: SimulatedVector3
    ) : SimulatedProjectileEvent

    data class Remove(
        override val tick: Long,
        override val projectileId: SimulatedProjectileId,
        val reason: SimulatedProjectileRemovalReason
    ) : SimulatedProjectileEvent
}

class SimulatedProjectileFrame internal constructor(
    val tick: Long,
    val projectileIds: IntArray,
    val positionX: DoubleArray,
    val positionY: DoubleArray,
    val positionZ: DoubleArray,
    val velocityX: DoubleArray,
    val velocityY: DoubleArray,
    val velocityZ: DoubleArray,
    val presentationIds: IntArray,
    val ageTicks: IntArray,
    val travelledDistance: DoubleArray
) {
    val size: Int
        get() = projectileIds.size

    init {
        require(positionX.size == size)
        require(positionY.size == size)
        require(positionZ.size == size)
        require(velocityX.size == size)
        require(velocityY.size == size)
        require(velocityZ.size == size)
        require(presentationIds.size == size)
        require(ageTicks.size == size)
        require(travelledDistance.size == size)
    }

    fun projectileIdAt(index: Int): SimulatedProjectileId {
        require(index in projectileIds.indices)
        return SimulatedProjectileId(
            projectileIds[index]
        )
    }

    fun positionAt(index: Int): SimulatedVector3 {
        require(index in projectileIds.indices)
        return SimulatedVector3(
            positionX[index],
            positionY[index],
            positionZ[index]
        )
    }

    fun velocityAt(index: Int): SimulatedVector3 {
        require(index in projectileIds.indices)
        return SimulatedVector3(
            velocityX[index],
            velocityY[index],
            velocityZ[index]
        )
    }

    fun presentationIdAt(index: Int) =
        SimulatedPresentationId(
            presentationIds[index]
        )

    inline fun forEachIndex(action: (index: Int) -> Unit) {
        var index = 0

        while (index < size) {
            action(index)
            index++
        }
    }

    companion object {
        val EMPTY =
            SimulatedProjectileFrame(
                tick = 0L,
                projectileIds = IntArray(0),
                positionX = DoubleArray(0),
                positionY = DoubleArray(0),
                positionZ = DoubleArray(0),
                velocityX = DoubleArray(0),
                velocityY = DoubleArray(0),
                velocityZ = DoubleArray(0),
                presentationIds = IntArray(0),
                ageTicks = IntArray(0),
                travelledDistance = DoubleArray(0)
            )
    }
}

internal class SimulatedProjectileFramePublisher {
    private val reference =
        AtomicReference(
            SimulatedProjectileFrame.EMPTY
        )

    val latest: SimulatedProjectileFrame
        get() = reference.get()

    fun publish(frame: SimulatedProjectileFrame) {
        reference.set(frame)
    }

    fun clear() {
        reference.set(
            SimulatedProjectileFrame.EMPTY
        )
    }
}
