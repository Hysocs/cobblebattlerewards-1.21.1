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

	// timeout for inactivity: 30 minutes
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
		// track last activity for timeout
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

	private fun parseCondition(condition: String): PokemonProperties =
		PokemonProperties.parse(
			if (condition.contains(":")) condition else "type:$condition",
			" ",
			"="
		)

	private fun rewardApplies(reward: Reward, properties: PokemonProperties?): Boolean {
		if (properties == null) return false
		if (reward.conditions.isEmpty()) return true

		val (_, fullPropsString) = toPropertyMap(properties)
		val lowerFullProps = fullPropsString.lowercase()

		for (rawCond in reward.conditions) {
			val cond = rawCond.lowercase().trim()
			val pattern = when {
				cond.startsWith(":") || cond.startsWith("=") -> cond.substring(1)
				":" in cond -> cond.substring(cond.indexOf(":") + 1)
				"=" in cond -> cond.substring(cond.indexOf("=") + 1)
				else -> cond
			}

			if (!lowerFullProps.contains(pattern)) return false
		}

		return true
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

		getEligibleReward(state, battleType, trigger, state.opponentPokemon?.level ?: 1)
			?.also { reward ->
				logDebug("Processing reward → type=${reward.type}, message='${reward.message}'", MOD_ID)
			}
			?.let { reward ->
				processReward(player, reward, state, trigger)
			}
			?: logDebug("No rewards eligible for ${player.name.string}", MOD_ID)
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
		result = result.replace("%player%", player.name.string)
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
			state.actors.filterIsInstance<PlayerBattleActor>().forEach { actor ->
				val player = actor.pokemonList.firstOrNull()?.effectedPokemon?.getOwnerPlayer()
						as? ServerPlayerEntity ?: return@forEach
				if (actor in winners) {
					logDebug("Granting 'BattleWon' to ${player.name.string}", MOD_ID)
					determineAndProcessReward(player, state, state.battleType, "BattleWon")
				} else {
					logDebug("Granting 'BattleLost' to ${player.name.string}", MOD_ID)
					determineAndProcessReward(player, state, state.battleType, "BattleLost")
				}
			}
			logDebug("Finalized ${state.battleType} Battle $battleId", MOD_ID)
		}
	}

	private fun findPlayerFromBattle(state: BattleState): ServerPlayerEntity? =
		(state.actors.find { it.type == ActorType.PLAYER } as? PlayerBattleActor)
			?.pokemonList
			?.firstOrNull()
			?.effectedPokemon
			?.getOwnerPlayer() as? ServerPlayerEntity

	private fun getEligibleReward(
		state: BattleState,
		battleType: BattleType,
		trigger: String,
		lvl: Int
	): Reward? {
		val cfg = BattleRewardsConfigManager.config
		val map = when (trigger) {
			"BattleWon"     -> cfg.battleWonRewards
			"BattleLost"    -> cfg.battleLostRewards
			"BattleForfeit" -> cfg.battleForfeitRewards
			"Captured"      -> cfg.captureRewards
			else            -> emptyMap()
		}
		return map.values
			.sortedBy { it.order }
			.firstOrNull { r ->
				r.battleTypes.contains(battleType.name.lowercase()) &&
						rewardApplies(r, state.opponentProperties) &&
						lvl in r.minLevel..r.maxLevel &&
						Random.nextDouble(100.0) < r.chance
			}
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

	// ★ Corrected deserializeItemStack ★
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



	// ★ Cleans up resolved or stale (>30m) battles ★
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
