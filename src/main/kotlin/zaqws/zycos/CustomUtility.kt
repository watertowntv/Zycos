@file:Suppress("unused", "UnstableApiUsage", "FunctionName")

package zaqws.zycos

import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.builder.ArgumentBuilder
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import io.papermc.paper.block.BlockPredicate
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.command.brigadier.argument.resolvers.selector.EntitySelectorArgumentResolver
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.*
import io.papermc.paper.dialog.Dialog
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import io.papermc.paper.registry.RegistryKey
import io.papermc.paper.registry.TypedKey
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.type.DialogType
import io.papermc.paper.registry.set.RegistrySet
import net.kyori.adventure.audience.Audience
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.key.Key
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.kyori.adventure.title.Title
import net.kyori.adventure.title.Title.Times
import org.bukkit.*
import org.bukkit.attribute.Attribute
import org.bukkit.block.BlockFace
import org.bukkit.block.data.BlockData
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.*
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.MerchantRecipe
import org.bukkit.inventory.PlayerInventory
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.Transformation
import org.bukkit.util.Vector
import org.joml.AxisAngle4f
import org.joml.Vector3f
import zaqws.zycos.AreaManager.Position
import zaqws.zycos.Main.Companion.plugin
import java.io.File
import java.time.Duration
import kotlin.math.*


//region ItemUtility

/**
 * Creates a named ItemStack
 *
 * @param type Material
 * @param name Name
 * @param lore Lore
 * @param enchantments Enchantments
 * @param amount Amount
 * @param unbreakable Unbreakable
 * @param hideEnchantments Hide Enchantments
 * @param hideAttributes Hide Attributes
 * @param hideUnbreakable Aide Unbreakable
 * @param hideTooltip Hides All
 * @param customModelData Custom Model Data String
 * @return ItemStack
 */
fun getNamedItem(
    type: Material,
    name: TextComponent? = null,
    lore: List<TextComponent> = listOf(),
    amount: Int = 1,
    enchantments: List<Pair<Enchantment, Int>> = listOf(),
    unbreakable: Boolean = false,
    canPlaceOn: List<Material> = listOf(),
    canBreak: List<Material> = listOf(),
    maxStackSize: Int? = null,
    hideTooltip: Boolean = false,
    hideEnchantments: Boolean = false,
    hideAttributes: Boolean = false,
    hideUnbreakable: Boolean = false,
    customModelData: String? = null
): ItemStack {
    val itemStack = ItemStack.of(type, amount).apply {
        if (name != null) {
            val nameComponent = name.decoration(TextDecoration.ITALIC, false)

            setData(
                DataComponentTypes.ITEM_NAME,
                nameComponent
            )
            setData(
                DataComponentTypes.CUSTOM_NAME,
                nameComponent
            )
        }
        if (lore.isNotEmpty()) setData(
            DataComponentTypes.LORE,
            ItemLore.lore(lore)
        )

        if (enchantments.isNotEmpty()) setData(
            DataComponentTypes.ENCHANTMENTS,
            ItemEnchantments.itemEnchantments(enchantments.toMap())
        )
        if (unbreakable) setData(
            DataComponentTypes.UNBREAKABLE
        )
        if (maxStackSize != null) setData(
            DataComponentTypes.MAX_STACK_SIZE,
            maxStackSize.coerceIn(1, 99)
        )

        if (hideEnchantments || hideAttributes || hideUnbreakable || hideTooltip) {
            val option = TooltipDisplay.tooltipDisplay()

            if (hideEnchantments) option.addHiddenComponents(DataComponentTypes.ENCHANTMENTS)
            if (hideAttributes) option.addHiddenComponents(DataComponentTypes.ATTRIBUTE_MODIFIERS)
            if (hideUnbreakable) option.addHiddenComponents(DataComponentTypes.UNBREAKABLE)

            setData(
                DataComponentTypes.TOOLTIP_DISPLAY,
                option.hideTooltip(hideTooltip).build()
            )
        }

        fun createAdventurePredicate(materials: List<Material>): ItemAdventurePredicate {
            val typedKeys = materials.map { TypedKey.create(RegistryKey.BLOCK, it.getKey()) }
            val keySet = RegistrySet.keySet(RegistryKey.BLOCK, typedKeys)
            val predicate = BlockPredicate.predicate().blocks(keySet).build()

            return ItemAdventurePredicate.itemAdventurePredicate(listOf(predicate))
        }

        if (canPlaceOn.isNotEmpty()) setData(
            DataComponentTypes.CAN_PLACE_ON,
            createAdventurePredicate(canPlaceOn)
        )
        if (canBreak.isNotEmpty()) setData(
            DataComponentTypes.CAN_BREAK,
            createAdventurePredicate(canBreak)
        )

        if (customModelData != null) setData(
            DataComponentTypes.CUSTOM_MODEL_DATA,
            CustomModelData.customModelData().addString(customModelData).build()
        )
    }

    return itemStack
}

/**
 * Creates a named player head
 *
 * @param playerName Name of Player
 * @param name Name
 * @param lore Lore
 * @param amount Amount
 * @return Player Head
 */
fun getNamedSkull(
    playerName: String,
    name: TextComponent,
    lore: List<TextComponent> = listOf(),
    amount: Int = 1
): ItemStack {
    val itemStack = ItemStack.of(Material.PLAYER_HEAD, amount).apply {
        setData(
            DataComponentTypes.ITEM_NAME,
            name.decoration(TextDecoration.ITALIC, false)
        )
        setData(
            DataComponentTypes.CUSTOM_NAME,
            name.decoration(TextDecoration.ITALIC, false)
        )
        if (lore.isNotEmpty()) setData(
            DataComponentTypes.LORE,
            ItemLore.lore(lore)
        )

        setData(
            DataComponentTypes.PROFILE,
            ResolvableProfile.resolvableProfile().name(playerName).build()
        )
    }

    return itemStack
}

/**
 * Creates a potion with custom effects
 *
 * @param type Effect Type
 * @param amplifier Amplifier
 * @param duration Duration Ticks
 * @param particle Show Particles
 * @param name Name
 * @param color Color
 * @return Potion with Custom Effect
 */
fun getCustomPotion(
    type: PotionEffectType,
    amplifier: Int,
    duration: Int,
    particle: Boolean = true,
    name: TextComponent? = null,
    color: Color? = null
) = getCustomPotionImpl(
    Material.POTION,
    type,
    amplifier,
    duration,
    particle,
    name,
    color
)

/**
 * Creates a splash potion with custom effects
 *
 * @param type Effect Type
 * @param amplifier Amplifier
 * @param duration Duration Ticks
 * @param particle Show Particles
 * @param name Name
 * @param color Color
 * @return Splash Potion with Custom Effect
 */
fun getCustomSplashPotion(
    type: PotionEffectType,
    amplifier: Int,
    duration: Int,
    particle: Boolean = true,
    name: TextComponent? = null,
    color: Color? = null
) = getCustomPotionImpl(
    Material.SPLASH_POTION,
    type,
    amplifier,
    duration,
    particle,
    name,
    color
)

private fun getCustomPotionImpl(
    material: Material,
    type: PotionEffectType,
    amplifier: Int,
    duration: Int,
    particle: Boolean,
    name: TextComponent?,
    color: Color?
): ItemStack {
    val itemStack = ItemStack.of(material)
    val effect = PotionEffect(type, duration, amplifier, false, particle)
    var contents = PotionContents.potionContents().addCustomEffect(effect)

    if (color != null) contents = contents.customColor(color)

    if (name != null) {
        val nameComponent = name.decoration(TextDecoration.ITALIC, false)

        itemStack.setData(
            DataComponentTypes.ITEM_NAME,
            nameComponent
        )
        itemStack.setData(
            DataComponentTypes.CUSTOM_NAME,
            nameComponent
        )
    }
    itemStack.setData(
        DataComponentTypes.POTION_CONTENTS,
        contents.build()
    )

    return itemStack
}

/**
 * Makes item glow
 *
 * @return Item with Luck_of_the_Sea enchantment (lvl 1)
 */
fun ItemStack.glow(): ItemStack {
    val enchantments = getData(DataComponentTypes.ENCHANTMENTS) ?: ItemEnchantments.itemEnchantments(emptyMap())
    val builder = ItemEnchantments.itemEnchantments()
        .addAll(enchantments.enchantments())
        .add(Enchantment.LUCK_OF_THE_SEA, 1)

    setData(
        DataComponentTypes.ENCHANTMENTS,
        builder.build()
    )
    setData(
        DataComponentTypes.TOOLTIP_DISPLAY,
        TooltipDisplay.tooltipDisplay()
            .addHiddenComponents(DataComponentTypes.ENCHANTMENTS)
            .build()
    )

    return this
}

fun ItemStack.invisible(): ItemStack {
    setData(
        DataComponentTypes.ITEM_NAME,
        Component.text("")
    )
    setData(
        DataComponentTypes.TOOLTIP_DISPLAY,
        TooltipDisplay.tooltipDisplay()
            .addHiddenComponents(DataComponentTypes.ENCHANTMENTS)
            .addHiddenComponents(DataComponentTypes.UNBREAKABLE)
            .hideTooltip(true)
            .build()
    )

    return this
}

/**
 * Merchant Recipe Helper
 *
 * @param ingredient Ingredient
 * @param result Result
 * @return MerchantRecipe
 */
fun MerchantRecipe(
    ingredient: ItemStack,
    result: ItemStack
) = MerchantRecipe(result, Int.MAX_VALUE).apply {
    addIngredient(ingredient)
}

/**
 * Merchant Recipe Helper
 *
 * @param ingredient1 Ingredient 1
 * @param ingredient2 Ingredient 2
 * @param result Result
 * @return MerchantRecipe
 */
fun MerchantRecipe(
    ingredient1: ItemStack,
    ingredient2: ItemStack,
    result: ItemStack
) = MerchantRecipe(result, Int.MAX_VALUE).apply {
    addIngredient(ingredient1)
    addIngredient(ingredient2)
}

//endregion


//region ClientUtility

/**
 * BroadcastMessage
 *
 * @param message Text Component
 */
fun broadcast(message: TextComponent){
    Bukkit.broadcast(message)
}

/**
 * Displays a global title
 *
 * @param titleText Title
 * @param subtitleText Subtitle
 * @param fadeIn Fade In Duration
 * @param stay Display Duration
 * @param fadeOut Fade Out Duration
 */
fun title(
    titleText: TextComponent,
    subtitleText: TextComponent = Component.text(""),
    fadeIn: Int = 5,
    stay: Int = 30,
    fadeOut: Int = 5
){
    val title = Title.title(
        titleText,
        subtitleText,
        TitleTimes(fadeIn, stay, fadeOut)
    )

    onlinePlayers.forEach { p ->
        p.showTitle(title)
    }
}

/**
 * Sends player a title
 *
 * @param titleText Title
 * @param subtitleText Subtitle
 * @param fadeIn Fade In Duration
 * @param stay Display Duration
 * @param fadeOut Fade Out Duration
 */
fun Player.sendTitle(
    titleText: TextComponent,
    subtitleText: TextComponent = Component.text(""),
    fadeIn: Int = 5,
    stay: Int = 30,
    fadeOut: Int = 5
) {
    val title = Title.title(
        titleText,
        subtitleText,
        TitleTimes(fadeIn, stay, fadeOut)
    )

    showTitle(title)
}

/**
 * TitleTimes helper
 *
 * @param fadeIn Fade In Duration
 * @param stay Display Duration
 * @param fadeOut Fade Out Duration
 * @return Times
 */
fun TitleTimes(fadeIn: Int, stay: Int, fadeOut: Int) = Times.times(
    Duration.ofMillis(fadeIn * 50L),
    Duration.ofMillis(stay * 50L),
    Duration.ofMillis(fadeOut * 50L)
)

/**
 * Broadcast Actionbar
 *
 * @param message Message
 */
fun actionbar(message: TextComponent){
    onlinePlayers.forEach { p ->
        p.sendActionBar(message)
    }
}

/**
 * Adventure Sound
 *
 * @param key Sound
 * @param source Source
 * @param volume Volume
 * @param pitch Pitch
 * @return Sound
 */
fun sound(
    key: org.bukkit.Sound,
    source: Sound.Source = Sound.Source.WEATHER,
    volume: Float = 1.0f,
    pitch: Float = 1.0f
) = Sound.sound(
    Key.key(Registry.SOUNDS.getKey(key).toString()),
    source,
    volume,
    pitch
)

/**
 * Adventure Sound
 *
 * @param key Sound String
 * @param source Source
 * @param volume Volume
 * @param pitch Pitch
 * @return Sound
 */
fun sound(
    key: String,
    source: Sound.Source = Sound.Source.WEATHER,
    volume: Float = 1.0f,
    pitch: Float = 1.0f
) = Sound.sound(
    Key.key(key),
    source,
    volume,
    pitch
)

/**
 * Adventure Sound
 *
 * @param key Key
 * @param source Source
 * @param volume Volume
 * @param pitch Pitch
 * @return Sound
 */
fun sound(
    key: Key,
    source: Sound.Source = Sound.Source.WEATHER,
    volume: Float = 1.0f,
    pitch: Float = 1.0f
) = Sound.sound(
    key,
    source,
    volume,
    pitch
)

/**
 * Play Sound
 */
fun Sound.play() {
    Audience.audience(onlinePlayers).playSound(this)
}

/**
 * Play Sound
 *
 * @param location Location
 */
fun Sound.play(location: Location) {
    location.world.playSound(this, location.x, location.y, location.z)
}

/**
 * Play Sound
 *
 * @param player Player
 */
fun Sound.play(player: Player) {
    Audience.audience(player).playSound(this)
}

/**
 * Play Sound as Player
 *
 * @param player Player
 * @param follow Follows Player
 */
fun Sound.play(player: Player, follow: Boolean) {
    if (follow) Audience.audience(player).playSound(this, Sound.Emitter.self())
    else Audience.audience(player).playSound(this)
}

/**
 * Play Sound as Entity
 *
 * @param entity Entity
 */
fun Sound.play(entity: Entity) {
    Audience.audience(onlinePlayers).playSound(this, entity)
}

/**
 * Play Sound
 *
 * @param players Players
 */
fun Sound.play(players: Iterable<Player>) {
    Audience.audience(players).playSound(this)
}

/**
 * Play Sound
 *
 * @param location Location
 * @param minVolume Minimum Volume in Maximum Distance
 * @param maxDist Maximum Distance
 */
fun Sound.play(
    location: Location,
    minVolume: Float = 0.0f,
    maxDist: Double = 0.0
) {
    location.world.playSound(this, location.x, location.y, location.z)

    if (minVolume > 0f) {
        val minSound = sound(this.name(), this.source(), minVolume, this.pitch())

        plugin.server.onlinePlayers.filter {
            maxDist == 0.0 || it.location.distanceSquared(location) <= maxDist * maxDist
        }.forEach { player ->
            val sourceLocation = player.location.add(
                location.toVector().subtract(player.location.toVector()).fastNormalize().multiply(5)
            )

            player.playSound(minSound, sourceLocation.x, sourceLocation.y, sourceLocation.z)
        }
    }
}

/**
 * Clears message history
 */
fun Player.clearMessage() {
    val empty = Component.text()

    for (i in 0..100) {
        sendMessage(empty)
    }
}

/**
 * Returns TextComponent
 * @param text String message
 * @param color Text color
 */
fun text(text: String = "", color: NamedTextColor = NamedTextColor.WHITE, decorations: Collection<TextDecoration> = emptySet()) =
    if (decorations.isEmpty()) Component.text(text, color)
    else Component.text(text, color, decorations.toSet())

/**
 * Append TextComponent
 * @param text String message
 * @param color Text color
 */
fun TextComponent.text(text: String = "", color: NamedTextColor = NamedTextColor.WHITE, decorations: Collection<TextDecoration> = emptySet()) =
    if (decorations.isEmpty()) append(Component.text(text, color))
    else append(Component.text(text, color, decorations.toSet()))

/**
 * Adds hover event
 *
 * @param hoverText Hover Event Text Component
 */
fun TextComponent.hover(hoverText: TextComponent) =
    hoverEvent(HoverEvent.showText(hoverText))

/**
 * Adds click event
 *
 * @param callback Callback on Click Event
 */
fun TextComponent.click(callback: (clicked: Player) -> Unit) =
    clickEvent(ClickEvent.callback { audience ->
        val player = audience as? Player ?: return@callback

        callback(player)
    })

/**
 * Joins collections of Component
 *
 * @param separator Separator
 * @return Component
 */
fun Iterable<Component>.join(separator: Component = Component.newline()): Component {
    val iterator = this.iterator()
    if (!iterator.hasNext()) return Component.empty()

    val builder = Component.text()
    builder.append(iterator.next())

    while (iterator.hasNext()) {
        builder.append(separator)
        builder.append(iterator.next())
    }

    return builder.build()
}

/**
 * MiniMessage helper
 */
fun String.miniMessage() = miniMessage
    .deserialize(this)
    .decoration(TextDecoration.ITALIC, false)

//endregion


//region MathUtility

fun Double.toRadians() = Math.toRadians(this)
fun Double.toDegrees() = Math.toDegrees(this)

/**
 * Returns a random number in a range
 *
 * @param min Minimum number
 * @param max Maximum number
 * @return Random number in a range
 */
@Suppress("NOTHING_TO_INLINE")
inline fun randomRange(min: Int, max: Int): Int {
    if (max <= min) return min

    return if (max < Int.MAX_VALUE) {
        random.nextInt(min, max + 1)
    } else if (min > Int.MIN_VALUE) {
        random.nextInt(min - 1, max) + 1
    } else {
        random.nextInt()
    }
}

/**
 * Splits string into list of strings
 *
 * @param maxLength Amount of characters until next line break
 * @return List of strings
 */
fun String.splitLines(maxLength: Int = 16): List<String> {
    if (this.isEmpty()) return emptyList()

    val result = mutableListOf<String>()
    val currentLine = StringBuilder()

    this.splitToSequence(' ').forEach { word ->
        if (word.isEmpty()) return@forEach
        val expectedLength = if (currentLine.isEmpty()) word.length
        else currentLine.length + 1 + word.length

        if (expectedLength <= maxLength) {
            if (currentLine.isNotEmpty()) currentLine.append(' ')

            currentLine.append(word)
        } else {
            if (currentLine.isNotEmpty()) {
                result.add(currentLine.toString())
                currentLine.clear()
            }

            currentLine.append(word)
        }
    }

    if (currentLine.isNotEmpty()) {
        result.add(currentLine.toString())
    }

    return result
}

/**
 * Returns amount of exp required for current level
 *
 * @param level Level
 * @return Amount of exp required
 */
fun expByLevel(level: Int) = level * (level + 6)


/**
 * Returns only digit characters
 *
 * @return Integer
 */
fun String.toNumber() = filter(Char::isDigit).toInt()

/**
 * Capitalizes string
 *
 * @return Capitalized string
 */
fun String.capitalizeWords() = lowercase()
    .split(' ')
    .joinToString(" ") { it.replaceFirstChar { char ->
        if (char.isLowerCase()) char.titlecase()
        else char.toString()
    }}

/**
 * Generates random UUID-like String
 *
 * @return Random UUID-like String
 */
fun getRandomString(length: Int): String {
    val allowedChars = ('A'..'Z') + ('a'..'z') + ('0'..'9')

    return (1..length).map {
        allowedChars.random()
    }.joinToString("")
}

/**
 * Divide and modulate two numbers
 *
 * @param a Number to be divided and modulated
 * @param b Number to divide and modulate
 * @return Pair of Divmod
 */
fun divmod(a: Int, b: Int) = a / b to a % b

/**
 * Convert string to minutes and seconds
 *
 * @param time Time String ("2:75" = Pair(3, 15))
 * @return Pair of Minutes and Seconds
 */
fun convertToTime(time: String): Pair<Int, Int> {
    assert(time.count { it == ':' } == 1)

    val index = time.indexOf(":")

    if (index == -1) {
        val t = time.toIntOrNull() ?: return -1 to -1
        return t / 60 to t % 60
    }

    val (minute, second) = time.split(":").map(String::toInt)

    return minute + second / 60 to second % 60
}

/**
 * If true, returns 1
 * If false, returns -1
 *
 * @return 1 or -1
 */
fun Boolean.sign() = if (this) 1 else -1

/**
 * x > 0: 1
 * x <= 0: -1
 *
 * @return 1 or -1
 */
fun Int.sign() = if (this > 0) 1 else -1

/**
 * If true, returns 1
 * If false, returns 0
 *
 * @return 1 or 0
 */
fun Boolean.toInt() = if (this) 1 else 0

/**
 * Reflects number
 *
 * @param c Center
 * @return Mirrored number
 */
fun Double.splitHalf(c: Double) = if(this > c) c * 2 - this else this

/**
 * Check if the number is prime or not
 *
 * @param n Number
 * @return Is Prime
 */
fun isPrime(n: Int): Boolean {
    if (n < 2) return false
    if (n < 4) return true
    if (n % 2 == 0 || n % 3 == 0) return false

    var i = 5
    while (i * i <= n) {
        if (n % i == 0 || n % (i + 2) == 0) return false

        i += 6
    }

    return true
}

/**
 * Normalize and return original length
 *
 * @return Length Before Normalization
 */
fun Vector.normalizeWithLength(): Double {
    val square = (x * x + y * y + z * z).toFloat()
    if (square < Constants.EPSILON) return 0.0

    val rsqrt = 1.0f / sqrt(square)
    x *= rsqrt
    y *= rsqrt
    z *= rsqrt

    return (square * rsqrt).toDouble()
}
/**
 * Normalize and return itself
 *
 * @return Vector After Normalization
 */
fun Vector.fastNormalize(): Vector {
    val square = (x * x + y * y + z * z).toFloat()
    if (square < Constants.EPSILON) return this

    val rsqrt = 1.0f / sqrt(square)
    x *= rsqrt
    y *= rsqrt
    z *= rsqrt

    return this
}

/**
 * Min to Max
 *
 * @param a Number
 * @param b Number
 * @return Minimum to Maximum
 */
fun <T: Comparable<T>> minmax(a: T, b: T) = if(a < b) a to b else b to a

//endregion


//region EntityUtility

/**
 * Returns the nearest living entity
 *
 * @param range Range
 * @return Nearest LivingEntity or null if not found
 */
fun LivingEntity.getNearestEntity(range: Double): Entity? =
    location.getNearbyLivingEntities(range)
        .asSequence()
        .filter {
            it !== this && this !in it.passengers
        }.minBy {
            location.distanceSquared(it.location)
        }

/**
 * Launches an arrow
 *
 * @param location Location
 * @param vector Direction
 * @param entity Shooter
 * @param speed Speed
 * @param spread Spread
 * @return Arrow
 */
fun launchArrow(
    location: Location,
    vector: Vector = location.direction,
    entity: LivingEntity? = null,
    speed: Float = 3f,
    spread: Float = 0.5f
) = location.world.spawnArrow(location, vector, speed, spread).apply {
    shooter = entity

    sound(org.bukkit.Sound.ENTITY_ARROW_SHOOT).play(location)
}

/**
 * Add a potion effect to entity
 *
 * @param effect Type of potion effect
 * @param amplifier Amplifier of the potion effect
 * @param duration Duration of the potion effect
 * @param particle Shows particle
 */
fun LivingEntity.addPotion(
    effect: PotionEffectType,
    amplifier: Int,
    duration: Int,
    particle: Boolean = false
) {
    addPotionEffect(PotionEffect(
        effect,
        duration,
        amplifier,
        particle,
        particle
    ))
}

/**
 * Add a potion effect to entity
 *
 * @parameffect Type
 * @param amplifier Amplifier
 * @param infiniteDuration Infinite
 * @param particle Show a particle
 */
fun LivingEntity.addPotion(
    effect: PotionEffectType,
    amplifier: Int,
    infiniteDuration: Boolean,
    particle: Boolean = false
) {
    addPotionEffect(PotionEffect(
        effect,
        PotionEffect.INFINITE_DURATION,
        amplifier,
        particle,
        particle
    ))
}

/**
 * Plays particle effects
 *
 * @param type Type of particle effects
 * @param amount Amount of particles
 * @param speed Speed of particles
 * @param range Range of particles
 * @param data Data of particles
 */
fun Location.spawnParticle(type: Particle, amount: Int, speed: Double, range: Double, data: Any? = null){
    world.spawnParticle(
        type,
        this,
        amount,
        range, range, range,
        speed,
        data,
        true
    )
}

/**
 * If player is survival or adventure, returns true
 */
val Player.isDamageable
    get() = gameMode == GameMode.SURVIVAL || gameMode == GameMode.ADVENTURE

/**
 * If player is on ground, returns true
 */
val Player.onGround: Boolean
    get() = (this as Entity).isOnGround

/**
 * Returns the nearest player
 *
 * @param excepts Filter
 * @return Nearest player
 */
fun Location.getNearestPlayer(
    excepts: List<Player> = listOf()
) = world.players
    .asSequence()
    .filter {
        it !in excepts && it.gameMode != GameMode.SPECTATOR
    }.minByOrNull {
        distanceSquared(it.location)
    }

/**
 * Returns the nearest living entity
 *
 * @param excepts Filter
 * @param exceptTypes Filter Entity Type
 * @return Nearest Living Entity
 */
fun LivingEntity.getNearestLivingEntity(
    excepts: List<LivingEntity> = listOf(),
    exceptTypes: List<EntityType> = listOf()
) = world.entities.asSequence()
    .filterIsInstance<LivingEntity>()
    .filter {
        it !== this && it !in excepts && it.type !in exceptTypes
    }.minByOrNull {
        location.distanceSquared(it.location)
    }

/**
 * Plays firework effect
 *
 * @param location Location
 * @param effect Firework Effect
 * @param pw Power
 */
fun World.playFirework(
    location: Location,
    effect: FireworkEffect,
    pw: Int = 0
) = spawn(location, Firework::class.java).apply {
    fireworkMeta = fireworkMeta.apply {
        if(pw > 0) power = pw - 1

        addEffect(effect)
    }

    if(pw == 0) detonate()
}

/**
 * Remove specific amount of material from inventory
 *
 * @param type Material
 * @param count Amount to remove
 */
fun Inventory.removeMaterial(type: Material, count: Int) {
    var remaining = count

    for (i in size - 1 downTo 0) {
        val item = getItem(i) ?: continue
        if (item.type != type) continue

        val new = max(0, item.amount - remaining)
        remaining -= item.amount
        item.amount = new

        if(remaining <= 0) break
    }
}

/**
 * Returns the amount of material in an inventory\
 *
 * @param type Material
 * @return Amount of material
 */
fun Inventory.countMaterial(type: Material) = contents.sumOf {
    if (it?.type == type) it.amount else 0
}

/**
 * Fills inventory with an ItemStack
 *
 * @param itemStack ItemStack to be filled
 */
fun Inventory.fill(itemStack: ItemStack) {
    contents = Array(size) { itemStack.clone() }
}

/**
 * Fills only empty slots, then returns not empty slots
 *
 * @param itemStack: Item to fill
 * @return Slots not Empty
 */
fun Inventory.fillEmpty(itemStack: ItemStack): List<Int> {
    val notEmpty = arrayListOf<Int>()

    for (i in 0 until size) {
        if (getItem(i) == null) setItem(i, itemStack)
        else notEmpty.add(i)
    }

    return notEmpty
}

/**
 * Adds item to player inventory
 *
 * @param itemStack ItemStack
 * @param silent Is Silent
 * @return Added Slot. If failed, -1
 */
fun PlayerInventory.addItem(itemStack: ItemStack, silent: Boolean): Int {
    if (!silent) {
        addItem(itemStack)
        return -1
    }

    for (i in 0 until size) getItem(i)?.let { item ->
        if (i == heldItemSlot) continue

        setItem(i, itemStack)

        (holder as Player).let { player ->
            sound(org.bukkit.Sound.ENTITY_ITEM_PICKUP).play(player.location)
        }

        return i
    }

    return -1
}

/**
 * Give or drop item
 *
 * @param item ItemStack
 */
fun Player.giveOrDropItem(item: ItemStack) {
    val leftover = inventory.addItem(item)
    if (leftover.isEmpty()) return

    leftover.values.forEach {
        world.dropItemNaturally(location, it)
    }
}

/**
 * Returns remaining slot amount
 *
 * @param item ItemStack
 * @return Available Spaces
 */
fun Inventory.getRemainingSpaceFor(item: ItemStack): Int {
    var space = 0
    val maxStack = item.maxStackSize

    for (i in 0 until size) {
        val current = getItem(i)

        if (current == null || current.type == Material.AIR) {
            space += maxStack
            continue
        }

        if (!current.isSimilar(item)) continue
        space += (maxStack - current.amount).coerceAtLeast(0)
    }

    return space
}


/**
 * Updates pivot point of Display
 *
 * @param size Size of the display (Vector3f)
 */
fun Display.updatePivot(size: Vector3f = transformation.scale){
    interpolationDelay = -1
    interpolationDuration = -1

    transformation = Transformation(
        Vector3f(
            -size.x * 0.5f,
            -size.y * 0.5f,
            -size.z * 0.5f
        ),
        transformation.leftRotation,
        size,
        transformation.rightRotation
    )
}

/**
 * Spawns Block Display
 *
 * @param location Spawn location
 * @param type Material
 * @param size Size of the display (Vector)
 * @return BlockDisplay
 */
fun World.spawnBlockDisplay(
    location: Location,
    type: Material,
    size: Vector = Vector(1, 1, 1)
) = spawnBlockDisplay(location, type.createBlockData(), size)

/**
 * Spawns Block Display
 *
 * @param location Spawn location
 * @param data BlockData
 * @param size Size of the display (Vector)
 * @return BlockDisplay
 */
fun World.spawnBlockDisplay(
    location: Location,
    data: BlockData,
    size: Vector = Vector(1, 1, 1)
) = spawn(location, BlockDisplay::class.java).apply {
    updatePivot(size.toFloat())
    teleport(location)
    block = data
}


/**
 * Reset attributes
 */
fun Player.clearAllAttributeModifiers() {
    Registry.ATTRIBUTE.forEach { attribute ->
        this.getAttribute(attribute)?.let { instance ->
            instance.modifiers.forEach { modifier ->
                instance.removeModifier(modifier)
            }
        }
    }
}

/**
 * Hides entity except for a certain player
 */
fun Entity.hideExcept(player: Player) {
    plugin.server.onlinePlayers.filter {
        it.uniqueId != player.uniqueId
    }.forEach { p ->
        p.hideEntity(plugin, this)
    }
}

/**
 * Attribute Helper
 */
var LivingEntity.maximumHealth: Double
    get() = getAttribute(Attribute.MAX_HEALTH)?.baseValue ?: 0.0
    set(hp) {
        getAttribute(Attribute.MAX_HEALTH)?.let {
            it.baseValue = hp

            if (this.health > hp) this.health = hp
        }
    }

/**
 * Attribute Helper
 */
val LivingEntity.attackDamage: Double
    get() = getAttribute(Attribute.ATTACK_DAMAGE)?.value ?: 0.0

/**
 * Attribute Helper
 */
val LivingEntity.attackSpeed: Double
    get() = getAttribute(Attribute.ATTACK_SPEED)?.value ?: 0.0

/**
 * Attribute Helper
 */
val LivingEntity.attackRange: Double
    get() = getAttribute(Attribute.ENTITY_INTERACTION_RANGE)?.value ?: 0.0

/**
 * Audience Helper
 *
 * @param entity Entity
 * @return Audience
 */
fun audience(entity: Entity) = Audience.audience(entity)

/**
 * Audience Helper
 *
 * @param entities Entities
 * @return Audiences
 */
fun audience(entities: Iterable<Entity>) = Audience.audience(entities)

//endregion


//region LocationUtility

/**
 * Location to fixed point (0.5, 0.0, 0.5)
 */
fun Location.toEntityLocation() =
    Location(world, blockX + 0.5, blockY.toDouble(), blockZ + 0.5, yaw, pitch)

/**
 * If in air, down to ground
 * If underground, up to air
 *
 * @param filter Materials to ignore
 * @return Location on Ground
 */
fun Location.toGround(filter: List<Material> = listOf()): Location {
    val world = this.world ?: return this
    val minHeight = world.minHeight
    val maxHeight = world.maxHeight - 1

    val x = this.blockX
    val z = this.blockZ

    var y = this.blockY.coerceIn(minHeight, maxHeight)
    val decimalY = this.y - this.blockY

    val hasFilter = filter.isNotEmpty()
    val filterSet = if (hasFilter && filter !is Set<*>) filter.toSet() else filter

    val chunk = world.getChunkAt(x shr Constants.CHUNK_SHIFT, z shr Constants.CHUNK_SHIFT)

    while (y > minHeight) {
        val block = chunk.getBlock(x and 15, y, z and 15)
        val type = block.type

        val isPassable = type.isAir || block.isPassable || (hasFilter && type in filterSet)
        if (!isPassable) break

        --y
    }

    while (y < maxHeight) {
        val block = chunk.getBlock(x and 15, y, z and 15)
        val type = block.type

        val isPassable = type.isAir || block.isPassable || (hasFilter && type in filterSet)
        if (isPassable) break

        ++y
    }

    return Location(world, this.x, y + decimalY, this.z, this.yaw, this.pitch)
}

/**
 * Distance 2D
 *
 * @param target Target Location
 * @return Distance
 */
fun Location.distance2D(target: Location): Double {
    val dx = target.x - x
    val dz = target.z - z

    return sqrt(dx * dx + dz * dz)
}

/**
 * Distance Squared 2D
 *
 * @param target Target Location
 * @return Distance Squared
 */
fun Location.distanceSquared2D(target: Location): Double {
    val dx = target.x - x
    val dz = target.z - z

    return dx * dx + dz * dz
}

/**
 * Coerce in n
 *
 * @param n Number
 * @return Vector After Clip
 */
fun Vector.clip(n: Double): Vector {
    x = if(n > 0) min(x, n) else max(x, n)
    y = if(n > 0) min(y, n) else max(y, n)
    z = if(n > 0) min(z, n) else max(z, n)

    return this
}

/**
 * Sign
 *
 * @param n Number
 * @return Vector After Sign
 */
fun Vector.sign(n: Double = 1.0): Vector {
    val e = Vector.getEpsilon()

    x = if(x > e) n else if(x < e) -n else 0.0
    y = if(y > e) n else if(y < e) -n else 0.0
    z = if(z > e) n else if(z < e) -n else 0.0

    return this
}

/**
 * Reflect by surface vector
 *
 * @param n Surface Vector
 * @return Vector After Reflection
 */
fun Vector.reflect(n: Vector) =
    subtract(clone().multiply(n).multiply(n).multiply(2.0))

/**
 * Surface vector by BlockFace
 *
 * @param face BlockFace
 * @return Surface Vector
 */
fun getSurfaceVector(face: BlockFace) = when(face) {
    BlockFace.EAST -> Vector(1.0, 0.0, 0.0)
    BlockFace.WEST -> Vector(-1.0, 0.0, 0.0)
    BlockFace.NORTH -> Vector(0.0, 0.0, -1.0)
    BlockFace.SOUTH -> Vector(0.0, 0.0, 1.0)
    BlockFace.UP -> Vector(0.0, 1.0, 0.0)
    BlockFace.DOWN -> Vector(0.0, -1.0, 0.0)

    else -> Vector(0.0, 0.0, 0.0)
}

/**
 * Vector to absolute value
 *
 * @return Vector after Abs
 */
fun Vector.abs(): Vector {
    x = x.absoluteValue
    y = y.absoluteValue
    z = z.absoluteValue

    return this
}

/**
 * Sum of x y z
 *
 * @return Sum
 */
fun Vector.sum() = x + y + z

/**
 * Operation Helper
 *
 * @param n Double
 * @return Vector After Multiplication
 */
operator fun Vector.times(n: Double) = Vector(x * n, y * n, z * n)

/**
 * Operation Helper
 *
 * @param n Double
 * @return Vector After Division
 */
operator fun Vector.div(n: Double) = Vector(x / n, y / n, z / n)

/**
 * Look at target location
 *
 * @param to Target Location
 * @return Itself
 */
fun Location.lookAt(to: Location): Location {
    direction = to.clone().subtract(this).toVector()

    return this
}

/**
 * Vector to target location
 *
 * @param to Target Location
 * @return Directional Vector
 */
fun Location.directionTo(to: Location) = to.clone().subtract(this).toVector()

/**
 * Wiggle Orientation
 *
 * @param yawAmplitude Amplitude
 * @param pitchAmplitude Amplitude
 * @return Location After Wiggle
 */
fun Location.wiggleOrientation(yawAmplitude: Float, pitchAmplitude: Float): Location {
    this.yaw += random.nextFloat() * abs(yawAmplitude) * 2 - abs(yawAmplitude)
    this.pitch += random.nextFloat() * abs(pitchAmplitude) * 2 - abs(pitchAmplitude)

    return this
}

/**
 * Clones target location
 *
 * @param target Target Location
 * @return Location after Clone
 */
fun Location.clone(target: Location): Location {
    this.world = target.world

    this.x = target.x
    this.y = target.y
    this.z = target.z

    this.yaw = target.yaw
    this.pitch = target.pitch

    return this
}

fun Vector.clone(target: Vector): Vector {
    this.x = target.x
    this.y = target.y
    this.z = target.z

    return this
}

/**
 * To Vector3f
 *
 * @return Vector3f
 */
fun Vector.toFloat() = Vector3f(x.toFloat(), y.toFloat(), z.toFloat())

/**
 * Direction to yaw and pitch
 *
 * @return yaw to pitch
 */
fun Vector.toYawPitch(): Pair<Float, Float> {
    val epsilon = Vector.getEpsilon()
    val doublePI = Math.PI * 2

    if (x.absoluteValue < epsilon && z.absoluteValue < epsilon) {
        return 0f to (if (y > 0) -90f else 90f)
    }

    val theta = atan2(-x, z)
    val xz = sqrt(x * x + z * z)

    val yaw = (theta + doublePI) % doublePI
    val pitch = atan(-y / xz)

    return yaw.toFloat() to pitch.toFloat()
}

/**
 * Yaw and Pitch to Vector
 *
 * @param yaw Yaw
 * @param pitch Pitch
 * @return Directional Vector
 */
fun getDirection(yaw: Float, pitch: Float): Vector {
    val xz = cos(pitch)

    return Vector(
        -xz * sin(yaw),
        -sin(pitch),
        xz * cos(yaw)
    )
}

/**
 * Right Vector
 *
 * @return Right Vector
 */
fun Location.getRightVector(): Vector {
    val x = cos(yaw.toDouble().toRadians())
    val z = sin(yaw.toDouble().toRadians())

    return Vector(-x, 0.0, -z).fastNormalize()
}

/**
 * Up Vector
 *
 * @return Up Vector
 */
fun Location.getUpVector(): Vector {
    val xz = getDirection()
    val right = getRightVector()

    return xz.crossProduct(right).multiply(-1).fastNormalize()
}

/**
 * Minecraft's ^ ^ ^ teleportation
 *
 * @param forward Offset
 * @param right Offset
 * @param up Offset
 * @return Location After Local Offset
 */
fun Location.addLocalOffset(forward: Double, right: Double, up: Double): Location {
    val directionVector = this.direction.clone().fastNormalize()
    val rightVector = this.getRightVector()
    val upVector = this.getUpVector()

    return this.clone().add(directionVector * forward).add(rightVector * right).add(upVector * up)
}

/**
 * Trace
 *
 * @param start Start Location
 * @param end End Location
 * @param interval Gap between iteration
 * @param callback Callback(World, Double, Double, Double)
 *
 * @throws IllegalArgumentException If interval is not in range 0..1
 */
fun trace(
    start: Location,
    end: Location,
    interval: Double = 0.05,
    callback: (world: World, x: Double, y: Double, z: Double) -> Unit
) {
    if(interval <= 0.0 || 1.0 < interval)
        throw IllegalArgumentException("Interval must be 0 < x <= 1")

    val world = start.world
    val startX = start.x
    val startY = start.y
    val startZ = start.z

    val dx = (end.x - start.x) * interval
    val dy = (end.y - start.y) * interval
    val dz = (end.z - start.z) * interval

    for(i in 0 until (1 / interval).toInt() + 1)
        callback(
            world,
            startX + dx * i,
            startY + dy * i,
            startZ + dz * i
        )
}

//endregion


//region ArrayUtility

/**
 * Iterate an array list
 */
@Deprecated("Bad")
fun <T> Iterable<T>.iterEach(value: (element: T) -> Unit) =
    toList().forEach(value)

/**
 * Iterate an array list (Includes null)
 */
@Deprecated("Bad")
fun <T> Iterable<T?>.iterEachOrNull(value: (element: T?) -> Unit) =
    toList().forEach(value)

/**
 * Add varargs to MutableList
 */
fun <T> MutableList<T>.merge(vararg values: T) {
    values.forEach(this::add)
}

/**
 * Returns a copy of an array list
 */
fun <T> MutableList<T>.copy() = toMutableList()

fun <T> Iterable<T>.random(exclude: Iterable<T>) =
    filter { it !in exclude }.random()

/**
 * Returns specific number of random elements from an array list
 *
 * @param n Amount of elements to pick from
 * @return MutableList of random elements
 */
fun <T> MutableList<T>.randomBatch(n: Int) =
    shuffled().take(n).toMutableList()

/**
 * Returns a flattened array of matrix values from hash map
 *
 * @param filter Filters
 * @return Array of flattened values
 */
fun <T, V> HashMap<V, Iterable<T>>.flattenValue(filter: List<V> = listOf()): MutableList<T>{
    val list = mutableListOf<T>()

    keys.filter { it !in filter }.forEach { list.addAll(this[it]!!) }

    return list
}

/**
 * Random Match
 *
 * @return Randomly Matched Pair List
 */
fun <T> List<T>.randomMatch(): List<Pair<T, T>> {
    val list = shuffled()

    return list.mapIndexed { i, t -> t to list[(i + 1) % size] }
}

/**
 * Adds then returns itself
 *
 * @param value Value to insert
 * @return Itself
 */
fun <T> MutableList<T>.insert(value: T): MutableList<T> {
    add(value)

    return this
}

/**
 * Adds then returns itself
 *
 * @param index Insertion Index
 * @param value Value to insert
 * @return Itself
 */
fun <T> MutableList<T>.insert(index: Int, value: T): MutableList<T> {
    add(index, value)

    return this
}

/**
 * Adds then returns itself
 *
 * @param values Values to insert
 * @return Itself
 */
fun <T> MutableList<T>.insertAll(vararg values: T): MutableList<T> {
    addAll(values)

    return this
}

/**
 * Adds then returns itself
 *
 * @param values Values to insert
 * @return Itself
 */
fun <T> MutableList<T>.insertAll(values: Iterable<T>): MutableList<T> {
    addAll(values)

    return this
}

/**
 * maxBy with multiple results
 *
 * @param selector Selector
 * @return Maximums
 */
inline fun <T, R : Comparable<R>> Iterable<T>.maxsBy(selector: (T) -> R): List<T> {
    val iterator = iterator()
    if (!iterator.hasNext()) return emptyList()

    val result = mutableListOf<T>()
    val maxElem = iterator.next()
    var maxValue = selector(maxElem)

    result.add(maxElem)

    while (iterator.hasNext()) {
        val element = iterator.next()
        val value = selector(element)

        if (value > maxValue) {
            maxValue = value

            result.clear()
            result.add(element)
        } else if (value == maxValue) {
            result.add(element)
        }
    }

    return result
}

/**
 * minBy with multiple results
 *
 * @param selector Selector
 * @return Minimums
 */
inline fun <T, R : Comparable<R>> Iterable<T>.minsBy(selector: (T) -> R): List<T> {
    val iterator = iterator()
    if (!iterator.hasNext()) return emptyList()

    val result = mutableListOf<T>()
    val minElem = iterator.next()
    var minValue = selector(minElem)

    result.add(minElem)

    while (iterator.hasNext()) {
        val element = iterator.next()
        val value = selector(element)

        if (value < minValue) {
            minValue = value

            result.clear()
            result.add(element)
        } else if (value == minValue) {
            result.add(element)
        }
    }

    return result
}

/**
 * For loop forEach
 *
 * @param action Callback
 */
inline fun <T> List<T>.fastForEach(action: (T) -> Unit) {
    for (i in 0..lastIndex) {
        action(this[i])
    }
}

/**
 * For Loop forEachIndexed
 *
 * @param action Callback
 */
inline fun <T> List<T>.fastForEachIndexed(action: (index: Int, T) -> Unit) {
    for (i in 0..lastIndex) {
        action(i, this[i])
    }
}

/**
 * Reversed For Loop forEach
 *
 * @param action Callback
 */
inline fun <T> List<T>.fastForEachReversed(action: (T) -> Unit) {
    for (i in lastIndex downTo 0) {
        action(this[i])
    }
}

/**
 * For Loop firstOrNull
 *
 * @param predicate Callback
 * @return First Found or null
 */
inline fun <T> List<T>.fastFirstOrNull(predicate: (T) -> Boolean): T? {
    for (i in 0..lastIndex) {
        val item = this[i]

        if (predicate(item)) return item
    }

    return null
}

/**
 * For Loop any
 *
 * @param predicate Callback
 * @return If any is true
 */
inline fun <T> List<T>.fastAny(predicate: (T) -> Boolean): Boolean {
    for (i in 0..lastIndex) {
        if (predicate(this[i])) return true
    }

    return false
}

/**
 * For Loop removeIf
 *
 * @param predicate Callback
 */
inline fun <T> MutableList<T>.fastRemoveIf(predicate: (T) -> Boolean) {
    var writeIndex = 0

    for (readIndex in 0..lastIndex) {
        val element = this[readIndex]
        if (predicate(element)) continue

        if (writeIndex != readIndex)
            this[writeIndex] = element

        writeIndex++
    }

    while (lastIndex >= writeIndex) removeAt(lastIndex)
}

//endregion


//region Event

/**
 * Cancels PlayerDeathEvent with death message
 */
fun PlayerDeathEvent.cancel() {
    isCancelled = true
    deathMessage()?.let(Bukkit::broadcast)
}

//endregion


//region NotUsed

fun Display.animate(
    tick: Int,
    position: Vector,
    rotation: Vector = Vector(0, 0, 0),
    scale: Vector = Vector(1, 1, 1)
){
    val axis = AxisAngle4f(Math.PI.toFloat(), rotation.toFloat())

    interpolationDuration = tick
    transformation = Transformation(
        position.toFloat(),
        axis,
        scale.toFloat(),
        axis,
    )
}

//endregion


//region Config

/**
 * Creates config file
 *
 * @param instance Java Plugin
 * @return Config File Created or not
 */
fun createConfigFile(instance: JavaPlugin): Boolean {
    val file = File(
        instance.dataFolder.toString() + File.separator + "config.yml"
    )

    if (!file.exists() || instance.config[instance.pluginMeta.name] == null) instance.config.apply {
        addDefault(
            instance.pluginMeta.name,
            "by ${instance.pluginMeta.authors.joinToString(", ")}"
        )
        options().copyDefaults(true)
        instance.saveConfig()

        return true
    }

    return false
}

//endregion


//region Dialog

/**
 * Notice Dialog
 *
 * @param title Title
 * @param body Contents
 * @return Dialog
 */
fun NoticeDialog(
    title: TextComponent,
    body: Iterable<TextComponent>
): Dialog = Dialog.create { builder ->
    builder.empty().base(
        DialogBase.builder(title)
            .body(body.map(DialogBody::plainMessage))
            .build()
    ).type(DialogType.notice())
}

/**
 * Multi Dialog
 *
 * @param title Title
 * @param body Contents
 * @param dialogs Dialogs
 * @return Dialog
 */
fun MultiDialog(
    title: TextComponent,
    body: Iterable<TextComponent>,
    dialogs: Iterable<Dialog>
): Dialog = Dialog.create { builder ->
    builder.empty().base(
        DialogBase.builder(title)
            .body(body.map(DialogBody::plainMessage))
            .build()
    ).type(DialogType.dialogList(RegistrySet.valueSet(
        RegistryKey.DIALOG,
        dialogs
    )).build())
}

/**
 * Action Dialog
 *
 * @param title Title
 * @param body Contents
 * @param actions Action Button Data
 * @return Dialog
 */
fun ActionDialog(
    title: TextComponent,
    body: Iterable<TextComponent>,
    actions: Iterable<ActionButtonData>
): Dialog = Dialog.create { builder ->
    builder.empty().base(
        DialogBase.builder(title)
            .body(body.map(DialogBody::plainMessage))
            .build()
    ).type(DialogType.multiAction(
        actions.map { action ->
            ActionButton.builder(action.name)
                .action(DialogAction.staticAction(
                    ClickEvent.callback { audience ->
                        if(audience !is Player) return@callback

                        action.callback(audience)
                    }
                )).build()
        }
    ).build())
}

/**
 * Action Button Data Component
 *
 * @param name Name
 * @param callback Callback
 * @return ActionButtonData
 */
data class ActionButtonData(
    val name: TextComponent,
    val callback: (Player) -> Unit
)

//endregion


//region Zycos

/**
 * Simple BossBar Timer
 *
 * @param name Name
 * @param tick Ticks
 * @param callback Callback when timer ends
 * @return BossBar
 */
fun simpleTimer(name: TextComponent, tick: Int, callback: () -> Unit): BossBar {
    val bossBar = BossBar.bossBar(
        name,
        1.0f,
        BossBar.Color.GREEN,
        BossBar.Overlay.PROGRESS
    )

    loop(tick) { i, _ ->
        bossBar.progress((1.0 - i.toDouble() / tick).coerceIn(0.0, 1.0).toFloat())
    }

    later(tick) {
        onlinePlayers.forEach { it.hideBossBar(bossBar) }
        callback()
    }

    return bossBar
}

/**
 * Location to AreaManager.Position
 *
 * @return Position
 */
fun Location.toPosition() = Position(blockX, blockY, blockZ)

/**
 * Vector to AreaManager.Position
 *
 * @return Position
 */
fun Vector.toPosition() = Position(blockX, blockY, blockZ)

/**
 * AreaManager.Area to List<Int>(6)
 *
 * @return List { startX, startY, startZ, endX, endY, endZ }
 */
fun AreaManager.Area.toList() = listOf(
    boundingBoxStart.x, boundingBoxStart.y, boundingBoxStart.z,
    boundingBoxEnd.x, boundingBoxEnd.y, boundingBoxEnd.z
)

fun <T : Comparable<T>> binaryListOf() =
    BinaryList(emptyList<T>(), compareBy { it })

fun <T : Comparable<T>> binaryListOf(vararg elements: T) =
    BinaryList(elements.toMutableList(), compareBy { it })

//endregion


//region Command

/**
 * Command Brigadier
 *
 * @param name Name of command
 * @param aliases Alias
 * @param description Description
 * @param builder Literal Argument Builder
 */
fun JavaPlugin.registerCommandTree(
    name: String,
    aliases: List<String> = emptyList(),
    description: String? = null,
    builder: LiteralArgumentBuilder<CommandSourceStack>.() -> Unit
) {
    this.lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
        val node = Commands.literal(name).apply(builder).build()
        event.registrar().register(node, description, aliases)
    }
}

/**
 * ArgumentBuilder::executes without 1
 */
inline fun <T : ArgumentBuilder<CommandSourceStack, T>> T.execute(
    crossinline block: (CommandContext<CommandSourceStack>) -> Unit
): T = this.executes { ctx ->
    block(ctx)
    1
}

/**
 * Child Command
 *
 * @param name Name
 * @param builder Literal Argument Builder
 */
fun <T : ArgumentBuilder<CommandSourceStack, T>> T.node(
    name: String,
    builder: LiteralArgumentBuilder<CommandSourceStack>.() -> Unit
): T = this.then(Commands.literal(name).apply(builder))

fun <T : ArgumentBuilder<CommandSourceStack, T>> T.leaf(
    name: String,
    action: (CommandContext<CommandSourceStack>) -> Unit
): T = this.then(Commands.literal(name).executes { ctx ->
    action(ctx)
    1
})


fun <T : ArgumentBuilder<CommandSourceStack, T>, R : Any> T.argument(
    name: String,
    type: ArgumentType<R>,
    builder: RequiredArgumentBuilder<CommandSourceStack, R>.() -> Unit
): T = this.then(Commands.argument(name, type).apply(builder))

fun <T : ArgumentBuilder<CommandSourceStack, T>, R : Any> T.argumentLeaf(
    name: String,
    type: ArgumentType<R>,
    suggest: ((CommandContext<CommandSourceStack>) -> Collection<String>)? = null,
    action: (CommandContext<CommandSourceStack>) -> Unit
): T {
    var builder = Commands.argument(name, type)
    if (suggest != null) builder = builder.suggest(suggest)

    return this.then(builder.execute(action))
}


fun <T : ArgumentBuilder<CommandSourceStack, T>, R : Any> T.arguments(
    vararg names: String,
    type: ArgumentType<R>,
    action: (CommandContext<CommandSourceStack>) -> Unit
): T {
    if (names.isEmpty()) return this
    var tail: ArgumentBuilder<CommandSourceStack, *> =
        Commands.argument(names.last(), type).execute(action)

    for (i in names.size - 2 downTo 0) {
        tail = Commands.argument(names[i], type).then(tail)
    }

    return this.then(tail)
}


inline fun <reified T> CommandContext<CommandSourceStack>.getArgument(name: String): T =
    this.getArgument(name, T::class.java)

inline fun <reified T> CommandContext<CommandSourceStack>.senderAs(): T? =
    this.source.sender as? T

fun <T> RequiredArgumentBuilder<CommandSourceStack, T>.suggest(
    provider: (CommandContext<CommandSourceStack>) -> Collection<String>
): RequiredArgumentBuilder<CommandSourceStack, T> = this.suggests { ctx, builder ->
    provider(ctx).forEach { builder.suggest(it) }
    builder.buildFuture()
}


inline fun <T : ArgumentBuilder<CommandSourceStack, T>> T.executeAsPlayer(
    crossinline block: CommandContext<CommandSourceStack>.(Player) -> Unit
): T = this.executes { ctx ->
    val player = ctx.source.sender as? Player
    if (player != null) {
        ctx.block(player)
        1
    } else {
        ctx.source.sender.sendMessage(
            Component.text("Sender is not Player", NamedTextColor.RED)
        )
        0
    }
}


fun <T : ArgumentBuilder<CommandSourceStack, T>> T.requiresOp(): T =
    this.requires { it.sender.isOp }

fun <T : ArgumentBuilder<CommandSourceStack, T>> T.requiresPermission(permission: String): T =
    this.requires { it.sender.hasPermission(permission) }


fun CommandContext<CommandSourceStack>.getSinglePlayer(name: String): Player? =
    this.getArgument<PlayerSelectorArgumentResolver>(name).resolve(this.source).firstOrNull()

fun CommandContext<CommandSourceStack>.getPlayers(name: String): List<Player> =
    this.getArgument<PlayerSelectorArgumentResolver>(name).resolve(this.source)

fun CommandContext<CommandSourceStack>.getEntities(name: String): List<Entity> =
    this.getArgument<EntitySelectorArgumentResolver>(name).resolve(this.source)


@Suppress("UnusedReceiverParameter")
fun CommandContext<CommandSourceStack>.fail(message: String): Nothing {
    throw SimpleCommandExceptionType(LiteralMessage(message)).create()
}

@Suppress("UnusedReceiverParameter")
fun CommandContext<CommandSourceStack>.fail(component: Component): Nothing {
    val plainText = PlainTextComponentSerializer.plainText().serialize(component)
    throw SimpleCommandExceptionType { plainText }.create()
}

//endregion
