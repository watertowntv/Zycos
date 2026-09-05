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
    internal val rawProjectileIds: IntArray,
    internal val rawPositionX: DoubleArray,
    internal val rawPositionY: DoubleArray,
    internal val rawPositionZ: DoubleArray,
    internal val rawVelocityX: DoubleArray,
    internal val rawVelocityY: DoubleArray,
    internal val rawVelocityZ: DoubleArray,
    internal val rawPresentationIds: IntArray,
    internal val rawAgeTicks: IntArray,
    internal val rawTravelledDistance: DoubleArray
) {
    val size: Int
        get() = rawProjectileIds.size

    val projectileIds: IntArray
        get() = rawProjectileIds.clone()

    val positionX: DoubleArray
        get() = rawPositionX.clone()

    val positionY: DoubleArray
        get() = rawPositionY.clone()

    val positionZ: DoubleArray
        get() = rawPositionZ.clone()

    val velocityX: DoubleArray
        get() = rawVelocityX.clone()

    val velocityY: DoubleArray
        get() = rawVelocityY.clone()

    val velocityZ: DoubleArray
        get() = rawVelocityZ.clone()

    val presentationIds: IntArray
        get() = rawPresentationIds.clone()

    val ageTicks: IntArray
        get() = rawAgeTicks.clone()

    val travelledDistance: DoubleArray
        get() = rawTravelledDistance.clone()

    init {
        require(rawPositionX.size == size)
        require(rawPositionY.size == size)
        require(rawPositionZ.size == size)
        require(rawVelocityX.size == size)
        require(rawVelocityY.size == size)
        require(rawVelocityZ.size == size)
        require(rawPresentationIds.size == size)
        require(rawAgeTicks.size == size)
        require(rawTravelledDistance.size == size)
    }

    fun rawProjectileIdAt(index: Int): Int {
        require(index in rawProjectileIds.indices)
        return rawProjectileIds[index]
    }

    fun projectileIdAt(index: Int): SimulatedProjectileId {
        require(index in rawProjectileIds.indices)
        return SimulatedProjectileId(
            rawProjectileIds[index]
        )
    }

    fun positionXAt(index: Int): Double {
        require(index in rawProjectileIds.indices)
        return rawPositionX[index]
    }

    fun positionYAt(index: Int): Double {
        require(index in rawProjectileIds.indices)
        return rawPositionY[index]
    }

    fun positionZAt(index: Int): Double {
        require(index in rawProjectileIds.indices)
        return rawPositionZ[index]
    }

    fun positionAt(index: Int): SimulatedVector3 {
        require(index in rawProjectileIds.indices)
        return SimulatedVector3(
            rawPositionX[index],
            rawPositionY[index],
            rawPositionZ[index]
        )
    }

    fun velocityXAt(index: Int): Double {
        require(index in rawProjectileIds.indices)
        return rawVelocityX[index]
    }

    fun velocityYAt(index: Int): Double {
        require(index in rawProjectileIds.indices)
        return rawVelocityY[index]
    }

    fun velocityZAt(index: Int): Double {
        require(index in rawProjectileIds.indices)
        return rawVelocityZ[index]
    }

    fun velocityAt(index: Int): SimulatedVector3 {
        require(index in rawProjectileIds.indices)
        return SimulatedVector3(
            rawVelocityX[index],
            rawVelocityY[index],
            rawVelocityZ[index]
        )
    }

    fun presentationIdAt(index: Int) =
        SimulatedPresentationId(
            rawPresentationIds[index]
        )

    fun ageTicksAt(index: Int): Int {
        require(index in rawProjectileIds.indices)
        return rawAgeTicks[index]
    }

    fun travelledDistanceAt(index: Int): Double {
        require(index in rawProjectileIds.indices)
        return rawTravelledDistance[index]
    }

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
                rawProjectileIds = IntArray(0),
                rawPositionX = DoubleArray(0),
                rawPositionY = DoubleArray(0),
                rawPositionZ = DoubleArray(0),
                rawVelocityX = DoubleArray(0),
                rawVelocityY = DoubleArray(0),
                rawVelocityZ = DoubleArray(0),
                rawPresentationIds = IntArray(0),
                rawAgeTicks = IntArray(0),
                rawTravelledDistance = DoubleArray(0)
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
