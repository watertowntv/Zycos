@file:Suppress("unused")

package zaqws.zycos.simulated

import zaqws.zycos.simulated.map.SimulatedMap
import zaqws.zycos.simulated.system.SimulatedSystem

class SimulatedEngineBuilder(
    private val map: SimulatedMap
) {
    private var config = SimulatedConfig()
    private val systems = ArrayList<SimulatedSystem>()

    fun config(config: SimulatedConfig): SimulatedEngineBuilder {
        this.config = config

        return this
    }

    fun system(system: SimulatedSystem): SimulatedEngineBuilder {
        systems.add(system)

        return this
    }

    fun systems(systems: Iterable<SimulatedSystem>): SimulatedEngineBuilder {
        this.systems.addAll(systems)

        return this
    }

    fun build(
        start: Boolean = false
    ): SimulatedEngine {
        val engine =
            SimulatedEngine(
                map,
                config
            )

        for (system in systems) {
            engine.registerSystem(system)
        }

        if (start) {
            engine.start()
        }

        return engine
    }
}
