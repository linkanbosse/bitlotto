package com.linkan.bitlotto.mining

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class MinerUi(
    val running: Boolean = false,
    val status: String = "Stoppad",
    val pauseReason: String? = null,
    val pool: String = "",
    val connected: Boolean = false,
    val authorized: Boolean = false,
    val hashrate: Double = 0.0,
    val threads: Int = 0,
    val poolDifficulty: Double = 0.0,
    val networkDifficulty: Double = 0.0,
    val blockHeightHint: String = "",
    val sessionHashes: Long = 0,
    val sessionSeconds: Long = 0,
    val bestSession: Double = 0.0,
    val bestEver: Double = 0.0,
    val sharesFound: Int = 0,
    val sharesAccepted: Int = 0,
    val sharesRejected: Int = 0,
    val lastShareError: String? = null,
    val batteryTempC: Float? = null,
    val charging: Boolean = false,
    val blockCandidateFound: Boolean = false,
    val log: List<String> = emptyList(),
)

/** Process-wide miner state shared between MiningService and the UI. */
object MinerState {
    private val _ui = MutableStateFlow(MinerUi())
    val ui: StateFlow<MinerUi> = _ui.asStateFlow()

    fun set(transform: (MinerUi) -> MinerUi) = _ui.update(transform)

    fun log(line: String) = _ui.update {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date())
        it.copy(log = (listOf("$time  $line") + it.log).take(60))
    }
}
