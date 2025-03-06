package com.cobblebattlerewards.utils

import com.blanketutils.config.ConfigData
import com.blanketutils.config.ConfigManager
import com.blanketutils.config.ConfigMetadata
import com.blanketutils.utils.LogDebug
import com.blanketutils.utils.logDebug
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
            "rewards" to "List of all rewards with their conditions and properties"
        ),
        footerComments = listOf(
            "===========================================",
            " End of CobbleBattleRewards Configuration",
            "===========================================",
            "",
            "For help and support: [Your contact information]"
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
            // Wild Pokemon Battle Currency Rewards (Various Chance Rates)
            Reward(
                type = "command",
                id = "wild_dollars_common",
                message = "You received §a$25§r for winning the battle!",
                command = "eco deposit 25 dollars %player%",
                chance = 50.0,
                cooldown = 0,
                triggerConditions = listOf("BattleWon"),
                battleTypes = listOf("wild"),
                scope = listOf("global"),
                minLevel = 1,
                maxLevel = 100
            ),
            Reward(
                type = "command",
                id = "wild_dollars_uncommon",
                message = "You received §a$50§r for winning the battle!",
                command = "eco deposit 50 dollars %player%",
                chance = 25.0,
                cooldown = 0,
                triggerConditions = listOf("BattleWon"),
                battleTypes = listOf("wild"),
                scope = listOf("global"),
                minLevel = 1,
                maxLevel = 100
            ),
            Reward(
                type = "command",
                id = "wild_dollars_rare",
                message = "You received §a$100§r for winning the battle!",
                command = "eco deposit 100 dollars %player%",
                chance = 10.0,
                cooldown = 0,
                triggerConditions = listOf("BattleWon"),
                battleTypes = listOf("wild"),
                scope = listOf("global"),
                minLevel = 1,
                maxLevel = 100
            ),

            // Battle Points for Wild Battle
            Reward(
                type = "command",
                id = "wild_battle_points",
                message = "You earned §b5 Battle Points§r!",
                command = "eco deposit 5 battlepoints %player%",
                chance = 100.0,
                cooldown = 0,
                triggerConditions = listOf("BattleWon"),
                battleTypes = listOf("wild"),
                scope = listOf("global"),
                minLevel = 1,
                maxLevel = 100
            ),

            // Extra reward for higher level Pokémon
            Reward(
                type = "command",
                id = "wild_high_level_bonus",
                message = "You received a §6High Level Bonus§r of §a$75§r!",
                command = "eco deposit 75 dollars %player%",
                chance = 100.0,
                cooldown = 0,
                triggerConditions = listOf("BattleWon"),
                battleTypes = listOf("wild"),
                scope = listOf("global"),
                minLevel = 50,
                maxLevel = 100
            ),

            // NPC Trainer Battle Rewards
            Reward(
                type = "command",
                id = "npc_dollars_reward",
                message = "You received §a$100§r for defeating the trainer!",
                command = "eco deposit 100 dollars %player%",
                chance = 100.0,
                cooldown = 0,
                triggerConditions = listOf("BattleWon"),
                battleTypes = listOf("npc"),
                scope = listOf("global"),
                minLevel = 1,
                maxLevel = 100
            ),
            Reward(
                type = "command",
                id = "npc_battle_points",
                message = "You earned §b15 Battle Points§r from the trainer battle!",
                command = "eco deposit 15 battlepoints %player%",
                chance = 100.0,
                cooldown = 0,
                triggerConditions = listOf("BattleWon"),
                battleTypes = listOf("npc"),
                scope = listOf("global"),
                minLevel = 1,
                maxLevel = 100
            ),
            Reward(
                type = "command",
                id = "npc_trainer_tokens",
                message = "You received §d10 Trainer Tokens§r!",
                command = "eco deposit 10 trainertokens %player%",
                chance = 100.0,
                cooldown = 0,
                triggerConditions = listOf("BattleWon"),
                battleTypes = listOf("npc"),
                scope = listOf("global"),
                minLevel = 1,
                maxLevel = 100
            ),

            // PVP Battle Rewards
            Reward(
                type = "command",
                id = "pvp_dollars_reward",
                message = "You received §a$150§r for winning the PVP battle!",
                command = "eco deposit 150 dollars %player%",
                chance = 100.0,
                cooldown = 0,
                triggerConditions = listOf("BattleWon"),
                battleTypes = listOf("pvp"),
                scope = listOf("global"),
                minLevel = 1,
                maxLevel = 100
            ),
            Reward(
                type = "command",
                id = "pvp_battle_points",
                message = "You earned §b25 Battle Points§r from the PVP battle!",
                command = "eco deposit 25 battlepoints %player%",
                chance = 100.0,
                cooldown = 0,
                triggerConditions = listOf("BattleWon"),
                battleTypes = listOf("pvp"),
                scope = listOf("global"),
                minLevel = 1,
                maxLevel = 100
            ),
            Reward(
                type = "command",
                id = "pvp_tokens",
                message = "You received §d15 PVP Tokens§r!",
                command = "eco deposit 15 pvptokens %player%",
                chance = 100.0,
                cooldown = 0,
                triggerConditions = listOf("BattleWon"),
                battleTypes = listOf("pvp"),
                scope = listOf("global"),
                minLevel = 1,
                maxLevel = 100
            ),

            // Capture Rewards
            Reward(
                type = "command",
                id = "capture_bonus_dollars",
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
            Reward(
                type = "command",
                id = "capture_bonus_points",
                message = "You earned §b10 Capture Points§r!",
                command = "eco deposit 10 capturepoints %player%",
                chance = 100.0,
                cooldown = 0,
                triggerConditions = listOf("Captured"),
                battleTypes = listOf("wild"),
                scope = listOf("global"),
                minLevel = 1,
                maxLevel = 100
            ),

            // Bonus for capturing rare types
            Reward(
                type = "command",
                id = "capture_rare_type_bonus",
                message = "You received a §6Rare Type Bonus§r of §a$150§r!",
                command = "eco deposit 150 dollars %player%",
                chance = 100.0,
                cooldown = 0,
                triggerConditions = listOf("Captured"),
                battleTypes = listOf("wild"),
                scope = listOf("type"),
                pokemonTypes = listOf("dragon", "ghost", "fairy"),
                minLevel = 1,
                maxLevel = 100
            ),

            // Forfeit consolation rewards
            Reward(
                type = "command",
                id = "forfeit_consolation_dollars",
                message = "You received §a$10§r as a consolation for forfeiting!",
                command = "eco deposit 10 dollars %player%",
                chance = 100.0,
                cooldown = 0,
                triggerConditions = listOf("BattleForfeit"),
                battleTypes = listOf("wild", "npc", "pvp"),
                scope = listOf("global"),
                minLevel = 1,
                maxLevel = 100
            ),
            Reward(
                type = "item",
                id = "forfeit_potion",
                message = "You received a Consolation Potion for forfeiting!",
                chance = 50.0,
                item = Item(
                    id = "minecraft:potion",
                    count = 1,
                    customName = "Consolation Potion",
                    lore = listOf("A small reward for your effort"),
                    trackerValue = 5678
                ),
                cooldown = 300,
                cooldownActiveMessage = "You need to wait %time% seconds before receiving another Consolation Potion.",
                triggerConditions = listOf("BattleForfeit"),
                battleTypes = listOf("wild", "npc", "pvp"),
                scope = listOf("global")
            ),

            // Example of a special redeemable item for Pikachu
            Reward(
                type = "redeemable",
                id = "pikachu_thunderstone",
                message = "You captured a Pikachu and received a Thunderstone!",
                redeemCommand = "give %player% minecraft:nether_star 1",
                redeemMessage = "You've redeemed your Thunderstone!",
                chance = 100.0,
                item = Item(
                    id = "cobblemon:thunder_stone",
                    count = 1,
                    customName = "Special Thunderstone",
                    lore = listOf("Right-click to redeem", "A reward for capturing Pikachu!"),
                    trackerValue = 5553
                ),
                cooldown = 86400,
                cooldownActiveMessage = "You can only redeem one Thunder Stone per day. Please wait %time% seconds.",
                triggerConditions = listOf("Captured"),
                battleTypes = listOf("wild"),
                scope = listOf("pokemon"),
                pokemonSpecies = listOf("pikachu")
            ),

            // Example of a tiered item reward by level
            Reward(
                type = "item",
                id = "tier1_reward_pokeball",
                message = "You received 5 Poké Balls for winning!",
                chance = 80.0,
                item = Item(
                    id = "cobblemon:poke_ball",
                    count = 5,
                    customName = "Battle Reward Poké Ball",
                    lore = listOf("A basic reward"),
                    trackerValue = 1001
                ),
                cooldown = 300,
                triggerConditions = listOf("BattleWon"),
                battleTypes = listOf("wild"),
                scope = listOf("global"),
                minLevel = 1,
                maxLevel = 30
            ),
            Reward(
                type = "item",
                id = "tier2_reward_greatball",
                message = "You received 3 Great Balls for winning!",
                chance = 60.0,
                item = Item(
                    id = "cobblemon:great_ball",
                    count = 3,
                    customName = "Battle Reward Great Ball",
                    lore = listOf("An intermediate reward"),
                    trackerValue = 1002
                ),
                cooldown = 600,
                triggerConditions = listOf("BattleWon"),
                battleTypes = listOf("wild"),
                scope = listOf("global"),
                minLevel = 31,
                maxLevel = 60
            ),
            Reward(
                type = "item",
                id = "tier3_reward_ultraball",
                message = "You received an Ultra Ball for winning!",
                chance = 40.0,
                item = Item(
                    id = "cobblemon:ultra_ball",
                    count = 1,
                    customName = "Battle Reward Ultra Ball",
                    lore = listOf("An advanced reward"),
                    trackerValue = 1003
                ),
                cooldown = 900,
                triggerConditions = listOf("BattleWon"),
                battleTypes = listOf("wild"),
                scope = listOf("global"),
                minLevel = 61,
                maxLevel = 100
            )
        )
    )
}