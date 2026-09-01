package zaqws.zycos.simulated.paper.map

@JvmInline
value class SimulatedMapRevision(
    val value: Long
) : Comparable<SimulatedMapRevision> {
    companion object {
        val INITIAL = SimulatedMapRevision(0L)
    }

    init {
        require(value >= 0L)
    }

    override fun compareTo(
        other: SimulatedMapRevision
    ): Int =
        value.compareTo(other.value)

    operator fun inc(): SimulatedMapRevision {
        check(value < Long.MAX_VALUE) {
            "Simulated map revision space exhausted"
        }

        return SimulatedMapRevision(value + 1L)
    }
}