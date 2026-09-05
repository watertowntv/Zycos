@file:Suppress("unused")

package zaqws.zycos.simulated.projectile

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import zaqws.zycos.simulated.combat.SimulatedDamage
import zaqws.zycos.simulated.combat.SimulatedRelationResolver
import zaqws.zycos.simulated.command.SimulatedCommand
import zaqws.zycos.simulated.entity.SimulatedEntityId
import zaqws.zycos.simulated.entity.SimulatedEntityStore
import zaqws.zycos.simulated.entity.SimulatedTeam
import zaqws.zycos.simulated.external.SimulatedExternalAction
import zaqws.zycos.simulated.external.SimulatedExternalFrame
import zaqws.zycos.simulated.map.SimulatedMap
import zaqws.zycos.simulated.math.SimulatedMath
import zaqws.zycos.simulated.math.SimulatedVector3
import zaqws.zycos.simulated.spatial.SimulatedSpatialIndex
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class SimulatedProjectileManager internal constructor(
    initialCapacity: Int,
    maximumQueuedEvents: Int,
    private val map: SimulatedMap,
    private val entityStore: SimulatedEntityStore,
    private val spatialIndex: SimulatedSpatialIndex,
    private val commandConsumer: (SimulatedCommand) -> Unit,
    private val damageConsumer: (SimulatedDamage, Long) -> Double,
    private val externalActionConsumer: (SimulatedExternalAction) -> Unit,
    private val relationResolver: SimulatedRelationResolver =
        SimulatedRelationResolver.DEFAULT
) : SimulatedProjectileController {
    private data class State(
        val projectileId: SimulatedProjectileId,
        var position: SimulatedVector3,
        var velocity: SimulatedVector3,
        val source: SimulatedProjectileSource,
        val team: SimulatedTeam,
        val definition: SimulatedProjectileDefinition,
        val spawnTick: Long,
        var ageTicks: Int = 0,
        var travelledDistance: Double = 0.0
    )

    private data class Definition(
        val source: SimulatedProjectileSource,
        val team: SimulatedTeam,
        val definition: SimulatedProjectileDefinition
    )

    private val states =
        ArrayList<State>(
            initialCapacity
        )

    private val projectileIdToSlot =
        Int2IntOpenHashMap(
            initialCapacity
        ).apply {
            defaultReturnValue(INVALID_SLOT)
        }

    private val reservedProjectileId =
        AtomicInteger(1)

    private val knownProjectileIds =
        ConcurrentHashMap.newKeySet<Int>()

    private val definitions =
        ConcurrentHashMap<Int, Definition>()

    private val events =
        ArrayBlockingQueue<SimulatedProjectileEvent>(
            maximumQueuedEvents
        )

    private val framePublisher =
        SimulatedProjectileFramePublisher()

    private val closed =
        AtomicBoolean(false)

    private val spawnLock = Any()

    private val collisionResolver =
        SimulatedProjectileCollisionResolver(
            map,
            entityStore,
            spatialIndex,
            relationResolver
        )

    val latestFrame: SimulatedProjectileFrame
        get() = framePublisher.latest

    val size: Int
        get() = latestFrame.size

    init {
        require(initialCapacity > 0)
        require(maximumQueuedEvents > 0)
    }

    fun spawn(
        block: SimulatedProjectileBuilder.() -> Unit
    ): SimulatedProjectile {
        val data =
            SimulatedProjectileBuilder()
                .apply(block)
                .build()

        return synchronized(spawnLock) {
            check(!closed.get()) {
                "SimulatedProjectileManager is closed"
            }

            val projectileId =
                allocateProjectileId()

            knownProjectileIds.add(
                projectileId.value
            )

            definitions[projectileId.value] =
                Definition(
                    source = data.source,
                    team = data.team,
                    definition = data.definition
                )

            commandConsumer(
                SimulatedCommand.SpawnProjectile(
                    projectileId = projectileId,
                    data = data
                )
            )

            SimulatedProjectile(
                projectileId,
                this
            )
        }
    }

    fun getProjectile(
        projectileId: SimulatedProjectileId
    ): SimulatedProjectile? =
        if (exists(projectileId)) {
            SimulatedProjectile(
                projectileId,
                this
            )
        } else {
            null
        }

    fun drainEvents(
        maximumEvents: Int = Int.MAX_VALUE
    ): List<SimulatedProjectileEvent> {
        require(maximumEvents >= 0)

        val result =
            ArrayList<SimulatedProjectileEvent>()

        var drained = 0

        while (drained < maximumEvents) {
            result.add(
                events.poll() ?: break
            )

            drained++
        }

        return result
    }

    override fun exists(
        projectileId: SimulatedProjectileId
    ): Boolean =
        knownProjectileIds.contains(
            projectileId.value
        )

    override fun snapshot(
        projectileId: SimulatedProjectileId
    ): SimulatedProjectileSnapshot? {
        if (!exists(projectileId)) {
            return null
        }

        val definition =
            definitions[projectileId.value]
                ?: return null

        val frame = latestFrame
        var index = 0

        while (index < frame.size) {
            if (
                frame.projectileIds[index] ==
                projectileId.value
            ) {
                return SimulatedProjectileSnapshot(
                    projectileId = projectileId,
                    position = frame.positionAt(index),
                    velocity = frame.velocityAt(index),
                    source = definition.source,
                    team = definition.team,
                    definition = definition.definition,
                    ageTicks = frame.ageTicks[index],
                    travelledDistance =
                        frame.travelledDistance[index]
                )
            }

            index++
        }

        return null
    }

    override fun remove(
        projectileId: SimulatedProjectileId
    ) {
        if (
            !knownProjectileIds.remove(
                projectileId.value
            )
        ) {
            return
        }

        definitions.remove(
            projectileId.value
        )

        commandConsumer(
            SimulatedCommand.RemoveProjectile(
                projectileId
            )
        )
    }

    override fun teleport(
        projectileId: SimulatedProjectileId,
        position: SimulatedVector3
    ) {
        if (!exists(projectileId)) return

        commandConsumer(
            SimulatedCommand.TeleportProjectile(
                projectileId,
                position
            )
        )
    }

    override fun setVelocity(
        projectileId: SimulatedProjectileId,
        velocity: SimulatedVector3
    ) {
        if (!exists(projectileId)) return

        commandConsumer(
            SimulatedCommand.SetProjectileVelocity(
                projectileId,
                velocity
            )
        )
    }

    override fun addVelocity(
        projectileId: SimulatedProjectileId,
        velocity: SimulatedVector3
    ) {
        if (!exists(projectileId)) return

        commandConsumer(
            SimulatedCommand.AddProjectileVelocity(
                projectileId,
                velocity
            )
        )
    }

    internal fun spawnNow(
        data: SimulatedProjectileSpawnData,
        tick: Long
    ): SimulatedProjectileId {
        val projectileId =
            allocateProjectileId()

        knownProjectileIds.add(
            projectileId.value
        )

        definitions[projectileId.value] =
            Definition(
                data.source,
                data.team,
                data.definition
            )

        processSpawn(
            projectileId,
            data,
            tick
        )

        return projectileId
    }

    internal fun processSpawn(
        projectileId: SimulatedProjectileId,
        data: SimulatedProjectileSpawnData,
        tick: Long
    ) {
        if (
            projectileIdToSlot.get(
                projectileId.value
            ) != INVALID_SLOT
        ) {
            return
        }

        val slot = states.size

        states.add(
            State(
                projectileId = projectileId,
                position = data.position,
                velocity = data.velocity,
                source = data.source,
                team = data.team,
                definition = data.definition,
                spawnTick = tick
            )
        )

        projectileIdToSlot.put(
            projectileId.value,
            slot
        )

        offerEvent(
            SimulatedProjectileEvent.Spawn(
                tick,
                projectileId
            )
        )
    }

    internal fun processRemove(
        projectileId: SimulatedProjectileId,
        tick: Long
    ) {
        removeState(
            projectileId,
            tick,
            SimulatedProjectileRemovalReason.REMOVED
        )
    }

    internal fun processTeleport(
        projectileId: SimulatedProjectileId,
        position: SimulatedVector3
    ) {
        val slot =
            projectileIdToSlot.get(
                projectileId.value
            )

        if (slot != INVALID_SLOT) {
            states[slot].position = position
        }
    }

    internal fun processSetVelocity(
        projectileId: SimulatedProjectileId,
        velocity: SimulatedVector3
    ) {
        val slot =
            projectileIdToSlot.get(
                projectileId.value
            )

        if (slot != INVALID_SLOT) {
            states[slot].velocity = velocity
        }
    }

    internal fun processAddVelocity(
        projectileId: SimulatedProjectileId,
        velocity: SimulatedVector3
    ) {
        val slot =
            projectileIdToSlot.get(
                projectileId.value
            )

        if (slot != INVALID_SLOT) {
            states[slot].velocity =
                states[slot].velocity +
                        velocity
        }
    }

    internal fun update(
        tick: Long,
        externalFrame: SimulatedExternalFrame
    ) {
        var slot = 0

        while (slot < states.size) {
            val state = states[slot]

            if (state.spawnTick == tick) {
                slot++
                continue
            }

            state.ageTicks++

            if (
                state.ageTicks >
                state.definition.maximumTicks
            ) {
                removeStateAt(
                    slot,
                    tick,
                    SimulatedProjectileRemovalReason.MAXIMUM_TICKS
                )
                continue
            }

            val start = state.position
            val remainingRange = maxOf(
                0.0,
                state.definition.maximumRange -
                        state.travelledDistance
            )

            if (
                remainingRange <=
                SimulatedMath.EPSILON
            ) {
                removeStateAt(
                    slot,
                    tick,
                    SimulatedProjectileRemovalReason.MAXIMUM_RANGE
                )
                continue
            }

            val velocityLength =
                state.velocity.length
            val distance =
                minOf(
                    velocityLength,
                    remainingRange
                )
            val end =
                if (
                    velocityLength >
                    SimulatedMath.EPSILON
                ) {
                    start +
                            state.velocity *
                            (distance / velocityLength)
                } else {
                    start
                }
            val movement =
                end - start
            val hit =
                collisionResolver.find(
                    state.source,
                    state.team,
                    state.definition,
                    start,
                    end,
                    externalFrame
                )

            if (hit != null) {
                val hitPosition =
                    start +
                            movement * hit.fraction

                state.position = hitPosition
                state.travelledDistance +=
                    distance * hit.fraction

                applyHit(
                    state,
                    hit,
                    hitPosition,
                    tick
                )

                removeStateAt(
                    slot,
                    tick,
                    when (hit) {
                        is SimulatedProjectileHit.Block ->
                            SimulatedProjectileRemovalReason.BLOCK_HIT

                        is SimulatedProjectileHit.Entity ->
                            SimulatedProjectileRemovalReason.ENTITY_HIT

                        is SimulatedProjectileHit.ExternalActor ->
                            SimulatedProjectileRemovalReason.EXTERNAL_ACTOR_HIT
                    }
                )

                continue
            }

            state.position = end
            state.travelledDistance += distance

            if (
                state.travelledDistance >=
                state.definition.maximumRange
            ) {
                removeStateAt(
                    slot,
                    tick,
                    SimulatedProjectileRemovalReason.MAXIMUM_RANGE
                )
                continue
            }

            val definition = state.definition

            state.velocity =
                SimulatedVector3(
                    state.velocity.x,
                    state.velocity.y +
                            definition.gravityPerTick,
                    state.velocity.z
                ) * definition.drag

            slot++
        }
    }

    internal fun publishFrame(tick: Long) {
        val size = states.size
        val projectileIds = IntArray(size)
        val positionX = DoubleArray(size)
        val positionY = DoubleArray(size)
        val positionZ = DoubleArray(size)
        val velocityX = DoubleArray(size)
        val velocityY = DoubleArray(size)
        val velocityZ = DoubleArray(size)
        val presentationIds = IntArray(size)
        val ageTicks = IntArray(size)
        val travelledDistance = DoubleArray(size)

        var index = 0

        while (index < size) {
            val state = states[index]

            projectileIds[index] =
                state.projectileId.value
            positionX[index] = state.position.x
            positionY[index] = state.position.y
            positionZ[index] = state.position.z
            velocityX[index] = state.velocity.x
            velocityY[index] = state.velocity.y
            velocityZ[index] = state.velocity.z
            presentationIds[index] =
                state.definition.presentationId.value
            ageTicks[index] = state.ageTicks
            travelledDistance[index] =
                state.travelledDistance

            index++
        }

        framePublisher.publish(
            SimulatedProjectileFrame(
                tick = tick,
                rawProjectileIds = projectileIds,
                rawPositionX = positionX,
                rawPositionY = positionY,
                rawPositionZ = positionZ,
                rawVelocityX = velocityX,
                rawVelocityY = velocityY,
                rawVelocityZ = velocityZ,
                rawPresentationIds = presentationIds,
                rawAgeTicks = ageTicks,
                rawTravelledDistance = travelledDistance
            )
        )
    }


    private fun applyHit(
        state: State,
        hit: SimulatedProjectileHit,
        position: SimulatedVector3,
        tick: Long
    ) {
        when (hit) {
            is SimulatedProjectileHit.Block ->
                offerEvent(
                    SimulatedProjectileEvent.BlockHit(
                        tick,
                        state.projectileId,
                        position
                    )
                )

            is SimulatedProjectileHit.Entity -> {
                offerEvent(
                    SimulatedProjectileEvent.EntityHit(
                        tick,
                        state.projectileId,
                        hit.entityId,
                        position
                    )
                )

                val sourceEntityId =
                    (state.source as?
                            SimulatedProjectileSource.Entity)
                        ?.entityId

                val appliedDamage =
                    damageConsumer(
                        SimulatedDamage(
                            targetEntityId =
                                hit.entityId,
                            amount =
                                state.definition.damage,
                            sourceEntityId =
                                sourceEntityId
                        ),
                        tick
                    )

                if (
                    appliedDamage > 0.0 &&
                    state.definition.knockbackStrength > 0.0
                ) {
                    entityStore.addVelocity(
                        hit.slot,
                        knockbackVelocity(state)
                    )
                }
            }

            is SimulatedProjectileHit.ExternalActor -> {
                offerEvent(
                    SimulatedProjectileEvent.ExternalActorHit(
                        tick,
                        state.projectileId,
                        hit.actor.actorId,
                        position
                    )
                )

                val sourceEntityId =
                    (state.source as?
                            SimulatedProjectileSource.Entity)
                        ?.entityId

                if (hit.actor.isDamageable) {
                    val damage =
                        if (state.definition.damage > 0.0) state.definition.damage else 0.0

                    val knockback =
                        if (state.definition.knockbackStrength > 0.0) knockbackVelocity(state) else null

                    if (damage > 0.0 || knockback != null) {
                        externalActionConsumer(
                            SimulatedExternalAction.Combined(
                                actorId = hit.actor.actorId,
                                damage = damage,
                                knockbackVelocity = knockback,
                                sourceEntityId = sourceEntityId
                            )
                        )
                    }
                }
            }
        }
    }

    private fun knockbackVelocity(
        state: State
    ): SimulatedVector3 {
        val horizontalDirection =
            state.velocity
                .normalizedHorizontal()

        val strength =
            state.definition
                .knockbackStrength

        return SimulatedVector3(
            horizontalDirection.x * strength,
            strength * VERTICAL_KNOCKBACK_MULTIPLIER,
            horizontalDirection.z * strength
        )
    }

    private fun removeState(
        projectileId: SimulatedProjectileId,
        tick: Long,
        reason: SimulatedProjectileRemovalReason
    ) {
        val slot =
            projectileIdToSlot.get(
                projectileId.value
            )

        if (slot != INVALID_SLOT) {
            removeStateAt(
                slot,
                tick,
                reason
            )
        }
    }

    private fun removeStateAt(
        slot: Int,
        tick: Long,
        reason: SimulatedProjectileRemovalReason
    ) {
        val removedState = states[slot]
        val lastSlot = states.lastIndex

        projectileIdToSlot.remove(
            removedState.projectileId.value
        )

        if (slot != lastSlot) {
            val movedState = states[lastSlot]
            states[slot] = movedState
            projectileIdToSlot.put(
                movedState.projectileId.value,
                slot
            )
        }

        states.removeLast()
        knownProjectileIds.remove(
            removedState.projectileId.value
        )
        definitions.remove(
            removedState.projectileId.value
        )

        offerEvent(
            SimulatedProjectileEvent.Remove(
                tick,
                removedState.projectileId,
                reason
            )
        )
    }

    private fun offerEvent(
        event: SimulatedProjectileEvent
    ) {
        while (!events.offer(event)) {
            events.poll()
        }
    }

    private fun allocateProjectileId():
            SimulatedProjectileId {
        val value =
            reservedProjectileId.getAndIncrement()

        check(value > 0) {
            "Simulated projectile identifier space exhausted"
        }

        return SimulatedProjectileId(value)
    }

    fun close() {
        synchronized(spawnLock) {
            if (!closed.compareAndSet(false, true)) return
            knownProjectileIds.clear()
            definitions.clear()
            states.clear()
            projectileIdToSlot.clear()
            events.clear()
            framePublisher.clear()
        }
    }

    companion object {
        private const val INVALID_SLOT = -1
        private const val VERTICAL_KNOCKBACK_MULTIPLIER = 0.5
    }
}
