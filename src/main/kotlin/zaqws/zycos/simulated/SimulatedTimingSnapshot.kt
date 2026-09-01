package zaqws.zycos.simulated

data class SimulatedTimingSnapshot(
    val measuredTicks: Long,
    val latestTickNanoseconds: Long,
    val averageTickNanoseconds: Long,
    val maximumTickNanoseconds: Long
) {
    init {
        require(measuredTicks >= 0L)
        require(latestTickNanoseconds >= 0L)
        require(averageTickNanoseconds >= 0L)
        require(maximumTickNanoseconds >= 0L)
    }
}
