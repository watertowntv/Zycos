@file:Suppress("unused", "duplicates")

package zaqws.zycos


internal const val loadFactor = 0.75


class PrimitiveLongSet(initialCapacity: Int = 16) : Iterable<Long> {
    private var capacity = if (initialCapacity < 2) 2
    else Integer.highestOneBit(initialCapacity - 1) * 2

    private var keys = LongArray(capacity)
    private var containsZero = false

    private var threshold = (capacity * loadFactor).toInt()
    private var mask = capacity - 1

    var size = 0
        private set


    fun add(value: Long): Boolean {
        if (value == 0L) {
            if (containsZero) return false

            containsZero = true
            size++

            return true
        }

        if (size >= threshold) rehash()
        var idx = hash(value) and mask

        while (keys[idx] != 0L) {
            if (keys[idx] == value) return false

            idx = (idx + 1) and mask
        }

        keys[idx] = value
        size++

        return true
    }

    fun remove(value: Long): Boolean {
        if (value == 0L) {
            if (!containsZero) return false

            containsZero = false
            size--

            return true
        }

        var idx = hash(value) and mask
        while (keys[idx] != 0L) {
            if (keys[idx] == value) {
                shiftKeys(idx)
                size--

                return true
            }

            idx = (idx + 1) and mask
        }

        return false
    }

    operator fun contains(value: Long): Boolean {
        if (value == 0L) return containsZero
        var idx = hash(value) and mask

        while (keys[idx] != 0L) {
            if (keys[idx] == value) return true

            idx = (idx + 1) and mask
        }

        return false
    }

    override fun iterator(): Iterator<Long> = object : Iterator<Long> {
        var index = 0
        var zeroYielded = !containsZero
        var remaining = size

        override fun hasNext(): Boolean = remaining > 0

        override fun next(): Long {
            if (!hasNext()) throw NoSuchElementException()
            remaining--

            if (!zeroYielded) {
                zeroYielded = true

                return 0L
            }

            while (index < capacity) {
                val v = keys[index++]

                if (v != 0L) return v
            }

            throw NoSuchElementException()
        }
    }

    fun forEach(action: (Long) -> Unit) {
        if (containsZero) action(0L)

        for (k in keys) if (k != 0L) action(k)
    }

    fun clear() {
        size = 0
        containsZero = false

        keys.fill(0L)
    }

    fun isEmpty() = size == 0
    fun isNotEmpty() = size != 0

    fun addAll(elements: LongArray) {
        for (item in elements) add(item)
    }

    fun toLongArray(): LongArray {
        val result = LongArray(size)
        var targetIdx = 0

        if (containsZero) result[targetIdx++] = 0L
        for (k in keys) if (k != 0L) result[targetIdx++] = k

        return result
    }


    private fun rehash() {
        val oldKeys = keys

        capacity = capacity shl 1
        threshold = (capacity * loadFactor).toInt()

        keys = LongArray(capacity)
        mask = capacity - 1

        for (value in oldKeys) {
            if (value == 0L) continue

            rehash(value)
        }
    }

    private fun rehash(value: Long) {
        var idx = hash(value) and mask

        while (keys[idx] != 0L) {
            idx = (idx + 1) and mask
        }

        keys[idx] = value
    }

    private fun hash(v: Long): Int {
        val x = v xor (v ushr 33)

        return ((x * -0xae502812aa7333L) xor (x ushr 33)).toInt()
    }

    private fun shiftKeys(pos: Int) {
        var curr = pos
        var next: Int
        var k: Long

        while (true) {
            next = (curr + 1) and mask

            while (true) {
                k = keys[next]

                if (k == 0L) {
                    keys[curr] = 0L
                    return
                }

                val slot = hash(k) and mask
                val canMove = if (curr < next) (slot <= curr) || (slot > next)
                else (slot <= curr) && (slot > next)

                if (canMove) break
                next = (next + 1) and mask
            }

            keys[curr] = k
            curr = next
        }
    }
}
