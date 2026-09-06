@file:Suppress("unused")

package zaqws.zycos.deprecated

import java.util.*

@Deprecated("Not Used")
class BinaryList<T> : AbstractMutableList<T>, RandomAccess {
    private val list: ArrayList<T>
    private val comparator: Comparator<T>

    override val size: Int
        get() = list.size

    constructor(comparator: Comparator<T>) : super() {
        this.list = ArrayList()
        this.comparator = comparator
    }
    constructor(elements: Collection<T>, comparator: Comparator<T>) : super() {
        this.list = ArrayList(elements).apply { sortWith(comparator) }
        this.comparator = comparator
    }

    fun copy(): BinaryList<T> = BinaryList(ArrayList(list), comparator)

    override fun get(index: Int) = list[index]
    override fun add(element: T): Boolean {
        val insertionPoint = list.binarySearch(element, comparator).let {
            if (it < 0) -it - 1 else it
        }

        list.add(insertionPoint, element)

        modCount++
        return true
    }
    override fun addAll(elements: Collection<T>): Boolean {
        if (elements.isEmpty()) return false

        val incoming = elements.sortedWith(comparator)
        val merged = ArrayList<T>(list.size + incoming.size)

        var i = 0
        var j = 0

        while (i < list.size && j < incoming.size) {
            if (comparator.compare(list[i], incoming[j]) <= 0) merged.add(list[i++])
            else merged.add(incoming[j++])
        }

        while (i < list.size) merged.add(list[i++])
        while (j < incoming.size) merged.add(incoming[j++])

        list.clear()
        list.addAll(merged)

        modCount++

        return true
    }

    override fun remove(element: T): Boolean {
        val index = indexOf(element)

        if (index >= 0) {
            removeAt(index)
            return true
        }

        return false
    }
    override fun removeAt(index: Int): T {
        val removed = list.removeAt(index)
        modCount++

        return removed
    }

    override fun contains(element: T): Boolean = binarySearchFirst(element) != -1
    override fun indexOf(element: T): Int = binarySearchFirst(element)
    override fun lastIndexOf(element: T): Int = binarySearchLast(element)


    override fun add(index: Int, element: T) = throw UnsupportedOperationException("Cannot add at specific index in Sorted List")
    override fun set(index: Int, element: T) = throw UnsupportedOperationException("Cannot set at specific index in Sorted List")

    private fun binarySearchFirst(element: T): Int {
        var low = 0
        var high = list.size - 1
        var result = -1

        while (low <= high) {
            val mid = (low + high) ushr 1
            val cmp = comparator.compare(list[mid], element)

            when {
                cmp == 0 -> {
                    result = mid
                    high = mid - 1
                }
                cmp < 0 -> low = mid + 1
                else -> high = mid - 1
            }
        }

        return result
    }
    private fun binarySearchLast(element: T): Int {
        var low = 0
        var high = list.size - 1
        var result = -1

        while (low <= high) {
            val mid = (low + high) ushr 1
            val cmp = comparator.compare(list[mid], element)

            when {
                cmp == 0 -> {
                    result = mid
                    low = mid + 1
                }
                cmp < 0 -> low = mid + 1
                else -> high = mid - 1
            }
        }

        return result
    }
}