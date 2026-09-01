@file:Suppress("unused")

package zaqws.zycos.simulated.system

internal class SimulatedSystemPipeline(
    systems: Iterable<SimulatedSystem> = emptyList()
) {
    private val systems = ArrayList<SimulatedSystem>()

    init {
        this.systems.addAll(systems)
    }

    val size: Int
        get() = systems.size

    fun add(system: SimulatedSystem): SimulatedSystemPipeline {
        systems.add(system)

        return this
    }

    fun addAll(systems: Iterable<SimulatedSystem>): SimulatedSystemPipeline {
        this.systems.addAll(systems)

        return this
    }

    fun clear() {
        systems.clear()
    }

    fun update(context: SimulatedSystemContext) {
        for (index in systems.indices) {
            systems[index].update(context)
        }
    }
}