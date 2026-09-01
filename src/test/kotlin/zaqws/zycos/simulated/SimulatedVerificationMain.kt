package zaqws.zycos.simulated

import zaqws.zycos.simulated.map.CollisionColumnTest
import zaqws.zycos.simulated.map.SimulatedMapPatchTest
import zaqws.zycos.simulated.map.WalkSurfaceTest
import zaqws.zycos.simulated.navigation.SimulatedAStarPathfinderTest
import zaqws.zycos.simulated.navigation.hpa.SimulatedHpaPathfinderTest
import zaqws.zycos.simulated.physics.SimulatedCollisionSolverTest

object SimulatedVerificationMain {
    @JvmStatic
    fun main(arguments: Array<String>) {
        require(arguments.isEmpty())

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
            `entity without movement goal retains horizontal velocity`()
            `default engine moves enemies into range and resolves combat`()
            `external actor frame participates in targeting and combat`()
        }

        SimulatedScaleSmokeTest()
            .`five thousand entities publish frames without engine failure`()
    }
}
