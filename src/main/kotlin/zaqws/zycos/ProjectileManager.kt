@file:Suppress("unused")

package zaqws.zycos


import org.bukkit.Location
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.util.Vector

class ProjectileManager(plugin: JavaPlugin) {
    private val projectiles = mutableListOf<SyncedProjectile>()

    init {
        plugin.server.scheduler.runTaskTimer(
            plugin,
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
        projectile.startLocation = startLocation.clone()
        projectile.location = startLocation.clone()

        if (initialVelocity != null) projectile.velocity = initialVelocity.clone()

        projectile.initialize()

        projectiles.add(projectile)
    }

    private fun update() {
        var i = 0

        while (i < projectiles.size) {
            val projectile = projectiles[i]
            projectile.update()

            if (projectile.removed) {
                val lastIndex = projectiles.size - 1
                if (i != lastIndex) projectiles[i] = projectiles[lastIndex]

                projectiles.removeLast()
                projectile.onPoolReturn?.invoke(projectile)
            } else i++
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
        protected var tick = 0
            private set

        private var historyIndex = 0
        private val locationHistory = Array(2) {
            Location(null, 0.0, 0.0, 0.0)
        }
        private val velocityHistory = Array(2) {
            Vector()
        }

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

            val prevLocation = locationHistory[historyIndex]
            val prevVelocity = velocityHistory[historyIndex]

            onMovement(prevLocation, prevVelocity)

            locationHistory[historyIndex].clone(location)
            velocityHistory[historyIndex].clone(velocity)

            historyIndex = 1 - historyIndex
        }

        fun remove() {
            if(removed) return
            removed = true

            onRemove()
        }

        fun initialize() {
            locationHistory[0].clone(location)
            velocityHistory[0].clone(velocity)

            locationHistory[1].clone(location)
            velocityHistory[1].clone(velocity)

            onInitialize()
        }

        internal fun reset() {
            tick = 0
            removed = false

            velocity.zero()
        }

        protected open fun onInitialize() {}

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
            } ?: factory().apply {
                onPoolReturn = {
                    queue.addLast(this)
                }
            }

            initializer(projectile)

            spawnProjectile(projectile, location, velocity)
        }

        fun clear() {
            queue.clear()
        }
    }
}