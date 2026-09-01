package zaqws.zycos.simulated.physics

import zaqws.zycos.simulated.map.CollisionKind
import zaqws.zycos.simulated.map.SimulatedMap
import zaqws.zycos.simulated.math.SimulatedAABB
import zaqws.zycos.simulated.math.SimulatedMath
import zaqws.zycos.simulated.math.SimulatedVector3
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

class SimulatedCollisionSolver(
    private val map: SimulatedMap,
    private val config: SimulatedPhysicsConfig =
        SimulatedPhysicsConfig.DEFAULT
) {
    companion object {
        private const val MAXIMUM_COLLISION_EXTENSION =
            0.5
    }
    
    data class Result(
        val boundingBox: SimulatedAABB,
        val movement: SimulatedVector3,
        val collidedX: Boolean,
        val collidedY: Boolean,
        val collidedZ: Boolean
    ) {
        val collidedHorizontally: Boolean
            get() = collidedX || collidedZ

        val collidedVertically: Boolean
            get() = collidedY

        val collided: Boolean
            get() =
                collidedX ||
                        collidedY ||
                        collidedZ

        val onGround: Boolean
            get() =
                collidedY &&
                        movement.y <= 0.0
    }

    fun move(
        boundingBox: SimulatedAABB,
        requestedMovement: SimulatedVector3
    ): Result {
        require(requestedMovement.isFinite)

        if (
            requestedMovement.lengthSquared <=
            SimulatedMath.EPSILON_SQUARED
        ) {
            return Result(
                boundingBox = boundingBox,
                movement = SimulatedVector3.ZERO,
                collidedX = false,
                collidedY = false,
                collidedZ = false
            )
        }

        var currentBoundingBox = boundingBox

        val allowedY =
            clipY(
                currentBoundingBox,
                requestedMovement.y
            )

        currentBoundingBox =
            currentBoundingBox.moved(
                0.0,
                allowedY,
                0.0
            )

        val allowedX =
            clipX(
                currentBoundingBox,
                requestedMovement.x
            )

        currentBoundingBox =
            currentBoundingBox.moved(
                allowedX,
                0.0,
                0.0
            )

        val allowedZ =
            clipZ(
                currentBoundingBox,
                requestedMovement.z
            )

        currentBoundingBox =
            currentBoundingBox.moved(
                0.0,
                0.0,
                allowedZ
            )

        return Result(
            boundingBox =
                currentBoundingBox,

            movement =
                SimulatedVector3(
                    allowedX,
                    allowedY,
                    allowedZ
                ),

            collidedX =
                !approximatelySameMovement(
                    requestedMovement.x,
                    allowedX
                ),

            collidedY =
                !approximatelySameMovement(
                    requestedMovement.y,
                    allowedY
                ),

            collidedZ =
                !approximatelySameMovement(
                    requestedMovement.z,
                    allowedZ
                )
        )
    }

    fun isOnGround(
        boundingBox: SimulatedAABB
    ): Boolean {
        if (
            config.groundDetectionDistance <=
            0.0
        ) {
            return false
        }

        val result =
            move(
                boundingBox,
                SimulatedVector3(
                    0.0,
                    -config.groundDetectionDistance,
                    0.0
                )
            )

        return result.collidedY
    }

    fun collides(
        boundingBox: SimulatedAABB
    ): Boolean =
        map.hasCollision(
            boundingBox
        )

    private fun clipX(
        boundingBox: SimulatedAABB,
        requestedMovement: Double
    ): Double {
        if (
            kotlin.math.abs(requestedMovement) <=
            config.collisionEpsilon
        ) {
            return 0.0
        }

        var allowedMovement =
            requestedMovement

        forEachPotentialCollision(
            sweptBoundingBox(
                boundingBox,
                requestedMovement,
                0.0,
                0.0
            )
        ) { collisionBox ->
            if (
                !overlaps(
                    boundingBox.minimumY,
                    boundingBox.maximumY,
                    collisionBox.minimumY,
                    collisionBox.maximumY
                ) ||
                !overlaps(
                    boundingBox.minimumZ,
                    boundingBox.maximumZ,
                    collisionBox.minimumZ,
                    collisionBox.maximumZ
                )
            ) {
                return@forEachPotentialCollision
            }

            if (
                allowedMovement > 0.0 &&
                boundingBox.maximumX <=
                collisionBox.minimumX
            ) {
                allowedMovement =
                    min(
                        allowedMovement,
                        collisionBox.minimumX -
                                boundingBox.maximumX
                    )
            } else if (
                allowedMovement < 0.0 &&
                boundingBox.minimumX >=
                collisionBox.maximumX
            ) {
                allowedMovement =
                    max(
                        allowedMovement,
                        collisionBox.maximumX -
                                boundingBox.minimumX
                    )
            }
        }

        return normalizeMovement(
            allowedMovement
        )
    }

    private fun clipY(
        boundingBox: SimulatedAABB,
        requestedMovement: Double
    ): Double {
        if (
            kotlin.math.abs(requestedMovement) <=
            config.collisionEpsilon
        ) {
            return 0.0
        }

        var allowedMovement =
            requestedMovement

        forEachPotentialCollision(
            sweptBoundingBox(
                boundingBox,
                0.0,
                requestedMovement,
                0.0
            )
        ) { collisionBox ->
            if (
                !overlaps(
                    boundingBox.minimumX,
                    boundingBox.maximumX,
                    collisionBox.minimumX,
                    collisionBox.maximumX
                ) ||
                !overlaps(
                    boundingBox.minimumZ,
                    boundingBox.maximumZ,
                    collisionBox.minimumZ,
                    collisionBox.maximumZ
                )
            ) {
                return@forEachPotentialCollision
            }

            if (
                allowedMovement > 0.0 &&
                boundingBox.maximumY <=
                collisionBox.minimumY
            ) {
                allowedMovement =
                    min(
                        allowedMovement,
                        collisionBox.minimumY -
                                boundingBox.maximumY
                    )
            } else if (
                allowedMovement < 0.0 &&
                boundingBox.minimumY >=
                collisionBox.maximumY
            ) {
                allowedMovement =
                    max(
                        allowedMovement,
                        collisionBox.maximumY -
                                boundingBox.minimumY
                    )
            }
        }

        return normalizeMovement(
            allowedMovement
        )
    }

    private fun clipZ(
        boundingBox: SimulatedAABB,
        requestedMovement: Double
    ): Double {
        if (
            kotlin.math.abs(requestedMovement) <=
            config.collisionEpsilon
        ) {
            return 0.0
        }

        var allowedMovement =
            requestedMovement

        forEachPotentialCollision(
            sweptBoundingBox(
                boundingBox,
                0.0,
                0.0,
                requestedMovement
            )
        ) { collisionBox ->
            if (
                !overlaps(
                    boundingBox.minimumX,
                    boundingBox.maximumX,
                    collisionBox.minimumX,
                    collisionBox.maximumX
                ) ||
                !overlaps(
                    boundingBox.minimumY,
                    boundingBox.maximumY,
                    collisionBox.minimumY,
                    collisionBox.maximumY
                )
            ) {
                return@forEachPotentialCollision
            }

            if (
                allowedMovement > 0.0 &&
                boundingBox.maximumZ <=
                collisionBox.minimumZ
            ) {
                allowedMovement =
                    min(
                        allowedMovement,
                        collisionBox.minimumZ -
                                boundingBox.maximumZ
                    )
            } else if (
                allowedMovement < 0.0 &&
                boundingBox.minimumZ >=
                collisionBox.maximumZ
            ) {
                allowedMovement =
                    max(
                        allowedMovement,
                        collisionBox.maximumZ -
                                boundingBox.minimumZ
                    )
            }
        }

        return normalizeMovement(
            allowedMovement
        )
    }

    private inline fun forEachPotentialCollision(
        searchBoundingBox: SimulatedAABB,
        action: (SimulatedAABB) -> Unit
    ) {
        val minimumBlockX =
            floor(
                searchBoundingBox.minimumX +
                        config.collisionEpsilon
            ).toInt()

        val maximumBlockX =
            floor(
                searchBoundingBox.maximumX -
                        config.collisionEpsilon
            ).toInt()

        val minimumBlockY =
            floor(
                searchBoundingBox.minimumY -
                        MAXIMUM_COLLISION_EXTENSION
            ).toInt()

        val maximumBlockY =
            floor(
                searchBoundingBox.maximumY -
                        config.collisionEpsilon
            ).toInt()

        val minimumBlockZ =
            floor(
                searchBoundingBox.minimumZ +
                        config.collisionEpsilon
            ).toInt()

        val maximumBlockZ =
            floor(
                searchBoundingBox.maximumZ -
                        config.collisionEpsilon
            ).toInt()

        var blockY =
            minimumBlockY

        while (
            blockY <=
            maximumBlockY
        ) {
            var blockZ =
                minimumBlockZ

            while (
                blockZ <=
                maximumBlockZ
            ) {
                var blockX =
                    minimumBlockX

                while (
                    blockX <=
                    maximumBlockX
                ) {
                    val collisionKind =
                        map.collisionKindAt(
                            blockX,
                            blockY,
                            blockZ
                        )

                    if (
                        collisionKind.blocksMovement
                    ) {
                        action(
                            collisionBox(
                                blockX,
                                blockY,
                                blockZ,
                                collisionKind
                            )
                        )
                    }

                    blockX++
                }

                blockZ++
            }

            blockY++
        }
    }

    private fun collisionBox(
        blockX: Int,
        blockY: Int,
        blockZ: Int,
        collisionKind: CollisionKind
    ) = SimulatedAABB(
        minimumX =
            blockX.toDouble(),

        minimumY =
            blockY +
                    collisionKind.minimumHeight,

        minimumZ =
            blockZ.toDouble(),

        maximumX =
            blockX + 1.0,

        maximumY =
            blockY +
                    collisionKind.maximumHeight,

        maximumZ =
            blockZ + 1.0
    )

    private fun sweptBoundingBox(
        boundingBox: SimulatedAABB,
        movementX: Double,
        movementY: Double,
        movementZ: Double
    ) = SimulatedAABB(
        minimumX =
            min(
                boundingBox.minimumX,
                boundingBox.minimumX +
                        movementX
            ),

        minimumY =
            min(
                boundingBox.minimumY,
                boundingBox.minimumY +
                        movementY
            ),

        minimumZ =
            min(
                boundingBox.minimumZ,
                boundingBox.minimumZ +
                        movementZ
            ),

        maximumX =
            max(
                boundingBox.maximumX,
                boundingBox.maximumX +
                        movementX
            ),

        maximumY =
            max(
                boundingBox.maximumY,
                boundingBox.maximumY +
                        movementY
            ),

        maximumZ =
            max(
                boundingBox.maximumZ,
                boundingBox.maximumZ +
                        movementZ
            )
    )

    private fun overlaps(
        firstMinimum: Double,
        firstMaximum: Double,
        secondMinimum: Double,
        secondMaximum: Double
    ): Boolean =
        firstMaximum >
                secondMinimum +
                config.collisionEpsilon &&
                firstMinimum <
                secondMaximum -
                config.collisionEpsilon

    private fun normalizeMovement(
        movement: Double
    ): Double =
        if (
            kotlin.math.abs(movement) <=
            config.collisionEpsilon
        ) {
            0.0
        } else {
            movement
        }

    private fun approximatelySameMovement(
        requestedMovement: Double,
        allowedMovement: Double
    ): Boolean =
        kotlin.math.abs(
            requestedMovement -
                    allowedMovement
        ) <= config.collisionEpsilon
}
