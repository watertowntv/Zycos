@file:Suppress("unused")

package zaqws.zycos

import java.util.*

class BinaryList<T> : AbstractMutableList<T>, RandomAccess, Cloneable {
    private val list: ArrayList<T>
    private val comparator: Comparator<T>

    override val size: Int
        get() = list.size

    constructor(comparator: Comparator<T>) {
        list = ArrayList()
        this.comparator = comparator
    }

    constructor(elements: Collection<T>, comparator: Comparator<T>) {
        list = ArrayList(elements)
        list.sortWith(comparator)

        this.comparator = comparator
    }

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
        val toAdd = if (elements === this) ArrayList(elements) else elements

        val modified = list.addAll(toAdd)
        if (modified) {
            list.sortWith(comparator)
            modCount++
        }

        return modified
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

    override fun contains(element: T) = list.binarySearch(element, comparator) >= 0

    override fun indexOf(element: T): Int {
        val index = list.binarySearch(element, comparator)
        if (index < 0) return -1

        var firstOccurrence = index
        while (firstOccurrence > 0 && comparator.compare(list[firstOccurrence - 1], element) == 0) {
            firstOccurrence--
        }

        return firstOccurrence
    }

    override fun lastIndexOf(element: T): Int {
        val index = list.binarySearch(element, comparator)
        if (index < 0) return -1

        var lastOccurrence = index
        while (lastOccurrence < list.size - 1 && comparator.compare(list[lastOccurrence + 1], element) == 0) {
            lastOccurrence++
        }

        return lastOccurrence
    }

    public override fun clone() = BinaryList(ArrayList(list), comparator)

    override fun add(index: Int, element: T) = throw UnsupportedOperationException("Cannot add at specific index in Sorted List")
    override fun set(index: Int, element: T) = throw UnsupportedOperationException("Cannot set at specific index in Sorted List")
}