@file:Suppress("unused")

package zaqws.zycos.simulated

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import kotlinx.coroutines.*
import zaqws.zycos.simulated.command.SimulatedCommand
import zaqws.zycos.simulated.command.SimulatedCommandQueue
import zaqws.zycos.simulated.entity.*
import zaqws.zycos.simulated.combat.SimulatedCombatSystem
import zaqws.zycos.simulated.combat.SimulatedDamage
import zaqws.zycos.simulated.external.SimulatedExternalAction
import zaqws.zycos.simulated.external.SimulatedExternalActionQueue
import zaqws.zycos.simulated.external.SimulatedExternalFrame
import zaqws.zycos.simulated.goal.SimulatedGoalSet
import zaqws.zycos.simulated.goal.SimulatedGoalSystem
import zaqws.zycos.simulated.goal.SimulatedLookSystem
import zaqws.zycos.simulated.map.SimulatedMap
import zaqws.zycos.simulated.math.SimulatedVector3
import zaqws.zycos.simulated.navigation.SimulatedNavigationService
import zaqws.zycos.simulated.navigation.SimulatedNavigationSystem
import zaqws.zycos.simulated.navigation.SimulatedPathFollower
import zaqws.zycos.simulated.navigation.hpa.SimulatedHpaPathfinder
import zaqws.zycos.simulated.physics.SimulatedPhysicsConfig
import zaqws.zycos.simulated.physics.SimulatedPhysicsSystem
import zaqws.zycos.simulated.physics.SimulatedSeparationSystem
import zaqws.zycos.simulated.snapshot.SimulatedEvent
import zaqws.zycos.simulated.snapshot.SimulatedEventQueue
import zaqws.zycos.simulated.snapshot.SimulatedFrame
import zaqws.zycos.simulated.snapshot.SimulatedFramePublisher
import zaqws.zycos.simulated.system.SimulatedSystem
import zaqws.zycos.simulated.system.SimulatedSystemContext
import zaqws.zycos.simulated.system.SimulatedSystemPipeline
import zaqws.zycos.simulated.system.SimulatedInterestSystem
import zaqws.zycos.simulated.system.SimulatedSpatialSystem
import zaqws.zycos.simulated.spatial.SimulatedEntityQuery
import zaqws.zycos.simulated.spatial.SimulatedInterestIndex
import zaqws.zycos.simulated.spatial.SimulatedSpatialIndex
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil
import kotlin.time.Duration.Companion.milliseconds

class SimulatedEngine internal constructor(
    val map: SimulatedMap,
    val config: SimulatedConfig
) : SimulatedEntityController, AutoCloseable {
    companion object {
        private const val NANOS_PER_MILLISECOND =
            1_000_000.0

        private val engineCounter =
            AtomicInteger(1)

        private val navigationThreadCounter =
            AtomicInteger(1)
    }

    private enum class LifecycleState {
        CREATED,
        RUNNING,
        STOPPED,
        CLOSED
    }

    private data class SimulatedEntityDefinition(
        val hitbox: SimulatedHitbox,
        val attributes: SimulatedAttributes
    )

    private val engineNumber = engineCounter.getAndIncrement()
    private val lifecycleLock = Any()
    private val spawnLock = Any()

    private val lifecycleState = AtomicReference(LifecycleState.CREATED)
    private val failureReference = AtomicReference<Throwable?>(null)
    private val tickCounter = AtomicLong(0L)
    private val measuredTickCount = AtomicLong(0L)
    private val totalTickNanoseconds = AtomicLong(0L)
    private val latestTickNanoseconds = AtomicLong(0L)
    private val maximumTickNanoseconds = AtomicLong(0L)

    private val reservedEntityId = AtomicInteger(1)
    private val knownEntityIds = ConcurrentHashMap.newKeySet<Int>()
    private val entityDefinitions = ConcurrentHashMap<Int, SimulatedEntityDefinition>()

    private val commandQueue = SimulatedCommandQueue()
    private val framePublisher = SimulatedFramePublisher()
    private val eventQueue =
        SimulatedEventQueue(
            config.maximumQueuedEvents
        )

    private val visualEventQueue =
        SimulatedEventQueue(
            config.maximumQueuedEvents
        )

    private val externalActionQueue =
        SimulatedExternalActionQueue(
            config.maximumQueuedExternalActions
        )

    private val pendingExternalFrame =
        AtomicReference(
            SimulatedExternalFrame.EMPTY
        )

    private var currentExternalFrame =
        SimulatedExternalFrame.EMPTY

    internal val entityStore = SimulatedEntityStore(
        config.initialEntityCapacity
    )

    internal val goalSets = Int2ObjectOpenHashMap<SimulatedGoalSet>()

    private val simulationDispatcher: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(
                runnable,
                "Simulated-$engineNumber-Simulation"
            ).apply {
                isDaemon = true
            }
        }.asCoroutineDispatcher()

    internal val navigationDispatcher: ExecutorCoroutineDispatcher =
        Executors.newFixedThreadPool(
            config.navigationWorkerCount
        ) { runnable ->
            Thread(
                runnable,
                "Simulated-$engineNumber-Navigation-${navigationThreadCounter.getAndIncrement()}"
            ).apply {
                isDaemon = true
            }
        }.asCoroutineDispatcher()

    private val physicsConfig =
        SimulatedPhysicsConfig(
            gravityPerTick =
                config.gravityPerTick,
            airDrag =
                config.airDrag,
            groundFriction =
                config.groundFriction,
            jumpVelocity =
                config.jumpVelocity,
            entityMass =
                config.entityMass
        )

    private val spatialIndex =
        SimulatedSpatialIndex(
            config.spatialCellSize
        )

    private val entityQuery =
        SimulatedEntityQuery(
            entityStore,
            spatialIndex
        )

    private val interestIndex =
        SimulatedInterestIndex(
            config.fullSimulationRadius
        )

    private val goalSystem =
        SimulatedGoalSystem(
            entityStore,
            entityQuery,
            ::goalSet,
            ::externalFrame
        )

    private val pathFollower =
        SimulatedPathFollower(
            entityStore,
            physicsConfig
        )

    private val navigationService =
        SimulatedNavigationService(
            map = map,
            pathfinder =
                SimulatedHpaPathfinder(),
            workerCount =
                config.navigationWorkerCount,
            dispatcher =
                navigationDispatcher
        )

    private val navigationSystem =
        SimulatedNavigationSystem(
            entityStore = entityStore,
            map = map,
            goalSystem = goalSystem,
            navigationService =
                navigationService,
            pathFollower =
                pathFollower,
            config = config
        )

    private val combatSystem =
        SimulatedCombatSystem(
            entityStore,
            goalSystem,
            ::offerEvent,
            ::externalFrame,
            externalActionQueue::offer
        )

    private val physicsSystem =
        SimulatedPhysicsSystem(
            entityStore,
            map,
            physicsConfig
        ) { entityId, damage, tick ->
            combatSystem.damage(
                SimulatedDamage(
                    targetEntityId =
                        entityId,
                    amount = damage
                ),
                tick
            )
        }

    private val systemPipeline =
        SimulatedSystemPipeline(
            listOf(
                SimulatedSpatialSystem(
                    entityStore,
                    spatialIndex
                ),
                SimulatedInterestSystem(
                    entityStore,
                    interestIndex,
                    ::externalFrame
                ),
                goalSystem,
                navigationSystem,
                pathFollower,
                SimulatedLookSystem(
                    entityStore,
                    goalSystem
                ),
                SimulatedSeparationSystem(
                    entityStore = entityStore,
                    spatialIndex = spatialIndex,
                    config = physicsConfig,
                    requireFullSimulation = false
                ),
                physicsSystem,
                combatSystem
            )
        )

    private val engineJob = SupervisorJob()

    private val simulationScope = CoroutineScope(
        engineJob +
                simulationDispatcher +
                CoroutineName("Simulated-$engineNumber")
    )

    @Volatile
    private var simulationJob: Job? = null

    val tick: Long
        get() = tickCounter.get()

    val latestFrame: SimulatedFrame
        get() = framePublisher.latest

    val failure: Throwable?
        get() = failureReference.get()

    val isRunning: Boolean
        get() = lifecycleState.get() == LifecycleState.RUNNING

    val isClosed: Boolean
        get() = lifecycleState.get() == LifecycleState.CLOSED

    val entityCount: Int
        get() = latestFrame.size

    fun timingSnapshot(): SimulatedTimingSnapshot {
        val measuredTicks =
            measuredTickCount.get()

        val totalNanoseconds =
            totalTickNanoseconds.get()

        return SimulatedTimingSnapshot(
            measuredTicks = measuredTicks,
            latestTickNanoseconds =
                latestTickNanoseconds.get(),
            averageTickNanoseconds =
                if (measuredTicks == 0L) {
                    0L
                } else {
                    totalNanoseconds /
                            measuredTicks
                },
            maximumTickNanoseconds =
                maximumTickNanoseconds.get()
        )
    }

    fun start(): SimulatedEngine {
        synchronized(lifecycleLock) {
            when (lifecycleState.get()) {
                LifecycleState.CREATED -> Unit
                LifecycleState.RUNNING -> return this

                LifecycleState.STOPPED ->
                    throw IllegalStateException(
                        "A stopped SimulatedEngine cannot be restarted"
                    )

                LifecycleState.CLOSED ->
                    throw IllegalStateException(
                        "SimulatedEngine is closed"
                    )
            }

            lifecycleState.set(LifecycleState.RUNNING)

            simulationJob = simulationScope.launch {
                try {
                    runSimulationLoop()
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Throwable) {
                    failureReference.compareAndSet(
                        null,
                        exception
                    )

                    throw exception
                } finally {
                    synchronized(lifecycleLock) {
                        if (lifecycleState.get() == LifecycleState.RUNNING) {
                            lifecycleState.set(LifecycleState.STOPPED)
                        }
                    }
                }
            }
        }

        return this
    }

    fun stop() {
        synchronized(lifecycleLock) {
            when (lifecycleState.get()) {
                LifecycleState.CREATED -> {
                    lifecycleState.set(LifecycleState.STOPPED)
                }

                LifecycleState.RUNNING -> {
                    lifecycleState.set(LifecycleState.STOPPED)
                    simulationJob?.cancel()
                }

                LifecycleState.STOPPED,
                LifecycleState.CLOSED -> Unit
            }
        }
    }

    fun spawn(
        block: SimulatedEntityBuilder.() -> Unit
    ): SimulatedEntity {
        check(!isClosed) {
            "SimulatedEngine is closed"
        }

        val data = SimulatedEntityBuilder()
            .apply(block)
            .build()

        return synchronized(spawnLock) {
            val entityId = allocateEntityId()

            knownEntityIds.add(entityId.value)

            entityDefinitions[entityId.value] = SimulatedEntityDefinition(
                hitbox = data.hitbox,
                attributes = data.attributes
            )

            commandQueue.offer(
                SimulatedCommand.Spawn(
                    entityId,
                    data
                )
            )

            SimulatedEntity(
                entityId,
                this
            )
        }
    }

    fun getEntity(
        entityId: SimulatedEntityId
    ): SimulatedEntity? {
        if (!exists(entityId)) return null

        return SimulatedEntity(
            entityId,
            this
        )
    }

    fun drainEvents(
        destination: MutableCollection<SimulatedEvent>,
        maximumEvents: Int = Int.MAX_VALUE
    ): Int =
        eventQueue.drainTo(
            destination,
            maximumEvents
        )

    fun drainEvents(
        maximumEvents: Int = Int.MAX_VALUE
    ): List<SimulatedEvent> {
        require(maximumEvents >= 0)

        val events = ArrayList<SimulatedEvent>()

        eventQueue.drainTo(
            events,
            maximumEvents
        )

        return events
    }

    internal fun drainVisualEvents(
        maximumEvents: Int
    ): List<SimulatedEvent> {
        require(maximumEvents >= 0)

        val events =
            ArrayList<SimulatedEvent>()

        visualEventQueue.drainTo(
            events,
            maximumEvents
        )

        return events
    }

    fun submitExternalFrame(
        frame: SimulatedExternalFrame
    ): Boolean {
        check(!isClosed) {
            "SimulatedEngine is closed"
        }

        while (true) {
            val currentFrame =
                pendingExternalFrame.get()

            if (
                frame.sequence <
                currentFrame.sequence
            ) {
                return false
            }

            if (
                pendingExternalFrame.compareAndSet(
                    currentFrame,
                    frame
                )
            ) {
                return true
            }
        }
    }

    fun drainExternalActions(
        maximumActions: Int = Int.MAX_VALUE
    ): List<SimulatedExternalAction> {
        require(maximumActions >= 0)

        val actions =
            ArrayList<SimulatedExternalAction>()

        externalActionQueue.drainTo(
            actions,
            maximumActions
        )

        return actions
    }

    override fun exists(
        entityId: SimulatedEntityId
    ): Boolean =
        knownEntityIds.contains(entityId.value)

    override fun snapshot(
        entityId: SimulatedEntityId
    ): SimulatedEntitySnapshot? {
        if (!exists(entityId)) return null

        val definition =
            entityDefinitions[entityId.value] ?: return null

        val frame = latestFrame
        val index = findFrameIndex(
            frame,
            entityId
        )

        if (index < 0) return null

        return SimulatedEntitySnapshot(
            entityId = entityId,
            position = frame.positionAt(index),
            velocity = frame.velocityAt(index),
            yaw = frame.yaw[index],
            pitch = frame.pitch[index],
            hitbox = definition.hitbox,
            health = frame.health[index],
            attributes = definition.attributes,
            team = frame.teamAt(index),
            presentationId = frame.presentationIdAt(index),
            flags = frame.flagsAt(index)
        )
    }

    override fun remove(
        entityId: SimulatedEntityId
    ) {
        if (!knownEntityIds.remove(entityId.value)) return

        entityDefinitions.remove(entityId.value)

        commandQueue.offer(
            SimulatedCommand.Remove(entityId)
        )
    }

    override fun teleport(
        entityId: SimulatedEntityId,
        position: SimulatedVector3,
        yaw: Float?,
        pitch: Float?
    ) {
        if (!exists(entityId)) return

        commandQueue.offer(
            SimulatedCommand.Teleport(
                entityId,
                position,
                yaw,
                pitch
            )
        )
    }

    override fun setVelocity(
        entityId: SimulatedEntityId,
        velocity: SimulatedVector3
    ) {
        if (!exists(entityId)) return

        commandQueue.offer(
            SimulatedCommand.SetVelocity(
                entityId,
                velocity
            )
        )
    }

    override fun addVelocity(
        entityId: SimulatedEntityId,
        velocity: SimulatedVector3
    ) {
        if (!exists(entityId)) return

        commandQueue.offer(
            SimulatedCommand.AddVelocity(
                entityId,
                velocity
            )
        )
    }

    override fun damage(
        entityId: SimulatedEntityId,
        amount: Double
    ) {
        if (!exists(entityId)) return

        commandQueue.offer(
            SimulatedCommand.Damage(
                entityId,
                amount
            )
        )
    }

    override fun heal(
        entityId: SimulatedEntityId,
        amount: Double
    ) {
        if (!exists(entityId)) return

        commandQueue.offer(
            SimulatedCommand.Heal(
                entityId,
                amount
            )
        )
    }

    override fun setTeam(
        entityId: SimulatedEntityId,
        team: SimulatedTeam
    ) {
        if (!exists(entityId)) return

        commandQueue.offer(
            SimulatedCommand.SetTeam(
                entityId,
                team
            )
        )
    }

    override fun setPresentation(
        entityId: SimulatedEntityId,
        presentationId: SimulatedPresentationId
    ) {
        if (!exists(entityId)) return

        commandQueue.offer(
            SimulatedCommand.SetPresentation(
                entityId,
                presentationId
            )
        )
    }

    internal fun registerSystem(
        system: SimulatedSystem
    ) {
        synchronized(lifecycleLock) {
            check(lifecycleState.get() == LifecycleState.CREATED) {
                "Systems can only be registered before SimulatedEngine starts"
            }

            systemPipeline.add(system)
        }
    }

    internal fun goalSet(
        entityId: SimulatedEntityId
    ): SimulatedGoalSet? =
        goalSets.get(entityId.value)

    private fun externalFrame():
            SimulatedExternalFrame =
        currentExternalFrame

    private suspend fun runSimulationLoop() {
        val tickDurationNanoseconds =
            config.simulationTickDurationNanoseconds

        var scheduledNanoseconds = System.nanoTime()

        while (simulationScope.isActive) {
            val currentNanoseconds = System.nanoTime()

            if (currentNanoseconds < scheduledNanoseconds) {
                delayUntil(scheduledNanoseconds)
                continue
            }

            var executedTicks = 0

            do {
                runSimulationTick()

                scheduledNanoseconds += tickDurationNanoseconds
                executedTicks++

                if (!simulationScope.isActive) return

                val now = System.nanoTime()

                if (now < scheduledNanoseconds) break

                if (executedTicks > config.maximumCatchUpTicks) {
                    scheduledNanoseconds =
                        now + tickDurationNanoseconds

                    break
                }
            } while (true)
        }
    }

    private fun runSimulationTick() {
        val startNanoseconds =
            System.nanoTime()

        val currentTick = tickCounter.incrementAndGet()

        currentExternalFrame =
            pendingExternalFrame.get()

        processCommands(
            currentTick
        )

        systemPipeline.update(
            SimulatedSystemContext(
                tick = currentTick,
                deltaSeconds =
                    1.0 / config.simulationTicksPerSecond
            )
        )

        publishFrame(
            currentTick
        )

        recordTickDuration(
            System.nanoTime() -
                    startNanoseconds
        )
    }

    private fun recordTickDuration(
        durationNanoseconds: Long
    ) {
        latestTickNanoseconds.set(
            durationNanoseconds
        )

        totalTickNanoseconds.addAndGet(
            durationNanoseconds
        )

        measuredTickCount.incrementAndGet()

        while (true) {
            val previousMaximum =
                maximumTickNanoseconds.get()

            if (
                durationNanoseconds <=
                previousMaximum ||
                maximumTickNanoseconds.compareAndSet(
                    previousMaximum,
                    durationNanoseconds
                )
            ) {
                return
            }
        }
    }

    private fun processCommands(
        currentTick: Long
    ) {
        commandQueue.drain(
            config.maximumCommandsPerTick
        ) { command ->
            when (command) {
                is SimulatedCommand.Spawn ->
                    processSpawn(
                        command,
                        currentTick
                    )

                is SimulatedCommand.Remove ->
                    processRemove(
                        command,
                        currentTick
                    )

                is SimulatedCommand.Teleport ->
                    processTeleport(command)

                is SimulatedCommand.SetVelocity ->
                    processSetVelocity(command)

                is SimulatedCommand.AddVelocity ->
                    processAddVelocity(command)

                is SimulatedCommand.Damage ->
                    processDamage(
                        command,
                        currentTick
                    )

                is SimulatedCommand.Heal ->
                    processHeal(command)

                is SimulatedCommand.SetTeam ->
                    processSetTeam(command)

                is SimulatedCommand.SetPresentation ->
                    processSetPresentation(command)
            }
        }
    }

    private fun processSpawn(
        command: SimulatedCommand.Spawn,
        currentTick: Long
    ) {
        val data = command.data

        val createdEntityId = entityStore.create(
            position = data.position,
            velocity = data.velocity,
            yaw = data.yaw,
            pitch = data.pitch,
            hitbox = data.hitbox,
            attributes = data.attributes,
            team = data.team,
            presentationId = data.presentationId,
            flags = data.flags
        )

        check(createdEntityId == command.entityId) {
            "Simulated entity identifier sequence diverged: " +
                    "reserved=${command.entityId}, created=$createdEntityId"
        }

        data.goalSet?.let {
            goalSets.put(
                command.entityId.value,
                it
            )
        }

        offerEvent(
            SimulatedEvent.Spawn(
                currentTick,
                command.entityId
            )
        )
    }

    private fun processRemove(
        command: SimulatedCommand.Remove,
        currentTick: Long
    ) {
        val removed = entityStore.remove(
            command.entityId
        )

        goalSets.remove(
            command.entityId.value
        )

        if (!removed) return

        offerEvent(
            SimulatedEvent.Remove(
                currentTick,
                command.entityId
            )
        )
    }

    private fun processTeleport(
        command: SimulatedCommand.Teleport
    ) {
        val slot = entityStore.slotOf(
            command.entityId
        )

        if (slot < 0) return

        entityStore.setPosition(
            slot,
            command.position
        )

        if (
            command.yaw != null ||
            command.pitch != null
        ) {
            entityStore.setRotation(
                slot,
                command.yaw ?: entityStore.yaw(slot),
                command.pitch ?: entityStore.pitch(slot)
            )
        }
    }

    private fun processSetVelocity(
        command: SimulatedCommand.SetVelocity
    ) {
        val slot = entityStore.slotOf(
            command.entityId
        )

        if (slot < 0) return

        entityStore.setVelocity(
            slot,
            command.velocity
        )
    }

    private fun processAddVelocity(
        command: SimulatedCommand.AddVelocity
    ) {
        val slot = entityStore.slotOf(
            command.entityId
        )

        if (slot < 0) return

        entityStore.addVelocity(
            slot,
            command.velocity
        )
    }

    private fun processDamage(
        command: SimulatedCommand.Damage,
        currentTick: Long
    ) {
        combatSystem.damage(
            SimulatedDamage(
                targetEntityId =
                    command.entityId,
                amount =
                    command.amount
            ),
            currentTick
        )
    }

    private fun processHeal(
        command: SimulatedCommand.Heal
    ) {
        val slot = entityStore.slotOf(
            command.entityId
        )

        if (slot < 0) return

        entityStore.heal(
            slot,
            command.amount
        )
    }

    private fun processSetTeam(
        command: SimulatedCommand.SetTeam
    ) {
        val slot = entityStore.slotOf(
            command.entityId
        )

        if (slot < 0) return

        entityStore.setTeam(
            slot,
            command.team
        )
    }

    private fun processSetPresentation(
        command: SimulatedCommand.SetPresentation
    ) {
        val slot = entityStore.slotOf(
            command.entityId
        )

        if (slot < 0) return

        entityStore.setPresentationId(
            slot,
            command.presentationId
        )
    }

    private fun publishFrame(
        currentTick: Long
    ) {
        val size = entityStore.size

        val entityIds = IntArray(size)

        val positionX = DoubleArray(size)
        val positionY = DoubleArray(size)
        val positionZ = DoubleArray(size)

        val velocityX = DoubleArray(size)
        val velocityY = DoubleArray(size)
        val velocityZ = DoubleArray(size)

        val yaw = FloatArray(size)
        val pitch = FloatArray(size)

        val health = DoubleArray(size)
        val maximumHealth = DoubleArray(size)

        val hitboxWidth = DoubleArray(size)
        val hitboxHeight = DoubleArray(size)

        val teams = IntArray(size)
        val presentationIds = IntArray(size)
        val flags = LongArray(size)

        entityStore.copyFrameStateTo(
            entityIds = entityIds,
            positionX = positionX,
            positionY = positionY,
            positionZ = positionZ,
            velocityX = velocityX,
            velocityY = velocityY,
            velocityZ = velocityZ,
            yaw = yaw,
            pitch = pitch,
            health = health,
            maximumHealth = maximumHealth,
            hitboxWidth = hitboxWidth,
            hitboxHeight = hitboxHeight,
            teams = teams,
            presentationIds =
                presentationIds,
            flags = flags
        )

        framePublisher.publish(
            SimulatedFrame(
                tick = currentTick,
                entityIds = entityIds,
                positionX = positionX,
                positionY = positionY,
                positionZ = positionZ,
                velocityX = velocityX,
                velocityY = velocityY,
                velocityZ = velocityZ,
                yaw = yaw,
                pitch = pitch,
                health = health,
                maximumHealth = maximumHealth,
                hitboxWidth = hitboxWidth,
                hitboxHeight = hitboxHeight,
                teams = teams,
                presentationIds = presentationIds,
                flags = flags
            )
        )
    }

    private suspend fun delayUntil(
        targetNanoseconds: Long
    ) {
        val remainingNanoseconds =
            targetNanoseconds - System.nanoTime()

        if (remainingNanoseconds <= 0L) return

        val delayMilliseconds = ceil(
            remainingNanoseconds / NANOS_PER_MILLISECOND
        ).toLong()

        if (delayMilliseconds > 0L) {
            delay(delayMilliseconds.milliseconds)
        }
    }

    private fun allocateEntityId(): SimulatedEntityId {
        val value = reservedEntityId.getAndIncrement()

        check(value > 0) {
            "Simulated entity identifier space exhausted"
        }

        return SimulatedEntityId(value)
    }

    private fun findFrameIndex(
        frame: SimulatedFrame,
        entityId: SimulatedEntityId
    ): Int {
        var index = 0

        while (index < frame.size) {
            if (frame.entityIds[index] == entityId.value) {
                return index
            }

            index++
        }

        return -1
    }

    private fun offerEvent(
        event: SimulatedEvent
    ) {
        eventQueue.offer(event)
        visualEventQueue.offer(event)
    }

    override fun close() {
        val job: Job?

        synchronized(lifecycleLock) {
            if (
                lifecycleState.get() ==
                LifecycleState.CLOSED
            ) {
                return
            }

            lifecycleState.set(
                LifecycleState.CLOSED
            )

            job = simulationJob
            simulationJob = null
        }

        runBlocking {
            job?.cancelAndJoin()
        }

        simulationScope.cancel()

        navigationService.close()

        commandQueue.clear()
        eventQueue.clear()
        visualEventQueue.clear()
        externalActionQueue.clear()
        framePublisher.clear()

        entityStore.clear()
        goalSets.clear()

        knownEntityIds.clear()
        entityDefinitions.clear()

        simulationDispatcher.close()
        navigationDispatcher.close()
    }
}
