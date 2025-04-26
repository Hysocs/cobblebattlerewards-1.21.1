package com.cobblebattlerewards

import com.cobblebattlerewards.utils.BattleRewardsCommands
import com.cobblebattlerewards.utils.BattleRewardsConfigManager
import com.cobblebattlerewards.utils.Reward
import com.everlastingutils.utils.logDebug
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor
import com.cobblemon.mod.common.api.battles.model.actor.ActorType
import com.cobblemon.mod.common.api.events.CobblemonEvents
import com.cobblemon.mod.common.api.pokemon.PokemonProperties
import com.cobblemon.mod.common.api.pokemon.PokemonPropertyExtractor
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor
import com.cobblemon.mod.common.entity.npc.NPCEntity
import com.cobblemon.mod.common.pokemon.Pokemon
import com.cobblemon.mod.common.pokemon.EVs
import com.cobblemon.mod.common.pokemon.IVs
import com.everlastingutils.colors.KyoriHelper
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.mojang.serialization.DynamicOps
import com.mojang.serialization.JsonOps
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.minecraft.item.ItemStack
import net.minecraft.server.network.ServerPlayerEntity
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.reflect.full.memberProperties

object CobbleBattleRewards : ModInitializer {
	private val logger = LoggerFactory.getLogger("cobblebattlerewards")
	private const val MOD_ID = "cobblebattlerewards"
	private val battles = ConcurrentHashMap<UUID, BattleState>()
	private val cooldowns = ConcurrentHashMap<UUID, MutableMap<String, Long>>()
	private val scheduler = Executors.newSingleThreadScheduledExecutor()
	private val GSON = Gson()

	// Timeout for inactivity: 30 minutes
	private val BATTLE_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(30)

	enum class BattleType { WILD, NPC, PVP }

	data class BattleState(
		var actors: List<BattleActor> = emptyList(),
		var playerPokemon: Pokemon? = null,
		var opponentPokemon: Pokemon? = null,
		var playerProperties: PokemonProperties? = null,
		var opponentProperties: PokemonProperties? = null,
		var isResolved: Boolean = false,
		var isCaptured: Boolean = false,
		var playerWon: Boolean = false,
		var battleType: BattleType = BattleType.WILD,
		var winningPlayers: MutableList<UUID> = Collections.synchronizedList(mutableListOf()),
		var forfeitingActors: MutableList<UUID> = Collections.synchronizedList(mutableListOf()),
		// Track last activity for timeout
		var lastActivity: Long = System.currentTimeMillis()
	)

	override fun onInitialize() {
		logDebug("CobbleBattleRewards: Initializing...", MOD_ID)
		BattleRewardsConfigManager.initializeAndLoad()
		setupEventHandlers()
		BattleRewardsCommands.registerCommands()
		scheduler.scheduleAtFixedRate({ cleanupBattles() }, 1, 1, TimeUnit.SECONDS)
		ServerLifecycleEvents.SERVER_STOPPED.register { scheduler.shutdown() }
		logDebug("CobbleBattleRewards: Ready", MOD_ID)
	}

	private fun sendMinimessage(player: ServerPlayerEntity, message: String) {
		val registryWrapper = player.server.registryManager
		val formatted = KyoriHelper.parseToMinecraft(message, registryWrapper)
		player.sendMessage(formatted, false)
	}

	private fun rewardApplies(reward: Reward, pokemon: Pokemon?): Boolean {
		if (pokemon == null) return false
		if (reward.conditions.isEmpty()) return true
		val speciesConditions = reward.conditions.map { it.substringAfter("cobblemon:").lowercase() }
		val pokemonSpecies = pokemon.species.name.lowercase()
		return pokemonSpecies in speciesConditions
	}

	private fun setupEventHandlers() {
		CobblemonEvents.apply {
			BATTLE_STARTED_PRE.subscribe { event ->
				battles[event.battle.battleId] = BattleState().apply {
					event.battle.actors.forEach { actor ->
						actor.pokemonList.firstOrNull()?.effectedPokemon?.let { poke ->
							if (actor.type == ActorType.PLAYER) {
								playerPokemon = poke
								playerProperties = createDynamicProperties(poke)
							} else {
								opponentPokemon = poke
								opponentProperties = createDynamicProperties(poke)
							}
						}
					}
				}
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
						logDebug("Pokémon captured in battle $battleId", MOD_ID)
						if (opponentPokemon?.uuid != event.pokemon.uuid) {
							opponentPokemon = event.pokemon
							opponentProperties = createDynamicProperties(event.pokemon)
						}
						isCaptured = true
						findPlayerFromBattle(this)?.let { player ->
							logDebug("Granting 'Captured' rewards to player: ${player.name.string}", MOD_ID)
							determineAndProcessReward(player, this, battleType, "Captured")
						}
						isResolved = true
					}
				}
			}

			BATTLE_VICTORY.subscribe { event ->
				battles[event.battle.battleId]?.let { state ->
					if (state.isCaptured) return@subscribe
					finalizeBattleVictory(event.battle.battleId, event.winners)
				}
			}

			BATTLE_FLED.subscribe { event ->
				battles[event.battle.battleId]?.let { state ->
					if (state.isCaptured) return@subscribe
					state.isResolved = true
					val fleeingPlayer = event.player
					state.actors.filterIsInstance<PlayerBattleActor>().forEach { actor ->
						val player = actor.pokemonList.firstOrNull()?.effectedPokemon?.getOwnerPlayer()
								as? ServerPlayerEntity ?: return@forEach
						if (actor.uuid == fleeingPlayer.uuid) {
							logDebug("Granting 'BattleForfeit' to ${player.name.string}", MOD_ID)
							determineAndProcessReward(player, state, state.battleType, "BattleForfeit")
						} else if (state.battleType == BattleType.PVP) {
							logDebug("Granting 'BattleWon' to ${player.name.string} (opponent fled)", MOD_ID)
							determineAndProcessReward(player, state, state.battleType, "BattleWon")
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
	}

	// 1) PVP first, then WILD if any wild actor present, otherwise NPC or WILD
	private fun determineBattleType(actors: Iterable<BattleActor>): BattleType {
		val playerCount = actors.count { it.type == ActorType.PLAYER }
		if (playerCount > 1) return BattleType.PVP
		if (actors.any { it.type.name == "WILD" }) return BattleType.WILD

		val hasNpc = actors.any { it.type == ActorType.NPC }
		val hasNpcOwner = actors.any { actor ->
			actor.type != ActorType.PLAYER && actor.pokemonList.any { bp ->
				bp.effectedPokemon.entity?.owner is NPCEntity
			}
		}
		return if (hasNpc || hasNpcOwner) BattleType.NPC else BattleType.WILD
	}

	private fun determineAndProcessReward(
		player: ServerPlayerEntity,
		state: BattleState,
		battleType: BattleType,
		trigger: String
	) {
		logDebug("==== Determining rewards for ${player.name.string} ====", MOD_ID)
		logDebug("Trigger=$trigger, Type=$battleType, OppLvl=${state.opponentPokemon?.level}", MOD_ID)
		state.opponentProperties?.let { props ->
			val (_, fullProps) = toPropertyMap(props)
			logDebug("Props snapshot: $fullProps", MOD_ID)
		}

		val rewards = getEligibleRewards(state, battleType, trigger, state.opponentPokemon?.level ?: 1)
		if (rewards.isEmpty()) {
			logDebug("No rewards eligible for ${player.name.string}", MOD_ID)
			return
		}
		rewards.forEach { reward ->
			logDebug("Processing reward → type=${reward.type}, message='${reward.message}'", MOD_ID)
			processReward(player, reward, state, trigger)
		}
	}

	private fun processReward(
		player: ServerPlayerEntity,
		reward: Reward,
		state: BattleState,
		trigger: String
	): Boolean {
		val rewardId = "${reward.type}_${reward.message.hashCode()}"
		val now = System.currentTimeMillis()
		val cd = cooldowns.getOrPut(player.uuid) { mutableMapOf() }
		val last = cd[rewardId] ?: 0L

		if (now - last < reward.cooldown * 1000) {
			val timeMsgTemplate = reward.cooldownMessage.ifEmpty { "<red>Wait %time% seconds...</red>" }
			val cdMsg = applyPlaceholders(
				timeMsgTemplate.replace("%time%", ((reward.cooldown * 1000 - (now - last)) / 1000).toString()),
				player,
				state,
				reward,
				trigger
			)
			sendMinimessage(player, cdMsg)
			return false
		}

		val success = when (reward.type.lowercase()) {
			"item"    -> giveItem(player, reward, state, trigger)
			"command" -> executeCommand(player, reward, state, trigger)
			else      -> {
				logDebug("Invalid reward type: ${reward.type}", MOD_ID)
				false
			}
		}

		if (success) cd[rewardId] = now
		return success
	}

	private fun giveItem(
		player: ServerPlayerEntity,
		reward: Reward,
		state: BattleState,
		trigger: String
	): Boolean {
		if (reward.itemStack.isEmpty()) {
			logDebug("No item stack defined for reward", MOD_ID)
			return false
		}
		return try {
			val stack = deserializeItemStack(reward.itemStack, JsonOps.INSTANCE)
			val inserted = player.inventory.insertStack(stack)
			if (!inserted && BattleRewardsConfigManager.config.inventoryFullBehavior == "drop") {
				player.dropItem(stack, false, false)
			}
			if (reward.message.isNotEmpty()) {
				val msg = applyPlaceholders(reward.message, player, state, reward, trigger)
				sendMinimessage(player, msg)
			}
			inserted || BattleRewardsConfigManager.config.inventoryFullBehavior == "drop"
		} catch (e: Exception) {
			logDebug("Failed to give item: ${e.message}", MOD_ID)
			false
		}
	}

	private fun executeCommand(
		player: ServerPlayerEntity,
		reward: Reward,
		state: BattleState,
		trigger: String
	): Boolean {
		return try {
			if (reward.command.isBlank()) {
				logDebug("Empty command for ${player.name.string}", MOD_ID)
				return false
			}
			val cmd = applyPlaceholders(reward.command, player, state, reward, trigger)
			player.server.commandManager.dispatcher.execute(cmd, player.server.commandSource)
			if (reward.message.isNotEmpty()) {
				val msg = applyPlaceholders(reward.message, player, state, reward, trigger)
				sendMinimessage(player, msg)
			}
			true
		} catch (e: Exception) {
			logDebug("Cmd failed for ${player.name.string}: ${e.message}", MOD_ID)
			false
		}
	}

	private fun applyPlaceholders(
		text: String,
		player: ServerPlayerEntity,
		state: BattleState,
		reward: Reward,
		trigger: String
	): String {
		var result = text
		result = result.replace("%player%", player.name.string) // Corrected from "% Slate"
		result = result.replace("%pokemon%", state.opponentPokemon?.species?.name?.toString() ?: "")
		result = result.replace("%level%", state.opponentPokemon?.level?.toString() ?: "")
		result = result.replace("%battleType%", state.battleType.name.lowercase())
		result = result.replace("%chance%", reward.chance.toString())
		val pos = player.blockPos
		result = result.replace("%coords%", "${pos.x},${pos.y},${pos.z}")
		result = result.replace("%trigger%", trigger)
		return result
	}

	private fun finalizeBattleVictory(battleId: UUID, winners: List<BattleActor>) {
		battles[battleId]?.let { state ->
			if (state.isCaptured) return
			state.isResolved = true

			val playerActors = state.actors.filterIsInstance<PlayerBattleActor>()
			val winnerIds = winners.map { it.uuid }.toSet()

			// Process Winners
			playerActors.filter { it.uuid in winnerIds }.forEach { winnerActor ->
				val player = winnerActor.pokemonList.firstOrNull()?.effectedPokemon
					?.getOwnerPlayer() as? ServerPlayerEntity ?: return@forEach
				val loserPoke = playerActors
					.firstOrNull { it.uuid !in winnerIds }
					?.pokemonList
					?.firstOrNull()
					?.effectedPokemon
				if (loserPoke != null) {
					state.opponentPokemon = loserPoke
					state.opponentProperties = createDynamicProperties(loserPoke)
				}
				logDebug("Granting 'BattleWon' to ${player.name.string}", MOD_ID)
				determineAndProcessReward(player, state, state.battleType, "BattleWon")
			}

			// Process Non-Winners (Losers or Forfeiters)
			playerActors.filter { it.uuid !in winnerIds }.forEach { actor ->
				val player = actor.pokemonList.firstOrNull()?.effectedPokemon
					?.getOwnerPlayer() as? ServerPlayerEntity ?: return@forEach
				val winnerPoke = playerActors
					.firstOrNull { it.uuid in winnerIds }
					?.pokemonList
					?.firstOrNull()
					?.effectedPokemon
				if (winnerPoke != null) {
					state.opponentPokemon = winnerPoke
					state.opponentProperties = createDynamicProperties(winnerPoke)
				}

				// Check if the player has any Pokémon with health > 0
				val hasHealthyPokemon = actor.pokemonList.any { it.effectedPokemon.currentHealth > 0 }
				if (hasHealthyPokemon) {
					logDebug("Granting 'BattleForfeit' to ${player.name.string}", MOD_ID)
					determineAndProcessReward(player, state, state.battleType, "BattleForfeit")
				} else {
					logDebug("Granting 'BattleLost' to ${player.name.string}", MOD_ID)
					determineAndProcessReward(player, state, state.battleType, "BattleLost")
				}
			}

			logDebug("Finalized ${state.battleType} Battle $battleId", MOD_ID)
			battles.remove(battleId)
		}
	}

	private fun findPlayerFromBattle(state: BattleState): ServerPlayerEntity? =
		(state.actors.find { it.type == ActorType.PLAYER } as? PlayerBattleActor)
			?.pokemonList
			?.firstOrNull()
			?.effectedPokemon
			?.getOwnerPlayer() as? ServerPlayerEntity

	private fun getEligibleRewards(
		state: BattleState,
		battleType: BattleType,
		trigger: String,
		lvl: Int
	): List<Reward> {
		val cfg = BattleRewardsConfigManager.config
		val map = when (trigger) {
			"BattleWon"     -> cfg.battleWonRewards
			"BattleLost"    -> cfg.battleLostRewards
			"BattleForfeit" -> cfg.battleForfeitRewards
			"Captured"      -> cfg.captureRewards
			else            -> emptyMap()
		}

		// Filter rewards that match battle type, conditions, and level range
		val matchingRewards = map.values.filter { r ->
			r.battleTypes.contains(battleType.name.lowercase()) &&
					rewardApplies(r, state.opponentPokemon) &&
					lvl in r.minLevel..r.maxLevel
		}

		if (matchingRewards.isEmpty()) return emptyList()

		// Find the minimum order among matching rewards
		val minOrder = matchingRewards.minOf { it.order }

		// Get all rewards with that minimum order
		val minOrderRewards = matchingRewards.filter { it.order == minOrder }

		// For each of these, check the chance and collect those that pass
		return minOrderRewards.filter { Random.nextDouble(100.0) < it.chance }
	}

	private fun toPropertyMap(properties: PokemonProperties): Pair<Map<String, String>, String> {
		val map = mutableMapOf<String, String>()
		val full = StringBuilder()
		PokemonProperties::class.memberProperties.forEach { prop ->
			val key = prop.name.lowercase(Locale.getDefault())
			val raw = prop.get(properties)
			val value = when (key) {
				"ivs" -> if (raw is IVs) raw.joinToString(",") { (k, v) -> "${k}_iv=$v" } else ""
				"evs" -> if (raw is EVs) raw.joinToString(",") { (k, v) -> "${k}_ev=$v" } else ""
				else  -> raw?.toString() ?: ""
			}
			map[key] = value
			full.append("$key=$value ")
		}
		return map to full.toString().trim()
	}

	private fun createDynamicProperties(pokemon: Pokemon): PokemonProperties {
		try {
			val m = pokemon.javaClass.getMethod("getProperties")
			val props = m.invoke(pokemon) as? PokemonProperties
			if (props != null && props.asString().isNotEmpty()) return props.copy()
		} catch (_: Exception) {}
		val properties = PokemonProperties()
		PokemonPropertyExtractor.ALL.forEach { it(pokemon, properties) }
		properties.type = pokemon.form.types.joinToString(",") { it.name }
		return properties
	}

	// Corrected deserializeItemStack
	private fun deserializeItemStack(jsonString: String, ops: DynamicOps<JsonElement>): ItemStack {
		return try {
			val elem = GSON.fromJson(jsonString, JsonElement::class.java)
			// parse() returns DataResult<ItemStack>, so .result() is Optional<ItemStack>
			ItemStack.CODEC
				.parse(ops, elem)
				.result()
				.orElse(ItemStack.EMPTY)
		} catch (e: Exception) {
			logDebug("Failed to deserialize item stack, returning empty: ${e.message}", MOD_ID)
			ItemStack.EMPTY
		}
	}

	// Cleans up resolved or stale (>30m) battles
	private fun cleanupBattles() {
		val now = System.currentTimeMillis()
		battles.entries.removeIf { (_, state) ->
			state.isResolved || (now - state.lastActivity) > BATTLE_TIMEOUT_MS
		}
	}

	private fun updateBattlePokemon(battleId: UUID, pokemon: Pokemon? = null) {
		battles[battleId]?.let { state ->
			if (pokemon != null) {
				if (pokemon.entity?.owner is ServerPlayerEntity) {
					state.playerPokemon = pokemon
					state.playerProperties = createDynamicProperties(pokemon)
				} else {
					state.opponentPokemon = pokemon
					state.opponentProperties = createDynamicProperties(pokemon)
					if (pokemon.entity?.owner is NPCEntity && state.battleType != BattleType.PVP) {
						state.battleType = BattleType.NPC
					}
				}
			} else {
				state.actors.forEach { actor ->
					val poke = actor.pokemonList.firstOrNull()?.effectedPokemon ?: return@forEach
					if (actor is PlayerBattleActor) {
						state.playerPokemon = poke
						state.playerProperties = createDynamicProperties(poke)
					} else {
						state.opponentPokemon = poke
						state.opponentProperties = createDynamicProperties(poke)
						if (poke.entity?.owner is NPCEntity && state.battleType != BattleType.PVP) {
							state.battleType = BattleType.NPC
						}
					}
				}
			}
			state.lastActivity = System.currentTimeMillis()
		}
	}

	private fun findBattleByPokemon(pokemon: Pokemon): UUID? =
		battles.entries.firstOrNull { (_, st) ->
			st.actors.any { actor ->
				actor.pokemonList.any { it.effectedPokemon.uuid == pokemon.uuid }
			}
		}?.key
}