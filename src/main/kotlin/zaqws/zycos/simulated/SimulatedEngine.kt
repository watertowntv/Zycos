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
import zaqws.zycos.simulated.goal.SimulatedGoalAction
import zaqws.zycos.simulated.goal.SimulatedGoalActionBuffer
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
import zaqws.zycos.simulated.projectile.SimulatedProjectileManager
import zaqws.zycos.simulated.projectile.SimulatedProjectileSource
import zaqws.zycos.simulated.projectile.SimulatedProjectileSpawnData
import zaqws.zycos.simulated.signal.SimulatedSignalEvent
import zaqws.zycos.simulated.signal.SimulatedSignalQueue
import zaqws.zycos.simulated.snapshot.SimulatedEvent
import zaqws.zycos.simulated.snapshot.SimulatedEventQueue
import zaqws.zycos.simulated.snapshot.SimulatedFrame
import zaqws.zycos.simulated.snapshot.SimulatedFramePublisher
import zaqws.zycos.simulated.system.SimulatedSystem
import zaqws.zycos.simulated.system.SimulatedSystemContext
import zaqws.zycos.simulated.spatial.SimulatedEntityQuery
import zaqws.zycos.simulated.spatial.SimulatedInterestIndex
import zaqws.zycos.simulated.spatial.SimulatedSpatialCell
import zaqws.zycos.simulated.spatial.SimulatedSpatialIndex
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
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

    private val signalQueue =
        SimulatedSignalQueue(
            config.maximumQueuedEvents
        )

    private val goalActionBuffer =
        SimulatedGoalActionBuffer()

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

    @Volatile
    private var simulationThread: Thread? = null

    private val simulationDispatcher: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(
                runnable,
                "Simulated-$engineNumber-Simulation"
            ).apply {
                isDaemon = true
                simulationThread = this
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
                config.entityMass,
            spatialCellSize =
                config.spatialCellSize
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
            ::externalFrame,
            goalActionBuffer::offer
        )

    private val pathFollower =
        SimulatedPathFollower(
            entityStore,
            physicsConfig,
            ::offerEvent
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

    val projectileManager =
        SimulatedProjectileManager(
            initialCapacity =
                config.initialProjectileCapacity,
            maximumQueuedEvents =
                config.maximumQueuedEvents,
            map = map,
            entityStore = entityStore,
            spatialIndex = spatialIndex,
            commandConsumer = { command ->
                enqueueIfOperational {
                    commandQueue.offer(command)
                }
            },
            damageConsumer =
                ::applyProjectileDamage,
            externalActionConsumer =
                externalActionQueue::offer,
            ingressLock =
                lifecycleLock
        )

    private val combatSystem =
        SimulatedCombatSystem(
            entityStore,
            goalSystem,
            ::offerEvent,
            ::externalFrame,
            externalActionQueue::offer,
            projectileManager::spawnNow
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

    private val lookSystem = SimulatedLookSystem(entityStore, goalSystem)

    private val separationSystem = SimulatedSeparationSystem(
        entityStore = entityStore,
        spatialIndex = spatialIndex,
        config = physicsConfig,
        requireFullSimulation = false
    )

    private val customSystems = ArrayList<SimulatedSystem>()

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

    val isOperational: Boolean
        get() = lifecycleState.get().let { it == LifecycleState.CREATED || it == LifecycleState.RUNNING }

    val entityCount: Int
        get() = latestFrame.size

    val projectileCount: Int
        get() = projectileManager.size

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

    private inline fun enqueueIfOperational(
        operation: () -> Unit
    ): Boolean =
        synchronized(lifecycleLock) {
            if (!isOperational) {
                false
            } else {
                operation()
                true
            }
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

            val job = simulationScope.launch {
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
                    cleanup()
                }
            }
            job.invokeOnCompletion {
                cleanup()
            }
            simulationJob = job
        }

        return this
    }

    fun stop() {
        val shouldCleanup: Boolean
        synchronized(lifecycleLock) {
            when (lifecycleState.get()) {
                LifecycleState.CREATED -> {
                    lifecycleState.set(LifecycleState.STOPPED)
                    shouldCleanup = true
                }

                LifecycleState.RUNNING -> {
                    lifecycleState.set(LifecycleState.STOPPED)
                    simulationJob?.cancel()
                    shouldCleanup = false
                }

                LifecycleState.STOPPED,
                LifecycleState.CLOSED -> {
                    shouldCleanup = false
                }
            }
        }
        if (shouldCleanup) {
            cleanup()
        }
    }

    fun spawn(
        block: SimulatedEntityBuilder.() -> Unit
    ): SimulatedEntity {
        val data = SimulatedEntityBuilder()
            .apply(block)
            .build()

        require(SimulatedSpatialCell.isValidPosition(data.position, config.spatialCellSize)) {
            "Spawn position ${data.position} exceeds spatial bounds for cell size ${config.spatialCellSize}"
        }

        synchronized(lifecycleLock) {
            check(isOperational) {
                "SimulatedEngine is not operational (state: ${lifecycleState.get()})"
            }

            return synchronized(spawnLock) {
                val entityId = allocateEntityId()

                val accepted = commandQueue.offer(
                    SimulatedCommand.Spawn(
                        entityId,
                        data
                    )
                )
                check(accepted) { "Command queue capacity exceeded" }

                knownEntityIds.add(entityId.value)

                entityDefinitions[entityId.value] = SimulatedEntityDefinition(
                    hitbox = data.hitbox,
                    attributes = data.attributes
                )

                SimulatedEntity(
                    entityId,
                    this
                )
            }
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
        synchronized(lifecycleLock) {
            check(isOperational) {
                "SimulatedEngine is not operational (state: ${lifecycleState.get()})"
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

    val droppedExternalActionCount: Long
        get() = externalActionQueue.droppedCount

    fun drainSignals(
        maximumSignals: Int = Int.MAX_VALUE
    ): List<SimulatedSignalEvent> {
        require(maximumSignals >= 0)

        val signals =
            ArrayList<SimulatedSignalEvent>()

        signalQueue.drainTo(
            signals,
            maximumSignals
        )

        return signals
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
            yaw = frame.yawAt(index),
            pitch = frame.pitchAt(index),
            hitbox = definition.hitbox,
            health = frame.healthAt(index),
            attributes = definition.attributes,
            team = frame.teamAt(index),
            presentationId = frame.presentationIdAt(index),
            flags = frame.flagsAt(index)
        )
    }

    override fun remove(
        entityId: SimulatedEntityId
    ) {
        enqueueIfOperational {
            if (!knownEntityIds.remove(entityId.value)) return@enqueueIfOperational
            entityDefinitions.remove(entityId.value)
            commandQueue.offer(
                SimulatedCommand.Remove(entityId)
            )
        }
    }

    override fun teleport(
        entityId: SimulatedEntityId,
        position: SimulatedVector3,
        yaw: Float?,
        pitch: Float?
    ) {
        require(SimulatedSpatialCell.isValidPosition(position, config.spatialCellSize)) {
            "Teleport position $position exceeds spatial bounds for cell size ${config.spatialCellSize}"
        }

        enqueueIfOperational {
            if (!exists(entityId)) return@enqueueIfOperational
            commandQueue.offer(
                SimulatedCommand.Teleport(
                    entityId,
                    position,
                    yaw,
                    pitch
                )
            )
        }
    }

    override fun setVelocity(
        entityId: SimulatedEntityId,
        velocity: SimulatedVector3
    ) {
        enqueueIfOperational {
            if (!exists(entityId)) return@enqueueIfOperational
            commandQueue.offer(
                SimulatedCommand.SetVelocity(
                    entityId,
                    velocity
                )
            )
        }
    }

    override fun addVelocity(
        entityId: SimulatedEntityId,
        velocity: SimulatedVector3
    ) {
        enqueueIfOperational {
            if (!exists(entityId)) return@enqueueIfOperational
            commandQueue.offer(
                SimulatedCommand.AddVelocity(
                    entityId,
                    velocity
                )
            )
        }
    }

    override fun damage(
        entityId: SimulatedEntityId,
        amount: Double
    ) {
        enqueueIfOperational {
            if (!exists(entityId)) return@enqueueIfOperational
            commandQueue.offer(
                SimulatedCommand.Damage(
                    entityId,
                    amount
                )
            )
        }
    }

    override fun heal(
        entityId: SimulatedEntityId,
        amount: Double
    ) {
        enqueueIfOperational {
            if (!exists(entityId)) return@enqueueIfOperational
            commandQueue.offer(
                SimulatedCommand.Heal(
                    entityId,
                    amount
                )
            )
        }
    }

    override fun setTeam(
        entityId: SimulatedEntityId,
        team: SimulatedTeam
    ) {
        enqueueIfOperational {
            if (!exists(entityId)) return@enqueueIfOperational
            commandQueue.offer(
                SimulatedCommand.SetTeam(
                    entityId,
                    team
                )
            )
        }
    }

    override fun setPresentation(
        entityId: SimulatedEntityId,
        presentationId: SimulatedPresentationId
    ) {
        enqueueIfOperational {
            if (!exists(entityId)) return@enqueueIfOperational
            commandQueue.offer(
                SimulatedCommand.SetPresentation(
                    entityId,
                    presentationId
                )
            )
        }
    }

    internal fun registerSystem(
        system: SimulatedSystem
    ) {
        synchronized(lifecycleLock) {
            check(lifecycleState.get() == LifecycleState.CREATED) {
                "Systems can only be registered before SimulatedEngine starts"
            }

            customSystems.add(system)
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
        try {
            val tickDurationNanoseconds =
                config.simulationTickDurationNanoseconds

            var scheduledNanoseconds = System.nanoTime()

            while (simulationScope.isActive && lifecycleState.get() != LifecycleState.CLOSED) {
                val currentNanoseconds = System.nanoTime()

                if (currentNanoseconds < scheduledNanoseconds) {
                    delayUntil(scheduledNanoseconds)
                    continue
                }

                var executedTicks = 0

                do {
                    if (lifecycleState.get() == LifecycleState.CLOSED) return
                    runSimulationTick()

                    scheduledNanoseconds += tickDurationNanoseconds
                    executedTicks++

                    if (!simulationScope.isActive || lifecycleState.get() == LifecycleState.CLOSED) return

                    val now = System.nanoTime()

                    if (now < scheduledNanoseconds) break

                    if (executedTicks > config.maximumCatchUpTicks) {
                        scheduledNanoseconds =
                            now + tickDurationNanoseconds

                        break
                    }
                } while (true)
            }
        } finally {
            cleanup()
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

        val context = SimulatedSystemContext(
            tick = currentTick,
            deltaSeconds = 1.0 / config.simulationTicksPerSecond
        )

        spatialIndex.rebuild(entityStore)
        interestIndex.rebuild(currentExternalFrame)
        interestIndex.updateEntitySimulationFlags(entityStore)
        projectileManager.update(
            currentTick,
            currentExternalFrame
        )
        goalSystem.update(context)
        applyGoalActions(currentTick)
        navigationSystem.update(context)
        pathFollower.update(context)
        lookSystem.update(context)
        separationSystem.update(context)
        combatSystem.update(context)
        physicsSystem.update(context)

        for (system in customSystems) {
            system.update(context)
        }

        if (lifecycleState.get() == LifecycleState.CLOSED) return

        publishFrame(
            currentTick
        )

        projectileManager.publishFrame(
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

                is SimulatedCommand.SpawnProjectile ->
                    projectileManager.processSpawn(
                        command.projectileId,
                        command.data,
                        currentTick
                    )

                is SimulatedCommand.RemoveProjectile ->
                    projectileManager.processRemove(
                        command.projectileId,
                        currentTick
                    )

                is SimulatedCommand.TeleportProjectile ->
                    projectileManager.processTeleport(
                        command.projectileId,
                        command.position
                    )

                is SimulatedCommand.SetProjectileVelocity ->
                    projectileManager.processSetVelocity(
                        command.projectileId,
                        command.velocity
                    )

                is SimulatedCommand.AddProjectileVelocity ->
                    projectileManager.processAddVelocity(
                        command.projectileId,
                        command.velocity
                    )
            }
        }
    }

    private fun applyProjectileDamage(
        damage: SimulatedDamage,
        tick: Long
    ): Double =
        combatSystem.damage(
            damage,
            tick
        )

    private fun applyGoalActions(
        currentTick: Long
    ) {
        goalActionBuffer.forEach { action ->
            when (action) {
                is SimulatedGoalAction.SetVelocity -> {
                    val slot = entityStore.slotOf(action.sourceEntityId)

                    if (slot >= 0) {
                        entityStore.setVelocity(slot, action.velocity)
                    }
                }

                is SimulatedGoalAction.AddVelocity -> {
                    val slot = entityStore.slotOf(action.sourceEntityId)

                    if (slot >= 0) {
                        entityStore.addVelocity(slot, action.velocity)
                    }
                }

                is SimulatedGoalAction.Teleport -> {
                    val slot = entityStore.slotOf(action.sourceEntityId)

                    if (slot >= 0) {
                        teleportEntity(
                            entityId = action.sourceEntityId,
                            slot = slot,
                            position = action.position
                        )
                    }
                }

                is SimulatedGoalAction.Damage ->
                    applyGoalDamage(action, currentTick)

                is SimulatedGoalAction.Heal ->
                    applyGoalHeal(action)

                is SimulatedGoalAction.Knockback ->
                    applyGoalKnockback(action)

                is SimulatedGoalAction.SpawnProjectile ->
                    applyGoalProjectile(action, currentTick)

                is SimulatedGoalAction.EmitSignal ->
                    applyGoalSignal(action, currentTick)
            }
        }

        goalActionBuffer.clear()
    }

    private fun applyGoalDamage(
        action: SimulatedGoalAction.Damage,
        currentTick: Long
    ) {
        when (val target = action.target) {
            is SimulatedTarget.Entity ->
                combatSystem.damage(
                    SimulatedDamage(
                        targetEntityId = target.entityId,
                        amount = action.amount,
                        sourceEntityId = action.sourceEntityId
                    ),
                    currentTick
                )

            is SimulatedTarget.ExternalActor -> {
                val actor = currentExternalFrame[target.actorId]

                if (actor?.isDamageable == true) {
                    externalActionQueue.offer(
                        SimulatedExternalAction.Damage(
                            actorId = target.actorId,
                            amount = action.amount,
                            sourceEntityId = action.sourceEntityId
                        )
                    )
                }
            }
        }
    }

    private fun applyGoalHeal(
        action: SimulatedGoalAction.Heal
    ) {
        val slot = entityStore.slotOf(action.targetEntityId)

        if (slot >= 0) {
            entityStore.heal(slot, action.amount)
        }
    }

    private fun applyGoalKnockback(
        action: SimulatedGoalAction.Knockback
    ) {
        when (val target = action.target) {
            is SimulatedTarget.Entity -> {
                val slot = entityStore.slotOf(target.entityId)

                if (slot >= 0) {
                    entityStore.addVelocity(slot, action.velocity)
                }
            }

            is SimulatedTarget.ExternalActor -> {
                if (currentExternalFrame[target.actorId]?.isAlive == true) {
                    externalActionQueue.offer(
                        SimulatedExternalAction.Knockback(
                            actorId = target.actorId,
                            velocity = action.velocity,
                            sourceEntityId = action.sourceEntityId
                        )
                    )
                }
            }
        }
    }

    private fun applyGoalProjectile(
        action: SimulatedGoalAction.SpawnProjectile,
        currentTick: Long
    ) {
        val slot = entityStore.slotOf(action.sourceEntityId)
        if (slot < 0) return

        projectileManager.spawnNow(
            SimulatedProjectileSpawnData(
                position = action.position,
                velocity = action.velocity,
                source =
                    SimulatedProjectileSource.Entity(
                        action.sourceEntityId
                    ),
                team = entityStore.team(slot),
                definition = action.definition
            ),
            currentTick
        )
    }

    private fun applyGoalSignal(
        action: SimulatedGoalAction.EmitSignal,
        currentTick: Long
    ) {
        val slot = entityStore.slotOf(action.sourceEntityId)
        if (slot < 0) return

        signalQueue.offer(
            SimulatedSignalEvent(
                tick = currentTick,
                entityId = action.sourceEntityId,
                position = entityStore.position(slot),
                signal = action.signal
            )
        )
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
            flags = data.flags,
            entityId = command.entityId
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

    private fun teleportEntity(
        entityId: SimulatedEntityId,
        slot: Int,
        position: SimulatedVector3,
        yaw: Float? = null,
        pitch: Float? = null
    ): Boolean {
        if (slot < 0) return false
        if (!SimulatedSpatialCell.isValidPosition(position, config.spatialCellSize)) {
            return false
        }

        entityStore.setPosition(slot, position)
        entityStore.setVelocity(slot, SimulatedVector3.ZERO)
        entityStore.setMovementVelocity(slot, 0.0, 0.0, 0.0)
        physicsSystem.resetFallDistance(entityId)
        navigationSystem.clearNavigation(entityId)
        pathFollower.clearPath(entityId)

        if (yaw != null || pitch != null) {
            entityStore.setRotation(
                slot,
                yaw ?: entityStore.yaw(slot),
                pitch ?: entityStore.pitch(slot)
            )
        }

        return true
    }

    private fun processTeleport(
        command: SimulatedCommand.Teleport
    ) {
        val slot = entityStore.slotOf(
            command.entityId
        )

        if (slot < 0) return

        teleportEntity(
            entityId = command.entityId,
            slot = slot,
            position = command.position,
            yaw = command.yaw,
            pitch = command.pitch
        )
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
                rawEntityIds = entityIds,
                rawPositionX = positionX,
                rawPositionY = positionY,
                rawPositionZ = positionZ,
                rawVelocityX = velocityX,
                rawVelocityY = velocityY,
                rawVelocityZ = velocityZ,
                rawYaw = yaw,
                rawPitch = pitch,
                rawHealth = health,
                rawMaximumHealth = maximumHealth,
                rawHitboxWidth = hitboxWidth,
                rawHitboxHeight = hitboxHeight,
                rawTeams = teams,
                rawPresentationIds = presentationIds,
                rawFlags = flags
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
            if (frame.rawEntityIdAt(index) == entityId.value) {
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

    private val isCleanedUp = AtomicBoolean(false)

    private fun cleanup() {
        if (!isCleanedUp.compareAndSet(false, true)) return

        simulationScope.cancel()

        navigationService.close()

        commandQueue.clear()
        eventQueue.clear()
        visualEventQueue.clear()
        externalActionQueue.clear()
        signalQueue.clear()
        goalActionBuffer.clear()
        framePublisher.clear()
        projectileManager.close()

        entityStore.clear()
        goalSets.clear()

        knownEntityIds.clear()
        entityDefinitions.clear()

        simulationDispatcher.close()
        navigationDispatcher.close()
    }

    override fun close() {
        val job: Job?

        synchronized(lifecycleLock) {
            synchronized(spawnLock) {
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
        }

        if (job != null) {
            if (Thread.currentThread() !== simulationThread) {
                runBlocking {
                    job.cancelAndJoin()
                }
                cleanup()
            } else {
                job.cancel()
            }
        } else {
            cleanup()
        }
    }
}
