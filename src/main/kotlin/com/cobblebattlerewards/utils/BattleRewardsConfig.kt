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
    override val version: String = "1.0.1",
    override val configId: String = "cobblebattlerewards",
    var debugEnabled: Boolean = true,
    var inventoryFullBehavior: String = "drop", // "drop" or "skip"
    var rewards: List<Reward> = listOf()
) : ConfigData

data class Reward(
    var type: String, // "item", "command", or "redeemable"
    var id: String, // Unique identifier for the reward
    var message: String,
    var command: String = "",
    var redeemCommand: String = "",
    var redeemMessage: String = "",
    var chance: Double,
    var item: Item? = null,
    var cooldown: Long = 0L,
    var cooldownActiveMessage: String = "",
    var excludesRewards: List<String> = listOf(), // List of reward IDs that should not trigger if this one does
    @SerializedName("triggerCondition")
    var triggerConditions: List<String> = listOf("BattleWon"), // e.g., ["Captured", "BattleWon", "BattleForfeit"]
    @SerializedName("TriggerTypes")
    var battleTypes: List<String> = listOf(), // e.g., ["wild", "pvp", "npc"]
    @SerializedName("RewardTypes")
    var scope: List<String> = listOf("global"), // e.g., ["pokemon"], ["type"], ["global"]
    var pokemonSpecies: List<String> = listOf(), // Used if scope includes "pokemon"
    var pokemonTypes: List<String> = listOf(), // Used if scope includes "type"
    var minLevel: Int = 1,
    var maxLevel: Int = 100
)

data class Item(
    var id: String,
    var count: Int,
    var customName: String,
    var lore: List<String>,
    var trackerValue: Int
)

object BattleRewardsConfigManager {
    private val logger = LoggerFactory.getLogger("CobbleBattleRewards")
    private const val MOD_ID = "cobblebattlerewards"
    private const val CURRENT_VERSION = "1.0.1"
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
            "- Rewards: A list of all reward entries with their conditions and properties",
            "",
            "REWARD TYPES:",
            "- 'item': Gives a Minecraft item directly to the player",
            "- 'command': Executes a server command (e.g., economy, give, etc.)",
            "- 'redeemable': Creates a special item that players can right-click to redeem",
            "",
            "BATTLE TRIGGERS:",
            "- 'BattleWon': Triggered when a player wins a battle",
            "- 'Captured': Triggered when a player captures a Pokémon",
            "- 'BattleForfeit': Triggered when a battle ends by forfeit",
            "- 'BattleLost': Triggered when a player loses a battle",
            "",
            "BATTLE TYPES:",
            "- 'wild': Battles against wild Pokémon",
            "- 'pvp': Player versus player battles",
            "- 'npc': Battles against NPC trainers",
            "",
            "REWARD SCOPE:",
            "- 'global': Applies to all battles of specified type and trigger",
            "- 'pokemon': Restricted to specific Pokémon species (defined in pokemonSpecies)",
            "- 'type': Restricted to Pokémon of specific types (defined in pokemonTypes)",
            "",
            "REWARD EXCLUSIONS:",
            "- 'excludesRewards': List of reward IDs that should not trigger if this reward triggers",
            "  This allows you to create tiered rewards where better rewards prevent lesser ones from triggering",
            "",
            "VARIABLES IN MESSAGES AND COMMANDS:",
            "- %player% : Will be replaced with the player's name",
            "- %time% : In cooldown messages, will be replaced with remaining cooldown time",
            "",
            "ECONOMY COMMANDS:",
            "The default configuration uses 'eco deposit' commands which are compatible with",
            "many economy plugins. You may need to adjust these commands to match your server's",
            "economy system. The format used is:",
            "",
            "eco deposit [amount] [currency] [player]",
            "",
            "Where:",
            "- [amount]: The amount of currency to give",
            "- [currency]: The currency type (e.g., dollars, points, tokens)",
            "- [player]: Replaced with %player% to target the correct player"
        ),
        sectionComments = mapOf(
            "version" to "DO NOT EDIT - Version tracking for config migrations",
            "configId" to "DO NOT EDIT - Unique identifier for this config file",
            "debugEnabled" to "Set to true for detailed logging (helps troubleshooting)",
            "inventoryFullBehavior" to "What happens when a player's inventory is full: 'drop' or 'skip'",
            "rewards" to "List of all rewards with their conditions and properties",
            "excludesRewards" to "List of reward IDs that should not trigger if this reward triggers"
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
            // First initialize LogDebug with default state (disabled)
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
        // Add a test debug message to verify debug state
        logDebug("Config reload completed - this message should appear if debug is enabled")
    }

    private fun updateDebugState() {
        val currentConfig = configManager.getCurrentConfig()
        val debugEnabled = currentConfig.debugEnabled
        logDebug("Setting debug state to: $debugEnabled")
        LogDebug.setDebugEnabledForMod(MOD_ID, debugEnabled)
        // Add a test debug message
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
        rewards = listOf(
            // Tiered rewards for winning battles - showing reward exclusions
            Reward(
                type = "command",
                id = "rare_battle_win",
                message = "You received §a$100§r for winning! §6(Rare Reward)§r",
                command = "eco deposit 100 dollars %player%",
                chance = 25.0,
                cooldown = 0,
                // This reward excludes the uncommon and common rewards
                excludesRewards = listOf("uncommon_battle_win", "basic_battle_win"),
                triggerConditions = listOf("BattleWon"),
                battleTypes = listOf("wild", "npc", "pvp"),
                scope = listOf("global"),
                minLevel = 1,
                maxLevel = 100
            ),
            Reward(
                type = "command",
                id = "uncommon_battle_win",
                message = "You received §a$50§r for winning! §e(Uncommon Reward)§r",
                command = "eco deposit 50 dollars %player%",
                chance = 50.0,
                cooldown = 0,
                // This reward only excludes the basic reward
                excludesRewards = listOf("basic_battle_win"),
                triggerConditions = listOf("BattleWon"),
                battleTypes = listOf("wild", "npc", "pvp"),
                scope = listOf("global"),
                minLevel = 1,
                maxLevel = 100
            ),
            Reward(
                type = "command",
                id = "basic_battle_win",
                message = "You received §a$25§r for winning!",
                command = "eco deposit 25 dollars %player%",
                chance = 100.0,
                cooldown = 0,
                // This is the fallback reward with no exclusions
                triggerConditions = listOf("BattleWon"),
                battleTypes = listOf("wild", "npc", "pvp"),
                scope = listOf("global"),
                minLevel = 1,
                maxLevel = 100
            ),

            // Reward for capturing a Pokémon
            Reward(
                type = "command",
                id = "capture_reward",
                message = "You received §a$50§r for capturing a Pokémon!",
                command = "eco deposit 50 dollars %player%",
                chance = 100.0,
                cooldown = 0,
                triggerConditions = listOf("Captured"),
                battleTypes = listOf("wild"),
                scope = listOf("global"),
                minLevel = 1,
                maxLevel = 100
            ),

            // Bonus for Ghost-type Pokémon (works for both capture and battle)
            Reward(
                type = "command",
                id = "ghost_type_bonus",
                message = "You received a §6Ghost Type Bonus§r of §a$100§r!",
                command = "eco deposit 100 dollars %player%",
                chance = 100.0,
                cooldown = 0,
                triggerConditions = listOf("Captured", "BattleWon"),
                battleTypes = listOf("wild"),
                scope = listOf("type"),
                pokemonTypes = listOf("ghost"),
                minLevel = 1,
                maxLevel = 100
            ),

            // Item reward - gives Poké Balls
            Reward(
                type = "item",
                id = "pokeball_reward",
                message = "You received 3 Poké Balls!",
                chance = 100.0,
                item = Item(
                    id = "cobblemon:poke_ball",
                    count = 3,
                    customName = "Battle Reward Poké Ball",
                    lore = listOf("A reward for your battle"),
                    trackerValue = 1001
                ),
                cooldown = 0,
                triggerConditions = listOf("BattleWon"),
                battleTypes = listOf("wild"),
                scope = listOf("global"),
                minLevel = 1,
                maxLevel = 100
            ),

            // Species-specific redeemable reward for Pikachu
            Reward(
                type = "redeemable",
                id = "pikachu_reward",
                message = "You found a §ePikachu Reward Ticket§r!",
                redeemCommand = "give %player% minecraft:gold_ingot 3",
                redeemMessage = "You've redeemed your Pikachu Reward Ticket!",
                chance = 100.0,
                item = Item(
                    id = "minecraft:paper",
                    count = 1,
                    customName = "Pikachu Reward Ticket",
                    lore = listOf("Right-click to redeem", "A special reward for Pikachu battles!"),
                    trackerValue = 2001
                ),
                cooldown = 300,
                cooldownActiveMessage = "You need to wait %time% seconds before receiving another ticket.",
                triggerConditions = listOf("BattleWon", "Captured"),
                battleTypes = listOf("wild"),
                scope = listOf("pokemon"),
                pokemonSpecies = listOf("pikachu"),
                minLevel = 1,
                maxLevel = 100
            )
        )
    )
}