package zaqws.zycos.simulated.entity

@JvmInline
value class SimulatedEntityId(
    val value: Int
) {
    init {
        require(value > 0)
    }

    override fun toString() = value.toString()
}
