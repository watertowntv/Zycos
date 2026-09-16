@file:Suppress("unused", "UnstableApiUsage")

package zaqws.zycos

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.inventory.*
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.ItemType


object InventoryManager : Listener {
    internal fun register() {
        Main.plugin.server.pluginManager.registerEvents(
            this, Main.plugin
        )
    }

    internal fun unregister() {
        HandlerList.unregisterAll(this)
    }


    enum class Click {
        LEFT,
        RIGHT,
        SHIFT_LEFT,
        SHIFT_RIGHT,
        MIDDLE
    }

    data class ClickContext(
        val player: Player,
        val slot: Int,
        val click: Click
    )


    abstract class InventoryGUI(
        title: Component,
        rows: Int = 3
    ) : InventoryHolder {
        companion object {
            private val openedGUIs = mutableSetOf<InventoryGUI>()
        }

        val size = rows * 9
        private val inventory = Bukkit.createInventory(
            this,
            size,
            title
        )
        private val clickHandlers = arrayOfNulls<((ClickContext) -> Unit)?>(size)

        final override fun getInventory() = inventory


        fun open(player: Player) {
            refresh()

            player.openInventory(inventory)
        }
        fun open(players: Iterable<Player>) {
            refresh()

            players.forEach { player ->
                player.openInventory(inventory)
            }
        }

        fun close(player: Player) {
            if (player.openInventory.topInventory !== inventory) return

            player.closeInventory()
        }
        fun close(players: Iterable<Player>) {
            players.forEach { player ->
                if (player.openInventory.topInventory !== inventory) return@forEach

                player.closeInventory()
            }
        }

        operator fun contains(player: Player): Boolean {
            return player.openInventory.topInventory === inventory
        }


        fun item(
            slot: Int,
            item: ItemStack?
        ) {
            requireSlot(slot)

            if (item != null && !item.type.isAir) inventory.setItem(slot, item.clone())
            else inventory.clear(slot)

            clickHandlers[slot] = null
        }

        fun button(
            slot: Int,
            item: ItemStack,
            action: (ClickContext) -> Unit
        ) {
            requireSlot(slot)

            inventory.setItem(slot, item.clone())
            clickHandlers[slot] = action
        }

        fun clear(slot: Int) {
            requireSlot(slot)

            inventory.clear(slot)
            clickHandlers[slot] = null
        }

        fun clear() {
            inventory.clear()
            clickHandlers.fill(null)
        }

        fun fill(item: ItemStack) {
            for (slot in 0 until size) {
                inventory.setItem(slot, item.clone())
                clickHandlers[slot] = null
            }
        }
        fun fill() {
            fill(getNamedItem(
                ItemType.LIGHT_GRAY_STAINED_GLASS_PANE,
                text(""),
                hideTooltip = true
            ))
        }

        internal fun handleClick(context: ClickContext) {
            clickHandlers[context.slot]?.invoke(context)
        }
        internal fun handleOpen(player: Player) {
            openedGUIs.add(this)
            onOpen(player)
        }
        internal fun handleClose(player: Player) {
            onClose(player)

            if (inventory.viewers.none { it !== player }) {
                openedGUIs -= this
            }
        }

        protected open fun onOpen(player: Player) {}
        protected open fun onClose(player: Player) {}
        open fun refresh() {}

        protected fun refreshAll() {
            val type = this::class

            openedGUIs.filter {
                it::class == type
            }.forEach {
                it.refresh()
            }
        }

        private fun requireSlot(slot: Int) {
            require(slot in 0 until size) {
                "Slot $slot is outside GUI size $size"
            }
        }
    }


    @EventHandler
    private fun onInventoryClick(event: InventoryClickEvent) {
        val gui = event.view.topInventory.getHolder(false) as? InventoryGUI ?: return

        event.isCancelled = true

        val slot = event.rawSlot
        if (slot !in 0 until gui.size) return

        val player = event.whoClicked as? Player ?: return
        val click = event.click.toGUIClick() ?: return

        gui.handleClick(
            ClickContext(
                player = player,
                slot = slot,
                click = click
            )
        )
    }

    @EventHandler
    private fun onInventoryDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.getHolder(false) !is InventoryGUI) return

        event.isCancelled = true
    }

    @EventHandler
    private fun onInventoryOpen(event: InventoryOpenEvent) {
        val gui = event.inventory.getHolder(false) as? InventoryGUI ?: return
        val player = event.player as? Player ?: return

        gui.handleOpen(player)
    }

    @EventHandler
    private fun onInventoryClose(event: InventoryCloseEvent) {
        val gui = event.inventory.getHolder(false) as? InventoryGUI ?: return
        val player = event.player as? Player ?: return

        gui.handleClose(player)
    }


    private fun ClickType.toGUIClick() = when (this) {
        ClickType.LEFT -> Click.LEFT
        ClickType.RIGHT -> Click.RIGHT
        ClickType.SHIFT_LEFT -> Click.SHIFT_LEFT
        ClickType.SHIFT_RIGHT -> Click.SHIFT_RIGHT
        ClickType.MIDDLE -> Click.MIDDLE

        else -> null
    }
}
