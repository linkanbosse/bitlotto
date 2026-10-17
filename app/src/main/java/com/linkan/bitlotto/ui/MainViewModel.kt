package com.linkan.bitlotto.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.linkan.bitlotto.BitLottoApp
import com.linkan.bitlotto.WalletNet
import com.linkan.bitlotto.wallet.FeeOptions
import com.linkan.bitlotto.wallet.PendingSend
import com.linkan.bitlotto.wallet.WalletSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface WalletPhase {
    data object Starting : WalletPhase
    data object NoWallet : WalletPhase
    data object Opening : WalletPhase
    data class Ready(val snapshot: WalletSnapshot) : WalletPhase
    data class Failed(val message: String) : WalletPhase
}

data class WalletUiState(
    val phase: WalletPhase = WalletPhase.Starting,
    val syncing: Boolean = false,
    val lastSyncError: String? = null,
    val message: String? = null,
)

sealed interface SendState {
    data object Idle : SendState
    data object Working : SendState
    data class Review(val pending: PendingSend) : SendState
    data class Done(val txid: String, val explorerUrl: String) : SendState
    data class Error(val message: String) : SendState
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as BitLottoApp
    val settings = app.settings
    private val repo = app.wallet
    private val vault = app.seedVault

    private val _wallet = MutableStateFlow(WalletUiState())
    val wallet: StateFlow<WalletUiState> = _wallet.asStateFlow()

    private val _send = MutableStateFlow<SendState>(SendState.Idle)
    val send: StateFlow<SendState> = _send.asStateFlow()

    private val _fees = MutableStateFlow<FeeOptions?>(null)
    val fees: StateFlow<FeeOptions?> = _fees.asStateFlow()

    init {
        if (vault.hasSeed()) openWallet() else _wallet.update { it.copy(phase = WalletPhase.NoWallet) }
    }

    fun newPhrase(): String = repo.newMnemonic()

    /** Validates and normalizes a phrase. Returns null if invalid. */
    fun validatePhrase(phrase: String): String? = runCatching { repo.normalizeMnemonic(phrase) }.getOrNull()

    fun saveAndOpen(phrase: String) {
        val normalized = validatePhrase(phrase)
        if (normalized == null) {
            _wallet.update { it.copy(message = "Ogiltig återställningsfras") }
            return
        }
        repo.deleteDatabases()
        vault.save(normalized)
        openWallet()
    }

    fun openWallet() {
        viewModelScope.launch {
            _wallet.update { it.copy(phase = WalletPhase.Opening) }
            try {
                val phrase = vault.load() ?: run {
                    _wallet.update { it.copy(phase = WalletPhase.NoWallet) }
                    return@launch
                }
                repo.close()
                repo.open(phrase, settings.current.network)
                _wallet.update { it.copy(phase = WalletPhase.Ready(repo.snapshot())) }
                sync()
            } catch (e: Exception) {
                _wallet.update { it.copy(phase = WalletPhase.Failed(e.message ?: e.javaClass.simpleName)) }
            }
        }
    }

    fun sync() {
        if (_wallet.value.syncing || !repo.isOpen) return
        viewModelScope.launch {
            _wallet.update { it.copy(syncing = true, lastSyncError = null) }
            try {
                repo.sync()
                val snap = repo.snapshot()
                _wallet.update { it.copy(phase = WalletPhase.Ready(snap), syncing = false) }
            } catch (e: Exception) {
                _wallet.update { it.copy(syncing = false, lastSyncError = "Synk misslyckades: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    fun switchNetwork(net: WalletNet) {
        if (settings.current.network == net) return
        settings.update { it.copy(network = net) }
        openWallet()
    }

    fun revealPhrase(): String? = runCatching { vault.load() }.getOrNull()

    fun deleteWallet() {
        repo.deleteDatabases()
        vault.clear()
        _send.value = SendState.Idle
        _wallet.value = WalletUiState(phase = WalletPhase.NoWallet)
    }

    fun loadFees() {
        viewModelScope.launch { _fees.value = runCatching { repo.feeOptions() }.getOrNull() }
    }

    fun prepareSend(address: String, amountSats: Long, sendAll: Boolean, feeRate: Long) {
        viewModelScope.launch {
            _send.value = SendState.Working
            _send.value = try {
                SendState.Review(repo.prepareSend(address, amountSats, sendAll, feeRate))
            } catch (e: Exception) {
                SendState.Error(friendlyError(e))
            }
        }
    }

    fun confirmSend(p: PendingSend) {
        viewModelScope.launch {
            _send.value = SendState.Working
            _send.value = try {
                val txid = repo.broadcast(p)
                runCatching { _wallet.update { it.copy(phase = WalletPhase.Ready(repo.snapshot())) } }
                SendState.Done(txid, repo.explorerTxUrl(txid))
            } catch (e: Exception) {
                SendState.Error(friendlyError(e))
            }
        }
    }

    fun resetSend() {
        _send.value = SendState.Idle
    }

    fun consumeMessage() = _wallet.update { it.copy(message = null) }

    /** Fills the mining payout address from the wallet (mainnet only). */
    fun useWalletAddressForMining(): Boolean {
        if (repo.openNetwork != WalletNet.MAINNET || !repo.isOpen) return false
        viewModelScope.launch {
            runCatching { repo.receiveAddress() }.onSuccess { addr ->
                settings.update { it.copy(payoutAddress = addr) }
            }
        }
        return true
    }

    fun isValidMainnetAddress(a: String) = repo.isValidAddress(a, WalletNet.MAINNET)

    private fun friendlyError(e: Exception): String {
        val m = e.message ?: e.javaClass.simpleName
        return when {
            m.contains("InsufficientFunds", true) -> "Otillräckligt saldo (inklusive avgift)"
            m.contains("OutputBelowDustLimit", true) -> "Beloppet är för litet (under dust-gränsen)"
            else -> m
        }
    }
}
