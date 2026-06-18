@file:Suppress("unused")

package zaqws.zycos


import org.bukkit.Location
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.util.Vector

class ProjectileManager(private val plugin: JavaPlugin) {
    private val projectiles = mutableListOf<SyncedProjectile>()
    private val addQueue = ArrayDeque<SyncedProjectile>()
    private var isUpdating = false

    init {
        plugin.server.scheduler.runTaskTimer(
            Main.plugin,
            this::update,
            0L,
            1L
        )
    }

    fun spawnProjectile(
        projectile: SyncedProjectile,
        startLocation: Location,
        initialVelocity: Vector? = null
    ) {
        projectile.startLocation = startLocation
        projectile.location = startLocation

        if (initialVelocity != null) projectile.velocity = initialVelocity

        projectile.initialize()

        if (isUpdating) addQueue.add(projectile) else projectiles.add(projectile)
    }

    private fun update() {
        isUpdating = true

        val iterator = projectiles.iterator()
        while (iterator.hasNext()) {
            val projectile = iterator.next()

            projectile.update()
            if (projectile.removed) iterator.remove()
        }

        isUpdating = false
        while (addQueue.isNotEmpty()) {
            projectiles.add(addQueue.removeFirst())
        }
    }

    fun clear(){
        projectiles.forEach(SyncedProjectile::remove)
        projectiles.clear()
    }


    abstract class SyncedProjectile(
        private val maxTick: Int,
        maxRange: Double
    ) {
        lateinit var startLocation: Location
        lateinit var location: Location

        private val maxRangeSquared = maxRange * maxRange
        private var tick = 0

        private val movementStack = ArrayDeque<Pair<Location, Vector>>(2)

        var velocity = Vector()
        internal var removed = false
            private set

        internal var onPoolReturn: ((SyncedProjectile) -> Unit)? = null

        /**
         * Where location and velocity updates are calculated
         */
        fun update() {
            if (removed) return

            if (++tick > maxTick || startLocation.distanceSquared(location) >= maxRangeSquared) {
                remove()
                return
            }

            onUpdate()

            val (prevLocation, prevVelocity) = movementStack.removeLast()
            movementStack.addFirst(location.clone() to velocity.clone())

            onMovement(prevLocation, prevVelocity)
        }

        fun remove() {
            if(removed) return
            removed = true

            onRemove()

            onPoolReturn?.invoke(this)
            onPoolReturn = null
        }

        fun initialize() {
            movementStack.addLast(location.clone() to velocity.clone())
            movementStack.addLast(location.clone() to velocity.clone())

            onInit()
        }

        internal fun reset() {
            tick = 0
            removed = false

            velocity = Vector()

            movementStack.clear()
        }

        protected open fun onInit() {}

        /**
         * Where rayTrace is performed
         */
        protected open fun onMovement(location: Location, velocity: Vector) {}

        /**
         * Where location, velocity, and display entity modifications are performed
         */
        protected open fun onUpdate() {}
        protected open fun onRemove() {}
    }

    inner class SyncedProjectilePool<T: SyncedProjectile>(private val factory: () -> T) {
        private val queue = ArrayDeque<T>()

        fun spawn(
            location: Location,
            velocity: Vector? = null,
            initializer: (T) -> Unit
        ) {
            val projectile = queue.removeLastOrNull()?.apply {
                reset()
            } ?: factory()

            initializer(projectile)
            projectile.onPoolReturn = {
                @Suppress("UNCHECKED_CAST")
                queue.addLast(it as T)
            }

            spawnProjectile(projectile, location, velocity)
        }

        fun clear() {
            queue.clear()
        }
    }
}