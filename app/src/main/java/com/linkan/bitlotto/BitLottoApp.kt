package com.linkan.bitlotto

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.linkan.bitlotto.wallet.SeedVault
import com.linkan.bitlotto.wallet.WalletRepository

class BitLottoApp : Application() {
    lateinit var settings: AppSettings
        private set
    lateinit var seedVault: SeedVault
        private set
    lateinit var wallet: WalletRepository
        private set

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        seedVault = SeedVault(this)
        wallet = WalletRepository(this)

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MINING, "Mining", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Visar att minern körs"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Viktiga händelser", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Godkända andelar och hittade block"
            }
        )
    }

    companion object {
        const val CHANNEL_MINING = "mining"
        const val CHANNEL_ALERTS = "alerts"
    }
}
