package zaqws.zycos.simulated.entity

@JvmInline
value class SimulatedTeam(
    val value: Int
) {
    companion object {
        val NONE = SimulatedTeam(0)
    }

    init {
        require(value >= 0)
    }

    override fun toString() = value.toString()
}