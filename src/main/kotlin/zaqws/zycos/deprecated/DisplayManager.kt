@file:Suppress("unused")

package zaqws.zycos.deprecated

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.data.BlockData
import org.bukkit.entity.BlockDisplay
import org.bukkit.util.Transformation
import org.joml.Vector3f
import zaqws.zycos.AreaManager
import zaqws.zycos.Main
import zaqws.zycos.clone
import zaqws.zycos.overworld
import zaqws.zycos.toPosition

@Deprecated("Not Used")
object DisplayManager {
    private val holograms = mutableListOf<Hologram>()
    private val removeQueue = ArrayDeque<Hologram>()
    private var initialized = false

    class Hologram(
        spawnLocation: Location,
        dataList: ArrayList<Pair<AreaManager.Position, BlockData>>,
        scale: Float = 1f
    ){
        val location = spawnLocation.clone()
        private val displayList = ArrayList<BlockDisplay>(dataList.size)
        var removed = false
            private set

        init {
            dataList.forEach { (offset, blockData) ->
                val entity = spawnLocation.world.spawn(spawnLocation, BlockDisplay::class.java).apply {
                    val size = transformation.scale.apply {
                        x *= scale
                        y *= scale
                        z *= scale
                    }

                    interpolationDelay = -1
                    interpolationDuration = -1

                    transformation = Transformation(
                        Vector3f(
                            -size.x * 0.5f + offset.x * scale,
                            -size.y * 0.5f + offset.y * scale,
                            -size.z * 0.5f + offset.z * scale
                        ),
                        transformation.leftRotation,
                        size,
                        transformation.rightRotation
                    )

                    block = blockData
                }

                displayList.add(entity)
            }
        }

        fun update(){
            if(removed) return

            displayList.forEach { display ->
                display.teleport(location)
            }
        }

        fun remove(){
            removed = true

            displayList.forEach(BlockDisplay::remove)
            displayList.clear()

            removeQueue.add(this)
        }

        fun moveTo(target: Location){
            location.clone(target)
        }
    }

    fun init(){
        if(initialized) return
        initialized = true

        Main.plugin.let { plugin ->
            plugin.server.scheduler.runTaskTimer(
                plugin,
                this::update,
                0L,
                1L
            )
        }
    }

    private fun update(){
        holograms.forEach(Hologram::update)
        removeQueue.forEach(holograms::remove)
        removeQueue.clear()
    }

    fun spawnHologram(
        spawnLocation: Location?,
        area: AreaManager.Area,
        centerLocation: Location,
        size: Double = 1.0,
        filter: List<Material> = listOf(Material.REDSTONE_BLOCK)
    ): Hologram {
        val center = centerLocation.toPosition()
        val data = arrayListOf<Pair<AreaManager.Position, BlockData>>()

        area.query().forEach { x, y, z ->
            val position = AreaManager.Position(x, y, z)
            val block = overworld.getBlockAt(position.toLocation())

            if(block.type.isAir || block.type in filter) return@forEach
            val offset = block.location.toPosition() - center

            data.add(offset to block.blockData)
        }

        val hologram = Hologram(spawnLocation ?: centerLocation, data, scale = size.toFloat())
        holograms.add(hologram)

        return hologram
    }

    fun clear(){
        holograms.forEach(Hologram::remove)
        holograms.clear()
        removeQueue.clear()
    }
}