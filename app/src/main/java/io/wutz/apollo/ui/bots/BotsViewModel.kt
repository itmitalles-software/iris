package io.wutz.apollo.ui.bots

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.wutz.apollo.R
import io.wutz.apollo.data.local.AuthManager
import io.wutz.apollo.data.model.BotAvatarMeta
import io.wutz.apollo.data.model.BotRosterMeta
import io.wutz.apollo.data.model.CreateProfileRequest
import io.wutz.apollo.data.model.GroupChatRoomMeta
import io.wutz.apollo.data.model.GroupChatSyncSnapshot
import io.wutz.apollo.data.model.ProfileInfo
import io.wutz.apollo.data.model.ProfilesResponse
import io.wutz.apollo.data.remote.ApiClient
import io.wutz.apollo.data.remote.NetworkResult
import io.wutz.apollo.data.remote.OkHttpProvider
import io.wutz.apollo.data.remote.safeApiCall
import io.wutz.apollo.data.session.ProfileSwitchCoordinator
import io.wutz.apollo.data.ws.HermesWsClient
import io.wutz.apollo.data.ws.contract.ProfilesConfigureParams
import io.wutz.apollo.data.ws.contract.ProfilesListParams
import io.wutz.apollo.data.ws.contract.RpcMethods
import io.wutz.apollo.data.ws.toJsonElement
import io.wutz.apollo.ui.common.ToastHost
import io.wutz.apollo.ui.common.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement

enum class BotsTab {
    BOTS,
    GROUPS,
}

data class GroupInfo(
    val name: String,
    val members: List<ProfileInfo>,
    val lastActivity: Long = 0L,
)

data class BotsUiState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val profiles: List<ProfileInfo> = emptyList(),
    val activeProfileName: String? = null,
    val searchQuery: String = "",
    val showHidden: Boolean = false,
    val hiddenProfiles: Set<String> = emptySet(),
    val selectedTab: BotsTab = BotsTab.BOTS,
    val errorMessage: UiText? = null,
    val toastMessage: UiText? = null,
) {
    val hasHiddenBots: Boolean
        get() = profiles.any { it.isHidden || it.name in hiddenProfiles }

    val allGroups: List<GroupInfo>
        get() {
            val defaultProfile =
                profiles.find { it.is_default == true || it.name == "default" }
                    ?: profiles.firstOrNull()
            val syncSnapshot = defaultProfile?.groupChatSyncSnapshot()
            val deletedKeys =
                syncSnapshot
                    ?.deleted
                    ?.keys
                    .orEmpty()
                    .map { it.lowercase() }

            val groupNameMap = linkedMapOf<String, String>()

            // 1. Collect from bot metadata (both `groups` array and `group` scalar)
            for (profile in profiles) {
                for (g in profile.botMeta()?.allGroups.orEmpty()) {
                    val trimmed = g.trim()
                    if (trimmed.isNotBlank()) {
                        val lower = trimmed.lowercase()
                        if (!groupNameMap.containsKey(lower)) {
                            groupNameMap[lower] = trimmed
                        }
                    }
                }
            }

            // 2. Collect from cross-device snapshot rooms (Desktop parity)
            val snapshotRooms = syncSnapshot?.rooms.orEmpty()
            for ((key, room) in snapshotRooms) {
                if (room.tombstone == true) continue
                val roomKeyLower = key.lowercase()
                if (deletedKeys.contains(roomKeyLower) ||
                    deletedKeys.contains("name:${room.name?.lowercase()}") ||
                    deletedKeys.contains("id:${room.roomId?.lowercase()}")
                ) {
                    continue
                }
                val roomName =
                    room.name?.trim()?.takeIf { it.isNotBlank() }
                        ?: key.removePrefix("name:").removePrefix("id:").trim()
                if (roomName.isNotBlank()) {
                    val lower = roomName.lowercase()
                    if (!groupNameMap.containsKey(lower)) {
                        groupNameMap[lower] = roomName
                    }
                }
            }

            return groupNameMap.values
                .map { gName ->
                    val lower = gName.lowercase()
                    val matchingRoom =
                        snapshotRooms[gName]
                            ?: snapshotRooms["name:$gName"]
                            ?: snapshotRooms["id:$gName"]
                            ?: snapshotRooms.values.find { it.name.equals(gName, ignoreCase = true) }

                    val roomMemberNames = matchingRoom?.memberNames.orEmpty().map { it.lowercase() }

                    val matchedProfiles =
                        profiles
                            .filter { profile ->
                                val botGroups = profile.botMeta()?.allGroups.orEmpty()
                                botGroups.any { it.equals(gName, ignoreCase = true) } ||
                                    roomMemberNames.contains(profile.name.lowercase()) ||
                                    roomMemberNames.contains(profile.effectiveTitle.lowercase())
                            }.toMutableList()

                    // If room listed members (e.g. remote bots or bots not in local profiles), ensure they are seated
                    if (matchingRoom != null && roomMemberNames.isNotEmpty()) {
                        for (mName in matchingRoom.memberNames) {
                            val exists =
                                matchedProfiles.any {
                                    it.name.equals(mName, ignoreCase = true) ||
                                        it.effectiveTitle.equals(mName, ignoreCase = true)
                                }
                            if (!exists) {
                                val local = profiles.find { it.name.equals(mName, ignoreCase = true) }
                                matchedProfiles.add(local ?: ProfileInfo(name = mName))
                            }
                        }
                    }

                    var groupMembers: List<ProfileInfo> = matchedProfiles

                    // Fallback: If group name was composed from bot names (e.g. "default, scoutbot")
                    if (groupMembers.isEmpty() && gName.contains(",")) {
                        val targetNames = gName.split(",").map { it.trim().lowercase() }
                        groupMembers = profiles.filter { targetNames.contains(it.name.lowercase()) }
                    }

                    val lastAt =
                        matchingRoom?.log?.lastOrNull()?.at
                            ?: matchingRoom?.updatedAt
                            ?: 0L

                    GroupInfo(
                        name = gName,
                        members = groupMembers,
                        lastActivity = lastAt,
                    )
                }.sortedWith(
                    compareByDescending<GroupInfo> { it.lastActivity }
                        .thenBy { it.name },
                )
        }

    val displayGroups: List<GroupInfo>
        get() {
            val query = searchQuery.trim().lowercase()
            return allGroups.filter { group ->
                if (query.isBlank()) return@filter true
                group.name.lowercase().contains(query) ||
                    group.members.any {
                        it.name.lowercase().contains(query) ||
                            it.effectiveTitle.lowercase().contains(query)
                    }
            }
        }

    val activeNowBots: List<ProfileInfo>
        get() {
            val nowSeconds = System.currentTimeMillis() / 1000.0
            return profiles.filter { profile ->
                profile.worker_session != null ||
                    profile.name == activeProfileName ||
                    ((profile.canonical_session?.last_active ?: 0.0) > nowSeconds - 90) ||
                    ((profile.last_session?.last_active ?: 0.0) > nowSeconds - 90)
            }
        }

    val displayProfiles: List<ProfileInfo>
        get() {
            val query = searchQuery.trim().lowercase()
            return profiles
                .filter { profile ->
                    val isHidden = profile.isHidden || profile.name in hiddenProfiles
                    if (!showHidden && isHidden) return@filter false
                    if (query.isBlank()) return@filter true
                    profile.name.lowercase().contains(query) ||
                        profile.effectiveTitle.lowercase().contains(query) ||
                        profile.effectiveDescription.lowercase().contains(query)
                }.sortedWith(
                    compareByDescending<ProfileInfo> { it.name == activeProfileName }
                        .thenByDescending {
                            it.canonical_session?.last_active
                                ?: it.last_session?.last_active
                                ?: 0.0
                        }.thenBy { it.name },
                )
        }
}

class BotsViewModel(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    autoLoad: Boolean = true,
) : ViewModel(),
    ToastHost {
    private val _uiState = MutableStateFlow(BotsUiState())
    val uiState: StateFlow<BotsUiState> = _uiState.asStateFlow()

    init {
        _uiState.update { it.copy(hiddenProfiles = AuthManager.getHiddenProfiles().toSet()) }
        if (autoLoad) {
            loadBots()
        }
    }

    fun loadBots(isRefresh: Boolean = false) {
        _uiState.update {
            if (isRefresh) {
                it.copy(isRefreshing = true, errorMessage = null)
            } else {
                it.copy(isLoading = true, errorMessage = null)
            }
        }
        viewModelScope.launch(ioDispatcher) {
            coroutineScope {
                val activeDeferred = async(ioDispatcher) { safeApiCall { ApiClient.hermesApi.getActiveProfile() } }

                // First try fetching profiles via WebSocket RPC (profiles.list) which includes ui_meta (groups, custom avatars).
                var profilesWithMeta: List<ProfileInfo>? = null
                try {
                    val jsonElement = HermesWsClient.call(RpcMethods.PROFILES_LIST, ProfilesListParams)
                    if (jsonElement !is JsonNull) {
                        val resp = OkHttpProvider.json.decodeFromJsonElement<ProfilesResponse>(jsonElement)
                        if (!resp.profiles.isNullOrEmpty()) {
                            profilesWithMeta = resp.profiles
                        }
                    }
                } catch (_: Exception) {
                    // Fallback to REST API below
                }

                val profilesResult =
                    if (profilesWithMeta != null) {
                        null
                    } else {
                        safeApiCall { ApiClient.hermesApi.getProfiles() }
                    }
                val activeResult = activeDeferred.await()

                if (profilesWithMeta != null) {
                    val activeName = (activeResult as? NetworkResult.Success)?.data?.active
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            profiles = profilesWithMeta,
                            activeProfileName = activeName ?: it.activeProfileName,
                            hiddenProfiles = AuthManager.getHiddenProfiles().toSet(),
                            errorMessage = null,
                        )
                    }
                } else if (profilesResult is NetworkResult.Success) {
                    val activeName = (activeResult as? NetworkResult.Success)?.data?.active
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            profiles = profilesResult.data.profiles.orEmpty(),
                            activeProfileName = activeName ?: it.activeProfileName,
                            hiddenProfiles = AuthManager.getHiddenProfiles().toSet(),
                            errorMessage = null,
                        )
                    }
                } else {
                    val err =
                        (profilesResult as? NetworkResult.Failure)?.error?.message?.let(UiText::raw)
                            ?: UiText.res(R.string.bots_msg_load_failed)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            errorMessage = err,
                        )
                    }
                }
            }
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun setSelectedTab(tab: BotsTab) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    fun toggleShowHidden() {
        _uiState.update { it.copy(showHidden = !it.showHidden) }
    }

    /** Switches the server-side profile; on failure shows the reason and returns false (caller must not navigate). */
    suspend fun selectBot(bot: ProfileInfo): Boolean {
        val result = ProfileSwitchCoordinator.switchProfile(bot.name)
        if (result is NetworkResult.Failure) {
            _uiState.update {
                it.copy(toastMessage = UiText.res(R.string.profiles_msg_switch_failed, result.error.message))
            }
        }
        return result is NetworkResult.Success
    }

    fun showToast(message: UiText) {
        _uiState.update { it.copy(toastMessage = message) }
    }

    override fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    fun createBot(
        name: String,
        title: String,
        description: String,
        shape: String,
        color: String,
        onSuccess: () -> Unit,
    ) {
        viewModelScope.launch(ioDispatcher) {
            val req =
                CreateProfileRequest(
                    name = name,
                    description = description.ifBlank { null },
                    clone_from_default = false,
                )
            val result = safeApiCall { ApiClient.hermesApi.createProfile(req) }
            if (result is NetworkResult.Success) {
                // Configure UI metadata (avatar, custom title) and bot SOUL via RPC
                val botMeta =
                    BotRosterMeta(
                        title = title.ifBlank { null },
                        description = description.ifBlank { null },
                        avatar =
                            BotAvatarMeta(
                                shape = shape,
                                color = color,
                            ),
                    )
                val botSoul = composeBotSoul(name, title, description)
                // Fire-and-forget as before: the metadata write never gated the reload or onSuccess,
                // but a failed write is reported instead of silently dropped.
                launch {
                    wsClientConfigureBot(name, botMeta, soul = botSoul)?.let(::showMetaSaveFailed)
                }
                loadBots()
                onSuccess()
            } else {
                // The error state is only rendered for an empty list, so report this as a toast.
                val err =
                    (result as? NetworkResult.Failure)
                        ?.let { UiText.res(R.string.bots_msg_create_failed_reason, it.error.message) }
                        ?: UiText.res(R.string.bots_msg_create_failed)
                _uiState.update { it.copy(toastMessage = err) }
            }
        }
    }

    fun updateBotMeta(
        name: String,
        title: String,
        description: String,
        shape: String,
        color: String,
        onSuccess: () -> Unit,
    ) {
        viewModelScope.launch(ioDispatcher) {
            val bot = _uiState.value.profiles.find { it.name == name }
            val existingMeta = bot?.botMeta() ?: BotRosterMeta()
            val updatedMeta =
                existingMeta.copy(
                    title = title.ifBlank { null },
                    description = description.ifBlank { null },
                    avatar =
                        BotAvatarMeta(
                            shape = shape,
                            color = color,
                        ),
                )
            val failure = wsClientConfigureBot(name, updatedMeta)
            loadBots()
            if (failure != null) {
                // Keep the editor open so the user can retry.
                showMetaSaveFailed(failure)
            } else {
                onSuccess()
            }
        }
    }

    fun deleteBot(
        name: String,
        onSuccess: () -> Unit,
    ) {
        viewModelScope.launch(ioDispatcher) {
            val result = safeApiCall { ApiClient.hermesApi.deleteProfile(name) }
            if (result is NetworkResult.Success) {
                loadBots()
                onSuccess()
            } else {
                val err =
                    (result as? NetworkResult.Failure)
                        ?.let { UiText.res(R.string.bots_msg_delete_failed_reason, it.error.message) }
                        ?: UiText.res(R.string.bots_msg_delete_failed)
                _uiState.update { it.copy(toastMessage = err) }
            }
        }
    }

    fun createGroupChat(
        groupName: String,
        botNames: List<String>,
        onSuccess: () -> Unit,
    ) {
        viewModelScope.launch(ioDispatcher) {
            val currentProfiles = _uiState.value.profiles
            var failure: UiText? = null
            // 1. Update bot metadata on each member bot
            for (name in botNames) {
                val bot = currentProfiles.find { it.name == name } ?: continue
                val existingGroups = bot.botMeta()?.allGroups.orEmpty()
                if (!existingGroups.any { it.equals(groupName, ignoreCase = true) }) {
                    val updatedMeta =
                        (bot.botMeta() ?: BotRosterMeta()).copy(
                            groups = existingGroups + groupName,
                            group = existingGroups.firstOrNull() ?: groupName,
                        )
                    failure = wsClientConfigureBot(name, updatedMeta) ?: failure
                }
            }

            // 2. Also register room in hermes-bots-groups sync snapshot on default profile
            run {
                val defaultProfile =
                    currentProfiles.find { it.is_default == true || it.name == "default" }
                        ?: currentProfiles.firstOrNull()
                if (defaultProfile != null) {
                    val existingSnapshot =
                        defaultProfile.groupChatSyncSnapshot()
                            ?: GroupChatSyncSnapshot(version = 3, rooms = emptyMap())
                    val roomKey = "name:$groupName"
                    val newRoom =
                        GroupChatRoomMeta(
                            name = groupName,
                            members = botNames.map { JsonPrimitive(it) },
                            updatedAt = System.currentTimeMillis(),
                            createdAt = System.currentTimeMillis(),
                        )
                    val updatedRooms = existingSnapshot.rooms.orEmpty() + (roomKey to newRoom)
                    val updatedDeleted =
                        existingSnapshot.deleted.orEmpty().filterKeys {
                            !it.equals(roomKey, ignoreCase = true) && !it.equals(groupName, ignoreCase = true)
                        }
                    val newSnapshot =
                        existingSnapshot.copy(
                            version = 3,
                            updatedAt = System.currentTimeMillis(),
                            rooms = updatedRooms,
                            deleted = updatedDeleted,
                        )
                    failure =
                        configureUiMeta(
                            defaultProfile.name,
                            mapOf("hermes-bots-groups" to newSnapshot.toMap()).toJsonObject(),
                        ) ?: failure
                }
            }

            loadBots()
            if (failure != null) showGroupSaveFailed(failure) else onSuccess()
        }
    }

    fun disbandGroupChat(
        groupName: String,
        onSuccess: () -> Unit,
    ) {
        viewModelScope.launch(ioDispatcher) {
            val currentProfiles = _uiState.value.profiles
            var failure: UiText? = null
            // 1. Remove from all member bots' metadata
            for (bot in currentProfiles) {
                val existingGroups = bot.botMeta()?.allGroups.orEmpty()
                if (existingGroups.any { it.equals(groupName, ignoreCase = true) }) {
                    val filtered = existingGroups.filterNot { it.equals(groupName, ignoreCase = true) }
                    val updatedMeta =
                        (bot.botMeta() ?: BotRosterMeta()).copy(
                            groups = filtered,
                            group = filtered.firstOrNull(),
                        )
                    failure = wsClientConfigureBot(bot.name, updatedMeta) ?: failure
                }
            }

            // 2. Remove room from hermes-bots-groups snapshot and register tombstone in deleted map
            run {
                val defaultProfile =
                    currentProfiles.find { it.is_default == true || it.name == "default" }
                        ?: currentProfiles.firstOrNull()
                if (defaultProfile != null) {
                    val existingSnapshot = defaultProfile.groupChatSyncSnapshot()
                    if (existingSnapshot != null) {
                        val updatedRooms =
                            existingSnapshot.rooms.orEmpty().filterKeys { key ->
                                val room = existingSnapshot.rooms?.get(key)
                                !key.equals(groupName, ignoreCase = true) &&
                                    !key.equals("name:$groupName", ignoreCase = true) &&
                                    !key.equals("id:$groupName", ignoreCase = true) &&
                                    !(room?.name.equals(groupName, ignoreCase = true))
                            }
                        val deletedMap =
                            existingSnapshot.deleted.orEmpty() + ("name:$groupName" to System.currentTimeMillis())
                        val newSnapshot =
                            existingSnapshot.copy(
                                version = 3,
                                updatedAt = System.currentTimeMillis(),
                                rooms = updatedRooms,
                                deleted = deletedMap,
                            )
                        failure =
                            configureUiMeta(
                                defaultProfile.name,
                                mapOf("hermes-bots-groups" to newSnapshot.toMap()).toJsonObject(),
                            ) ?: failure
                    }
                }
            }

            loadBots()
            if (failure != null) showGroupSaveFailed(failure) else onSuccess()
        }
    }

    private fun composeBotSoul(
        name: String,
        title: String,
        description: String,
    ): String {
        val displayName = title.ifBlank { name }
        val lines = mutableListOf<String>()
        lines.add("# $displayName")
        lines.add("")
        if (title.isNotBlank()) lines.add("**Role:** $title")
        if (description.isNotBlank()) lines.add("**Mission:** $description")
        lines.add("")
        lines.add("You are $displayName, a persistent named agent (profile `$name`) on this machine.")
        lines.add("You keep your own memory, skills, and conversation history across sessions.")
        return lines.joinToString("\n")
    }

    /**
     * Writes [meta] into the bot's `hermes-bots` ui_meta. The gateway replaces each ui_meta key as a whole
     * (`_configure_ui_meta` in hermes-agent), so the edited fields are merged into the stored object instead of
     * rebuilding it: fields this client does not edit (hidden, created, avatar.image_url, unknown keys) survive.
     * Returns the failure reason, or null when the write was applied.
     */
    private suspend fun wsClientConfigureBot(
        name: String,
        meta: BotRosterMeta,
        soul: String? = null,
    ): UiText? {
        val stored =
            _uiState.value.profiles
                .find { it.name == name }
                ?.ui_meta
                ?.get(BOT_META_KEY)
        return configureUiMeta(
            name = name,
            uiMeta = JsonObject(mapOf(BOT_META_KEY to mergeBotRosterMeta(stored, meta))),
            soul = soul,
        )
    }

    /** Sends one `profiles.configure` ui_meta write; returns the failure reason, or null when it was applied. */
    private suspend fun configureUiMeta(
        name: String,
        uiMeta: JsonObject,
        soul: String? = null,
    ): UiText? =
        try {
            val result =
                HermesWsClient.call(
                    RpcMethods.PROFILES_CONFIGURE,
                    ProfilesConfigureParams(name = name, uiMeta = uiMeta, soul = soul),
                )
            val applied = ((result as? JsonObject)?.get("applied") as? JsonObject)?.get("ui_meta") as? JsonPrimitive
            if (applied?.booleanOrNull == false) UiText.res(R.string.bots_msg_meta_not_applied) else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UiText.raw(e.message ?: e.javaClass.simpleName)
        }

    private fun showMetaSaveFailed(reason: UiText) {
        _uiState.update { it.copy(toastMessage = UiText.res(R.string.bots_msg_meta_save_failed, reason)) }
    }

    private fun showGroupSaveFailed(reason: UiText) {
        _uiState.update { it.copy(toastMessage = UiText.res(R.string.bots_msg_group_save_failed, reason)) }
    }

    private fun Map<String, Any?>.toJsonObject(): JsonObject = toJsonElement() as JsonObject

    private companion object {
        const val BOT_META_KEY = "hermes-bots"
    }
}

/**
 * Overlays the fields Apollo edits (title, description, avatar shape/color/icon, groups and the legacy `group`
 * scalar) onto the stored `hermes-bots` object. Everything else is kept, and avatar sub-fields are only
 * overwritten when [meta] sets them, so an uploaded avatar image or a Desktop-only field is not lost.
 */
internal fun mergeBotRosterMeta(
    stored: JsonElement?,
    meta: BotRosterMeta,
): JsonObject {
    val merged = (stored as? JsonObject)?.toMutableMap() ?: mutableMapOf()

    fun put(
        key: String,
        value: JsonElement?,
    ) {
        if (value == null) merged.remove(key) else merged[key] = value
    }
    put("title", meta.title?.let(::JsonPrimitive))
    put("description", meta.description?.let(::JsonPrimitive))
    put("groups", meta.groups?.takeIf { it.isNotEmpty() }?.let { groups -> JsonArray(groups.map(::JsonPrimitive)) })
    put("group", meta.group?.let(::JsonPrimitive))
    meta.avatar?.let { avatar ->
        val mergedAvatar = (merged["avatar"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        avatar.shape?.let { mergedAvatar["shape"] = JsonPrimitive(it) }
        avatar.color?.let { mergedAvatar["color"] = JsonPrimitive(it) }
        avatar.icon?.let { mergedAvatar["icon"] = JsonPrimitive(it) }
        avatar.image_url?.let { mergedAvatar["image_url"] = JsonPrimitive(it) }
        merged["avatar"] = JsonObject(mergedAvatar)
    }
    return JsonObject(merged)
}
