package io.wutz.apollo.ui.webhooks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.wutz.apollo.R
import io.wutz.apollo.data.local.SwrCache
import io.wutz.apollo.data.model.CreateWebhookRequest
import io.wutz.apollo.data.model.WebhookSubscription
import io.wutz.apollo.data.model.WebhookToggleSubscriptionRequest
import io.wutz.apollo.data.model.WebhooksResponse
import io.wutz.apollo.data.model.WebhooksToggleRequest
import io.wutz.apollo.data.remote.ApiClient
import io.wutz.apollo.data.remote.NetworkResult
import io.wutz.apollo.data.remote.safeApiCall
import io.wutz.apollo.ui.common.ToastHost
import io.wutz.apollo.ui.common.UiText
import io.wutz.apollo.ui.common.safeLaunchLoad
import io.wutz.apollo.ui.common.safeLaunchSwrLoad
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class WebhooksUiState(
    val isLoading: Boolean = false,
    val isActionRunning: Boolean = false,
    val enabled: Boolean = false,
    val baseUrl: String? = null,
    val subscriptions: List<WebhookSubscription> = emptyList(),
    val errorMessage: UiText? = null,
    val toastMessage: UiText? = null,
    // Create form state
    val showCreateDialog: Boolean = false,
    val createFormName: String = "",
    val createFormDescription: String = "",
    val createFormEvents: String = "",
    val createFormDeliver: String = "log",
    val createFormSecret: String = "",
    val createFormError: UiText? = null,
    // Delete confirmation state
    val deleteTarget: WebhookSubscription? = null,
    val isDeleting: Boolean = false,
    // Toggle tracking
    val togglingName: String? = null,
)

class WebhooksViewModel(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel(),
    ToastHost {
    private val _uiState = MutableStateFlow(WebhooksUiState())
    val uiState: StateFlow<WebhooksUiState> = _uiState.asStateFlow()

    private val webhooksCache = SwrCache<String, WebhooksResponse>()

    fun clearScopeOwnedState() {
        _uiState.update {
            it.copy(
                isLoading = false,
                enabled = false,
                baseUrl = null,
                subscriptions = emptyList(),
                errorMessage = null,
                deleteTarget = null,
                togglingName = null,
            )
        }
    }

    fun loadWebhooks(forceRefresh: Boolean = false) {
        safeLaunchSwrLoad(
            cache = webhooksCache,
            forceRefresh = forceRefresh,
            onCacheHit = { data ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        enabled = data.enabled,
                        baseUrl = data.base_url,
                        subscriptions = data.subscriptions.orEmpty(),
                        errorMessage = null,
                    )
                }
            },
            apiCall = { safeApiCall { ApiClient.hermesApi.getWebhooks() } },
            onStart = { _uiState.update { it.copy(isLoading = true, errorMessage = null) } },
            onSuccess = { data ->
                val body = data
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        enabled = body.enabled,
                        baseUrl = body.base_url,
                        subscriptions = body.subscriptions.orEmpty(),
                    )
                }
            },
            onError = { errorMsg ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = UiText.res(R.string.webhooks_msg_load_failed, errorMsg),
                    )
                }
            },
        )
    }

    fun toggleWebhooks(targetEnabled: Boolean) {
        val originalEnabled = _uiState.value.enabled
        _uiState.update { it.copy(enabled = targetEnabled) }

        viewModelScope.launch {
            val result =
                withContext(ioDispatcher) {
                    safeApiCall { ApiClient.hermesApi.toggleWebhooks(WebhooksToggleRequest(targetEnabled)) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            toastMessage =
                                if (targetEnabled) {
                                    UiText.res(R.string.webhooks_msg_global_enabled)
                                } else {
                                    UiText.res(R.string.webhooks_msg_global_disabled)
                                },
                        )
                    }
                    loadWebhooks(forceRefresh = true)
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            enabled = originalEnabled,
                            toastMessage = UiText.res(R.string.webhooks_msg_toggle_global_failed, result.error.message),
                        )
                    }
                }
            }
        }
    }

    // ── Create dialog ────────────────────────────────────────────────

    fun showCreateDialog() {
        _uiState.update {
            it.copy(
                showCreateDialog = true,
                createFormName = "",
                createFormDescription = "",
                createFormEvents = "",
                createFormDeliver = "log",
                createFormSecret = "",
                createFormError = null,
            )
        }
    }

    fun dismissCreateDialog() {
        _uiState.update { it.copy(showCreateDialog = false, createFormError = null) }
    }

    fun updateCreateFormName(value: String) {
        _uiState.update { it.copy(createFormName = value, createFormError = null) }
    }

    fun updateCreateFormDescription(value: String) {
        _uiState.update { it.copy(createFormDescription = value) }
    }

    fun updateCreateFormEvents(value: String) {
        _uiState.update { it.copy(createFormEvents = value) }
    }

    fun updateCreateFormDeliver(value: String) {
        _uiState.update { it.copy(createFormDeliver = value) }
    }

    fun updateCreateFormSecret(value: String) {
        _uiState.update { it.copy(createFormSecret = value) }
    }

    fun createSubscription() {
        val state = _uiState.value
        val name = state.createFormName.trim()
        if (name.isBlank()) {
            _uiState.update { it.copy(createFormError = UiText.res(R.string.webhooks_msg_name_required)) }
            return
        }

        val events =
            state.createFormEvents
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }

        _uiState.update { it.copy(isActionRunning = true, createFormError = null) }

        viewModelScope.launch {
            val result =
                withContext(ioDispatcher) {
                    safeApiCall {
                        ApiClient.hermesApi.createWebhook(
                            CreateWebhookRequest(
                                name = name,
                                description = state.createFormDescription.trim().ifEmpty { null },
                                events = events.ifEmpty { null },
                                deliver = state.createFormDeliver.ifBlank { null },
                                secret = state.createFormSecret.trim().ifEmpty { null },
                            ),
                        )
                    }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isActionRunning = false,
                            showCreateDialog = false,
                            toastMessage = UiText.res(R.string.webhooks_msg_subscription_created, name),
                        )
                    }
                    loadWebhooks(forceRefresh = true)
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isActionRunning = false,
                            createFormError = UiText.raw(result.error.message),
                        )
                    }
                }
            }
        }
    }

    // ── Per-subscription toggle ──────────────────────────────────────

    fun toggleSubscription(
        name: String,
        enabled: Boolean,
    ) {
        _uiState.update { it.copy(togglingName = name) }

        viewModelScope.launch {
            val result =
                withContext(ioDispatcher) {
                    safeApiCall {
                        ApiClient.hermesApi.setWebhookEnabled(
                            name = name,
                            body = WebhookToggleSubscriptionRequest(enabled = enabled),
                        )
                    }
                }
            _uiState.update { it.copy(togglingName = null) }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            toastMessage =
                                if (enabled) {
                                    UiText.res(R.string.webhooks_msg_subscription_enabled, name)
                                } else {
                                    UiText.res(R.string.webhooks_msg_subscription_disabled, name)
                                },
                        )
                    }
                    loadWebhooks(forceRefresh = true)
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            toastMessage = UiText.res(R.string.webhooks_msg_toggle_failed, name, result.error.message),
                        )
                    }
                }
            }
        }
    }

    // ── Delete confirmation ─────────────────────────────────────────

    fun promptDeleteSubscription(sub: WebhookSubscription) {
        _uiState.update { it.copy(deleteTarget = sub) }
    }

    fun dismissDeleteDialog() {
        _uiState.update { it.copy(deleteTarget = null) }
    }

    fun confirmDeleteSubscription() {
        val target = _uiState.value.deleteTarget ?: return
        _uiState.update { it.copy(isDeleting = true) }

        viewModelScope.launch {
            val result =
                withContext(ioDispatcher) {
                    safeApiCall { ApiClient.hermesApi.deleteWebhook(target.name) }
                }
            _uiState.update { it.copy(isDeleting = false, deleteTarget = null) }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(toastMessage = UiText.res(R.string.webhooks_msg_subscription_deleted, target.name))
                    }
                    loadWebhooks(forceRefresh = true)
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            toastMessage =
                                UiText.res(
                                    R.string.webhooks_msg_delete_failed,
                                    target.name,
                                    result.error.message,
                                ),
                        )
                    }
                }
            }
        }
    }

    override fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }
}
