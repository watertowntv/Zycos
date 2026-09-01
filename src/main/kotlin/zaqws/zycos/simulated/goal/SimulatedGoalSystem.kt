@file:Suppress("unused")

package zaqws.zycos.simulated.goal

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import zaqws.zycos.simulated.entity.SimulatedEntityFlag
import zaqws.zycos.simulated.entity.SimulatedEntityId
import zaqws.zycos.simulated.entity.SimulatedEntityStore
import zaqws.zycos.simulated.spatial.SimulatedEntityQuery
import zaqws.zycos.simulated.system.SimulatedSystem
import zaqws.zycos.simulated.system.SimulatedSystemContext

internal class SimulatedGoalSystem(
    private val entityStore: SimulatedEntityStore,
    private val entityQuery: SimulatedEntityQuery,
    private val goalSetProvider:
        (SimulatedEntityId) -> SimulatedGoalSet?
) : SimulatedSystem {
    @ConsistentCopyVisibility
    data class ResolvedIntents internal constructor(
        val movement: SimulatedIntent?,
        val look: SimulatedIntent.LookAt?,
        val attack: SimulatedIntent.Attack?
    )

    private data class EntityGoalState(
        val goalSet: SimulatedGoalSet,
        val runtimes: Array<SimulatedGoalRuntime>
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as EntityGoalState

            if (goalSet != other.goalSet) return false
            if (!runtimes.contentEquals(other.runtimes)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = goalSet.hashCode()
            result = 31 * result + runtimes.contentHashCode()
            return result
        }
    }

    private val goalStates =
        Int2ObjectOpenHashMap<EntityGoalState>()

    private val resolvedIntents =
        Int2ObjectOpenHashMap<ResolvedIntents>()

    private val targetEntityIds =
        Int2IntOpenHashMap().apply {
            defaultReturnValue(NO_TARGET)
        }

    private val evaluationBuffer =
        ArrayList<SimulatedIntent>(8)

    override fun update(
        context: SimulatedSystemContext
    ) {
        resolvedIntents.clear()

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
        ]?.attack

    fun target(
        entityId: SimulatedEntityId
    ): SimulatedEntityId? {
        val targetEntityId =
            targetEntityIds.get(
                entityId.value
            )

        return if (
            targetEntityId ==
            NO_TARGET
        ) {
            null
        } else {
            SimulatedEntityId(
                targetEntityId
            )
        }
    }

    fun clearTarget(
        entityId: SimulatedEntityId
    ) {
        targetEntityIds.remove(
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

        targetEntityIds.remove(
            entityId.value
        )
    }

    fun clear() {
        goalStates.clear()
        resolvedIntents.clear()
        targetEntityIds.clear()
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
            ) ?: return

        if (goalSet.isEmpty) {
            return
        }

        val state =
            goalState(
                entityId,
                goalSet
            )

        val currentTargetEntityId =
            validatedTarget(
                entityId
            )

        val goalContext =
            SimulatedGoalContext(
                entityStore =
                    entityStore,

                entityQuery =
                    entityQuery,

                entityId =
                    entityId,

                tick =
                    tick,

                currentTargetEntityId =
                    currentTargetEntityId
            )

        evaluationBuffer.clear()

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
            }

            goalIndex++
        }

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

        var attackIntent:
                SimulatedIntent.Attack? = null

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
                        attackIntent == null ||
                        intent.priority >
                        attackIntent.priority
                    ) {
                        attackIntent =
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
            val targetEntityId =
                targetIntent
                    .targetEntityId

            if (targetEntityId == null) {
                targetEntityIds.remove(
                    entityId.value
                )
            } else {
                targetEntityIds.put(
                    entityId.value,
                    targetEntityId.value
                )
            }
        }

        if (
            movementIntent != null ||
            lookIntent != null ||
            attackIntent != null
        ) {
            resolvedIntents.put(
                entityId.value,
                ResolvedIntents(
                    movement =
                        movementIntent,

                    look =
                        lookIntent,

                    attack =
                        attackIntent
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
                    runtimes
            )

        goalStates.put(
            entityId.value,
            state
        )

        return state
    }

    private fun validatedTarget(
        sourceEntityId: SimulatedEntityId
    ): SimulatedEntityId? {
        val targetEntityIdValue =
            targetEntityIds.get(
                sourceEntityId.value
            )

        if (
            targetEntityIdValue ==
            NO_TARGET
        ) {
            return null
        }

        val targetEntityId =
            SimulatedEntityId(
                targetEntityIdValue
            )

        val targetSlot =
            entityStore.slotOf(
                targetEntityId
            )

        if (
            targetSlot < 0 ||
            entityStore.hasFlag(
                targetSlot,
                SimulatedEntityFlag.REMOVED
            ) ||
            entityStore.hasFlag(
                targetSlot,
                SimulatedEntityFlag.DEAD
            )
        ) {
            targetEntityIds.remove(
                sourceEntityId.value
            )

            return null
        }

        return targetEntityId
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
            targetEntityIds
                .int2IntEntrySet()
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
    }

    companion object {
        private const val NO_TARGET = 0
    }
}