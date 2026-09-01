package zaqws.zycos.simulated

data class SimulatedConfig(
    val simulationTicksPerSecond: Int = DEFAULT_SIMULATION_TICKS_PER_SECOND,
    val maximumCatchUpTicks: Int = DEFAULT_MAXIMUM_CATCH_UP_TICKS,
    val initialEntityCapacity: Int = DEFAULT_INITIAL_ENTITY_CAPACITY,
    val initialProjectileCapacity: Int =
        DEFAULT_INITIAL_PROJECTILE_CAPACITY,
    val maximumCommandsPerTick: Int = DEFAULT_MAXIMUM_COMMANDS_PER_TICK,
    val maximumQueuedEvents: Int = DEFAULT_MAXIMUM_QUEUED_EVENTS,
    val maximumQueuedExternalActions: Int =
        DEFAULT_MAXIMUM_QUEUED_EXTERNAL_ACTIONS,
    val goalIntervalTicks: Int = DEFAULT_GOAL_INTERVAL_TICKS,
    val targetSearchIntervalTicks: Int = DEFAULT_TARGET_SEARCH_INTERVAL_TICKS,
    val fullSimulationRadius: Double = DEFAULT_FULL_SIMULATION_RADIUS,
    val spatialCellSize: Double = DEFAULT_SPATIAL_CELL_SIZE,
    val gravityPerTick: Double = DEFAULT_GRAVITY_PER_TICK,
    val airDrag: Double = DEFAULT_AIR_DRAG,
    val groundFriction: Double = DEFAULT_GROUND_FRICTION,
    val jumpVelocity: Double = DEFAULT_JUMP_VELOCITY,
    val entityMass: Double = DEFAULT_ENTITY_MASS,
    val navigationWorkerCount: Int = DEFAULT_NAVIGATION_WORKER_COUNT,
    val maximumPathRequestsPerTick: Int = DEFAULT_MAXIMUM_PATH_REQUESTS_PER_TICK,
    val minimumRepathIntervalTicks: Int = DEFAULT_MINIMUM_REPATH_INTERVAL_TICKS,
    val repathDistance: Double = DEFAULT_REPATH_DISTANCE
) {
    companion object {
        private const val NANOS_PER_SECOND = 1_000_000_000L

        const val DEFAULT_SIMULATION_TICKS_PER_SECOND = 20
        const val DEFAULT_MAXIMUM_CATCH_UP_TICKS = 2

        const val DEFAULT_INITIAL_ENTITY_CAPACITY = 512
        const val DEFAULT_INITIAL_PROJECTILE_CAPACITY = 128
        const val DEFAULT_MAXIMUM_COMMANDS_PER_TICK = 100_000
        const val DEFAULT_MAXIMUM_QUEUED_EVENTS = 16_384
        const val DEFAULT_MAXIMUM_QUEUED_EXTERNAL_ACTIONS = 8_192

        const val DEFAULT_GOAL_INTERVAL_TICKS = 4
        const val DEFAULT_TARGET_SEARCH_INTERVAL_TICKS = 5

        const val DEFAULT_FULL_SIMULATION_RADIUS = 96.0
        const val DEFAULT_SPATIAL_CELL_SIZE = 4.0

        const val DEFAULT_GRAVITY_PER_TICK = -0.08
        const val DEFAULT_AIR_DRAG = 0.98
        const val DEFAULT_GROUND_FRICTION = 0.6
        const val DEFAULT_JUMP_VELOCITY = 0.42
        const val DEFAULT_ENTITY_MASS = 1.0

        val DEFAULT_NAVIGATION_WORKER_COUNT =
            Runtime.getRuntime().availableProcessors()
                .coerceAtLeast(2)
                .minus(1)
                .coerceAtLeast(1)

        const val DEFAULT_MAXIMUM_PATH_REQUESTS_PER_TICK = 512
        const val DEFAULT_MINIMUM_REPATH_INTERVAL_TICKS = 5
        const val DEFAULT_REPATH_DISTANCE = 2.0
    }

    init {
        require(simulationTicksPerSecond > 0)
        require(maximumCatchUpTicks >= 0)
        require(initialEntityCapacity > 0)
        require(initialProjectileCapacity > 0)
        require(maximumCommandsPerTick > 0)
        require(maximumQueuedEvents > 0)
        require(maximumQueuedExternalActions > 0)

        require(goalIntervalTicks > 0)
        require(targetSearchIntervalTicks > 0)
        require(fullSimulationRadius.isFinite())
        require(fullSimulationRadius >= 0.0)

        require(spatialCellSize.isFinite())
        require(spatialCellSize > 0.0)

        require(gravityPerTick.isFinite())

        require(airDrag.isFinite())
        require(airDrag in 0.0..1.0)

        require(groundFriction.isFinite())
        require(groundFriction in 0.0..1.0)

        require(jumpVelocity.isFinite())
        require(jumpVelocity >= 0.0)

        require(entityMass.isFinite())
        require(entityMass > 0.0)

        require(navigationWorkerCount > 0)
        require(maximumPathRequestsPerTick > 0)
        require(minimumRepathIntervalTicks >= 0)

        require(repathDistance.isFinite())
        require(repathDistance >= 0.0)
    }

    val simulationTickDurationNanoseconds: Long
        get() = NANOS_PER_SECOND / simulationTicksPerSecond
}
