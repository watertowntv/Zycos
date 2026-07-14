@file:Suppress("unused")

package zaqws.zycos

import com.google.common.cache.CacheBuilder
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.cbor.Cbor
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.*
import org.bukkit.event.player.*
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import java.util.*
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

class PersistentItemDataManager<T: PersistentItemDataManager.PersistentItemData>(
    plugin: JavaPlugin,
    keyName: String,
    private val serializer: KSerializer<T>,
    private val defaultFactory: () -> T,
    expireMinutes: Int = 5,
    strictListener: Boolean = false
) {
    private data class CacheEntry<T>(
        val data: T,
        var version: Int
    )

    private val cache = CacheBuilder.newBuilder()
        .expireAfterAccess(expireMinutes.minutes.toJavaDuration())
        .build<UUID, CacheEntry<T>>()
    private val referenceCache = CacheBuilder.newBuilder()
        .weakKeys()
        .expireAfterAccess(expireMinutes.minutes.toJavaDuration())
        .build<ItemStack, CacheEntry<T>>()

    @OptIn(ExperimentalSerializationApi::class)
    private val cbor = Cbor { ignoreUnknownKeys = true }

    private val namespacedKey = keyName.lowercase().replace(Regex("[^a-z0-9/._-]"), "_")

    private val uuidKey = NamespacedKey(plugin, "${namespacedKey}_uuid")
    private val dataKey = NamespacedKey(plugin, "${namespacedKey}_data")
    private val versionKey = NamespacedKey(plugin, "${namespacedKey}_version")

    private val listener = PersistentItemDataListener(strictListener)

    init {
        plugin.server.pluginManager.registerEvents(listener, plugin)
    }

    fun initialize(item: ItemStack, factory: (() -> T)? = null): T? {
        if (item.isEmpty) return null

        if (item.persistentDataContainer.has(uuidKey, PersistentDataType.STRING)) {
            val data = this[item]

            if (data != null) return data
        }

        val data = factory?.invoke() ?: defaultFactory()
        return createEntry(item, data)
    }

    @OptIn(ExperimentalSerializationApi::class)
    operator fun get(item: ItemStack): T? {
        if (item.isEmpty) return null

        val cachedEntry = referenceCache.getIfPresent(item)
        if (cachedEntry != null) return cachedEntry.data

        val uuid = getUUID(item) ?: return null
        val version = item.persistentDataContainer.get(
            versionKey,
            PersistentDataType.INTEGER
        ) ?: 0

        var entry = cache.getIfPresent(uuid)
        if (entry != null && entry.version >= version) {
            referenceCache.put(item, entry)

            return entry.data
        }

        val byteArray = item.persistentDataContainer.get(
            dataKey,
            PersistentDataType.BYTE_ARRAY
        )
        val data = if (byteArray != null) cbor.decodeFromByteArray(serializer, byteArray)
        else defaultFactory()

        entry = CacheEntry(data, version)
        cache.put(uuid, entry)
        referenceCache.put(item, entry)

        return entry.data
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun save(item: ItemStack, data: T): Boolean {
        if (item.isEmpty) return false
        val uuid = getUUID(item) ?: return false

        val currentVersion = item.persistentDataContainer.get(
            versionKey,
            PersistentDataType.INTEGER
        ) ?: 0
        val nextVersion = currentVersion + 1

        item.editPersistentDataContainer { pdc ->
            val byteArray = cbor.encodeToByteArray(serializer, data)

            pdc.set(dataKey, PersistentDataType.BYTE_ARRAY, byteArray)
            pdc.set(versionKey, PersistentDataType.INTEGER, nextVersion)
        }

        val entry = CacheEntry(data, nextVersion)
        cache.put(uuid, entry)
        referenceCache.put(item, entry)

        return true
    }

    fun remove(item: ItemStack): Boolean {
        if (item.isEmpty) return false
        val uuid = getUUID(item) ?: return false

        cache.invalidate(uuid)
        referenceCache.invalidate(item)

        item.editPersistentDataContainer { pdc ->
            pdc.remove(uuidKey)
            pdc.remove(dataKey)
            pdc.remove(versionKey)
        }

        return true
    }

    inline fun modify(item: ItemStack, block: (T) -> Unit): Boolean {
        val data = this[item] ?: return false
        block(data)

        return save(item, data)
    }


    fun clear(unregister: Boolean = false) {
        saveAll()

        cache.invalidateAll()
        referenceCache.invalidateAll()

        if (unregister) {
            HandlerList.unregisterAll(listener)
        }
    }

    fun saveAll() {
        referenceCache.asMap().forEach { (item, entry) ->
            if (item == null || item.isEmpty) return@forEach

            save(item, entry.data)
        }
    }


    private fun getUUID(item: ItemStack): UUID? {
        val id = item.persistentDataContainer.get(
            uuidKey,
            PersistentDataType.STRING
        ) ?: return null

        return runCatching {
            UUID.fromString(id)
        }.getOrNull()
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun createEntry(item: ItemStack, data: T): T {
        val newId = UUID.randomUUID()
        val byteArray = cbor.encodeToByteArray(serializer, data)

        item.editPersistentDataContainer { pdc ->
            pdc.set(uuidKey, PersistentDataType.STRING, newId.toString())
            pdc.set(dataKey, PersistentDataType.BYTE_ARRAY, byteArray)
            pdc.set(versionKey, PersistentDataType.INTEGER, 1)
        }

        cache.put(newId, CacheEntry(data, 1))

        return data
    }

    interface PersistentItemData


    private inner class PersistentItemDataListener(
        private val strict: Boolean = false
    ) : Listener {

        private fun processSave(item: ItemStack?) {
            if (item == null || item.isEmpty) return
            val data = this@PersistentItemDataManager[item] ?: return

            save(item, data)
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        fun onInventoryClick(event: InventoryClickEvent) {
            processSave(event.currentItem)
            processSave(event.cursor)
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        fun onInventoryDrag(event: InventoryDragEvent) {
            event.newItems.values.forEach(this::processSave)
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        fun onPlayerDropItem(event: PlayerDropItemEvent) {
            processSave(event.itemDrop.itemStack)
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        fun onEntityPickupItem(event: EntityPickupItemEvent) {
            if (event.entity !is Player) return

            processSave(event.item.itemStack)
        }

        @EventHandler(priority = EventPriority.MONITOR)
        fun onInventoryClose(event: InventoryCloseEvent) {
            event.inventory.contents.forEach(this::processSave)
        }

        @EventHandler(priority = EventPriority.MONITOR)
        fun onPlayerQuit(event: PlayerQuitEvent) {
            event.player.inventory.contents.forEach(this::processSave)
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        fun onPlayerItemHeld(event: PlayerItemHeldEvent) {
            processSave(event.player.inventory.getItem(event.previousSlot))
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        fun onPlayerSwapHandItems(event: PlayerSwapHandItemsEvent) {
            processSave(event.mainHandItem)
            processSave(event.offHandItem)
        }

        @EventHandler(priority = EventPriority.MONITOR)
        fun onPlayerDeath(event: PlayerDeathEvent) {
            event.drops.forEach(this::processSave)
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        fun onInventoryMoveItem(event: InventoryMoveItemEvent) {
            processSave(event.item)
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        fun onCraftItem(event: CraftItemEvent) {
            processSave(event.currentItem)
            event.inventory.matrix.forEach(this::processSave)
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        fun onPlayerInteract(event: PlayerInteractEvent) {
            if (!strict) return

            processSave(event.item)
        }
    }
}