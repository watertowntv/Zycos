package zaqws.zycos.simulated.entity

@JvmInline
value class SimulatedPresentationId(
    val value: Int
) {
    companion object {
        val NONE = SimulatedPresentationId(0)
    }

    init {
        require(value >= 0)
    }

    override fun toString() = value.toString()
}
