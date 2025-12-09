package com.cobblebattlerewards

import com.cobblemon.mod.common.Cobblemon
import com.cobblebattlerewards.utils.BattleRewardsCommands
import com.cobblebattlerewards.utils.BattleRewardsConfigManager
import com.cobblebattlerewards.utils.Reward
import com.everlastingutils.utils.logDebug
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor
import com.cobblemon.mod.common.api.battles.model.actor.ActorType
import com.cobblemon.mod.common.api.events.CobblemonEvents
import com.cobblemon.mod.common.api.pokemon.PokemonProperties
import com.cobblemon.mod.common.api.pokemon.PokemonPropertyExtractor
import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor
import com.cobblemon.mod.common.entity.npc.NPCEntity
import com.cobblemon.mod.common.pokemon.Pokemon
import com.cobblemon.mod.common.pokemon.EVs
import com.cobblemon.mod.common.pokemon.IVs
import com.everlastingutils.colors.KyoriHelper
import com.everlastingutils.scheduling.SchedulerManager
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.mojang.serialization.DynamicOps
import com.mojang.serialization.JsonOps
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.minecraft.item.ItemStack
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties
import org.slf4j.Logger
import net.fabricmc.loader.api.FabricLoader
import net.fabricmc.loader.api.Version
import net.fabricmc.loader.api.VersionParsingException


object CobbleBattleRewards : ModInitializer {
    private val logger = LoggerFactory.getLogger("cobblebattlerewards")
    private const val MOD_ID = "cobblebattlerewards"
    private val battles = ConcurrentHashMap<UUID, BattleState>()
    private val cooldowns = ConcurrentHashMap<UUID, MutableMap<String, Long>>()
    private val pokemonUuidToBattleId = ConcurrentHashMap<UUID, UUID>()
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val GSON = Gson()

    private val BATTLE_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(30)

    private var debugLogging = false

    private val PROPERTY_KEYS: List<KProperty1<PokemonProperties, Any?>> = PokemonProperties::class.memberProperties.toList()

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
        var lastActivity: Long = System.currentTimeMillis()
    )
    private const val MOD_NAME = "CobbleBattleRewards"
    override fun onInitialize() {
        val dependenciesMet = checkDependency(
            currentModName = MOD_NAME,
            dependencyModId = "everlastingutils",
            requiredVersionStr = "1.1.1",
            dependencyUrl = "https://modrinth.com/mod/e-utils",
            logger = logger
        )

        // If the check fails, stop loading the rest of the mod
        if (!dependenciesMet) {
            return
        }
        if (debugLogging) logDebug("CobbleBattleRewards: Initializing...", MOD_ID)
        BattleRewardsConfigManager.initializeAndLoad()
        setupEventHandlers()
        BattleRewardsCommands.registerCommands()
        scheduler.scheduleAtFixedRate({ cleanupBattles() }, 1, 1, TimeUnit.SECONDS)
        ServerLifecycleEvents.SERVER_STOPPING.register {
            logDebug("Server is stopping. Shutting down all schedulers...", MOD_ID)
            scheduler.shutdown()
            SchedulerManager.shutdownAll()
        }
        if (debugLogging) logDebug("CobbleBattleRewards: Ready", MOD_ID)

        // Overwrite function for listing conditions in BattleRewardsCommands
        BattleRewardsCommands.onListConditionsCommand = { context ->
            val plr: ServerPlayerEntity? = context.source.player
            var fullPropString: String? = null
            if (plr != null){
                val party: PlayerPartyStore = Cobblemon.storage.getParty(plr)
                val pokemon: Pokemon? = party.get(0)
                if (pokemon != null){
                    val properties: PokemonProperties = createDynamicProperties(pokemon)
                    // Get all property names and values
                    val (_, fullProps) = toPropertyMap(properties)
                    fullPropString = fullProps
                } else {
                    val propNames: Collection<KProperty1<PokemonProperties, Any?>> = PokemonProperties::class.memberProperties
                    val stringPropertyKeys = StringBuilder()
                    propNames.forEach { prop ->
                        val lowerkey = prop.name.lowercase(Locale.getDefault())
                        stringPropertyKeys.append("$lowerkey, ")
                    }
                    stringPropertyKeys.removeSuffix(", ")
                    fullPropString = stringPropertyKeys.toString()
                }
            }
            if (fullPropString != null){
                context.source.sendFeedback({ Text.literal("§6§lList Of Usable Conditions§r\n$fullPropString") }, false)
            }
        }
    }
    private fun checkDependency(
        currentModName: String,
        dependencyModId: String,
        requiredVersionStr: String,
        dependencyUrl: String,
        logger: Logger
    ): Boolean {
        // Attempt to get the dependency's container from the Fabric Loader
        val modContainerOpt = FabricLoader.getInstance().getModContainer(dependencyModId)

        // Check if the dependency is missing entirely
        if (modContainerOpt.isEmpty) {
            logger.error("************************************************************")
            logger.error("* FATAL: $currentModName requires the mod '$dependencyModId', but it is missing.")
            logger.error("* Please install '$dependencyModId' version $requiredVersionStr or newer.")
            logger.error("* You can download it from: $dependencyUrl")
            logger.error("************************************************************")
            return false
        }

        // Get the installed version of the dependency
        val installedVersion = modContainerOpt.get().metadata.version

        try {
            // Parse the required version string into a Version object
            val requiredVersion = Version.parse(requiredVersionStr)

            // Compare the installed version with the required version.
            // A result less than 0 means the installed version is older.
            if (installedVersion.compareTo(requiredVersion) < 0) {
                logger.error("************************************************************")
                logger.error("* FATAL: Your version of '$dependencyModId' ($installedVersion) is too old.")
                logger.error("* $currentModName requires version $requiredVersionStr or newer.")
                logger.error("* Please update '$dependencyModId' to prevent issues.")
                logger.error("* You can download it from: $dependencyUrl")
                logger.error("************************************************************")
                return false
            }

            // If the check passes, log a success message
            logger.info("Found compatible version of '$dependencyModId': $installedVersion")
            return true

        } catch (e: VersionParsingException) {
            // This catch block handles errors in your own code (e.g., a typo in the version string)
            logger.error("Could not parse required version string '$requiredVersionStr' for '$dependencyModId'. This is a bug in $currentModName.", e)
            return false
        }
    }

    private fun sendMinimessage(player: ServerPlayerEntity, message: String) {
        val registryWrapper = player.server.registryManager
        val formatted = KyoriHelper.parseToMinecraft(message, registryWrapper)
        player.sendMessage(formatted, false)
    }

    private fun checkCondition(condition: String, propertyMap: Map<String, String>): Boolean {
        if (debugLogging) logDebug("Checking condition: $condition", MOD_ID)
        val separatorIndex = condition.indexOfAny(charArrayOf(':', '='))

        return if (separatorIndex != -1) {
            val key = condition.substring(0, separatorIndex).trim().lowercase(Locale.getDefault())
            val value = condition.substring(separatorIndex + 1).trim()

            if (propertyMap.containsKey(key)) {
                val pokemonValue = propertyMap[key]
                if (debugLogging) logDebug("  Parsed key=$key, value=$value. Pokemon property '$key' value: $pokemonValue", MOD_ID)
                if (pokemonValue != null) {
                    val result = pokemonValue.contains(value, ignoreCase = true)
                    if (debugLogging) logDebug("  Containment check result: $result ('$pokemonValue' contains '$value'?)", MOD_ID)
                    result
                } else {
                    if (debugLogging) logDebug("  Pokemon property '$key' found but value is null.", MOD_ID)
                    false
                }
            } else {
                if (debugLogging) logDebug("  Separator found but key '$key' is not a known property key. Treating as raw tag.", MOD_ID)
                val speciesValue = propertyMap["species"]
                if (debugLogging) logDebug("  Checking raw tag against species: '$condition' == '$speciesValue'?", MOD_ID)
                if (speciesValue != null) {
                    val result = speciesValue.equals(condition, ignoreCase = true)
                    if (debugLogging) logDebug("  Equality check result: $result", MOD_ID)
                    result
                } else {
                    if (debugLogging) logDebug("  Species property not found for raw tag check.", MOD_ID)
                    false
                }
            }
        } else {
            val speciesValue = propertyMap["species"]
            if (debugLogging) logDebug("  Checking raw tag against species: '$condition' == '$speciesValue'?", MOD_ID)
            if (speciesValue != null) {
                val result = speciesValue.equals(condition, ignoreCase = true)
                if (debugLogging) logDebug("  Equality check result: $result", MOD_ID)
                result
            } else {
                if (debugLogging) logDebug("  Species property not found for raw tag check.", MOD_ID)
                false
            }
        }
    }

    private fun rewardApplies(reward: Reward, pokemon: Pokemon?): Boolean {
        if (pokemon == null) return false
        if (reward.conditions.isEmpty()) return true

        val (propertyMap, fullProps) = toPropertyMap(createDynamicProperties(pokemon))
        if (debugLogging) logDebug("Props snapshot: $fullProps", MOD_ID)

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
                    if (debugLogging) logDebug("Battle ${event.battle.battleId} started: ${state.battleType} Battle", MOD_ID)
                    updateBattlePokemon(event.battle.battleId)
                    // Add Pokémon mappings
                    state.actors.forEach { actor ->
                        actor.pokemonList.forEach { pkmn ->
                            pokemonUuidToBattleId[pkmn.effectedPokemon.uuid] = event.battle.battleId
                        }
                    }
                }
            }

            POKEMON_SENT_POST.subscribe { event ->
                findBattleByPokemon(event.pokemon)?.let { battleId ->
                    // Add mapping for the new Pokémon
                    pokemonUuidToBattleId[event.pokemon.uuid] = battleId
                    updateBattlePokemon(battleId, event.pokemon)
                }
            }

            POKEMON_CAPTURED.subscribe { event ->
                findBattleByPokemon(event.pokemon)?.let { battleId ->
                    battles[battleId]?.apply {
                        if (debugLogging) logDebug("Pokémon captured in battle $battleId", MOD_ID)
                        if (opponentPokemon?.uuid != event.pokemon.uuid) {
                            opponentPokemon = event.pokemon
                            opponentProperties = createDynamicProperties(event.pokemon)
                        }
                        isCaptured = true
                        findPlayerFromBattle(this)?.let { player ->
                            if (debugLogging) logDebug("Granting 'Captured' rewards to player: ${player.name.string}", MOD_ID)
                            determineAndProcessReward(player, this, battleType, "Captured")
                        }
                        isResolved = true
                        // Cleanup mappings and remove battle
                        removePokemonMappings(this)
                        battles.remove(battleId)
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
                            if (debugLogging) logDebug("Granting 'BattleForfeit' to ${player.name.string}", MOD_ID)
                            determineAndProcessReward(player, state, state.battleType, "BattleForfeit")
                        } else if (state.battleType == BattleType.PVP) {
                            if (debugLogging) logDebug("Granting 'BattleWon' to ${player.name.string} (opponent fled)", MOD_ID)
                            determineAndProcessReward(player, state, state.battleType, "BattleWon")
                        }
                    }
                    // Cleanup mappings and remove battle
                    removePokemonMappings(state)
                    battles.remove(event.battle.battleId)
                }
            }

            BATTLE_FAINTED.subscribe { event ->
                battles[event.battle.battleId]?.let { state ->
                    if (debugLogging) logDebug(
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
        if (debugLogging) logDebug("==== Determining rewards for ${player.name.string} ====", MOD_ID)
        if (debugLogging) logDebug("Trigger=$trigger, Type=$battleType, OppLvl=${state.opponentPokemon?.level}", MOD_ID)
        state.opponentProperties?.let { props ->
        }

        val rewards = getEligibleRewards(player, state, battleType, trigger, state.opponentPokemon?.level ?: 1)
        if (rewards.isEmpty()) {
            if (debugLogging) logDebug("No rewards eligible for ${player.name.string}", MOD_ID)
            return
        }
        rewards.forEach { reward ->
            if (debugLogging) logDebug("Processing reward → type=${reward.type}, message='${reward.message}'", MOD_ID)
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
            "item" -> giveItem(player, reward, state, trigger)
            "command" -> executeCommand(player, reward, state, trigger)
            else -> {
                if (debugLogging) logDebug("Invalid reward type: ${reward.type}", MOD_ID)
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
            if (debugLogging) logDebug("No item stack defined for reward", MOD_ID)
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
            if (debugLogging) logDebug("Failed to give item: ${e.message}", MOD_ID)
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
                if (debugLogging) logDebug("Empty command for ${player.name.string}", MOD_ID)
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
            if (debugLogging) logDebug("Cmd failed for ${player.name.string}: ${e.message}", MOD_ID)
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
        result = result.replace("%dimension%", player.world.registryKey.value.toString())
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
                if (debugLogging) logDebug("Granting 'BattleWon' to ${player.name.string}", MOD_ID)
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
                    if (debugLogging) logDebug("Granting 'BattleForfeit' to ${player.name.string}", MOD_ID)
                    determineAndProcessReward(player, state, state.battleType, "BattleForfeit")
                } else {
                    if (debugLogging) logDebug("Granting 'BattleLost' to ${player.name.string}", MOD_ID)
                    determineAndProcessReward(player, state, state.battleType, "BattleLost")
                }
            }

            if (debugLogging) logDebug("Finalized ${state.battleType} Battle $battleId", MOD_ID)
            // Cleanup mappings and remove battle
            removePokemonMappings(state)
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
            else -> return emptyList()
        }

        val playerDimension = player.world.registryKey.value.toString()

        // Filter rewards that match conditions and group by order
        val groupedByOrder = map.entries
            .filter { (id, reward) ->
                (reward.allowedDimensions.isNullOrEmpty() || playerDimension in (reward.allowedDimensions ?: emptyList())) &&
                        reward.battleTypes.contains(battleType.name.lowercase()) &&
                        rewardApplies(reward, state.opponentPokemon) &&
                        lvl in reward.minLevel..reward.maxLevel
            }
            .groupBy { it.value.order }
            .toSortedMap()  // Sorts ascending by order (1 -> 2 -> 3)

        val rewardsToGive = mutableListOf<Reward>()
        val excludedIds = mutableSetOf<String>()

        // Process each order level in sequence
        for ((order, entries) in groupedByOrder) {
            for ((id, reward) in entries) {
                // Skip if this reward was excluded by a higher-order reward
                if (id in excludedIds) {
                    continue
                }

                // Check chance roll
                if (Random.nextDouble(100.0) < reward.chance) {
                    rewardsToGive.add(reward)
                    // Add any rewards this one excludes for lower orders
                    reward.excludedRewards?.let { excludedIds.addAll(it) }
                }
            }
        }

        return rewardsToGive
    }

    private fun toPropertyMap(properties: PokemonProperties): Pair<Map<String, String>, String> {
        val map = mutableMapOf<String, String>()
        val full = StringBuilder()
        PROPERTY_KEYS.forEach { prop ->
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
        val properties = PokemonProperties()
        PokemonPropertyExtractor.ALL.forEach { it(pokemon, properties) }
        properties.type = pokemon.form.types.joinToString(",") { it.name.lowercase() }
        return properties
    }

    private fun deserializeItemStack(jsonString: String, ops: DynamicOps<JsonElement>): ItemStack {
        return try {
            val elem = GSON.fromJson(jsonString, JsonElement::class.java)
            ItemStack.CODEC
                .parse(ops, elem)
                .result()
                .orElse(ItemStack.EMPTY)
        } catch (e: Exception) {
            if (debugLogging) logDebug("Failed to deserialize item stack, returning empty: ${e.message}", MOD_ID)
            ItemStack.EMPTY
        }
    }

    private fun cleanupBattles() {
        val now = System.currentTimeMillis()
        val toRemove = battles.filter { (_, state) -> state.isResolved || (now - state.lastActivity) > BATTLE_TIMEOUT_MS }.keys
        toRemove.forEach { id ->
            battles[id]?.let { removePokemonMappings(it) }
            battles.remove(id)
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
        pokemonUuidToBattleId[pokemon.uuid]

    private fun removePokemonMappings(state: BattleState) {
        state.actors.forEach { actor ->
            actor.pokemonList.forEach { pkmn ->
                pokemonUuidToBattleId.remove(pkmn.effectedPokemon.uuid)
            }
        }
    }
}