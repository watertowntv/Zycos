@file:Suppress("unused")

package zaqws.zycos.simulated.goal

interface SimulatedGoal {
    fun createRuntime(): SimulatedGoalRuntime =
        SimulatedGoalRuntime.EMPTY

    fun evaluate(
        context: SimulatedGoalContext,
        runtime: SimulatedGoalRuntime,
        intents: MutableCollection<SimulatedIntent>
    )
}

interface SimulatedGoalRuntime {
    data object EMPTY : SimulatedGoalRuntime
}

class SimulatedGoalSet private constructor(
    goals: List<Entry>
) {
    data class Entry(
        val goal: SimulatedGoal,
        val intervalTicks: Int,
        val phaseOffsetTicks: Int
    ) {
        init {
            require(intervalTicks > 0)
            require(phaseOffsetTicks in 0 until intervalTicks)
        }

        fun shouldEvaluate(
            tick: Long
        ): Boolean =
            (tick + phaseOffsetTicks) %
                    intervalTicks == 0L
    }

    private val entries =
        goals.toList()

    val size: Int
        get() = entries.size

    val isEmpty: Boolean
        get() = entries.isEmpty()

    operator fun get(
        index: Int
    ): Entry =
        entries[index]

    fun asList(): List<Entry> =
        entries

    class Builder(
        private val defaultIntervalTicks: Int = 1
    ) {
        init {
            require(defaultIntervalTicks > 0)
        }

        private val entries =
            ArrayList<Entry>()

        fun goal(
            goal: SimulatedGoal,
            intervalTicks: Int = defaultIntervalTicks,
            phaseOffsetTicks: Int = 0
        ): Builder {
            entries.add(
                Entry(
                    goal = goal,
                    intervalTicks = intervalTicks,
                    phaseOffsetTicks = phaseOffsetTicks
                )
            )

            return this
        }

        fun build(): SimulatedGoalSet =
            SimulatedGoalSet(entries)
    }

    companion object {
        val EMPTY =
            SimulatedGoalSet(
                emptyList()
            )

        fun build(
            block: Builder.() -> Unit
        ): SimulatedGoalSet =
            Builder(1)
                .apply(block)
                .build()

        fun build(
            defaultIntervalTicks: Int,
            block: Builder.() -> Unit
        ): SimulatedGoalSet =
            Builder(defaultIntervalTicks)
                .apply(block)
                .build()
    }
}
