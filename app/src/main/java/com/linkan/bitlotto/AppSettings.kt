package com.linkan.bitlotto

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class WalletNet(val label: String) {
    TESTNET4("Testnet4 (låtsaspengar)"),
    MAINNET("Mainnet (riktiga bitcoin)")
}

data class PoolPreset(val name: String, val host: String, val port: Int, val userSuffix: String)

val POOL_PRESETS = listOf(
    PoolPreset("CKPool Solo (EU)", "eusolo.ckpool.org", 3333, ""),
    PoolPreset("CKPool Solo (global)", "solo.ckpool.org", 3333, ""),
    PoolPreset("Public Pool", "public-pool.io", 21496, ".bitlotto"),
)

data class SettingsState(
    val network: WalletNet = WalletNet.TESTNET4,
    val poolHost: String = POOL_PRESETS[0].host,
    val poolPort: Int = POOL_PRESETS[0].port,
    val userSuffix: String = POOL_PRESETS[0].userSuffix,
    val payoutAddress: String = "",
    val threads: Int = 2,
    val onlyWhenCharging: Boolean = true,
    val maxBatteryTempC: Int = 42,
)

/** Lightweight SharedPreferences-backed settings with a StateFlow for Compose. */
class AppSettings(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(load())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    val current: SettingsState get() = _state.value

    private fun load() = SettingsState(
        network = runCatching { WalletNet.valueOf(prefs.getString("network", null) ?: "") }
            .getOrDefault(WalletNet.TESTNET4),
        poolHost = prefs.getString("poolHost", null) ?: POOL_PRESETS[0].host,
        poolPort = prefs.getInt("poolPort", POOL_PRESETS[0].port),
        userSuffix = prefs.getString("userSuffix", null) ?: POOL_PRESETS[0].userSuffix,
        payoutAddress = prefs.getString("payoutAddress", null) ?: "",
        threads = prefs.getInt("threads", 2),
        onlyWhenCharging = prefs.getBoolean("onlyWhenCharging", true),
        maxBatteryTempC = prefs.getInt("maxBatteryTempC", 42),
    )

    fun update(transform: (SettingsState) -> SettingsState) {
        val s = transform(_state.value)
        prefs.edit()
            .putString("network", s.network.name)
            .putString("poolHost", s.poolHost.trim())
            .putInt("poolPort", s.poolPort)
            .putString("userSuffix", s.userSuffix)
            .putString("payoutAddress", s.payoutAddress.trim())
            .putInt("threads", s.threads)
            .putBoolean("onlyWhenCharging", s.onlyWhenCharging)
            .putInt("maxBatteryTempC", s.maxBatteryTempC)
            .apply()
        _state.value = s
    }

    // Lifetime mining statistics.
    var bestDifficultyEver: Double
        get() = java.lang.Double.longBitsToDouble(prefs.getLong("bestEver", 0L))
        set(v) = prefs.edit().putLong("bestEver", java.lang.Double.doubleToRawLongBits(v)).apply()

    var totalHashesEver: Long
        get() = prefs.getLong("totalHashes", 0L)
        set(v) = prefs.edit().putLong("totalHashes", v).apply()

    var sharesAcceptedEver: Long
        get() = prefs.getLong("sharesEver", 0L)
        set(v) = prefs.edit().putLong("sharesEver", v).apply()
}
