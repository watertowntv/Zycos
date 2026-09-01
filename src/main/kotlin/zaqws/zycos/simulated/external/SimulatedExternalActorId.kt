package zaqws.zycos.simulated.external

@JvmInline
value class SimulatedExternalActorId(
    val value: Long
) {
    init {
        require(value > 0L)
    }

    override fun toString(): String =
        value.toString()
}