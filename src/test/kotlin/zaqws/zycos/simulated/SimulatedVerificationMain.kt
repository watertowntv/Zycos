package zaqws.zycos.simulated

import zaqws.zycos.simulated.map.CollisionColumnTest
import zaqws.zycos.simulated.map.SimulatedMapPatchTest
import zaqws.zycos.simulated.map.WalkSurfaceTest
import zaqws.zycos.simulated.math.SimulatedAABBTest
import zaqws.zycos.simulated.navigation.SimulatedAStarPathfinderTest
import zaqws.zycos.simulated.navigation.hpa.SimulatedHpaPathfinderTest
import zaqws.zycos.simulated.physics.SimulatedCollisionSolverTest
import zaqws.zycos.simulated.paper.SimulatedPaperConversionsTest

object SimulatedVerificationMain {
    @JvmStatic
    fun main(arguments: Array<String>) {
        require(arguments.isEmpty())

        SimulatedArchitectureTest()
            .`core has no Paper Bukkit or outer Zycos dependency`()

        SimulatedPaperConversionsTest()
            .`area and position conversions preserve normalized coordinates`()

        SimulatedPaperConversionsTest()
            .`legacy map revision alias resolves to core type`()

        SimulatedAABBTest()
            .`segment intersection returns first fraction`()

        CollisionColumnTest().apply {
            `stacked bottom slabs retain the air gap in each block`()
            `top slab occupies only the upper half of its block`()
            `full block and tall collision retain their exact upper bounds`()
        }

        WalkSurfaceTest().apply {
            `low ceiling rejects entity height`()
            `column retains multiple floors at the same coordinates`()
        }

        SimulatedMapPatchTest()
            .`multi chunk replacement publishes one coherent revision`()

        SimulatedAStarPathfinderTest().apply {
            `straight path crosses multiple chunks`()
            `diagonal cannot cut through a blocked orthogonal neighbor`()
            `one block step is valid and higher climb is rejected`()
            `drop limit distinguishes safe and unsafe falls`()
            `shallow water is traversable and deep water is not`()
            `bridge surface remains traversable above deep water`()
        }

        SimulatedHpaPathfinderTest().apply {
            `continuous chunk entrance is represented by one portal`()
            `cross chunk drop remains directed and respects request drop limit`()
        }

        SimulatedCollisionSolverTest().apply {
            `entity lands on bottom slab at half block height`()
            `solid wall clips horizontal movement`()
        }

        SimulatedEngineIntegrationTest().apply {
            `path follower emits jump event when climbing`()
            `entity without movement goal retains horizontal velocity`()
            `default engine moves enemies into range and resolves combat`()
            `external actor frame participates in targeting and combat`()
        }

        SimulatedGoalExtensionTest()
            .`goal actions control motion area effects projectiles and signals`()

        SimulatedProjectileIntegrationTest().apply {
            `projectile handle controls lifecycle and published frame`()
            `high speed projectile stops at first solid column`()
            `projectile damages nearest enemy without hitting source`()
            `ranged goal publishes projectile and damages target`()
            `projectile emits external actor damage and knockback`()
            `projectile lifetime and range remove with exact reasons`()
        }

        SimulatedRound4RegressionTest().apply {
            saturatedCommandQueueRejectsEntitySpawnWithoutLeakingIdentity()
            saturatedCommandQueueRejectsProjectileSpawnWithoutLeakingIdentity()
        }

        SimulatedScaleSmokeTest()
            .`five thousand entities publish frames without engine failure`()

        SimulatedScaleSmokeTest()
            .`five thousand entities and one thousand projectiles publish frames`()
    }
}
