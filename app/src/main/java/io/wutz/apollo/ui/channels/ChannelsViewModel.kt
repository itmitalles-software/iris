package io.wutz.apollo.ui.channels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.wutz.apollo.R
import io.wutz.apollo.data.local.AuthManager
import io.wutz.apollo.data.model.MessagingPlatform
import io.wutz.apollo.data.model.MessagingPlatformUpdate
import io.wutz.apollo.data.model.MessagingPlatformUpdateResponse
import io.wutz.apollo.data.model.TelegramOnboardingApplyRequest
import io.wutz.apollo.data.model.TelegramOnboardingStartRequest
import io.wutz.apollo.data.model.TelegramOnboardingStartResponse
import io.wutz.apollo.data.remote.ApiClient
import io.wutz.apollo.data.remote.safeApiCall
import io.wutz.apollo.data.ws.ChangeEvents
import io.wutz.apollo.ui.common.ToastHost
import io.wutz.apollo.ui.common.UiText
import io.wutz.apollo.ui.common.refreshOnChange
import io.wutz.apollo.ui.common.safeLaunchLoad
import io.wutz.apollo.ui.common.toUiText
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.Response

enum class OnboardingPhase {
    IDLE,
    STARTING,
    WAITING,
    READY,
    APPLYING,
}

data class ChannelsUiState(
    val isLoading: Boolean = false,
    val platforms: List<MessagingPlatform> = emptyList(),
    val errorMessage: UiText? = null,
    val toastMessage: UiText? = null,
    val restartNeeded: Boolean = false,
    val isRestarting: Boolean = false,
    val togglingId: String? = null,
    val testingId: String? = null,
    val removingId: String? = null,
    val gatewayStartCommand: String = "hermes gateway start",
    // Telegram onboarding
    val onboardingPhase: OnboardingPhase = OnboardingPhase.IDLE,
    val onboardingSetup: TelegramOnboardingStartResponse? = null,
    val onboardingQrDataUrl: String? = null,
    val onboardingBotUsername: String? = null,
    val onboardingAllowedIds: List<String> = emptyList(),
    val onboardingDetectedOwnerId: String? = null,
    val onboardingNewAllowedId: String = "",
    val onboardingError: UiText? = null,
    /** Countdown ("m:ss") or "expired" for the QR code; null when unknown. */
    val onboardingExpiresIn: UiText? = null,
    // Admin section
    val envPath: String = "~/.hermes/.env",
)

class ChannelsViewModel :
    ViewModel(),
    ToastHost {
    private val _uiState = MutableStateFlow(ChannelsUiState())
    val uiState: StateFlow<ChannelsUiState> = _uiState.asStateFlow()

    private var launchJob: Job? = null

    /** The in-flight onboarding start request; cancelled with the onboarding it belongs to. */
    private var onboardingStartJob: Job? = null

    /** Bumped whenever onboarding is reset, so a late start response of an earlier attempt is ignored. */
    private var onboardingGeneration = 0

    init {
        // Issue #784: gateway broadcasts platforms.changed — refresh silently
        // (no spinner) instead of waiting for a manual pull.
        refreshOnChange(
            eventType = ChangeEvents.PLATFORMS,
            apiCall = { safeApiCall { ApiClient.hermesApi.getMessagingPlatforms() } },
            onSuccess = { data ->
                _uiState.update {
                    it.copy(
                        platforms = data.platforms.orEmpty(),
                        gatewayStartCommand = data.gatewayStartCommand,
                        envPath = data.envPath,
                    )
                }
            },
        )
    }

    fun loadPlatforms() {
        safeLaunchLoad(
            apiCall = { safeApiCall { ApiClient.hermesApi.getMessagingPlatforms() } },
            onStart = {
                _uiState.update {
                    it.copy(isLoading = true, errorMessage = null)
                }
            },
            onSuccess = { data ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        platforms = data.platforms.orEmpty(),
                        gatewayStartCommand = data.gatewayStartCommand,
                        envPath = data.envPath,
                    )
                }
            },
            onError = { errorMsg ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = UiText.res(R.string.channels_msg_load_failed, errorMsg.toUiText()),
                    )
                }
            },
        )
    }

    /** Toggle a platform's enabled state without a full reload. */
    fun togglePlatform(
        platformId: String,
        enabled: Boolean,
    ) {
        _uiState.update { it.copy(togglingId = platformId) }
        safeLaunchLoad(
            apiCall = {
                safeApiCall {
                    ApiClient.hermesApi.configurePlatform(
                        platformId,
                        MessagingPlatformUpdate(enabled = enabled),
                    )
                }
            },
            onStart = {},
            onSuccess = { response ->
                val hotServed = response.hotServed
                _uiState.update { state ->
                    state.copy(
                        platforms =
                            state.platforms.map { p ->
                                if (p.id == platformId) {
                                    p.copy(
                                        enabled = enabled,
                                        state =
                                            when {
                                                !enabled -> "disabled"
                                                hotServed -> "connected"
                                                else -> "pending_restart"
                                            },
                                    )
                                } else {
                                    p
                                }
                            },
                        restartNeeded = !hotServed,
                        togglingId = null,
                        toastMessage =
                            if (hotServed) {
                                UiText.res(R.string.channels_msg_updated_live, platformId)
                            } else {
                                null
                            },
                    )
                }
                loadPlatforms()
            },
            onError = { error ->
                _uiState.update {
                    it.copy(
                        togglingId = null,
                        toastMessage = UiText.failure(R.string.channels_msg_toggle_failed, error.toUiText()),
                    )
                }
            },
        )
    }

    /** Issue #1468: disconnect a catalog platform using its supported configuration endpoint. */
    fun removePlatform(platformId: String) {
        val scope = AuthManager.currentDataScope()
        val profile = AuthManager.activeProfileId.value ?: "default"
        val api = ApiClient.hermesApi
        _uiState.update { it.copy(removingId = platformId) }
        safeLaunchLoad(
            apiCall = {
                // Do not replay a credential mutation after the active connection changes.
                safeApiCall(retries = 0) {
                    val catalog = api.getMessagingPlatforms(profile)
                    if (!catalog.isSuccessful) {
                        Response.error<MessagingPlatformUpdateResponse>(
                            catalog.code(),
                            requireNotNull(catalog.errorBody()),
                        )
                    } else {
                        val platform =
                            catalog.body()?.platforms?.firstOrNull { it.id == platformId }
                                ?: error("Platform is unavailable in the selected profile")
                        val fields = platform.envVars ?: error("Platform credential metadata is unavailable")
                        check(
                            AuthManager.currentDataScope() == scope &&
                                (AuthManager.activeProfileId.value ?: "default") == profile,
                        ) { "Connection or profile changed; retry disconnect" }
                        val response =
                            api.configurePlatform(
                                platformId,
                                MessagingPlatformUpdate(
                                    enabled = false,
                                    clearEnv = fields.map { it.key }.distinct(),
                                    profile = profile,
                                ),
                            )
                        if (response.isSuccessful) {
                            check(response.body()?.ok == true) { "Gateway did not confirm platform disconnection" }
                        }
                        response
                    }
                }
            },
            onStart = {},
            onSuccess = { response ->
                _uiState.update { state ->
                    state.copy(
                        removingId = null,
                        restartNeeded = state.restartNeeded || !response.hotServed,
                        toastMessage =
                            if (response.hotServed) {
                                UiText.res(R.string.channels_msg_disconnected_live)
                            } else {
                                UiText.res(R.string.channels_msg_disconnected_restart)
                            },
                    )
                }
                loadPlatforms()
            },
            onError = { error ->
                _uiState.update {
                    it.copy(
                        removingId = null,
                        toastMessage = UiText.failure(R.string.channels_msg_disconnect_failed, error.toUiText()),
                    )
                }
            },
        )
    }

    /** Configure a platform's env vars and/or settings. Reloads on success. */
    fun configurePlatform(
        platformId: String,
        update: MessagingPlatformUpdate,
    ) {
        safeLaunchLoad(
            apiCall = {
                safeApiCall {
                    ApiClient.hermesApi.configurePlatform(platformId, update)
                }
            },
            onStart = {
                _uiState.update {
                    it.copy(isLoading = true, errorMessage = null)
                }
            },
            onSuccess = { response ->
                val hotServed = response.hotServed
                val message =
                    if (hotServed) {
                        UiText.res(R.string.channels_msg_configured_live, platformId)
                    } else {
                        UiText.res(R.string.channels_msg_configured_restart, platformId)
                    }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        restartNeeded = !hotServed,
                        toastMessage = message,
                    )
                }
                loadPlatforms()
            },
            onError = { errorMsg ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        toastMessage = UiText.failure(R.string.channels_msg_configure_failed, errorMsg.toUiText()),
                    )
                }
            },
        )
    }

    /** Convenience: configure a platform's env vars via a Map<String,String>. */
    fun configurePlatform(
        platformId: String,
        config: Map<String, String>,
    ) {
        configurePlatform(platformId, MessagingPlatformUpdate(env = config))
    }

    /** Test connectivity for a platform. */
    fun testPlatform(platformId: String) {
        _uiState.update { it.copy(testingId = platformId) }
        safeLaunchLoad(
            apiCall = { safeApiCall { ApiClient.hermesApi.testMessagingPlatform(platformId) } },
            onStart = {},
            onSuccess = { result ->
                val msg = result.message
                _uiState.update {
                    it.copy(testingId = null, toastMessage = UiText.raw(msg))
                }
            },
            onError = { error ->
                _uiState.update {
                    it.copy(
                        testingId = null,
                        toastMessage = UiText.failure(R.string.channels_msg_test_failed, error.toUiText()),
                    )
                }
            },
        )
    }

    /** Restart the whole gateway. Reloads platforms after a 4s delay. */
    fun restartGateway() {
        safeLaunchLoad(
            apiCall = { safeApiCall { ApiClient.hermesApi.restartGateway() } },
            onStart = {
                _uiState.update {
                    it.copy(isRestarting = true, errorMessage = null)
                }
            },
            onSuccess = {
                _uiState.update {
                    it.copy(isRestarting = false, restartNeeded = false)
                }
                viewModelScope.launch {
                    delay(4000)
                    loadPlatforms()
                }
            },
            onError = { error ->
                _uiState.update {
                    it.copy(
                        isRestarting = false,
                        toastMessage = UiText.failure(R.string.channels_msg_restart_failed, error.toUiText()),
                    )
                }
            },
        )
    }

    // ── Telegram onboarding ────────────────────────────────────────────────

    fun startTelegramOnboarding() {
        val state = _uiState.value
        if (state.onboardingPhase == OnboardingPhase.STARTING ||
            state.onboardingPhase == OnboardingPhase.WAITING
        ) {
            return
        }
        val generation = onboardingGeneration
        _uiState.update {
            it.copy(
                onboardingPhase = OnboardingPhase.STARTING,
                onboardingError = null,
                onboardingBotUsername = null,
                onboardingAllowedIds = emptyList(),
                onboardingDetectedOwnerId = null,
                onboardingNewAllowedId = "",
            )
        }
        onboardingStartJob?.cancel()
        onboardingStartJob =
            viewModelScope.launch {
                val result =
                    safeApiCall {
                        ApiClient.hermesApi.startTelegramOnboarding(
                            TelegramOnboardingStartRequest(botName = "Hermes Agent"),
                        )
                    }
                // Cancelled or reset meanwhile: the late response must not revive the onboarding.
                if (generation != onboardingGeneration ||
                    _uiState.value.onboardingPhase != OnboardingPhase.STARTING
                ) {
                    return@launch
                }
                _uiState.update {
                    when (result) {
                        is io.wutz.apollo.data.remote.NetworkResult.Success -> {
                            val setup = result.data
                            it.copy(
                                onboardingPhase = OnboardingPhase.WAITING,
                                onboardingSetup = setup,
                                onboardingQrDataUrl = setup.qrPayload,
                                onboardingExpiresIn = formatExpiry(setup.expiresAt),
                            )
                        }

                        is io.wutz.apollo.data.remote.NetworkResult.Failure -> {
                            it.copy(
                                onboardingPhase = OnboardingPhase.IDLE,
                                onboardingError = result.error.toUiText(),
                            )
                        }
                    }
                }
                if (_uiState.value.onboardingPhase == OnboardingPhase.WAITING) {
                    pollOnboardingStatus()
                }
            }
    }

    private fun pollOnboardingStatus() {
        launchJob?.cancel()
        launchJob =
            viewModelScope.launch {
                while (_uiState.value.onboardingPhase == OnboardingPhase.WAITING) {
                    delay(2000)
                    val pairingId = _uiState.value.onboardingSetup?.pairingId ?: break
                    val result =
                        safeApiCall {
                            ApiClient.hermesApi.getTelegramOnboardingStatus(pairingId)
                        }
                    when (result) {
                        is io.wutz.apollo.data.remote.NetworkResult.Success -> {
                            val status = result.data
                            if (status.status == "ready") {
                                _uiState.update {
                                    it.copy(
                                        onboardingPhase = OnboardingPhase.READY,
                                        onboardingBotUsername = status.botUsername,
                                        onboardingError = null,
                                    )
                                }
                                val ownerId = status.ownerUserId
                                if (ownerId != null && TELEGRAM_USER_ID_RE.matches(ownerId)) {
                                    _uiState.update {
                                        it.copy(
                                            onboardingDetectedOwnerId = ownerId,
                                            onboardingAllowedIds = listOf(ownerId),
                                        )
                                    }
                                }
                                return@launch
                            }
                            _uiState.update { it.copy(onboardingError = null) }
                        }

                        is io.wutz.apollo.data.remote.NetworkResult.Failure -> {
                            val expired = isOnboardingExpired(_uiState.value.onboardingSetup)
                            if (expired) {
                                _uiState.update {
                                    it.copy(
                                        onboardingPhase = OnboardingPhase.IDLE,
                                        onboardingSetup = null,
                                        onboardingQrDataUrl = null,
                                        onboardingError = UiText.res(R.string.channels_msg_onboarding_expired),
                                    )
                                }
                                return@launch
                            }
                            _uiState.update {
                                it.copy(
                                    onboardingError = UiText.res(R.string.channels_msg_onboarding_waiting),
                                )
                            }
                        }
                    }
                    // Update expiry countdown
                    _uiState.update {
                        it.copy(
                            onboardingExpiresIn = formatExpiry(it.onboardingSetup?.expiresAt),
                        )
                    }
                }
            }
    }

    fun cancelTelegramOnboarding() {
        val pairingId = _uiState.value.onboardingSetup?.pairingId
        launchJob?.cancel()
        if (pairingId != null) {
            viewModelScope.launch {
                safeApiCall {
                    ApiClient.hermesApi.cancelTelegramOnboarding(pairingId)
                }
            }
        }
        resetOnboarding()
    }

    fun setOnboardingNewAllowedId(id: String) {
        _uiState.update { it.copy(onboardingNewAllowedId = id) }
    }

    fun addOnboardingAllowedId() {
        val trimmed = _uiState.value.onboardingNewAllowedId.trim()
        if (!TELEGRAM_USER_ID_RE.matches(trimmed)) {
            _uiState.update {
                it.copy(
                    onboardingError = UiText.res(R.string.channels_msg_onboarding_ids_numeric),
                )
            }
            return
        }
        _uiState.update { state ->
            val ids = state.onboardingAllowedIds
            state.copy(
                onboardingError = null,
                onboardingNewAllowedId = "",
                onboardingAllowedIds =
                    if (ids.contains(trimmed)) ids else ids + trimmed,
            )
        }
    }

    fun removeOnboardingAllowedId(id: String) {
        _uiState.update { state ->
            state.copy(
                onboardingAllowedIds = state.onboardingAllowedIds.filter { it != id },
            )
        }
    }

    fun applyTelegramOnboarding() {
        val setup = _uiState.value.onboardingSetup ?: return
        val allowedIds = _uiState.value.onboardingAllowedIds
        if (allowedIds.isEmpty()) {
            _uiState.update {
                it.copy(
                    onboardingError = UiText.res(R.string.channels_msg_onboarding_ids_required),
                )
            }
            return
        }
        _uiState.update {
            it.copy(
                onboardingPhase = OnboardingPhase.APPLYING,
                onboardingError = null,
            )
        }
        viewModelScope.launch {
            val result =
                safeApiCall {
                    ApiClient.hermesApi.applyTelegramOnboarding(
                        setup.pairingId,
                        TelegramOnboardingApplyRequest(
                            allowedUserIds = allowedIds,
                        ),
                    )
                }
            when (result) {
                is io.wutz.apollo.data.remote.NetworkResult.Success -> {
                    val applyResult = result.data
                    _uiState.update {
                        it.copy(
                            toastMessage = UiText.res(R.string.channels_msg_telegram_saved),
                        )
                    }
                    resetOnboarding()
                    if (applyResult.needsRestart) {
                        restartGateway()
                    } else {
                        loadPlatforms()
                    }
                }

                is io.wutz.apollo.data.remote.NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            onboardingPhase = OnboardingPhase.READY,
                            onboardingError = result.error.toUiText(),
                        )
                    }
                }
            }
        }
    }

    private fun resetOnboarding() {
        onboardingGeneration++
        onboardingStartJob?.cancel()
        onboardingStartJob = null
        launchJob?.cancel()
        _uiState.update {
            it.copy(
                onboardingPhase = OnboardingPhase.IDLE,
                onboardingSetup = null,
                onboardingQrDataUrl = null,
                onboardingBotUsername = null,
                onboardingAllowedIds = emptyList(),
                onboardingDetectedOwnerId = null,
                onboardingNewAllowedId = "",
                onboardingError = null,
                onboardingExpiresIn = null,
            )
        }
    }

    override fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }
}

private val TELEGRAM_USER_ID_RE = Regex("^\\d+$")

private val isoTimestampParser =
    java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }

private fun formatExpiry(expiresAt: String?): UiText? {
    if (expiresAt == null) return null
    val ms =
        try {
            isoTimestampParser
                .parse(expiresAt)
                ?.time
                ?.minus(System.currentTimeMillis())
        } catch (_: Exception) {
            null
        }
            ?: return null
    if (ms <= 0) return UiText.res(R.string.channels_msg_qr_expired)
    val seconds = (ms / 1000).toInt()
    val mins = seconds / 60
    val secs = seconds % 60
    return UiText.raw("$mins:${secs.toString().padStart(2, '0')}")
}

private fun isOnboardingExpired(setup: io.wutz.apollo.data.model.TelegramOnboardingStartResponse?): Boolean {
    val expiresAt = setup?.expiresAt ?: return false
    val ms =
        try {
            isoTimestampParser
                .parse(expiresAt)
                ?.time
        } catch (_: Exception) {
            null
        }
            ?: return false
    return System.currentTimeMillis() >= ms
}
