@file:Suppress("unused", "PackageDirectoryMismatch")

package zaqws.zycos.simulated.sample

import kotlinx.coroutines.launch
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.entity.EntityType
import org.bukkit.plugin.java.JavaPlugin
import zaqws.zycos.AreaManager
import zaqws.zycos.ClientEntityManager
import zaqws.zycos.CoroutineManager
import zaqws.zycos.CoroutineManager.scope
import zaqws.zycos.simulated.SimulatedConfig
import zaqws.zycos.simulated.SimulatedEngine
import zaqws.zycos.simulated.SimulatedEngineBuilder
import zaqws.zycos.simulated.entity.SimulatedEntity
import zaqws.zycos.simulated.entity.SimulatedEntityFlag
import zaqws.zycos.simulated.entity.SimulatedTeam
import zaqws.zycos.simulated.goal.SimulatedGoal
import zaqws.zycos.simulated.goal.SimulatedGoalContext
import zaqws.zycos.simulated.goal.SimulatedGoalRuntime
import zaqws.zycos.simulated.goal.SimulatedGoalSet
import zaqws.zycos.simulated.goal.SimulatedIntent
import zaqws.zycos.simulated.goal.builtin.SimulatedMeleeGoal
import zaqws.zycos.simulated.goal.builtin.SimulatedNearestTargetGoal
import zaqws.zycos.simulated.math.SimulatedVector3
import zaqws.zycos.simulated.paper.map.SimulatedMapBaker
import zaqws.zycos.simulated.paper.map.SimulatedMapPatchBaker
import zaqws.zycos.simulated.paper.map.SimulatedMapUpdater
import zaqws.zycos.simulated.paper.player.PaperPlayerBridge
import zaqws.zycos.simulated.paper.player.PaperPlayerProvider
import zaqws.zycos.simulated.paper.player.PaperPlayerTeamResolver
import zaqws.zycos.simulated.paper.render.PaperSimulatedRenderer
import zaqws.zycos.simulated.paper.render.SimulatedPresentation
import zaqws.zycos.simulated.paper.render.SimulatedPresentationRegistry
import zaqws.zycos.simulated.paper.signal.PaperSimulatedSignalBridge
import zaqws.zycos.simulated.projectile.SimulatedProjectile
import zaqws.zycos.simulated.projectile.SimulatedProjectileDefinition
import zaqws.zycos.simulated.signal.SimulatedSignal
import zaqws.zycos.simulated.signal.SimulatedSignalEvent

class Main : JavaPlugin() {
    companion object {
        private val PLAYER_TEAM = SimulatedTeam(1)
        private val MONSTER_TEAM = SimulatedTeam(2)
    }

    private var runtime: SampleRuntime? = null

    override fun onEnable() {
        scope.launch {
            val world = server.worlds.firstOrNull() ?: return@launch

            runtime = createRuntime(world)
        }
    }

    override fun onDisable() {
        runtime?.close()
        runtime = null
        CoroutineManager.cancel(this)
    }

    fun resetEntities() {
        runtime?.let { runtime ->
            runtime.entities.removeAll()
            runtime.entities = spawnSampleEntities(
                runtime.engine,
                runtime.spawnPosition,
                runtime.presentations
            )
        }
    }

    fun teleportSlamEntityToSpawn() {
        runtime?.let { runtime ->
            runtime.entities.slam.teleport(
                runtime.spawnPosition
            )
        }
    }

    fun slamClientEntity(): ClientEntityManager.ClientEntity? {
        val runtime = runtime ?: return null

        return runtime.renderer.clientEntity(
            runtime.entities.slam.entityId
        )
    }

    fun fireSampleProjectile(): SimulatedProjectile? {
        val runtime = runtime ?: return null

        val source = runtime.entities.slam
        val snapshot = source.snapshot() ?: return null

        return runtime.engine.projectileManager.spawn {
            position(
                snapshot.position + SimulatedVector3(0.0, 1.4, 0.0)
            )
            velocity(0.0, 0.1, 1.2)
            source(source.entityId)
            team(MONSTER_TEAM)
            definition(
                SimulatedProjectileDefinition(
                    radius = 0.2,
                    maximumTicks = 60,
                    maximumRange = 40.0,
                    damage = 5.0,
                    knockbackStrength = 0.6
                )
            )
        }
    }

    private suspend fun createRuntime(
        world: World
    ): SampleRuntime {
        val spawnLocation = world.spawnLocation
        val spawnX = spawnLocation.blockX
        val spawnZ = spawnLocation.blockZ
        val spawnY =
            world.getHighestBlockYAt(
                spawnX,
                spawnZ
            ) + 1

        val area = AreaManager.Area(
            spawnX - 48,
            maxOf(world.minHeight, spawnY - 16),
            spawnZ - 48,
            spawnX + 48,
            minOf(world.maxHeight - 1, spawnY + 32),
            spawnZ + 48
        )

        val map = SimulatedMapBaker(this, world)
            .area(area)
            .chunksPerBatch(8)
            .bake()

        val engine = SimulatedEngineBuilder(map)
                .config(SimulatedConfig(
                    navigationWorkerCount = 2,
                    maximumPathRequestsPerTick = 128
                ))
                .build()

        val presentationRegistry = SimulatedPresentationRegistry()

        val presentations = SamplePresentations(
            normal = presentationRegistry.register(
                SimulatedPresentation(EntityType.ZOMBIE)
            ),
            regenerating = presentationRegistry.register(
                SimulatedPresentation(EntityType.HUSK)
            ),
            slam = presentationRegistry.register(
                SimulatedPresentation(EntityType.IRON_GOLEM)
            )
        )

        val playerProvider = PaperPlayerProvider(
            this,
            PaperPlayerTeamResolver.fixed(
                PLAYER_TEAM
            ),
            world
        )

        val playerBridge = PaperPlayerBridge(
            this,
            engine,
            playerProvider
        )

        val renderer = PaperSimulatedRenderer(
            this,
            world,
            engine,
            presentationRegistry
        )

        val signalBridge = PaperSimulatedSignalBridge(
            this,
            engine,
            handler = { event ->
                renderSignal(
                    world,
                    event
                )
            }
        )

        val mapUpdater = SimulatedMapUpdater(
            this,
            map,
            SimulatedMapPatchBaker(
                this,
                world,
                map
            )
        )

        val spawnPosition = SimulatedVector3(
            spawnX + 0.5,
            spawnY.toDouble(),
            spawnZ + 0.5
        )

        val entities = spawnSampleEntities(
            engine,
            spawnPosition,
            presentations
        )

        engine.start()
        playerBridge.start()
        renderer.start()
        signalBridge.start()

        return SampleRuntime(
            engine = engine,
            playerBridge = playerBridge,
            renderer = renderer,
            signalBridge = signalBridge,
            mapUpdater = mapUpdater,
            presentationRegistry = presentationRegistry,
            spawnPosition = spawnPosition,
            presentations = presentations,
            entities = entities
        )
    }

    private fun spawnSampleEntities(
        engine: SimulatedEngine,
        spawnPosition: SimulatedVector3,
        presentations: SamplePresentations
    ): SpawnedEntities {
        val normal = engine.spawn {
            position(
                spawnPosition + SimulatedVector3(4.0, 0.0, 0.0)
            )
            team(MONSTER_TEAM)
            presentation(presentations.normal)
            movementSpeed(0.28)
            attackDamage(3.0)
            goals(meleeGoals())
        }

        val regenerating = engine.spawn {
            position(spawnPosition + SimulatedVector3(-4.0, 0.0, 0.0))
            team(MONSTER_TEAM)
            presentation(presentations.regenerating)
            health(30.0)
            movementSpeed(0.24)
            attackDamage(4.0)
            goals(SimulatedGoalSet.build {
                goal(
                    SimulatedNearestTargetGoal(searchRadius = 32.0),
                    intervalTicks = 5
                )
                goal(SimulatedMeleeGoal())
                goal(
                    RegenerationGoal(amount = 1.5),
                    intervalTicks = 20
                )
            })
        }

        val slam = engine.spawn {
            position(spawnPosition + SimulatedVector3(0.0, 0.0, 6.0))
            team(MONSTER_TEAM)
            presentation(presentations.slam)
            hitbox(1.4, 2.7)
            health(80.0)
            movementSpeed(0.2)
            attackDamage(6.0)
            attackRange(2.5)
            goals(SimulatedGoalSet.build {
                goal(
                    SimulatedNearestTargetGoal(searchRadius = 40.0),
                    intervalTicks = 5
                )
                goal(SimulatedMeleeGoal())
                goal(GroundSlamGoal())
            })
        }

        regenerating.damage(8.0)

        return SpawnedEntities(
            normal,
            regenerating,
            slam
        )
    }

    private fun meleeGoals(): SimulatedGoalSet =
        SimulatedGoalSet.build {
            goal(
                SimulatedNearestTargetGoal(
                    searchRadius = 32.0
                ),
                intervalTicks = 5
            )
            goal(SimulatedMeleeGoal())
        }

    private fun renderSignal(
        world: World,
        event: SimulatedSignalEvent
    ) {
        val location =
            Location(
                world,
                event.position.x,
                event.position.y,
                event.position.z
            )

        when (val signal = event.signal) {
            is SlamLeapSignal -> {
                world.spawnParticle(
                    Particle.CLOUD,
                    location,
                    20,
                    0.7,
                    0.1,
                    0.7,
                    0.03
                )
                world.playSound(
                    location,
                    Sound.ENTITY_IRON_GOLEM_ATTACK,
                    1.0f,
                    0.8f
                )
            }

            is SlamImpactSignal -> {
                world.spawnParticle(
                    Particle.EXPLOSION_EMITTER,
                    location,
                    1
                )
                world.spawnParticle(
                    Particle.CLOUD,
                    location,
                    80,
                    signal.radius,
                    0.25,
                    signal.radius,
                    0.1
                )
                world.playSound(
                    location,
                    Sound.ENTITY_GENERIC_EXPLODE,
                    1.5f,
                    0.7f
                )
            }

            is RegenerationSignal ->
                world.spawnParticle(
                    Particle.HEART,
                    location,
                    4,
                    0.4,
                    0.8,
                    0.4,
                    0.0
                )
        }
    }

    private data class SampleRuntime(
        val engine: SimulatedEngine,
        val playerBridge: PaperPlayerBridge,
        val renderer: PaperSimulatedRenderer,
        val signalBridge: PaperSimulatedSignalBridge,
        val mapUpdater: SimulatedMapUpdater,
        val presentationRegistry:
            SimulatedPresentationRegistry,
        val spawnPosition: SimulatedVector3,
        val presentations: SamplePresentations,
        var entities: SpawnedEntities
    ) : AutoCloseable {
        override fun close() {
            signalBridge.close()
            renderer.close()
            playerBridge.close()
            mapUpdater.close()
            engine.close()
            presentationRegistry.clear()
        }
    }

    private data class SamplePresentations(
        val normal:
            zaqws.zycos.simulated.entity.SimulatedPresentationId,
        val regenerating:
            zaqws.zycos.simulated.entity.SimulatedPresentationId,
        val slam:
            zaqws.zycos.simulated.entity.SimulatedPresentationId
    )

    private data class SpawnedEntities(
        val normal: SimulatedEntity,
        val regenerating: SimulatedEntity,
        val slam: SimulatedEntity
    ) {
        fun removeAll() {
            normal.remove()
            regenerating.remove()
            slam.remove()
        }
    }
}

private data object RegenerationSignal : SimulatedSignal

private data object SlamLeapSignal : SimulatedSignal

private data class SlamImpactSignal(
    val radius: Double
) : SimulatedSignal

private class RegenerationGoal(
    private val amount: Double
) : SimulatedGoal {
    init {
        require(amount.isFinite())
        require(amount > 0.0)
    }

    override fun evaluate(
        context: SimulatedGoalContext,
        runtime: SimulatedGoalRuntime,
        intents: MutableCollection<SimulatedIntent>
    ) {
        if (context.health >= context.maximumHealth) return

        context.heal(amount)
        context.emit(RegenerationSignal)
    }
}

private class GroundSlamGoal(
    private val triggerRadius: Double = 4.0,
    private val impactRadius: Double = 5.0,
    private val jumpVelocity: Double = 1.1,
    private val diveVelocity: Double = 1.4,
    private val damage: Double = 10.0,
    private val horizontalKnockback: Double = 1.1,
    private val verticalKnockback: Double = 0.65,
    private val cooldownTicks: Long = 60L
) : SimulatedGoal {
    private enum class Phase {
        READY,
        RISING,
        FALLING,
        COOLDOWN
    }

    private class Runtime : SimulatedGoalRuntime {
        var phase = Phase.READY
        var cooldownUntilTick = 0L
    }

    init {
        require(triggerRadius.isFinite() && triggerRadius > 0.0)
        require(impactRadius.isFinite() && impactRadius > 0.0)
        require(jumpVelocity.isFinite() && jumpVelocity > 0.0)
        require(diveVelocity.isFinite() && diveVelocity > 0.0)
        require(damage.isFinite() && damage >= 0.0)
        require(horizontalKnockback.isFinite() && horizontalKnockback >= 0.0)
        require(verticalKnockback.isFinite() && verticalKnockback >= 0.0)
        require(cooldownTicks >= 0L)
    }

    override fun createRuntime(): SimulatedGoalRuntime =
        Runtime()

    override fun evaluate(
        context: SimulatedGoalContext,
        runtime: SimulatedGoalRuntime,
        intents: MutableCollection<SimulatedIntent>
    ) {
        runtime as Runtime

        when (runtime.phase) {
            Phase.READY ->
                beginSlam(
                    context,
                    runtime,
                    intents
                )

            Phase.RISING ->
                updateRising(
                    context,
                    runtime,
                    intents
                )

            Phase.FALLING ->
                updateFalling(
                    context,
                    runtime,
                    intents
                )

            Phase.COOLDOWN -> {
                if (
                    context.tick >=
                    runtime.cooldownUntilTick
                ) {
                    runtime.phase = Phase.READY
                }
            }
        }
    }

    private fun beginSlam(
        context: SimulatedGoalContext,
        runtime: Runtime,
        intents: MutableCollection<SimulatedIntent>
    ) {
        if (
            SimulatedEntityFlag.ON_GROUND !in
            context.flags
        ) {
            return
        }

        val target =
            context.nearestTarget(
                triggerRadius
            ) {
                it.isTargetable &&
                        it.team != context.team
            } ?: return

        intents.add(
            SimulatedIntent.StopMovement(
                priority = ACTION_PRIORITY
            )
        )
        intents.add(
            SimulatedIntent.LookAt(
                position = target.position,
                priority = ACTION_PRIORITY
            )
        )
        context.setVelocity(
            SimulatedVector3(
                0.0,
                jumpVelocity,
                0.0
            ),
            priority = ACTION_PRIORITY
        )
        context.emit(SlamLeapSignal)
        runtime.phase = Phase.RISING
    }

    private fun updateRising(
        context: SimulatedGoalContext,
        runtime: Runtime,
        intents: MutableCollection<SimulatedIntent>
    ) {
        intents.add(
            SimulatedIntent.StopMovement(
                priority = ACTION_PRIORITY
            )
        )

        if (context.velocity.y > 0.0) return

        context.setVelocity(
            context.velocity.withY(
                -diveVelocity
            ),
            priority = ACTION_PRIORITY
        )
        runtime.phase = Phase.FALLING
    }

    private fun updateFalling(
        context: SimulatedGoalContext,
        runtime: Runtime,
        intents: MutableCollection<SimulatedIntent>
    ) {
        intents.add(
            SimulatedIntent.StopMovement(
                priority = ACTION_PRIORITY
            )
        )

        if (
            SimulatedEntityFlag.ON_GROUND in
            context.flags
        ) {
            impact(
                context,
                runtime
            )
            return
        }

        context.setVelocity(
            context.velocity.withY(
                -diveVelocity
            ),
            priority = ACTION_PRIORITY
        )
    }

    private fun impact(
        context: SimulatedGoalContext,
        runtime: Runtime
    ) {
        val targets =
            context.targetsWithin(
                impactRadius
            ) {
                it.isTargetable &&
                        it.team != context.team
            }

        for (target in targets) {
            val direction =
                (
                        target.position -
                                context.position
                        ).normalizedHorizontal()

            context.damage(
                target.target,
                damage
            )
            context.knockback(
                target.target,
                direction * horizontalKnockback +
                        SimulatedVector3(
                            0.0,
                            verticalKnockback,
                            0.0
                        )
            )
        }

        context.emit(
            SlamImpactSignal(
                impactRadius
            )
        )
        runtime.cooldownUntilTick =
            context.tick + cooldownTicks
        runtime.phase = Phase.COOLDOWN
    }

    companion object {
        private const val ACTION_PRIORITY = 100
    }
}
