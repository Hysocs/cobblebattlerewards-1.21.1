package com.cobblebattlerewards.utils

import com.everlastingutils.config.ConfigData
import com.everlastingutils.config.ConfigManager
import com.everlastingutils.config.ConfigMetadata
import com.everlastingutils.utils.LogDebug
import com.everlastingutils.utils.logDebug
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

data class BattleRewardsConfig(
    override val version: String = "2.0.3",
    override val configId: String = "cobblebattlerewards",
    var debugEnabled: Boolean = true,
    var inventoryFullBehavior: String = "drop", // "drop" or "skip"
    @SerializedName("battleWonRewards")
    var battleWonRewards: Map<String, Reward> = mapOf(),
    @SerializedName("battleLostRewards")
    var battleLostRewards: Map<String, Reward> = mapOf(),
    @SerializedName("battleForfeitRewards")
    var battleForfeitRewards: Map<String, Reward> = mapOf(),
    @SerializedName("captureRewards")
    var captureRewards: Map<String, Reward> = mapOf()
) : ConfigData

data class Reward(
    var type: String, // "item" or "command"
    var message: String = "", // MiniMessage-formatted message shown to player when reward is given
    var command: String = "", // Used if type is "command"
    var itemStack: String = "", // JSON-serialized ItemStack string, used if type is "item"
    var chance: Double = 100.0, // Percentage chance (0.0 to 100.0)
    var cooldown: Long = 0L, // Cooldown in seconds
    var cooldownMessage: String = "", // MiniMessage-formatted message shown when cooldown is active
    var battleTypes: List<String> = listOf("wild", "pvp", "npc"), // "wild", "pvp", "npc"
    val conditions: List<Any> = emptyList(),
    var minLevel: Int = 1,
    var maxLevel: Int = 100,
    var order: Int = 999, // Lower numbers = higher priority
    var excludedRewards: List<String> = listOf() // List of reward names to exclude if this reward is triggered
)

object BattleRewardsConfigManager {
    private val logger = LoggerFactory.getLogger("cobblebattlerewards")
    private const val MOD_ID = "cobblebattlerewards"
    private const val CURRENT_VERSION = "2.0.3"
    private lateinit var configManager: ConfigManager<BattleRewardsConfig>
    private var isInitialized = false

    private val configMetadata = ConfigMetadata(
        headerComments = listOf(
            "===========================================",
            " CobbleBattleRewards Configuration",
            "===========================================",
            "",
            "This configuration controls rewards for Pokémon battles.",
            "",
            "MAIN STRUCTURE:",
            "- General settings: Debug mode and inventory handling",
            "- Reward categories: Maps of named rewards for each trigger type",
            "",
            "REWARD TYPES:",
            "- 'item': Gives a Minecraft item directly to the player (uses JSON-serialized ItemStack strings)",
            "- 'command': Executes a server command (e.g., economy, give, etc.)",
            "",
            "REWARD CATEGORIES:",
            "- 'battleWonRewards': A map of named rewards for winning a battle",
            "- 'battleLostRewards': A map of named rewards for losing a battle",
            "- 'battleForfeitRewards': A map of named rewards for forfeiting a battle",
            "- 'captureRewards': A map of named rewards for capturing a Pokémon",
            "",
            "BATTLE TYPES:",
            "- 'wild': Battles against wild Pokémon",
            "- 'pvp': Player versus player battles",
            "- 'npc': Battles against NPC trainers",
            "",
            "REWARD FILTERS:",
            "- 'conditions': Restrict to Pokémon with specific conditions (empty = all)",
            "  Conditions are tags like 'cobblemon:pikachu', 'type:electric', 'form:alolan', etc.",
            "- 'minLevel'/'maxLevel': Restrict to Pokémon level range",
            "- 'order': Priority of the reward (lower numbers = higher priority, default 999)",
            "- 'excludedRewards': List of reward names to exclude if this reward is triggered",
            "",
            "PLACEHOLDERS (usable in message & command strings):",
            "- '%player%': the player's name",
            "- '%pokemon%': the opponent Pokémon species",
            "- '%level%': the opponent Pokémon's level",
            "- '%battleType%': the battle type (wild, npc, pvp)",
            "- '%chance%': the configured reward chance (%)",
            "- '%coords%': player's location as X,Y,Z",
            "- '%trigger%': event trigger (BattleWon, Captured, etc.)",
            "- '%time%': remaining cooldown time (in seconds)",
            "",
            "ITEM STACK STRINGS:",
            "- For 'item' rewards, use JSON-serialized ItemStack strings",
            "- Example: {\"id\":\"minecraft:diamond\",\"count\":1}",
            "- Use commands like '/give' to test and copy valid formats",
            "",
            "EXAMPLE CONFIG:",
            "- See default rewards below for examples"
        ),
        sectionComments = mapOf(
            "version" to "DO NOT EDIT - Version tracking for config migrations",
            "configId" to "DO NOT EDIT - Unique identifier for this config file",
            "debugEnabled" to "Set to true for detailed logging (helps troubleshooting)",
            "inventoryFullBehavior" to "What happens when a player's inventory is full: 'drop' or 'skip'",
            "battleWonRewards" to "Named rewards given when a player wins a battle",
            "battleLostRewards" to "Named rewards given when a player loses a battle",
            "battleForfeitRewards" to "Named rewards given when a player forfeits a battle",
            "captureRewards" to "Named rewards given when a player captures a Pokémon"
        ),
        footerComments = listOf(
            "===========================================",
            " End of CobbleBattleRewards Configuration",
            "===========================================",
            "",
            "For help and support: [https://discord.gg/nrENPTmQKt]"
        ),
        includeTimestamp = true,
        includeVersion = true
    )

    fun logDebug(message: String) {
        if (config.debugEnabled) {
            logDebug(message, MOD_ID)
        }
    }

    fun initializeAndLoad() {
        if (!isInitialized) {
            LogDebug.init(MOD_ID, false)
            initialize()
            runBlocking { load() }
            isInitialized = true
        }
    }

    private fun initialize() {
        configManager = ConfigManager(
            currentVersion = CURRENT_VERSION,
            defaultConfig = createDefaultConfig(),
            configClass = BattleRewardsConfig::class,
            metadata = configMetadata
        )
    }

    private suspend fun load() {
        logDebug("Loading configuration...")
        configManager.reloadConfig()
        logDebug("Configuration loaded, updating debug state...")
        updateDebugState()
        logDebug("Debug state updated")
    }

    fun reloadBlocking() {
        logDebug("Starting config reload...")
        runBlocking {
            configManager.reloadConfig()
            logDebug("Config reloaded, updating debug state...")
            updateDebugState()
            logDebug("Reload complete")
        }
        logDebug("Config reload completed - this message should appear if debug is enabled")
    }

    private fun updateDebugState() {
        val currentConfig = configManager.getCurrentConfig()
        val debugEnabled = currentConfig.debugEnabled
        logDebug("Setting debug state to: $debugEnabled")
        LogDebug.setDebugEnabledForMod(MOD_ID, debugEnabled)
        logDebug("Debug state updated - this message should appear if debug is enabled")
    }

    val config: BattleRewardsConfig
        get() = configManager.getCurrentConfig()

    fun cleanup() {
        if (isInitialized) {
            configManager.cleanup()
            isInitialized = false
        }
    }

    private fun createDefaultConfig() = BattleRewardsConfig(
        version = CURRENT_VERSION,
        debugEnabled = true,
        inventoryFullBehavior = "drop",
        battleWonRewards = mapOf(
            "money_100" to Reward(
                type = "command",
                message = "<green>You received $100 for winning a %battleType% battle against %pokemon% (lvl %level%)!</green>",
                command = "eco deposit 100 dollars %player%",
                chance = 25.0,
                battleTypes = listOf("wild", "npc", "pvp"),
                minLevel = 1,
                maxLevel = 100,
                order = 999
            ),
            "money_50" to Reward(
                type = "command",
                message = "<green>You received $50 for winning at %coords%!</green>",
                command = "eco deposit 50 dollars %player%",
                chance = 50.0,
                battleTypes = listOf("wild", "npc", "pvp"),
                minLevel = 1,
                maxLevel = 100,
                order = 999
            ),
            "pokeballs" to Reward(
                type = "item",
                message = "<aqua>You received 3 Poké Balls at %coords%!</aqua>",
                itemStack = "{\"id\":\"cobblemon:poke_ball\",\"count\":3,\"components\":{\"minecraft:custom_name\":\"\\\"Battle Reward Poké Ball\\\"\"}}",
                chance = 100.0,
                battleTypes = listOf("wild"),
                minLevel = 1,
                maxLevel = 100,
                order = 999
            ),
            "ghost_bonus" to Reward(
                type = "command",
                message = "<gold>You received a Ghost Type Bonus for defeating %pokemon%!</gold>",
                command = "eco deposit 100 dollars %player%",
                chance = 100.0,
                battleTypes = listOf("wild"),
                conditions = listOf("type:ghost"),
                minLevel = 1,
                maxLevel = 100,
                order = 2
            ),
            "pikachu_reward" to Reward(
                type = "item",
                message = "<yellow>You found a Pikachu reward after beating %pokemon% (lvl %level%)!</yellow>",
                itemStack = "{\"id\":\"minecraft:gold_ingot\",\"count\":3,\"components\":{\"minecraft:custom_name\":\"\\\"Pikachu Reward\\\"\"}}",
                chance = 100.0,
                cooldown = 300,
                cooldownMessage = "<red>Please wait %time% seconds before another Pikachu reward.</red>",
                battleTypes = listOf("wild"),
                conditions = listOf("cobblemon:pikachu"),
                minLevel = 1,
                maxLevel = 100,
                order = 1,
                excludedRewards = listOf("electric_bonus")
            ),
            "zapdos_reward" to Reward(
                type = "item",
                message = "<light_purple>You defeated Zapdos and received a legendary reward!</light_purple>",
                itemStack = "{\"id\":\"minecraft:emerald\",\"count\":5,\"components\":{\"minecraft:custom_name\":\"\\\"Zapdos Reward\\\"\"}}",
                chance = 100.0,
                battleTypes = listOf("wild"),
                conditions = listOf("cobblemon:zapdos"),
                minLevel = 1,
                maxLevel = 100,
                order = 1,
                excludedRewards = listOf("electric_bonus", "flying_bonus")
            ),
            "electric_bonus" to Reward(
                type = "command",
                message = "<light_purple>You received an Electric Type Bonus for winning!</light_purple>",
                command = "eco deposit 50 dollars %player%",
                chance = 100.0,
                battleTypes = listOf("wild"),
                conditions = listOf("type:electric"),
                minLevel = 1,
                maxLevel = 100,
                order = 2
            ),
            "flying_bonus" to Reward(
                type = "command",
                message = "<light_purple>You received a Flying Type Bonus for winning!</light_purple>",
                command = "eco deposit 25 dollars %player%",
                chance = 100.0,
                battleTypes = listOf("wild"),
                conditions = listOf("type:flying"),
                minLevel = 1,
                maxLevel = 100,
                order = 3
            )
        ),
        captureRewards = mapOf(
            "capture_money" to Reward(
                type = "command",
                message = "<green>You received $50 for capturing a Pokémon at %coords%!</green>",
                command = "eco deposit 50 dollars %player%",
                chance = 100.0,
                battleTypes = listOf("wild"),
                minLevel = 1,
                maxLevel = 100,
                order = 999
            ),
            "ghost_capture_bonus" to Reward(
                type = "command",
                message = "<gold>You received a Ghost Capture Bonus for catching %pokemon%!</gold>",
                command = "eco deposit 100 dollars %player%",
                chance = 100.0,
                battleTypes = listOf("wild"),
                conditions = listOf("type:ghost"),
                minLevel = 1,
                maxLevel = 100,
                order = 2
            ),
            "pikachu_capture_reward" to Reward(
                type = "item",
                message = "<yellow>You got a Pikachu capture reward!</yellow>",
                itemStack = "{\"id\":\"minecraft:gold_ingot\",\"count\":3,\"components\":{\"minecraft:custom_name\":\"\\\"Pikachu Reward\\\"\"}}",
                chance = 100.0,
                cooldown = 300,
                cooldownMessage = "<red>Please wait %time% seconds before another Pikachu capture reward.</red>",
                battleTypes = listOf("wild"),
                conditions = listOf("cobblemon:pikachu"),
                minLevel = 1,
                maxLevel = 100,
                order = 1,
                excludedRewards = listOf("electric_capture_bonus")
            ),
            "zapdos_capture_reward" to Reward(
                type = "item",
                message = "<light_purple>You captured the mighty Zapdos! Enjoy your legendary reward.</light_purple>",
                itemStack = "{\"id\":\"minecraft:emerald\",\"count\":5,\"components\":{\"minecraft:custom_name\":\"\\\"Zapdos Capture Reward\\\"\"}}",
                chance = 100.0,
                battleTypes = listOf("wild"),
                conditions = listOf("cobblemon:zapdos"),
                minLevel = 1,
                maxLevel = 100,
                order = 1,
                excludedRewards = listOf("electric_capture_bonus", "flying_capture_bonus")
            ),
            "electric_capture_bonus" to Reward(
                type = "command",
                message = "<light_purple>You received an Electric Capture Bonus!</light_purple>",
                command = "eco deposit 50 dollars %player%",
                chance = 100.0,
                battleTypes = listOf("wild"),
                conditions = listOf("type:electric"),
                minLevel = 1,
                maxLevel = 100,
                order = 2
            ),
            "flying_capture_bonus" to Reward(
                type = "command",
                message = "<light_purple>You received a Flying Capture Bonus!</light_purple>",
                command = "eco deposit 25 dollars %player%",
                chance = 100.0,
                battleTypes = listOf("wild"),
                conditions = listOf("type:flying"),
                minLevel = 1,
                maxLevel = 100,
                order = 3
            )
        ),
        battleLostRewards = mapOf(),
        battleForfeitRewards = mapOf()
    )
}
