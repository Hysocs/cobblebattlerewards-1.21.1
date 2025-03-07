package com.cobblebattlerewards

import com.cobblebattlerewards.utils.*
import com.everlastingutils.utils.logDebug
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor
import com.cobblemon.mod.common.api.battles.model.actor.ActorType
import com.cobblemon.mod.common.api.events.CobblemonEvents
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor
import com.cobblemon.mod.common.entity.npc.NPCEntity
import com.cobblemon.mod.common.pokemon.Pokemon
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.player.UseItemCallback
import net.minecraft.component.DataComponentTypes
import net.minecraft.component.type.LoreComponent
import net.minecraft.component.type.NbtComponent
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.nbt.NbtCompound
import net.minecraft.registry.Registries
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Hand
import net.minecraft.util.Identifier
import net.minecraft.util.TypedActionResult
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

object CobbleBattleRewards : ModInitializer {
	private val logger = LoggerFactory.getLogger("blanketcobblebattlerewards")
	private const val MOD_ID = "cobblebattlerewards"
	private val battles = ConcurrentHashMap<UUID, BattleState>()
	private val cooldowns = ConcurrentHashMap<UUID, MutableMap<String, Long>>()
	private val scheduler = Executors.newSingleThreadScheduledExecutor()

	enum class BattleType {
		WILD, NPC, PVP
	}

	data class BattleState(
		var actors: List<BattleActor> = emptyList(),
		var playerPokemon: Pokemon? = null,
		var opponentPokemon: Pokemon? = null,
		var isResolved: Boolean = false,
		var isCaptured: Boolean = false,
		var playerWon: Boolean = false,
		var battleType: BattleType = BattleType.WILD,
		var winningPlayers: MutableList<UUID> = mutableListOf(),
		var forfeitingActors: MutableList<UUID> = mutableListOf()
	)

	override fun onInitialize() {
		logDebug("BlanketCobbleBattleRewards: Initializing...", MOD_ID)
		BattleRewardsConfigManager.initializeAndLoad()
		setupEventHandlers()
		BattleRewardsCommands.registerCommands()

		scheduler.scheduleAtFixedRate({ cleanupBattles() }, 1, 1, TimeUnit.SECONDS)
		ServerLifecycleEvents.SERVER_STOPPED.register { scheduler.shutdown() }

		logDebug("BlanketCobbleBattleRewards: Ready", MOD_ID)
	}

	private fun setupEventHandlers() {
		CobblemonEvents.apply {
			BATTLE_STARTED_PRE.subscribe { event ->
				battles[event.battle.battleId] = BattleState()
			}

			BATTLE_STARTED_POST.subscribe { event ->
				battles[event.battle.battleId]?.let { state ->
					state.actors = event.battle.actors.toList()
					state.battleType = determineBattleType(event.battle.actors)
					logDebug("Battle ${event.battle.battleId} started: ${state.battleType} Battle", MOD_ID)
					updateBattlePokemon(event.battle.battleId)
				}
			}

			POKEMON_SENT_POST.subscribe { event ->
				findBattleByPokemon(event.pokemon)?.let { battleId ->
					updateBattlePokemon(battleId, event.pokemon)
				}
			}

			POKEMON_CAPTURED.subscribe { event ->
				findBattleByPokemon(event.pokemon)?.let { battleId ->
					battles[battleId]?.apply {
						logDebug("========================================", MOD_ID)
						logDebug("Pokémon captured in battle $battleId", MOD_ID)

						// Update opponent Pokémon to be the captured one to ensure type matching works
						if (opponentPokemon == null || opponentPokemon?.uuid != event.pokemon.uuid) {
							opponentPokemon = event.pokemon
							logDebug("Updated opponent Pokémon to captured: ${event.pokemon.species.name}", MOD_ID)

							// Log the captured Pokémon details
							val types = listOfNotNull(
								event.pokemon.species.primaryType?.name,
								event.pokemon.species.secondaryType?.name
							)
							logDebug("Captured Pokémon details:", MOD_ID)
							logDebug("- Species: ${event.pokemon.species.name}", MOD_ID)
							logDebug("- Types: ${types.joinToString(", ")}", MOD_ID)
							logDebug("- Level: ${event.pokemon.level}", MOD_ID)
						}

						isCaptured = true

						// Handle capture rewards immediately
						val player = findPlayerFromBattle(this)
						if (player != null) {
							logDebug("Granting 'Captured' rewards to player: ${player.name.string}", MOD_ID)
							determineAndProcessReward(player, this, battleType, "Captured")
						} else {
							logDebug("Could not find player for battle $battleId", MOD_ID)
						}

						// Mark battle as resolved
						isResolved = true
						logDebug("Finalizing $battleType Battle $battleId (Capture)", MOD_ID)
						logDebug("========================================", MOD_ID)
					}
				}
			}

			BATTLE_VICTORY.subscribe { event ->
				battles[event.battle.battleId]?.let { state ->
					if (state.isCaptured) {
						// Skip victory processing if the battle ended with a capture
						logDebug("Skipping victory processing for battle ${event.battle.battleId} as it was a capture", MOD_ID)
						return@subscribe
					}

					if (state.battleType == BattleType.PVP) {
						val winningPlayerActors =
							event.winners.filter { it.type == ActorType.PLAYER } as List<PlayerBattleActor>
						winningPlayerActors.forEach { actor ->
							state.winningPlayers.add(actor.uuid)
							logDebug("PVP battle winner registered: ${actor.uuid}", MOD_ID)
						}
					}

					finalizeBattleVictory(event.battle.battleId, event.winners)
				}
			}

			BATTLE_FLED.subscribe { event ->
				battles[event.battle.battleId]?.let { state ->
					if (state.isCaptured) {
						// Skip fled processing if the battle ended with a capture
						return@subscribe
					}

					if (state.battleType == BattleType.PVP) {
						val fleeingPlayerUUID = event.player.uuid
						state.actors.forEach { actor ->
							if (actor is PlayerBattleActor && actor.uuid != fleeingPlayerUUID) {
								state.winningPlayers.add(actor.uuid)
								logDebug("Player ${actor.uuid} won by opponent fleeing", MOD_ID)
							}
						}
					}
					battles.remove(event.battle.battleId)
				}
			}

			BATTLE_FAINTED.subscribe { event ->
				battles[event.battle.battleId]?.let { state ->
					logDebug(
						"Pokémon fainted in battle ${event.battle.battleId}: ${event.killed.effectedPokemon.species.name}",
						MOD_ID
					)
				}
			}
		}

		UseItemCallback.EVENT.register { player, world, hand ->
			handleItemUse(player as ServerPlayerEntity, hand)
		}
	}

	private fun determineBattleType(actors: Iterable<BattleActor>): BattleType {
		val playerActorCount = actors.count { it.type == ActorType.PLAYER }
		if (playerActorCount > 1) return BattleType.PVP
		val hasNpcActor = actors.any { it.type == ActorType.NPC }
		val hasNpcOwner = actors.any { actor ->
			actor.type != ActorType.PLAYER && actor.pokemonList.any { battlePokemon ->
				battlePokemon.effectedPokemon.entity?.owner is NPCEntity
			}
		}
		return if (hasNpcActor || hasNpcOwner) BattleType.NPC else BattleType.WILD
	}

	private fun handleItemUse(player: ServerPlayerEntity, hand: Hand): TypedActionResult<ItemStack> {
		if (hand != Hand.MAIN_HAND) return TypedActionResult.pass(player.getStackInHand(hand))
		val stack = player.getStackInHand(hand)
		val trackerValue =
			stack.get(DataComponentTypes.CUSTOM_DATA)?.nbt?.getInt("trackerValue") ?: return TypedActionResult.pass(
				stack
			)

		findRewardByTracker(trackerValue)?.let { reward ->
			if (reward.type.equals("redeemable", ignoreCase = true) && !reward.redeemCommand.isNullOrBlank()) {
				executeCommand(player, reward.redeemCommand, reward.redeemMessage ?: reward.message)
				stack.decrement(1)
				return TypedActionResult.success(stack)
			}
		}
		return TypedActionResult.pass(stack)
	}

	private fun processReward(player: ServerPlayerEntity, reward: Reward): Boolean {
		val rewardId = "reward_${reward.id}"
		val currentTime = System.currentTimeMillis()
		val lastUsed = cooldowns.getOrPut(player.uuid) { mutableMapOf() }[rewardId] ?: 0L

		if (currentTime - lastUsed < reward.cooldown * 1000) {
			val remainingTime = ((reward.cooldown * 1000 - (currentTime - lastUsed)) / 1000).toInt()
			player.sendMessage(
				Text.literal(
					reward.cooldownActiveMessage?.replace("%time%", remainingTime.toString())
						?: "Wait $remainingTime seconds before using this reward again."
				),
				false
			)
			return false
		}

		val success = when (reward.type.lowercase()) {
			"item" -> giveItem(player, reward)
			"redeemable" -> handleRedeemable(player, reward)
			"command" -> executeCommand(player, reward.command, reward.message)
			else -> false
		}

		if (success) {
			cooldowns.getOrPut(player.uuid) { mutableMapOf() }[rewardId] = currentTime
		}
		return success
	}

	private fun giveItem(player: ServerPlayerEntity, reward: Reward): Boolean {
		return reward.item?.let { item ->
			player.inventory.insertStack(createItemStack(item))
			player.sendMessage(Text.literal(reward.message), false)
			true
		} ?: false
	}

	private fun handleRedeemable(player: ServerPlayerEntity, reward: Reward): Boolean {
		return if (reward.item != null) {
			giveItem(player, reward)
		} else if (!reward.command.isNullOrBlank()) {
			executeCommand(player, reward.command, reward.message)
		} else {
			false
		}
	}

	private fun executeCommand(player: ServerPlayerEntity, command: String?, message: String): Boolean {
		return try {
			command?.let { cmd ->
				if (cmd.isBlank()) {
					logDebug("Empty command for player ${player.name.string}", MOD_ID)
					return false
				}
				player.server.commandManager.dispatcher.execute(
					cmd.replace("%player%", player.name.string),
					player.server.commandSource
				)
				player.sendMessage(Text.literal(message), false)
				true
			} ?: false
		} catch (e: Exception) {
			logDebug("Failed to execute command for ${player.name.string}: ${e.message}", MOD_ID)
			false
		}
	}

	// Now we have separate methods for the different ways a battle can end

	// This method handles battle victories (not captures)
	private fun finalizeBattleVictory(battleId: UUID, winners: List<BattleActor>) {
		battles[battleId]?.let { state ->
			// Skip if this is a capture (should be handled by the capture event)
			if (state.isCaptured) {
				logDebug("Skipping victory rewards for $battleId as it was a capture", MOD_ID)
				return
			}

			state.isResolved = true

			// Track who forfeited (only meaningful in non-capture scenarios)
			val losingActors = state.actors.filter { it !in winners }
			state.forfeitingActors.addAll(
				losingActors.filter { actor ->
					actor.pokemonList.any { it.health > 0 }
				}.map { it.uuid }
			)

			// Handle rewards for all players in the battle
			state.actors.filterIsInstance<PlayerBattleActor>().forEach { actor ->
				val player = actor.pokemonList.firstOrNull()?.effectedPokemon?.getOwnerPlayer() as? ServerPlayerEntity
				if (player != null) {
					when {
						actor in winners -> {
							logDebug("Granting 'BattleWon' rewards to player: ${player.name.string}", MOD_ID)
							determineAndProcessReward(player, state, state.battleType, "BattleWon")
						}

						actor.uuid in state.forfeitingActors -> {
							logDebug("Granting 'BattleForfeit' rewards to player: ${player.name.string}", MOD_ID)
							determineAndProcessReward(player, state, state.battleType, "BattleForfeit")
						}

						else -> {
							logDebug("Granting 'BattleLost' rewards to player: ${player.name.string}", MOD_ID)
							determineAndProcessReward(player, state, state.battleType, "BattleLost")
						}
					}
				}
			}

			logDebug("Finalizing ${state.battleType} Battle $battleId (Victory)", MOD_ID)
		}
	}

	private fun findPlayerFromBattle(state: BattleState): ServerPlayerEntity? {
		return (state.actors.find { it.type == ActorType.PLAYER } as? PlayerBattleActor)?.uuid?.let {
			state.playerPokemon?.getOwnerPlayer() as? ServerPlayerEntity
		}
	}

	private fun determineAndProcessReward(
		player: ServerPlayerEntity,
		state: BattleState,
		battleType: BattleType,
		triggerCondition: String
	) {
		val config = BattleRewardsConfigManager.config
		val pokemonLevel = state.opponentPokemon?.level ?: 1
		val pokemonName = state.opponentPokemon?.species?.name ?: "Unknown"

		logDebug(
			"==== Determining rewards for player ${player.name.string} ====", MOD_ID
		)
		logDebug("Pokémon: $pokemonName, Level: $pokemonLevel, Battle type: $battleType, Trigger: $triggerCondition", MOD_ID)

		// Log detailed information about the opponent Pokémon
		state.opponentPokemon?.let { pokemon ->
			val types = listOfNotNull(
				pokemon.species.primaryType?.name,
				pokemon.species.secondaryType?.name
			)
			logDebug("Opponent Pokémon details:", MOD_ID)
			logDebug("- Species: ${pokemon.species.name}", MOD_ID)
			logDebug("- Types: ${types.joinToString(", ")}", MOD_ID)
			logDebug("- Level: ${pokemon.level}", MOD_ID)
		}

		val eligibleRewards = getEligibleRewards(state, battleType, triggerCondition, pokemonLevel)

		if (eligibleRewards.isNotEmpty()) {
			eligibleRewards.forEach { reward ->
				logDebug("Processing reward ID: ${reward.id} for player ${player.name.string}", MOD_ID)
				val success = processReward(player, reward)
				logDebug("Reward processing ${if (success) "succeeded" else "failed"}", MOD_ID)
			}
		} else {
			logDebug("No eligible rewards found for player ${player.name.string}", MOD_ID)
		}
	}

	private fun getEligibleRewards(
		state: BattleState,
		battleType: BattleType,
		triggerCondition: String,
		pokemonLevel: Int
	): List<Reward> {
		val config = BattleRewardsConfigManager.config
		val opponentSpecies = state.opponentPokemon?.species?.name?.lowercase()
		val opponentTypes = listOfNotNull(
			state.opponentPokemon?.species?.primaryType?.name?.lowercase(),
			state.opponentPokemon?.species?.secondaryType?.name?.lowercase()
		)

		// Log the Pokémon information for debugging
		logDebug("Reward eligibility check for: ${state.opponentPokemon?.species?.name ?: "Unknown Pokémon"}", MOD_ID)
		logDebug("Pokémon types: ${opponentTypes.joinToString(", ")}", MOD_ID)
		logDebug("Battle type: $battleType, Trigger: $triggerCondition, Level: $pokemonLevel", MOD_ID)

		// First pass: Find all potentially eligible rewards based on conditions
		val potentialRewards = config.rewards.filter { reward ->
			// Check battle type match
			val battleTypeMatch = reward.battleTypes.contains(battleType.name.lowercase())
			if (!battleTypeMatch) {
				logDebug("Reward ${reward.id} rejected: battle type ${battleType.name.lowercase()} not in ${reward.battleTypes}", MOD_ID)
				return@filter false
			}

			// Check trigger condition match
			val triggerMatch = reward.triggerConditions.contains(triggerCondition)
			if (!triggerMatch) {
				logDebug("Reward ${reward.id} rejected: trigger $triggerCondition not in ${reward.triggerConditions}", MOD_ID)
				return@filter false
			}

			// Check scope match
			val scopeMatch = reward.scope.any { scope ->
				when (scope.lowercase()) {
					"global" -> true
					"pokemon" -> {
						val matches = opponentSpecies != null && reward.pokemonSpecies.contains(opponentSpecies)
						if (matches) {
							logDebug("Reward ${reward.id} species match: $opponentSpecies in ${reward.pokemonSpecies}", MOD_ID)
						}
						matches
					}
					"type" -> {
						// Check if ANY of the Pokémon's types match ANY of the reward's required types
						val typeIntersection = opponentTypes.intersect(reward.pokemonTypes.map { it.lowercase() }.toSet())
						val matches = typeIntersection.isNotEmpty()
						if (matches) {
							logDebug("Reward ${reward.id} type match: found types ${typeIntersection.joinToString(", ")}", MOD_ID)
						} else {
							logDebug("Reward ${reward.id} type mismatch: Pokémon types $opponentTypes not in ${reward.pokemonTypes}", MOD_ID)
						}
						matches
					}
					else -> false
				}
			}
			if (!scopeMatch) {
				logDebug("Reward ${reward.id} rejected: scope match failed", MOD_ID)
				return@filter false
			}

			// Check level range
			val levelMatch = pokemonLevel >= reward.minLevel && pokemonLevel <= reward.maxLevel
			if (!levelMatch) {
				logDebug("Reward ${reward.id} rejected: level $pokemonLevel not in range ${reward.minLevel}-${reward.maxLevel}", MOD_ID)
				return@filter false
			}

			// Apply chance
			val randomValue = Random.nextDouble(100.0)
			val chanceMatch = randomValue < reward.chance
			if (!chanceMatch) {
				logDebug("Reward ${reward.id} rejected: random roll $randomValue >= ${reward.chance}%", MOD_ID)
			} else {
				logDebug("Reward ${reward.id} passed chance check: $randomValue < ${reward.chance}%", MOD_ID)
			}

			battleTypeMatch && triggerMatch && scopeMatch && levelMatch && chanceMatch
		}

		// Sort potential rewards by exclusion count
		// This prioritizes rewards that exclude more others (generally "better" rewards)
		val sortedRewards = potentialRewards.sortedByDescending { it.excludesRewards.size }

		// Second pass: Apply exclusion rules
		val finalRewards = mutableListOf<Reward>()
		val excludedIds = mutableSetOf<String>()

		// Process rewards in order (rewards with more exclusions come first)
		for (reward in sortedRewards) {
			// Skip rewards that are excluded by already selected rewards
			if (reward.id in excludedIds) {
				logDebug("Reward ${reward.id} rejected: excluded by a higher priority reward", MOD_ID)
				continue
			}

			// This reward is eligible, add it to the final list
			finalRewards.add(reward)

			// Add any rewards that this one excludes to the excluded set
			excludedIds.addAll(reward.excludesRewards)
			if (reward.excludesRewards.isNotEmpty()) {
				logDebug("Reward ${reward.id} excludes: ${reward.excludesRewards.joinToString(", ")}", MOD_ID)
			}
		}

		logDebug("Found ${finalRewards.size} eligible rewards: ${finalRewards.map { it.id }.joinToString(", ")}", MOD_ID)
		return finalRewards
	}

	private fun createItemStack(item: Item): ItemStack {
		return ItemStack(
			Registries.ITEM.get(Identifier.of(item.id)) ?: Items.STONE,
			item.count
		).apply {
			if (item.customName.isNotEmpty()) {
				set(DataComponentTypes.CUSTOM_NAME, Text.literal(item.customName).formatted(Formatting.GOLD))
			}
			set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(NbtCompound().apply {
				putString("customName", item.customName)
				putInt("trackerValue", item.trackerValue)
			}))
			if (item.lore.isNotEmpty()) {
				set(
					DataComponentTypes.LORE, LoreComponent(
						item.lore.map { Text.literal(it).formatted(Formatting.GRAY) }
					))
			}
		}
	}

	private fun updateBattlePokemon(battleId: UUID, pokemon: Pokemon? = null) {
		battles[battleId]?.let { state ->
			when {
				pokemon != null -> {
					if (pokemon.entity?.owner is ServerPlayerEntity) {
						state.playerPokemon = pokemon
					} else {
						state.opponentPokemon = pokemon
						val owner = pokemon.entity?.owner
						if (owner is NPCEntity && state.battleType != BattleType.PVP) {
							state.battleType = BattleType.NPC
							logDebug(
								"Updated battle type to NPC based on Pokémon owner: ${owner.customName?.string ?: "Unnamed NPC"}",
								MOD_ID
							)
						}
					}
				}

				else -> state.actors.forEach { actor ->
					when (actor) {
						is PlayerBattleActor -> actor.pokemonList.firstOrNull()?.effectedPokemon?.let {
							state.playerPokemon = it
						}

						else -> actor.pokemonList.firstOrNull()?.effectedPokemon?.let {
							state.opponentPokemon = it
							val owner = it.entity?.owner
							if (owner is NPCEntity && state.battleType != BattleType.PVP) {
								state.battleType = BattleType.NPC
								logDebug(
									"Updated battle type to NPC based on Pokémon owner: ${owner.customName?.string ?: "Unnamed NPC"}",
									MOD_ID
								)
							}
						}
					}
				}
			}
		}
	}

	private fun findBattleByPokemon(pokemon: Pokemon): UUID? =
		battles.entries.firstOrNull { (_, state) ->
			state.actors.any { actor ->
				actor.pokemonList.any { it.effectedPokemon.uuid == pokemon.uuid }
			}
		}?.key

	private fun findRewardByTracker(trackerValue: Int): Reward? {
		val config = BattleRewardsConfigManager.config
		return config.rewards.find { it.item?.trackerValue == trackerValue }
	}

	private fun cleanupBattles() {
		battles.entries.removeIf { (_, state) -> state.isResolved }
	}
}