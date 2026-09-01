package zaqws.zycos.simulated.external

interface SimulatedExternalActorProvider {
    fun capture(
        sequence: Long
    ): SimulatedExternalFrame
}