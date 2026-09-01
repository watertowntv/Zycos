@file:Suppress("unused")

package zaqws.zycos.simulated.paper

import org.bukkit.Location
import org.bukkit.World
import org.bukkit.util.Vector
import zaqws.zycos.AreaManager
import zaqws.zycos.simulated.entity.SimulatedEntityBuilder
import zaqws.zycos.simulated.map.SimulatedBounds
import zaqws.zycos.simulated.math.SimulatedBlockPosition
import zaqws.zycos.simulated.math.SimulatedVector3
import zaqws.zycos.simulated.projectile.SimulatedProjectileBuilder

fun AreaManager.Area.toSimulatedBounds() =
    SimulatedBounds(
        minimumX = boundingBoxStart.x,
        minimumY = boundingBoxStart.y,
        minimumZ = boundingBoxStart.z,
        maximumX = boundingBoxEnd.x,
        maximumY = boundingBoxEnd.y,
        maximumZ = boundingBoxEnd.z
    )

fun AreaManager.Position.toSimulatedBlockPosition() =
    SimulatedBlockPosition.of(
        x,
        y,
        z
    )

fun AreaManager.Position.toSimulatedBlockCenter() =
    SimulatedVector3(
        x + 0.5,
        y.toDouble(),
        z + 0.5
    )

fun Location.toSimulatedVector3() =
    SimulatedVector3(
        x,
        y,
        z
    )

fun Vector.toSimulatedVector3() =
    SimulatedVector3(
        x,
        y,
        z
    )

fun SimulatedVector3.toBukkitVector() =
    Vector(
        x,
        y,
        z
    )

fun SimulatedVector3.toBukkitLocation(
    world: World,
    yaw: Float = 0.0f,
    pitch: Float = 0.0f
) = Location(
    world,
    x,
    y,
    z,
    yaw,
    pitch
)

fun SimulatedBounds.toArea() =
    AreaManager.Area(
        minimumX,
        minimumY,
        minimumZ,
        maximumX,
        maximumY,
        maximumZ
    )

fun SimulatedEntityBuilder.position(
    location: Location
) {
    position(
        location.toSimulatedVector3()
    )
}

fun SimulatedEntityBuilder.blockPosition(
    position: AreaManager.Position
) {
    position(
        position.toSimulatedBlockCenter()
    )
}

fun SimulatedProjectileBuilder.position(
    location: Location
) {
    position(
        location.toSimulatedVector3()
    )
}

fun SimulatedProjectileBuilder.blockPosition(
    position: AreaManager.Position
) {
    position(
        position.toSimulatedBlockCenter()
    )
}

fun SimulatedProjectileBuilder.velocity(
    velocity: Vector
) {
    velocity(
        velocity.toSimulatedVector3()
    )
}
