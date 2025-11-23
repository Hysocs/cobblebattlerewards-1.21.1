package com.cobblebattlerewards.utils

import com.everlastingutils.command.CommandManager
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

            // List available conditions subcommand
            subcommand("listconditions", permission = "cobblebattlerewards.list_conditions") {
                executes { context -> executeListConditionsCommand(context) }
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

        // Helper function to format rewards from a map
        fun formatRewards(rewards: Map<String, Reward>, category: String) {
            messageBuilder.append("§6§l$category:§r\n")
            if (rewards.isEmpty()) {
                messageBuilder.append("  §7No rewards configured§r\n")
            } else {
                rewards.forEach { (name, reward) ->
                    messageBuilder.append("  §7- $name: Type: ${reward.type}, Message: ${reward.message}, Chance: ${reward.chance}%§r\n")
                    if (reward.battleTypes.isNotEmpty()) {
                        messageBuilder.append("    Battle Types: ${reward.battleTypes.joinToString(", ")}§r\n")
                    }
                    if (reward.conditions.isNotEmpty()) {
                        messageBuilder.append("    Conditions: ${reward.conditions.joinToString(", ")}§r\n")
                    }
                    messageBuilder.append("    Level Range: ${reward.minLevel}-${reward.maxLevel}§r\n")
                }
            }
            messageBuilder.append("\n")
        }

        // List rewards by category
        formatRewards(config.battleWonRewards, "Battle Won Rewards")
        formatRewards(config.battleLostRewards, "Battle Lost Rewards")
        formatRewards(config.battleForfeitRewards, "Battle Forfeit Rewards")
        formatRewards(config.captureRewards, "Capture Rewards")

        source.sendFeedback({ Text.literal(messageBuilder.toString()) }, false)
        BattleRewardsConfigManager.logDebug("Listed rewards.")
        return 1
    }

    public var onListConditionsCommand: ((CommandContext<ServerCommandSource>) -> Unit)? = null

    private fun executeListConditionsCommand(context: CommandContext<ServerCommandSource>): Int {
        onListConditionsCommand?.invoke(context)
        return 1
    }
}