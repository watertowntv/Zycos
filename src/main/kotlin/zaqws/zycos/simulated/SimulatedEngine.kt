@file:Suppress("unused")

package zaqws.zycos.simulated

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import kotlinx.coroutines.*
import zaqws.zycos.simulated.command.SimulatedCommand
import zaqws.zycos.simulated.command.SimulatedCommandQueue
import zaqws.zycos.simulated.entity.*
import zaqws.zycos.simulated.goal.SimulatedGoalSet
import zaqws.zycos.simulated.math.SimulatedVector3
import zaqws.zycos.simulated.snapshot.SimulatedEvent
import zaqws.zycos.simulated.snapshot.SimulatedEventQueue
import zaqws.zycos.simulated.snapshot.SimulatedFrame
import zaqws.zycos.simulated.snapshot.SimulatedFramePublisher
import zaqws.zycos.simulated.system.SimulatedSystem
import zaqws.zycos.simulated.system.SimulatedSystemContext
import zaqws.zycos.simulated.system.SimulatedSystemPipeline
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil
import kotlin.time.Duration.Companion.milliseconds

class SimulatedEngine internal constructor(
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

    private val reservedEntityId = AtomicInteger(1)
    private val knownEntityIds = ConcurrentHashMap.newKeySet<Int>()
    private val entityDefinitions = ConcurrentHashMap<Int, SimulatedEntityDefinition>()

    private val commandQueue = SimulatedCommandQueue()
    private val framePublisher = SimulatedFramePublisher()
    private val eventQueue = SimulatedEventQueue()

    internal val entityStore = SimulatedEntityStore(
        config.initialEntityCapacity
    )

    internal val goalSets = Int2ObjectOpenHashMap<SimulatedGoalSet>()

    private val systemPipeline = SimulatedSystemPipeline()

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
        val currentTick = tickCounter.incrementAndGet()

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

        eventQueue.offer(
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

        eventQueue.offer(
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
        val slot = entityStore.slotOf(
            command.entityId
        )

        if (slot < 0) return

        val wasDead = entityStore.hasFlag(
            slot,
            SimulatedEntityFlag.DEAD
        )

        val appliedDamage = entityStore.damage(
            slot,
            command.amount
        )

        if (appliedDamage <= 0.0) return

        eventQueue.offer(
            SimulatedEvent.Hurt(
                tick = currentTick,
                entityId = command.entityId,
                damage = appliedDamage
            )
        )

        if (
            !wasDead &&
            entityStore.hasFlag(
                slot,
                SimulatedEntityFlag.DEAD
            )
        ) {
            eventQueue.offer(
                SimulatedEvent.Death(
                    currentTick,
                    command.entityId
                )
            )
        }
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

        entityStore.forEachSlot { slot ->
            val entityId = entityStore.entityIdAt(slot)
            val position = entityStore.position(slot)
            val velocity = entityStore.velocity(slot)
            val hitbox = entityStore.hitbox(slot)

            entityIds[slot] = entityId.value

            positionX[slot] = position.x
            positionY[slot] = position.y
            positionZ[slot] = position.z

            velocityX[slot] = velocity.x
            velocityY[slot] = velocity.y
            velocityZ[slot] = velocity.z

            yaw[slot] = entityStore.yaw(slot)
            pitch[slot] = entityStore.pitch(slot)

            health[slot] = entityStore.health(slot)
            maximumHealth[slot] =
                entityStore.maximumHealth(slot)

            hitboxWidth[slot] = hitbox.width
            hitboxHeight[slot] = hitbox.height

            teams[slot] =
                entityStore.team(slot).value

            presentationIds[slot] =
                entityStore.presentationId(slot).value

            flags[slot] =
                entityStore.flags(slot).bits
        }

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

        commandQueue.clear()
        eventQueue.clear()
        framePublisher.clear()

        entityStore.clear()
        goalSets.clear()

        knownEntityIds.clear()
        entityDefinitions.clear()

        simulationDispatcher.close()
        navigationDispatcher.close()
    }
}