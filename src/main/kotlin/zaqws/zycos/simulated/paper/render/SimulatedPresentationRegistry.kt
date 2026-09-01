@file:Suppress("unused")

package zaqws.zycos.simulated.paper.render

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import zaqws.zycos.simulated.entity.SimulatedPresentationId

class SimulatedPresentationRegistry {
    private val presentations =
        Int2ObjectOpenHashMap<SimulatedPresentation>()

    private var nextPresentationId =
        FIRST_PRESENTATION_ID

    val size: Int
        get() =
            presentations.size

    val isEmpty: Boolean
        get() =
            presentations.isEmpty()

    fun register(
        presentation: SimulatedPresentation
    ): SimulatedPresentationId {
        val presentationId =
            allocatePresentationId()

        presentations.put(
            presentationId.value,
            presentation
        )

        return presentationId
    }

    fun unregister(
        presentationId: SimulatedPresentationId
    ): SimulatedPresentation? {
        if (
            presentationId ==
            SimulatedPresentationId.NONE
        ) {
            return null
        }

        return presentations.remove(
            presentationId.value
        )
    }

    operator fun get(
        presentationId: SimulatedPresentationId
    ): SimulatedPresentation? {
        if (
            presentationId ==
            SimulatedPresentationId.NONE
        ) {
            return null
        }

        return presentations[
            presentationId.value
        ]
    }

    operator fun contains(
        presentationId: SimulatedPresentationId
    ): Boolean =
        presentationId !=
                SimulatedPresentationId.NONE &&
                presentations.containsKey(
                    presentationId.value
                )

    fun clear() {
        presentations.clear()
    }

    private fun allocatePresentationId():
            SimulatedPresentationId {
        check(
            nextPresentationId > 0
        ) {
            "Simulated presentation identifier space exhausted"
        }

        return SimulatedPresentationId(
            nextPresentationId++
        )
    }

    companion object {
        private const val FIRST_PRESENTATION_ID =
            1
    }
}