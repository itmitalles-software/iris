package io.wutz.apollo.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.wutz.apollo.R
import io.wutz.apollo.data.model.VaultItem
import io.wutz.apollo.data.model.VaultSource
import io.wutz.apollo.data.ws.VaultRepository
import io.wutz.apollo.ui.common.UiText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsVaultUiState(
    val sources: List<VaultSource> = emptyList(),
    val items: List<VaultItem> = emptyList(),
    val isLoading: Boolean = false,
    val isActionInProgress: Boolean = false,
    val errorMessage: UiText? = null,
    val toastMessage: UiText? = null,
    val unlockDialogSource: VaultSource? = null,
)

class SettingsVaultViewModel(
    private val vaultRepo: VaultRepository = VaultRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SettingsVaultUiState())
    val uiState: StateFlow<SettingsVaultUiState> = _uiState.asStateFlow()

    init {
        loadVaultData()
    }

    fun loadVaultData() {
        viewModelScope.launch(ioDispatcher) {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val sources = vaultRepo.getSources()
                val items =
                    try {
                        vaultRepo.listItems()
                    } catch (_: Exception) {
                        emptyList()
                    }
                _uiState.update {
                    it.copy(
                        sources = sources,
                        items = items,
                        isLoading = false,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = e.message?.let(UiText::raw) ?: UiText.res(R.string.vault_msg_load_failed),
                    )
                }
            }
        }
    }

    fun toggleSource(
        source: VaultSource,
        enabled: Boolean,
    ) {
        viewModelScope.launch(ioDispatcher) {
            _uiState.update { it.copy(isActionInProgress = true) }
            try {
                vaultRepo.setSourceEnabled(source.name, enabled)
                val sources = vaultRepo.getSources()
                _uiState.update { it.copy(sources = sources, isActionInProgress = false) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isActionInProgress = false,
                        toastMessage =
                            UiText.res(
                                R.string.vault_msg_update_failed,
                                source.displayName,
                                e.message.toString(),
                            ),
                    )
                }
            }
        }
    }

    fun unlockSource(
        name: String,
        password: String,
    ) {
        viewModelScope.launch(ioDispatcher) {
            _uiState.update { it.copy(isActionInProgress = true, unlockDialogSource = null) }
            try {
                val success = vaultRepo.unlockSource(name, password)
                val sources = vaultRepo.getSources()
                val items =
                    try {
                        vaultRepo.listItems()
                    } catch (_: Exception) {
                        emptyList()
                    }
                _uiState.update {
                    it.copy(
                        sources = sources,
                        items = items,
                        isActionInProgress = false,
                        toastMessage =
                            UiText.res(
                                if (success) R.string.vault_msg_unlocked else R.string.vault_msg_unlock_failed,
                                name,
                            ),
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isActionInProgress = false,
                        toastMessage = UiText.res(R.string.vault_msg_unlock_error, name, e.message.toString()),
                    )
                }
            }
        }
    }

    fun lockSource(name: String? = null) {
        viewModelScope.launch(ioDispatcher) {
            _uiState.update { it.copy(isActionInProgress = true) }
            try {
                vaultRepo.lockSource(name)
                val sources = vaultRepo.getSources()
                val items =
                    try {
                        vaultRepo.listItems()
                    } catch (_: Exception) {
                        emptyList()
                    }
                _uiState.update {
                    it.copy(
                        sources = sources,
                        items = items,
                        isActionInProgress = false,
                        toastMessage =
                            if (name != null) {
                                UiText.res(R.string.vault_msg_locked, name)
                            } else {
                                UiText.res(R.string.vault_msg_all_locked)
                            },
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isActionInProgress = false,
                        toastMessage = UiText.res(R.string.vault_msg_lock_error, e.message.toString()),
                    )
                }
            }
        }
    }

    fun showUnlockDialog(source: VaultSource) {
        _uiState.update { it.copy(unlockDialogSource = source) }
    }

    fun dismissUnlockDialog() {
        _uiState.update { it.copy(unlockDialogSource = null) }
    }

    fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }
}
