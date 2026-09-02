@file:Suppress("unused")

package zaqws.zycos.simulated.goal

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import zaqws.zycos.simulated.SimulatedTarget
import zaqws.zycos.simulated.entity.SimulatedEntityFlag
import zaqws.zycos.simulated.entity.SimulatedEntityId
import zaqws.zycos.simulated.entity.SimulatedEntityStore
import zaqws.zycos.simulated.external.SimulatedExternalFrame
import zaqws.zycos.simulated.spatial.SimulatedEntityQuery
import zaqws.zycos.simulated.system.SimulatedSystem
import zaqws.zycos.simulated.system.SimulatedSystemContext

internal class SimulatedGoalSystem(
    private val entityStore: SimulatedEntityStore,
    private val entityQuery: SimulatedEntityQuery,
    private val goalSetProvider:
        (SimulatedEntityId) -> SimulatedGoalSet?,
    private val externalFrameProvider:
        () -> SimulatedExternalFrame,
    private val actionConsumer:
        (SimulatedGoalAction) -> Unit
) : SimulatedSystem {
    @ConsistentCopyVisibility
    data class ResolvedIntents internal constructor(
        val movement: SimulatedIntent?,
        val look: SimulatedIntent.LookAt?,
        val combat: SimulatedIntent?
    )

    private class EntityGoalState(
        val goalSet: SimulatedGoalSet,
        val runtimes: Array<SimulatedGoalRuntime>,
        val intents: Array<List<SimulatedIntent>>
    )

    private val goalStates =
        Int2ObjectOpenHashMap<EntityGoalState>()

    private val resolvedIntents =
        Int2ObjectOpenHashMap<ResolvedIntents>()

    private val targets =
        Int2ObjectOpenHashMap<SimulatedTarget>()

    private val evaluationBuffer =
        ArrayList<SimulatedIntent>(8)

    override fun update(
        context: SimulatedSystemContext
    ) {
        var slot = 0

        while (slot < entityStore.size) {
            if (canEvaluate(slot)) {
                evaluateEntity(
                    slot,
                    context.tick
                )
            }

            slot++
        }

        removeStaleState()
    }

    fun intents(
        entityId: SimulatedEntityId
    ): ResolvedIntents? =
        resolvedIntents[
            entityId.value
        ]

    fun movementIntent(
        entityId: SimulatedEntityId
    ): SimulatedIntent? =
        resolvedIntents[
            entityId.value
        ]?.movement

    fun lookIntent(
        entityId: SimulatedEntityId
    ): SimulatedIntent.LookAt? =
        resolvedIntents[
            entityId.value
        ]?.look

    fun attackIntent(
        entityId: SimulatedEntityId
    ): SimulatedIntent.Attack? =
        resolvedIntents[
            entityId.value
        ]?.combat as?
                SimulatedIntent.Attack

    fun shootIntent(
        entityId: SimulatedEntityId
    ): SimulatedIntent.Shoot? =
        resolvedIntents[
            entityId.value
        ]?.combat as?
                SimulatedIntent.Shoot

    fun target(
        entityId: SimulatedEntityId
    ): SimulatedTarget? =
        targets[
            entityId.value
        ]

    fun clearTarget(
        entityId: SimulatedEntityId
    ) {
        targets.remove(
            entityId.value
        )
    }

    fun clearEntity(
        entityId: SimulatedEntityId
    ) {
        goalStates.remove(
            entityId.value
        )

        resolvedIntents.remove(
            entityId.value
        )

        targets.remove(
            entityId.value
        )
    }

    fun clear() {
        goalStates.clear()
        resolvedIntents.clear()
        targets.clear()
        evaluationBuffer.clear()
    }

    private fun evaluateEntity(
        slot: Int,
        tick: Long
    ) {
        val entityId =
            entityStore.entityIdAt(
                slot
            )

        val goalSet =
            goalSetProvider(
                entityId
            )

        if (goalSet == null) {
            resolvedIntents.remove(
                entityId.value
            )

            return
        }

        if (goalSet.isEmpty) {
            resolvedIntents.remove(
                entityId.value
            )

            return
        }

        val state =
            goalState(
                entityId,
                goalSet
            )

        val currentTarget =
            validatedTarget(
                entityId
            )

        val goalContext =
            SimulatedGoalContext(
                entityStore =
                    entityStore,

                entityQuery =
                    entityQuery,

                externalFrame =
                    externalFrameProvider(),

                entityId =
                    entityId,

                tick =
                    tick,

                currentTarget =
                    currentTarget,

                actionConsumer =
                    actionConsumer
            )

        var goalIndex = 0

        while (
            goalIndex <
            goalSet.size
        ) {
            val entry =
                goalSet[goalIndex]

            if (
                shouldEvaluate(
                    entityId =
                        entityId,

                    entry =
                        entry,

                    tick =
                        tick
                )
            ) {
                evaluationBuffer.clear()

                entry.goal.evaluate(
                    context =
                        goalContext,

                    runtime =
                        state.runtimes[
                            goalIndex
                        ],

                    intents =
                        evaluationBuffer
                )

                state.intents[goalIndex] =
                    evaluationBuffer.toList()
            }

            goalIndex++
        }

        evaluationBuffer.clear()

        for (intents in state.intents) {
            evaluationBuffer.addAll(
                intents
            )
        }

        resolvedIntents.remove(
            entityId.value
        )

        resolveIntents(
            entityId,
            evaluationBuffer
        )
    }

    private fun resolveIntents(
        entityId: SimulatedEntityId,
        intents: List<SimulatedIntent>
    ) {
        var movementIntent:
                SimulatedIntent? = null

        var lookIntent:
                SimulatedIntent.LookAt? = null

        var combatIntent:
                SimulatedIntent? = null

        var targetIntent:
                SimulatedIntent.SetTarget? = null

        for (intent in intents) {
            when (intent) {
                is SimulatedIntent.MoveTo,
                is SimulatedIntent.MoveAwayFrom,
                is SimulatedIntent.StopMovement -> {
                    if (
                        movementIntent == null ||
                        intent.priority >
                        movementIntent.priority
                    ) {
                        movementIntent =
                            intent
                    }
                }

                is SimulatedIntent.LookAt -> {
                    if (
                        lookIntent == null ||
                        intent.priority >
                        lookIntent.priority
                    ) {
                        lookIntent =
                            intent
                    }
                }

                is SimulatedIntent.Attack -> {
                    if (
                        combatIntent == null ||
                        intent.priority >
                        combatIntent.priority
                    ) {
                        combatIntent =
                            intent
                    }
                }

                is SimulatedIntent.Shoot -> {
                    if (
                        combatIntent == null ||
                        intent.priority >
                        combatIntent.priority
                    ) {
                        combatIntent =
                            intent
                    }
                }

                is SimulatedIntent.SetTarget -> {
                    if (
                        targetIntent == null ||
                        intent.priority >
                        targetIntent.priority
                    ) {
                        targetIntent =
                            intent
                    }
                }
            }
        }

        if (targetIntent != null) {
            val target =
                targetIntent
                    .target

            if (target == null) {
                targets.remove(
                    entityId.value
                )
            } else {
                targets.put(
                    entityId.value,
                    target
                )
            }
        }

        if (
            movementIntent != null ||
            lookIntent != null ||
            combatIntent != null
        ) {
            resolvedIntents.put(
                entityId.value,
                ResolvedIntents(
                    movement =
                        movementIntent,

                    look =
                        lookIntent,

                    combat =
                        combatIntent
                )
            )
        }
    }

    private fun goalState(
        entityId: SimulatedEntityId,
        goalSet: SimulatedGoalSet
    ): EntityGoalState {
        val existingState =
            goalStates[
                entityId.value
            ]

        if (
            existingState != null &&
            existingState.goalSet ===
            goalSet
        ) {
            return existingState
        }

        val runtimes =
            Array(
                goalSet.size
            ) { goalIndex ->
                goalSet[
                    goalIndex
                ].goal.createRuntime()
            }

        val state =
            EntityGoalState(
                goalSet =
                    goalSet,

                runtimes =
                    runtimes,

                intents =
                    Array(goalSet.size) {
                        emptyList()
                    }
            )

        goalStates.put(
            entityId.value,
            state
        )

        return state
    }

    private fun validatedTarget(
        sourceEntityId: SimulatedEntityId
    ): SimulatedTarget? {
        val target =
            targets[
                sourceEntityId.value
            ] ?: return null

        val valid =
            when (target) {
                is SimulatedTarget.Entity -> {
                    val targetSlot =
                        entityStore.slotOf(
                            target.entityId
                        )

                    targetSlot >= 0 &&
                            !entityStore.hasFlag(
                                targetSlot,
                                SimulatedEntityFlag.REMOVED
                            ) &&
                            !entityStore.hasFlag(
                                targetSlot,
                                SimulatedEntityFlag.DEAD
                            )
                }

                is SimulatedTarget.ExternalActor ->
                    externalFrameProvider()[
                        target.actorId
                    ]?.isTargetable == true
            }

        if (!valid) {
            targets.remove(
                sourceEntityId.value
            )

            return null
        }

        return target
    }

    private fun shouldEvaluate(
        entityId: SimulatedEntityId,
        entry: SimulatedGoalSet.Entry,
        tick: Long
    ): Boolean {
        val phase =
            Math.floorMod(
                entityId.value +
                        entry.phaseOffsetTicks,
                entry.intervalTicks
            )

        return Math.floorMod(
            tick,
            entry.intervalTicks.toLong()
        ) == phase.toLong()
    }

    private fun canEvaluate(
        slot: Int
    ): Boolean =
        !entityStore.hasFlag(
            slot,
            SimulatedEntityFlag.REMOVED
        ) &&
                !entityStore.hasFlag(
                    slot,
                    SimulatedEntityFlag.DEAD
                )

    private fun removeStaleState() {
        val goalStateIterator =
            goalStates
                .int2ObjectEntrySet()
                .fastIterator()

        while (
            goalStateIterator.hasNext()
        ) {
            val entry =
                goalStateIterator.next()

            if (
                entityStore.slotOf(
                    SimulatedEntityId(
                        entry.intKey
                    )
                ) < 0
            ) {
                goalStateIterator.remove()
            }
        }

        val targetIterator =
            targets
                .int2ObjectEntrySet()
                .fastIterator()

        while (
            targetIterator.hasNext()
        ) {
            val entry =
                targetIterator.next()

            if (
                entityStore.slotOf(
                    SimulatedEntityId(
                        entry.intKey
                    )
                ) < 0
            ) {
                targetIterator.remove()
            }
        }

        val intentIterator =
            resolvedIntents
                .int2ObjectEntrySet()
                .fastIterator()

        while (
            intentIterator.hasNext()
        ) {
            val entry =
                intentIterator.next()

            if (
                entityStore.slotOf(
                    SimulatedEntityId(
                        entry.intKey
                    )
                ) < 0
            ) {
                intentIterator.remove()
            }
        }
    }

}
