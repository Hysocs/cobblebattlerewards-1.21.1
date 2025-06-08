package com.cobblebattlerewards

import com.cobblebattlerewards.CobbleBattleRewards.BattleState
import com.cobblebattlerewards.utils.BattleRewardsCommands
import com.cobblebattlerewards.utils.BattleRewardsConfigManager
import com.cobblebattlerewards.utils.Reward
import com.cobblebattlerewards.utils.WeightedItem
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

	private val BATTLE_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(30)

	enum class BattleType { ROAM, WILD, NPC, PVP }

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

	private fun checkCondition(condition: String, propertyMap: Map<String, String>): Boolean {
		logDebug("Checking condition: $condition", MOD_ID)
		val separatorIndex = condition.indexOfAny(charArrayOf(':', '='))

		return if (separatorIndex != -1) {
			val key = condition.substring(0, separatorIndex).trim().lowercase(Locale.getDefault())
			val value = condition.substring(separatorIndex + 1).trim()

			if (propertyMap.containsKey(key)) {
				val pokemonValue = propertyMap[key]
				logDebug("  Parsed key=$key, value=$value. Pokemon property '$key' value: $pokemonValue", MOD_ID)
				if (pokemonValue != null) {
					val result = pokemonValue.contains(value, ignoreCase = true)
					logDebug("  Containment check result: $result ('$pokemonValue' contains '$value'?)", MOD_ID)
					result
				} else {
					logDebug("  Pokemon property '$key' found but value is null.", MOD_ID)
					false
				}
			} else {
				logDebug("  Separator found but key '$key' is not a known property key. Treating as raw tag.", MOD_ID)
				val speciesValue = propertyMap["species"]
				logDebug("  Checking raw tag against species: '$condition' == '$speciesValue'?", MOD_ID)
				if (speciesValue != null) {
					val result = speciesValue.equals(condition, ignoreCase = true)
					logDebug("  Equality check result: $result", MOD_ID)
					result
				} else {
					logDebug("  Species property not found for raw tag check.", MOD_ID)
					false
				}
			}
		} else {
			val speciesValue = propertyMap["species"]
			logDebug("  Checking raw tag against species: '$condition' == '$speciesValue'?", MOD_ID)
			if (speciesValue != null) {
				val result = speciesValue.equals(condition, ignoreCase = true)
				logDebug("  Equality check result: $result", MOD_ID)
				result
			} else {
				logDebug("  Species property not found for raw tag check.", MOD_ID)
				false
			}
		}
	}


	private fun rewardApplies(reward: Reward, pokemon: Pokemon?): Boolean {
		if (pokemon == null) return false
		if (reward.conditions.isEmpty()) return true

		val (propertyMap, fullProps) = toPropertyMap(createDynamicProperties(pokemon))
		logDebug("Props snapshot: $fullProps", MOD_ID)

		val conditionsMatch = reward.conditions.any { condition ->
			when (condition) {
				is String -> checkCondition(condition, propertyMap)
				is List<*> -> {
					condition.all { subCond ->
						if (subCond is String) {
							checkCondition(subCond, propertyMap)
						} else false
					}
				}
				else -> false
			}
		}

		return if (reward.conditionsBlacklist) {
			!conditionsMatch
		} else {
			conditionsMatch
		}
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
				val battleId = findBattleByPokemon(event.pokemon);

				if (battleId != null && battles[battleId] != null) {
					val battle = battles[battleId]!!

					logDebug("Pokémon captured in battle $battleId", MOD_ID)
					if (battle.opponentPokemon?.uuid != event.pokemon.uuid) {
						battle.opponentPokemon = event.pokemon
						battle.opponentProperties = createDynamicProperties(event.pokemon)
					}
					battle.isCaptured = true
					findPlayerFromBattle(battle)?.let { player ->
						logDebug("Granting 'Captured' rewards to player: ${player.name.string}", MOD_ID)
						determineAndProcessReward(player, battle, battle.battleType, "Captured")
					}
					battle.isResolved = true
				} else {
					logDebug("Granting 'Captured' rewards to player: ${event.player.name.string}", MOD_ID)
					val state = BattleState()
					state.opponentPokemon = event.pokemon
					state.opponentProperties = createDynamicProperties(event.pokemon)
					determineAndProcessReward(event.player, state, BattleType.ROAM, "Captured")
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
		}

		val rewards = getEligibleRewards(player, state, battleType, trigger, state.opponentPokemon?.level ?: 1)
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
				ItemStack.EMPTY,
				trigger
			)
			sendMinimessage(player, cdMsg)
			return false
		}

		val success = when (reward.type.lowercase()) {
			"item" -> giveItem(player, reward, state, trigger)
			"command" -> executeCommand(player, reward, state, trigger)
			else -> {
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
			val stack = deserializeItemStack(getRandomItem(reward.itemStack), JsonOps.INSTANCE)
			val copy = stack.copy()
			val inserted = player.inventory.insertStack(stack)
			if (!inserted && BattleRewardsConfigManager.config.inventoryFullBehavior == "drop") {
				player.dropItem(stack, false, false)
			}
			if (reward.message.isNotEmpty()) {
				val msg = applyPlaceholders(reward.message, player, state, reward, copy, trigger)
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
			val cmd = applyPlaceholders(reward.command, player, state, reward, ItemStack.EMPTY, trigger)
			player.server.commandManager.dispatcher.execute(cmd, player.server.commandSource)
			if (reward.message.isNotEmpty()) {
				val msg = applyPlaceholders(reward.message, player, state, reward, ItemStack.EMPTY, trigger)
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
		stack: ItemStack,
		trigger: String
	): String {
		var result = text
		result = result.replace("%player%", player.name.string)
		result = result.replace("%pokemon%", state.opponentPokemon?.species?.name?.toString() ?: "")
		result = result.replace("%level%", state.opponentPokemon?.level?.toString() ?: "")
		result = result.replace("%battleType%", state.battleType.name.lowercase())
		result = result.replace("%chance%", reward.chance.toString())
		val pos = player.blockPos
		result = result.replace("%coords%", "${pos.x},${pos.y},${pos.z}")
		result = result.replace("%trigger%", trigger)
		result = result.replace("%dimension%", player.world.registryKey.value.toString())
		result = result.replace("%rewardItemCount%", stack.count.toString())
		return result
	}

	private fun finalizeBattleVictory(battleId: UUID, winners: List<BattleActor>) {
		battles[battleId]?.let { state ->
			if (state.isCaptured) return
			state.isResolved = true

			val playerActors = state.actors.filterIsInstance<PlayerBattleActor>()
			val winnerIds = winners.map { it.uuid }.toSet()

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
		player: ServerPlayerEntity,
		state: BattleState,
		battleType: BattleType,
		trigger: String,
		lvl: Int
	): List<Reward> {
		val cfg = BattleRewardsConfigManager.config
		val map = when (trigger) {
			"BattleWon" -> cfg.battleWonRewards
			"BattleLost" -> cfg.battleLostRewards
			"BattleForfeit" -> cfg.battleForfeitRewards
			"Captured" -> cfg.captureRewards
			else -> emptyMap()
		}

		val playerDimension = player.world.registryKey.value.toString()

		val matchingRewards = map.values.filter { r ->
			(r.allowedDimensions.isNullOrEmpty() || playerDimension in (r.allowedDimensions ?: emptyList())) &&
					r.battleTypes.contains(battleType.name.lowercase()) &&
					rewardApplies(r, state.opponentPokemon) &&
					lvl in r.minLevel..r.maxLevel
		}

		if (matchingRewards.isEmpty()) return emptyList()

		val minOrder = matchingRewards.minOfOrNull { it.order } ?: return emptyList()

		val minOrderRewards = matchingRewards.filter { it.order == minOrder }

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
				else -> raw?.toString() ?: ""
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
		} catch (_: Exception) {
		}
		val properties = PokemonProperties()
		PokemonPropertyExtractor.ALL.forEach { it(pokemon, properties) }
		properties.type = pokemon.form.types.joinToString(",") { it.name }
		return properties
	}

	private fun getRandomItem(items: List<WeightedItem>): String {
		val totalWeight = items.sumOf { it.weight }
		if (totalWeight <= 0) return ""
		val random = (1..totalWeight).random()
		var weight = 0

		for (item in items) {
			weight += item.weight
			if (random <= weight) {
				return item.value
			}
		}

		return ""
	}

	private fun deserializeItemStack(jsonString: String, ops: DynamicOps<JsonElement>): ItemStack {
		return try {
			val elem = GSON.fromJson(jsonString, JsonElement::class.java)
			ItemStack.CODEC
				.parse(ops, elem)
				.result()
				.orElse(ItemStack.EMPTY)
		} catch (e: Exception) {
			logDebug("Failed to deserialize item stack, returning empty: ${e.message}", MOD_ID)
			ItemStack.EMPTY
		}
	}

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