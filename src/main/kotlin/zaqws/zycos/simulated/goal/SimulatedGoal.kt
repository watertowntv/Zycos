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