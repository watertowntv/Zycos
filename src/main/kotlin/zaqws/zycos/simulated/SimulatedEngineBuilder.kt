@file:Suppress("unused")

package zaqws.zycos.simulated

import zaqws.zycos.simulated.map.SimulatedMap
import zaqws.zycos.simulated.system.SimulatedSystem

class SimulatedEngineBuilder(private val map: SimulatedMap) {
    private var config = SimulatedConfig()
    private val systems = ArrayList<SimulatedSystem>()

    fun config(config: SimulatedConfig) = apply {
        this.config = config
    }

    fun system(system: SimulatedSystem) = apply {
        systems.add(system)
    }

    fun systems(systems: Iterable<SimulatedSystem>) = apply {
        this.systems.addAll(systems)
    }

    fun build(start: Boolean = false): SimulatedEngine {
        val engine = SimulatedEngine(map, config)

        for (system in systems) {
            engine.registerSystem(system)
        }

        if (start) engine.start()
        return engine
    }
}
