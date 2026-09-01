package zaqws.zycos.simulated.paper.map

import org.bukkit.Material
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.type.Slab
import zaqws.zycos.simulated.map.CollisionKind

fun interface PaperCollisionResolver {
    companion object {
        val DEFAULT = PaperCollisionResolver { blockData ->
            val material = blockData.material

            when {
                material == Material.WATER ->
                    CollisionKind.AIR

                material.isAir ->
                    CollisionKind.AIR

                blockData is Slab ->
                    when (blockData.type) {
                        Slab.Type.BOTTOM ->
                            CollisionKind.HALF

                        Slab.Type.TOP,
                        Slab.Type.DOUBLE ->
                            CollisionKind.FULL
                    }

                isTallCollision(material) ->
                    CollisionKind.TALL

                material.isSolid ->
                    CollisionKind.FULL

                else ->
                    CollisionKind.AIR
            }
        }

        fun isWater(
            blockData: BlockData
        ): Boolean =
            blockData.material == Material.WATER

        private fun isTallCollision(
            material: Material
        ): Boolean {
            val name = material.name

            return name.endsWith("_FENCE") ||
                    name.endsWith("_WALL")
        }
    }

    fun resolve(blockData: BlockData): CollisionKind
}