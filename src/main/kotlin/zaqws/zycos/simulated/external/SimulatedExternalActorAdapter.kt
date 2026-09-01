@file:Suppress("unused")

package zaqws.zycos.simulated.external

import java.util.concurrent.atomic.AtomicLong

abstract class SimulatedExternalActorAdapter<
        Actor : Any,
        Identity : Any
        > : SimulatedExternalActorProvider {
    private val identityToActorId =
        HashMap<
                Identity,
                SimulatedExternalActorId
                >()

    private val actorIdToIdentity =
        HashMap<
                SimulatedExternalActorId,
                Identity
                >()

    private val nextActorId =
        AtomicLong(1L)

    @Synchronized
    override fun capture(
        sequence: Long
    ): SimulatedExternalFrame {
        require(sequence >= 0L)

        val actors =
            currentActors().toList()

        if (actors.isEmpty()) {
            identityToActorId.clear()
            actorIdToIdentity.clear()

            return SimulatedExternalFrame(
                sequence = sequence,
                actors = emptyList()
            )
        }

        val activeIdentities =
            HashSet<Identity>(
                actors.size
            )

        val snapshots =
            ArrayList<
                    SimulatedExternalActorSnapshot
                    >(
                actors.size
            )

        for (actor in actors) {
            val identity =
                identityOf(actor)

            check(
                activeIdentities.add(
                    identity
                )
            ) {
                "Duplicate external actor identity: $identity"
            }

            val actorId =
                actorIdInternal(
                    identity
                )

            val snapshot =
                snapshotOf(
                    actor = actor,
                    actorId = actorId
                )

            check(
                snapshot.actorId ==
                        actorId
            ) {
                "External actor snapshot returned a different actor identifier"
            }

            snapshots.add(
                snapshot
            )
        }

        removeInactiveActors(
            activeIdentities
        )

        return SimulatedExternalFrame(
            sequence = sequence,
            actors = snapshots
        )
    }

    @Synchronized
    fun actorId(
        actor: Actor
    ): SimulatedExternalActorId =
        actorIdInternal(
            identityOf(actor)
        )

    @Synchronized
    fun actorIdByIdentity(
        identity: Identity
    ): SimulatedExternalActorId =
        actorIdInternal(
            identity
        )

    @Synchronized
    fun identity(
        actorId: SimulatedExternalActorId
    ): Identity? =
        actorIdToIdentity[
            actorId
        ]

    @Synchronized
    fun actor(
        actorId: SimulatedExternalActorId
    ): Actor? {
        val identity =
            actorIdToIdentity[
                actorId
            ] ?: return null

        return actorByIdentity(
            identity
        )
    }

    @Synchronized
    fun contains(
        actorId: SimulatedExternalActorId
    ): Boolean =
        actorIdToIdentity.containsKey(
            actorId
        )

    @Synchronized
    fun remove(
        actorId: SimulatedExternalActorId
    ): Boolean {
        val identity =
            actorIdToIdentity.remove(
                actorId
            ) ?: return false

        identityToActorId.remove(
            identity
        )

        return true
    }

    @Synchronized
    fun clear() {
        identityToActorId.clear()
        actorIdToIdentity.clear()
    }

    protected abstract fun currentActors():
            Iterable<Actor>

    protected abstract fun identityOf(
        actor: Actor
    ): Identity

    protected abstract fun actorByIdentity(
        identity: Identity
    ): Actor?

    protected abstract fun snapshotOf(
        actor: Actor,
        actorId: SimulatedExternalActorId
    ): SimulatedExternalActorSnapshot

    private fun actorIdInternal(
        identity: Identity
    ): SimulatedExternalActorId {
        val existingActorId =
            identityToActorId[
                identity
            ]

        if (existingActorId != null) {
            return existingActorId
        }

        val actorId =
            allocateActorId()

        identityToActorId[
            identity
        ] = actorId

        actorIdToIdentity[
            actorId
        ] = identity

        return actorId
    }

    private fun removeInactiveActors(
        activeIdentities: Set<Identity>
    ) {
        val iterator =
            identityToActorId
                .entries
                .iterator()

        while (iterator.hasNext()) {
            val entry =
                iterator.next()

            if (
                entry.key in
                activeIdentities
            ) {
                continue
            }

            actorIdToIdentity.remove(
                entry.value
            )

            iterator.remove()
        }
    }

    private fun allocateActorId():
            SimulatedExternalActorId {
        val value =
            nextActorId.getAndIncrement()

        check(value > 0L) {
            "Simulated external actor identifier space exhausted"
        }

        return SimulatedExternalActorId(
            value
        )
    }
}