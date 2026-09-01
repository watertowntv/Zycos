@file:Suppress("unused")

package zaqws.zycos.simulated.entity

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import zaqws.zycos.simulated.math.SimulatedVector3
import kotlin.math.max

internal class SimulatedEntityStore(
    initialCapacity: Int = DEFAULT_INITIAL_CAPACITY
) {
    companion object {
        private const val INVALID_SLOT = -1
        private const val MINIMUM_CAPACITY = 16
        private const val DEFAULT_INITIAL_CAPACITY = 256
    }

    private var capacity = initialCapacity.coerceAtLeast(MINIMUM_CAPACITY)

    private val entityIdToSlot = Int2IntOpenHashMap(capacity).apply {
        defaultReturnValue(INVALID_SLOT)
    }

    private var entityIds = IntArray(capacity)

    private var positionX = DoubleArray(capacity)
    private var positionY = DoubleArray(capacity)
    private var positionZ = DoubleArray(capacity)

    private var velocityX = DoubleArray(capacity)
    private var velocityY = DoubleArray(capacity)
    private var velocityZ = DoubleArray(capacity)

    private var yaw = FloatArray(capacity)
    private var pitch = FloatArray(capacity)

    private var hitboxWidth = DoubleArray(capacity)
    private var hitboxHeight = DoubleArray(capacity)

    private var health = DoubleArray(capacity)
    private var maximumHealth = DoubleArray(capacity)

    private var movementSpeed = DoubleArray(capacity)
    private var attackDamage = DoubleArray(capacity)
    private var attackRange = DoubleArray(capacity)
    private var attackCooldownTicks = IntArray(capacity)
    private var attackCooldownRemainingTicks = IntArray(capacity)
    private var knockbackStrength = DoubleArray(capacity)

    private var teams = IntArray(capacity)
    private var presentationIds = IntArray(capacity)
    private var flags = LongArray(capacity)

    private var nextEntityId = 1

    var size: Int = 0
        private set

    val isEmpty: Boolean
        get() = size == 0

    val isNotEmpty: Boolean
        get() = size != 0

    fun create(
        position: SimulatedVector3,
        velocity: SimulatedVector3 = SimulatedVector3.ZERO,
        yaw: Float = 0.0f,
        pitch: Float = 0.0f,
        hitbox: SimulatedHitbox = SimulatedHitbox.DEFAULT,
        attributes: SimulatedAttributes = SimulatedAttributes.DEFAULT,
        team: SimulatedTeam = SimulatedTeam.NONE,
        presentationId: SimulatedPresentationId = SimulatedPresentationId.NONE,
        flags: SimulatedEntityFlags = SimulatedEntityFlags.NONE
    ): SimulatedEntityId {
        require(position.isFinite)
        require(velocity.isFinite)
        require(yaw.isFinite())
        require(pitch.isFinite())

        ensureCapacity(size + 1)

        val entityId = allocateEntityId()
        val slot = size++

        entityIds[slot] = entityId.value
        entityIdToSlot.put(entityId.value, slot)

        positionX[slot] = position.x
        positionY[slot] = position.y
        positionZ[slot] = position.z

        velocityX[slot] = velocity.x
        velocityY[slot] = velocity.y
        velocityZ[slot] = velocity.z

        this.yaw[slot] = yaw
        this.pitch[slot] = pitch

        hitboxWidth[slot] = hitbox.width
        hitboxHeight[slot] = hitbox.height

        health[slot] = attributes.maximumHealth
        maximumHealth[slot] = attributes.maximumHealth

        movementSpeed[slot] = attributes.movementSpeed
        attackDamage[slot] = attributes.attackDamage
        attackRange[slot] = attributes.attackRange
        attackCooldownTicks[slot] = attributes.attackCooldownTicks
        attackCooldownRemainingTicks[slot] = 0
        knockbackStrength[slot] = attributes.knockbackStrength

        teams[slot] = team.value
        presentationIds[slot] = presentationId.value
        this.flags[slot] = flags.bits

        return entityId
    }

    fun remove(entityId: SimulatedEntityId): Boolean {
        val slot = entityIdToSlot.remove(entityId.value)
        if (slot == INVALID_SLOT) return false

        val lastSlot = size - 1

        if (slot != lastSlot) {
            copySlot(lastSlot, slot)

            val movedEntityId = entityIds[slot]
            entityIdToSlot.put(movedEntityId, slot)
        }

        clearSlot(lastSlot)
        size = lastSlot

        return true
    }

    fun clear() {
        entityIdToSlot.clear()

        entityIds.fill(0, 0, size)

        positionX.fill(0.0, 0, size)
        positionY.fill(0.0, 0, size)
        positionZ.fill(0.0, 0, size)

        velocityX.fill(0.0, 0, size)
        velocityY.fill(0.0, 0, size)
        velocityZ.fill(0.0, 0, size)

        yaw.fill(0.0f, 0, size)
        pitch.fill(0.0f, 0, size)

        hitboxWidth.fill(0.0, 0, size)
        hitboxHeight.fill(0.0, 0, size)

        health.fill(0.0, 0, size)
        maximumHealth.fill(0.0, 0, size)

        movementSpeed.fill(0.0, 0, size)
        attackDamage.fill(0.0, 0, size)
        attackRange.fill(0.0, 0, size)
        attackCooldownTicks.fill(0, 0, size)
        attackCooldownRemainingTicks.fill(0, 0, size)
        knockbackStrength.fill(0.0, 0, size)

        teams.fill(0, 0, size)
        presentationIds.fill(0, 0, size)
        flags.fill(0L, 0, size)

        size = 0
    }

    operator fun contains(entityId: SimulatedEntityId): Boolean =
        entityIdToSlot.containsKey(entityId.value)

    fun entityIdAt(slot: Int): SimulatedEntityId {
        requireValidSlot(slot)

        return SimulatedEntityId(entityIds[slot])
    }

    fun slotOf(entityId: SimulatedEntityId): Int =
        entityIdToSlot.get(entityId.value)

    fun requireSlot(entityId: SimulatedEntityId): Int {
        val slot = slotOf(entityId)

        require(slot != INVALID_SLOT) {
            "Unknown simulated entity: $entityId"
        }

        return slot
    }

    fun position(slot: Int): SimulatedVector3 {
        requireValidSlot(slot)

        return SimulatedVector3(
            positionX[slot],
            positionY[slot],
            positionZ[slot]
        )
    }

    fun setPosition(
        slot: Int,
        position: SimulatedVector3
    ) {
        requireValidSlot(slot)
        require(position.isFinite)

        positionX[slot] = position.x
        positionY[slot] = position.y
        positionZ[slot] = position.z
    }

    fun setPosition(
        slot: Int,
        x: Double,
        y: Double,
        z: Double
    ) {
        requireValidSlot(slot)
        require(x.isFinite())
        require(y.isFinite())
        require(z.isFinite())

        positionX[slot] = x
        positionY[slot] = y
        positionZ[slot] = z
    }

    fun velocity(slot: Int): SimulatedVector3 {
        requireValidSlot(slot)

        return SimulatedVector3(
            velocityX[slot],
            velocityY[slot],
            velocityZ[slot]
        )
    }

    fun setVelocity(
        slot: Int,
        velocity: SimulatedVector3
    ) {
        requireValidSlot(slot)
        require(velocity.isFinite)

        velocityX[slot] = velocity.x
        velocityY[slot] = velocity.y
        velocityZ[slot] = velocity.z
    }

    fun setVelocity(
        slot: Int,
        x: Double,
        y: Double,
        z: Double
    ) {
        requireValidSlot(slot)
        require(x.isFinite())
        require(y.isFinite())
        require(z.isFinite())

        velocityX[slot] = x
        velocityY[slot] = y
        velocityZ[slot] = z
    }

    fun addVelocity(
        slot: Int,
        velocity: SimulatedVector3
    ) {
        requireValidSlot(slot)
        require(velocity.isFinite)

        velocityX[slot] += velocity.x
        velocityY[slot] += velocity.y
        velocityZ[slot] += velocity.z
    }

    fun yaw(slot: Int): Float {
        requireValidSlot(slot)

        return yaw[slot]
    }

    fun pitch(slot: Int): Float {
        requireValidSlot(slot)

        return pitch[slot]
    }

    fun setRotation(
        slot: Int,
        yaw: Float,
        pitch: Float
    ) {
        requireValidSlot(slot)
        require(yaw.isFinite())
        require(pitch.isFinite())

        this.yaw[slot] = yaw
        this.pitch[slot] = pitch
    }

    fun hitbox(slot: Int): SimulatedHitbox {
        requireValidSlot(slot)

        return SimulatedHitbox(
            hitboxWidth[slot],
            hitboxHeight[slot]
        )
    }

    fun health(slot: Int): Double {
        requireValidSlot(slot)

        return health[slot]
    }

    fun maximumHealth(slot: Int): Double {
        requireValidSlot(slot)

        return maximumHealth[slot]
    }

    fun setHealth(
        slot: Int,
        health: Double
    ) {
        requireValidSlot(slot)
        require(health.isFinite())

        this.health[slot] = health.coerceIn(
            0.0,
            maximumHealth[slot]
        )
    }

    fun damage(
        slot: Int,
        amount: Double
    ): Double {
        requireValidSlot(slot)
        require(amount.isFinite())
        require(amount >= 0.0)

        val previousHealth = health[slot]
        val nextHealth = max(0.0, previousHealth - amount)

        health[slot] = nextHealth

        if (nextHealth <= 0.0) {
            flags[slot] = flags[slot] or SimulatedEntityFlag.DEAD.mask
        }

        return previousHealth - nextHealth
    }

    fun heal(
        slot: Int,
        amount: Double
    ): Double {
        requireValidSlot(slot)
        require(amount.isFinite())
        require(amount >= 0.0)

        val previousHealth = health[slot]
        val nextHealth = minOf(
            maximumHealth[slot],
            previousHealth + amount
        )

        health[slot] = nextHealth

        if (nextHealth > 0.0) {
            flags[slot] = flags[slot] and SimulatedEntityFlag.DEAD.mask.inv()
        }

        return nextHealth - previousHealth
    }

    fun movementSpeed(slot: Int): Double {
        requireValidSlot(slot)

        return movementSpeed[slot]
    }

    fun attackDamage(slot: Int): Double {
        requireValidSlot(slot)

        return attackDamage[slot]
    }

    fun attackRange(slot: Int): Double {
        requireValidSlot(slot)

        return attackRange[slot]
    }

    fun attackCooldownTicks(slot: Int): Int {
        requireValidSlot(slot)

        return attackCooldownTicks[slot]
    }

    fun attackCooldownRemainingTicks(slot: Int): Int {
        requireValidSlot(slot)

        return attackCooldownRemainingTicks[slot]
    }

    fun setAttackCooldownRemainingTicks(
        slot: Int,
        ticks: Int
    ) {
        requireValidSlot(slot)
        require(ticks >= 0)

        attackCooldownRemainingTicks[slot] = ticks
    }

    fun decreaseAttackCooldown(slot: Int) {
        requireValidSlot(slot)

        if (attackCooldownRemainingTicks[slot] > 0) {
            attackCooldownRemainingTicks[slot]--
        }
    }

    fun knockbackStrength(slot: Int): Double {
        requireValidSlot(slot)

        return knockbackStrength[slot]
    }

    fun attributes(slot: Int): SimulatedAttributes {
        requireValidSlot(slot)

        return SimulatedAttributes(
            maximumHealth = maximumHealth[slot],
            movementSpeed = movementSpeed[slot],
            attackDamage = attackDamage[slot],
            attackRange = attackRange[slot],
            attackCooldownTicks = attackCooldownTicks[slot],
            knockbackStrength = knockbackStrength[slot]
        )
    }

    fun team(slot: Int): SimulatedTeam {
        requireValidSlot(slot)

        return SimulatedTeam(teams[slot])
    }

    fun setTeam(
        slot: Int,
        team: SimulatedTeam
    ) {
        requireValidSlot(slot)

        teams[slot] = team.value
    }

    fun presentationId(slot: Int): SimulatedPresentationId {
        requireValidSlot(slot)

        return SimulatedPresentationId(presentationIds[slot])
    }

    fun setPresentationId(
        slot: Int,
        presentationId: SimulatedPresentationId
    ) {
        requireValidSlot(slot)

        presentationIds[slot] = presentationId.value
    }

    fun flags(slot: Int): SimulatedEntityFlags {
        requireValidSlot(slot)

        return SimulatedEntityFlags(flags[slot])
    }

    fun hasFlag(
        slot: Int,
        flag: SimulatedEntityFlag
    ): Boolean {
        requireValidSlot(slot)

        return flags[slot] and flag.mask != 0L
    }

    fun setFlag(
        slot: Int,
        flag: SimulatedEntityFlag,
        enabled: Boolean
    ) {
        requireValidSlot(slot)

        flags[slot] = if (enabled) {
            flags[slot] or flag.mask
        } else {
            flags[slot] and flag.mask.inv()
        }
    }

    inline fun forEachSlot(action: (slot: Int) -> Unit) {
        var slot = 0

        while (slot < size) {
            action(slot)
            slot++
        }
    }

    private fun allocateEntityId(): SimulatedEntityId {
        check(nextEntityId > 0) {
            "Simulated entity identifier space exhausted"
        }

        return SimulatedEntityId(nextEntityId++)
    }

    private fun ensureCapacity(requiredCapacity: Int) {
        if (requiredCapacity <= capacity) return

        var newCapacity = capacity

        while (newCapacity < requiredCapacity) {
            newCapacity = newCapacity shl 1

            check(newCapacity > 0) {
                "Simulated entity store capacity exhausted"
            }
        }

        capacity = newCapacity

        entityIds = entityIds.copyOf(capacity)

        positionX = positionX.copyOf(capacity)
        positionY = positionY.copyOf(capacity)
        positionZ = positionZ.copyOf(capacity)

        velocityX = velocityX.copyOf(capacity)
        velocityY = velocityY.copyOf(capacity)
        velocityZ = velocityZ.copyOf(capacity)

        yaw = yaw.copyOf(capacity)
        pitch = pitch.copyOf(capacity)

        hitboxWidth = hitboxWidth.copyOf(capacity)
        hitboxHeight = hitboxHeight.copyOf(capacity)

        health = health.copyOf(capacity)
        maximumHealth = maximumHealth.copyOf(capacity)

        movementSpeed = movementSpeed.copyOf(capacity)
        attackDamage = attackDamage.copyOf(capacity)
        attackRange = attackRange.copyOf(capacity)
        attackCooldownTicks = attackCooldownTicks.copyOf(capacity)
        attackCooldownRemainingTicks =
            attackCooldownRemainingTicks.copyOf(capacity)
        knockbackStrength = knockbackStrength.copyOf(capacity)

        teams = teams.copyOf(capacity)
        presentationIds = presentationIds.copyOf(capacity)
        flags = flags.copyOf(capacity)
    }

    private fun copySlot(
        sourceSlot: Int,
        destinationSlot: Int
    ) {
        entityIds[destinationSlot] = entityIds[sourceSlot]

        positionX[destinationSlot] = positionX[sourceSlot]
        positionY[destinationSlot] = positionY[sourceSlot]
        positionZ[destinationSlot] = positionZ[sourceSlot]

        velocityX[destinationSlot] = velocityX[sourceSlot]
        velocityY[destinationSlot] = velocityY[sourceSlot]
        velocityZ[destinationSlot] = velocityZ[sourceSlot]

        yaw[destinationSlot] = yaw[sourceSlot]
        pitch[destinationSlot] = pitch[sourceSlot]

        hitboxWidth[destinationSlot] = hitboxWidth[sourceSlot]
        hitboxHeight[destinationSlot] = hitboxHeight[sourceSlot]

        health[destinationSlot] = health[sourceSlot]
        maximumHealth[destinationSlot] = maximumHealth[sourceSlot]

        movementSpeed[destinationSlot] = movementSpeed[sourceSlot]
        attackDamage[destinationSlot] = attackDamage[sourceSlot]
        attackRange[destinationSlot] = attackRange[sourceSlot]
        attackCooldownTicks[destinationSlot] = attackCooldownTicks[sourceSlot]
        attackCooldownRemainingTicks[destinationSlot] =
            attackCooldownRemainingTicks[sourceSlot]
        knockbackStrength[destinationSlot] = knockbackStrength[sourceSlot]

        teams[destinationSlot] = teams[sourceSlot]
        presentationIds[destinationSlot] = presentationIds[sourceSlot]
        flags[destinationSlot] = flags[sourceSlot]
    }

    private fun clearSlot(slot: Int) {
        entityIds[slot] = 0

        positionX[slot] = 0.0
        positionY[slot] = 0.0
        positionZ[slot] = 0.0

        velocityX[slot] = 0.0
        velocityY[slot] = 0.0
        velocityZ[slot] = 0.0

        yaw[slot] = 0.0f
        pitch[slot] = 0.0f

        hitboxWidth[slot] = 0.0
        hitboxHeight[slot] = 0.0

        health[slot] = 0.0
        maximumHealth[slot] = 0.0

        movementSpeed[slot] = 0.0
        attackDamage[slot] = 0.0
        attackRange[slot] = 0.0
        attackCooldownTicks[slot] = 0
        attackCooldownRemainingTicks[slot] = 0
        knockbackStrength[slot] = 0.0

        teams[slot] = 0
        presentationIds[slot] = 0
        flags[slot] = 0L
    }

    private fun requireValidSlot(slot: Int) {
        require(slot in 0 until size) {
            "Invalid simulated entity slot: $slot"
        }
    }
}