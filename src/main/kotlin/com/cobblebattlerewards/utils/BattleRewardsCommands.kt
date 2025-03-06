package com.cobblebattlerewards.utils

import com.blanketutils.command.CommandManager
import net.minecraft.server.command.ServerCommandSource
import com.mojang.brigadier.context.CommandContext
import net.minecraft.text.Text
import org.slf4j.LoggerFactory

object BattleRewardsCommands {
    private val logger = LoggerFactory.getLogger("CommandRegistrar")
    private val manager = CommandManager("cobblebattlerewards")

    fun registerCommands() {
        manager.command("cobblebattlerewards", aliases = listOf("cbr")) {
            // Base command
            executes { context ->
                executeBaseCommand(context)
            }

            // Reload subcommand
            subcommand("reload", permission = "cobblebattlerewards.reload") {
                executes { context -> executeReloadCommand(context) }
            }

            // List rewards subcommand
            subcommand("listrewards", permission = "cobblebattlerewards.list") {
                executes { context -> executeListRewardsCommand(context) }
            }
        }

        // Register all commands
        manager.register()
    }

    private fun executeBaseCommand(context: CommandContext<ServerCommandSource>): Int {
        CommandManager.sendSuccess(
            context.source,
            "§aCobblemon Battle Rewards v1.3.0",
            false
        )
        return 1
    }

    private fun executeReloadCommand(context: CommandContext<ServerCommandSource>): Int {
        val source = context.source
        try {
            BattleRewardsConfigManager.reloadBlocking()
            CommandManager.sendSuccess(
                source,
                "§aCobblemon Battle Rewards configuration has been reloaded.",
                true
            )
            BattleRewardsConfigManager.logDebug("Configuration reloaded for CobbleBattleRewards.")
            return 1
        } catch (e: Exception) {
            CommandManager.sendError(
                source,
                "§cFailed to reload configuration: ${e.message}"
            )
            BattleRewardsConfigManager.logDebug("Error reloading configuration: ${e.message}")
            e.printStackTrace()
            return 0
        }
    }

    private fun executeListRewardsCommand(context: CommandContext<ServerCommandSource>): Int {
        val source = context.source
        val config = BattleRewardsConfigManager.config

        val messageBuilder = StringBuilder()
        messageBuilder.append("§6§lCobblemon Battle Rewards§r\n\n")

        // Group rewards by scope
        val globalRewards = config.rewards.filter { "global" in it.scope }
        val pokemonRewards = config.rewards.filter { "pokemon" in it.scope }
        val typeRewards = config.rewards.filter { "type" in it.scope }

        // Global Rewards
        messageBuilder.append("§6§lGlobal Rewards:§r\n")
        if (globalRewards.isEmpty()) {
            messageBuilder.append("  §7No global rewards configured§r\n")
        } else {
            globalRewards.forEach { reward ->
                messageBuilder.append("  §7- ${reward.id}: ${reward.message} (${reward.chance}% chance)§r\n")
            }
        }
        messageBuilder.append("\n")

        // Pokémon Rewards
        messageBuilder.append("§6§lPokémon Rewards:§r\n")
        if (pokemonRewards.isEmpty()) {
            messageBuilder.append("  §7No Pokémon-specific rewards configured§r\n")
        } else {
            val pokemonMap = pokemonRewards.groupBy { it.pokemonSpecies.joinToString(", ") }
            pokemonMap.forEach { (species, rewards) ->
                messageBuilder.append("§e§lPokémon $species:§r\n")
                rewards.forEach { reward ->
                    messageBuilder.append("  §7- ${reward.id}: ${reward.message} (${reward.chance}% chance)§r\n")
                }
            }
        }
        messageBuilder.append("\n")

        // Type Group Rewards
        messageBuilder.append("§6§lType Group Rewards:§r\n")
        if (typeRewards.isEmpty()) {
            messageBuilder.append("  §7No type-based rewards configured§r\n")
        } else {
            val typeMap = typeRewards.groupBy { it.pokemonTypes.joinToString(", ") }
            typeMap.forEach { (types, rewards) ->
                messageBuilder.append("§e§lType $types:§r\n")
                rewards.forEach { reward ->
                    messageBuilder.append("  §7- ${reward.id}: ${reward.message} (${reward.chance}% chance)§r\n")
                }
            }
        }

        source.sendFeedback({ Text.literal(messageBuilder.toString()) }, false)
        BattleRewardsConfigManager.logDebug("Listed rewards.")
        return 1
    }
}