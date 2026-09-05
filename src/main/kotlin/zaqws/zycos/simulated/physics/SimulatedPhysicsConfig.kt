package zaqws.zycos.simulated.physics

data class SimulatedPhysicsConfig(
    val gravityPerTick: Double = DEFAULT_GRAVITY_PER_TICK,
    val airDrag: Double = DEFAULT_AIR_DRAG,
    val groundFriction: Double = DEFAULT_GROUND_FRICTION,
    val jumpVelocity: Double = DEFAULT_JUMP_VELOCITY,
    val entityMass: Double = DEFAULT_ENTITY_MASS,
    val collisionEpsilon: Double = DEFAULT_COLLISION_EPSILON,
    val groundDetectionDistance: Double = DEFAULT_GROUND_DETECTION_DISTANCE,
    val maximumFallSpeed: Double = DEFAULT_MAXIMUM_FALL_SPEED,
    val separationStrength: Double = DEFAULT_SEPARATION_STRENGTH,
    val spatialCellSize: Double = DEFAULT_SPATIAL_CELL_SIZE
) {
    companion object {
        const val DEFAULT_GRAVITY_PER_TICK = -0.08
        const val DEFAULT_AIR_DRAG = 0.98
        const val DEFAULT_GROUND_FRICTION = 0.6
        const val DEFAULT_JUMP_VELOCITY = 0.42
        const val DEFAULT_ENTITY_MASS = 1.0

        const val DEFAULT_COLLISION_EPSILON = 1.0e-7
        const val DEFAULT_GROUND_DETECTION_DISTANCE = 1.0e-5

        const val DEFAULT_MAXIMUM_FALL_SPEED = 3.92

        const val DEFAULT_SEPARATION_STRENGTH = 0.05
        const val DEFAULT_SPATIAL_CELL_SIZE = 4.0

        val DEFAULT = SimulatedPhysicsConfig()
    }

    init {
        require(gravityPerTick.isFinite())

        require(airDrag.isFinite())
        require(airDrag in 0.0..1.0)

        require(groundFriction.isFinite())
        require(groundFriction in 0.0..1.0)

        require(jumpVelocity.isFinite())
        require(jumpVelocity >= 0.0)

        require(entityMass.isFinite())
        require(entityMass > 0.0)

        require(collisionEpsilon.isFinite())
        require(collisionEpsilon > 0.0)

        require(groundDetectionDistance.isFinite())
        require(groundDetectionDistance >= 0.0)

        require(maximumFallSpeed.isFinite())
        require(maximumFallSpeed >= 0.0)

        require(separationStrength.isFinite())
        require(separationStrength >= 0.0)

        require(spatialCellSize.isFinite())
        require(spatialCellSize >= 0.5)
    }
}