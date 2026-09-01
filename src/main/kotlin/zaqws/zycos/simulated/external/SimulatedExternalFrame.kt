@file:Suppress("unused")

package zaqws.zycos.simulated.external

class SimulatedExternalFrame(
    val sequence: Long,
    actors: Collection<SimulatedExternalActorSnapshot>
) {
    private val actors =
        actors.toList()

    private val actorIndex =
        HashMap<
                SimulatedExternalActorId,
                Int
                >(this.actors.size)

    init {
        require(sequence >= 0L)

        for (
        index in
        this.actors.indices
        ) {
            val actor =
                this.actors[index]

            require(
                actorIndex.put(
                    actor.actorId,
                    index
                ) == null
            ) {
                "Duplicate external actor identifier: ${actor.actorId}"
            }
        }
    }

    val size: Int
        get() =
            actors.size

    val isEmpty: Boolean
        get() =
            actors.isEmpty()

    operator fun get(
        index: Int
    ): SimulatedExternalActorSnapshot =
        actors[index]

    operator fun get(
        actorId: SimulatedExternalActorId
    ): SimulatedExternalActorSnapshot? {
        val index =
            actorIndex[actorId]
                ?: return null

        return actors[index]
    }

    operator fun contains(
        actorId: SimulatedExternalActorId
    ): Boolean =
        actorIndex.containsKey(
            actorId
        )

    fun asList():
            List<SimulatedExternalActorSnapshot> =
        actors

    inline fun forEach(
        action: (
            SimulatedExternalActorSnapshot
        ) -> Unit
    ) {
        var index = 0

        while (index < size) {
            action(this[index])
            index++
        }
    }

    companion object {
        val EMPTY =
            SimulatedExternalFrame(
                sequence = 0L,
                actors = emptyList()
            )
    }
}
