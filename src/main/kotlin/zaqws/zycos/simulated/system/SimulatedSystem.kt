package zaqws.zycos.simulated.system

fun interface SimulatedSystem {
    fun update(context: SimulatedSystemContext)
}

class SimulatedSystemContext internal constructor(
    val tick: Long,
    val deltaSeconds: Double
) {
    init {
        require(tick >= 0L)
        require(deltaSeconds.isFinite())
        require(deltaSeconds >= 0.0)
    }
}
