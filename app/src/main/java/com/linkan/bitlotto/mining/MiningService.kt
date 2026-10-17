package com.linkan.bitlotto.mining

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.linkan.bitlotto.BitLottoApp
import com.linkan.bitlotto.R
import com.linkan.bitlotto.ui.MainActivity
import com.linkan.bitlotto.ui.formatDifficulty
import com.linkan.bitlotto.ui.formatHashrate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicLong

class MiningService : Service() {

    private val app get() = application as BitLottoApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var monitorJob: Job? = null

    @Volatile private var engine: MiningEngine? = null
    @Volatile private var client: StratumClient? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile private var extranonce1: String? = null
    @Volatile private var extranonce2Size = 4
    @Volatile private var poolDifficulty = 1.0
    @Volatile private var lastJob: StratumJob? = null

    private val bestSessionBits = AtomicLong(0L)
    private var sessionStartMs = 0L
    private var hashesAtStart = 0L
    private var savedHashes = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopMining()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> startMining()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopMining()
        scope.cancel()
        super.onDestroy()
    }

    private fun startMining() {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification("Startar…"), type)
        if (engine != null) return

        val s = app.settings.current
        val payout = s.payoutAddress.trim()
        if (payout.isEmpty() || !app.wallet.isValidAddress(payout, com.linkan.bitlotto.WalletNet.MAINNET)) {
            MinerState.set { it.copy(running = false, status = "Ange en giltig mainnet-adress för utbetalning") }
            MinerState.log("Start avbruten: ogiltig utbetalningsadress")
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BitLotto:mining").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L)
        }

        val threads = s.threads.coerceIn(1, Runtime.getRuntime().availableProcessors())
        bestSessionBits.set(0L)
        val bestEver = app.settings.bestDifficultyEver
        sessionStartMs = System.currentTimeMillis()

        val eng = MiningEngine(engineListener)
        engine = eng
        hashesAtStart = 0L
        savedHashes = 0L

        MinerState.set {
            MinerUi(
                running = true,
                status = "Ansluter…",
                pool = "${s.poolHost}:${s.poolPort}",
                threads = threads,
                bestEver = bestEver,
                log = it.log,
            )
        }
        MinerState.log("Startar $threads trådar mot ${s.poolHost}:${s.poolPort}")

        val factory = ThreadFactory { r ->
            Thread {
                Process.setThreadPriority(Process.THREAD_PRIORITY_LOWEST)
                r.run()
            }
        }
        eng.start(threads, factory)

        // Both CKPool and Public Pool accept "address.worker".
        val user = "$payout.bitlotto"
        val endpoints = com.linkan.bitlotto.Endpoints.pools(s)
        client = StratumClient(
            endpoints.map { it.first }.toTypedArray(),
            endpoints.map { it.second }.toIntArray(),
            user, "x", 1.0, stratumListener,
        ).also { it.start() }
        monitorJob = scope.launch { monitorLoop() }
    }

    private fun stopMining() {
        monitorJob?.cancel()
        monitorJob = null
        client?.stop()
        client = null
        engine?.let { e ->
            e.stop()
            persistStats(e.totalHashes())
        }
        engine = null
        lastJob = null
        extranonce1 = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        if (MinerState.ui.value.running) MinerState.log("Minern stoppad")
        MinerState.set { it.copy(running = false, connected = false, hashrate = 0.0, status = "Stoppad", pauseReason = null) }
    }

    private fun persistStats(engineHashes: Long) {
        val delta = engineHashes - savedHashes
        if (delta > 0) {
            app.settings.totalHashesEver = app.settings.totalHashesEver + delta
            savedHashes = engineHashes
        }
        val best = java.lang.Double.longBitsToDouble(bestSessionBits.get())
        if (best > app.settings.bestDifficultyEver) app.settings.bestDifficultyEver = best
    }

    private suspend fun monitorLoop() {
        var lastHashes = 0L
        var lastTime = System.nanoTime()
        var ema = 0.0
        var tick = 0
        var overheated = false
        while (scope.isActive) {
            delay(1000)
            val e = engine ?: return
            val now = System.nanoTime()
            val h = e.totalHashes()
            val rate = (h - lastHashes) / ((now - lastTime) / 1e9)
            lastHashes = h
            lastTime = now
            ema = if (ema == 0.0) rate else ema * 0.8 + rate * 0.2

            // Battery / thermal guard.
            val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val tempC = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                ?.takeIf { it != Int.MIN_VALUE }?.let { it / 10f }
            val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
            val s = app.settings.current
            val thermal = if (Build.VERSION.SDK_INT >= 29) {
                getSystemService(PowerManager::class.java).currentThermalStatus
            } else {
                0
            }
            if (tempC != null) {
                if (tempC >= s.maxBatteryTempC) overheated = true
                else if (tempC <= s.maxBatteryTempC - 2) overheated = false
            }
            val reason = when {
                s.onlyWhenCharging && !plugged -> "Väntar på laddare"
                overheated -> "Svalnar (batteri ${"%.1f".format(tempC)} °C)"
                thermal >= PowerManager.THERMAL_STATUS_SEVERE -> "Telefonen är för varm"
                else -> null
            }
            val wasPaused = e.isPaused
            e.setPaused(reason != null)
            if (reason != null && !wasPaused) MinerState.log("Pausad: $reason")
            if (reason == null && wasPaused) MinerState.log("Fortsätter mina")

            val best = java.lang.Double.longBitsToDouble(bestSessionBits.get())
            MinerState.set {
                it.copy(
                    hashrate = if (reason != null) 0.0 else ema,
                    pauseReason = reason,
                    sessionHashes = h,
                    sessionSeconds = (System.currentTimeMillis() - sessionStartMs) / 1000,
                    bestSession = best,
                    bestEver = maxOf(it.bestEver, best),
                    batteryTempC = tempC,
                    charging = plugged,
                )
            }

            tick++
            if (tick % 5 == 0) {
                val ui = MinerState.ui.value
                val text = ui.pauseReason ?: "${formatHashrate(ui.hashrate)} · bästa ${formatDifficulty(ui.bestSession)}"
                getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
            }
            if (tick % 30 == 0) persistStats(h)
        }
    }

    private val engineListener = object : MiningEngine.Listener {
        override fun onCandidate(difficulty: Double) {
            while (true) {
                val cur = bestSessionBits.get()
                if (difficulty <= java.lang.Double.longBitsToDouble(cur)) return
                if (bestSessionBits.compareAndSet(cur, java.lang.Double.doubleToRawLongBits(difficulty))) return
            }
        }

        override fun onShareFound(
            work: MiningEngine.Work, extranonce2: String, nonceHex: String, difficulty: Double, isBlock: Boolean
        ) {
            client?.submit(work.job.jobId, extranonce2, work.job.ntime, nonceHex)
            MinerState.set { it.copy(sharesFound = it.sharesFound + 1, blockCandidateFound = it.blockCandidateFound || isBlock) }
            MinerState.log("Andel hittad! Svårighet ${formatDifficulty(difficulty)}" + if (isBlock) " — BLOCK!" else "")
            if (isBlock) alert("🎉 Möjligt block hittat!", "Svårighet ${formatDifficulty(difficulty)}. Kontrollera poolen.")
        }
    }

    private val stratumListener = object : StratumClient.Listener {
        override fun onStatus(status: String) {
            MinerState.set { it.copy(status = status) }
        }

        override fun onSubscribed(extranonce1: String, extranonce2Size: Int) {
            this@MiningService.extranonce1 = extranonce1
            this@MiningService.extranonce2Size = extranonce2Size
            MinerState.set { it.copy(connected = true, status = "Ansluten, loggar in…") }
            MinerState.log("Ansluten (extranonce2 = $extranonce2Size byte)")
        }

        override fun onAuthorized(ok: Boolean, error: String?) {
            if (ok) {
                MinerState.set { it.copy(authorized = true, status = "Minar") }
                MinerState.log("Inloggad hos poolen")
            } else {
                MinerState.set { it.copy(authorized = false, status = "Poolen nekade inloggning: ${error ?: "okänt fel"}") }
                MinerState.log("Inloggning nekad: ${error ?: "okänt fel"}")
                engine?.setWork(null)
            }
        }

        override fun onDifficulty(difficulty: Double) {
            poolDifficulty = difficulty
            MinerState.set { it.copy(poolDifficulty = difficulty) }
            MinerState.log("Pool-svårighet: ${formatDifficulty(difficulty)}")
            lastJob?.let { rebuildWork(it) }
        }

        override fun onJob(job: StratumJob) {
            lastJob = job
            rebuildWork(job)
            val first = MinerState.ui.value.networkDifficulty == 0.0
            MinerState.set { it.copy(networkDifficulty = job.networkDifficulty(), status = "Minar", connected = true) }
            if (first) MinerState.log("Första jobbet mottaget – hashning startar")
        }

        override fun onShareResult(accepted: Boolean, error: String?) {
            if (accepted) {
                app.settings.sharesAcceptedEver = app.settings.sharesAcceptedEver + 1
                MinerState.set { it.copy(sharesAccepted = it.sharesAccepted + 1) }
                MinerState.log("Andel godkänd av poolen ✓")
                alert("Andel godkänd ✓", "Din telefon hittade en giltig andel.")
            } else {
                MinerState.set { it.copy(sharesRejected = it.sharesRejected + 1, lastShareError = error) }
                MinerState.log("Andel nekad: ${error ?: "okänt"}")
            }
        }

        override fun onDisconnected(reason: String) {
            extranonce1 = null
            lastJob = null
            engine?.setWork(null)
            MinerState.set { it.copy(connected = false, authorized = false, status = "Frånkopplad: $reason") }
            MinerState.log("Frånkopplad: $reason")
        }
    }

    private fun rebuildWork(job: StratumJob) {
        val en1 = extranonce1 ?: return
        engine?.setWork(MiningEngine.Work(job, en1, extranonce2Size, poolDifficulty))
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MiningService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, BitLottoApp.CHANNEL_MINING)
            .setSmallIcon(R.drawable.ic_stat_miner)
            .setContentTitle("BitLotto minar")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Stoppa", stop)
            .build()
    }

    private fun alert(title: String, text: String) {
        val n = NotificationCompat.Builder(this, BitLottoApp.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_miner)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        runCatching {
            getSystemService(NotificationManager::class.java).notify((System.currentTimeMillis() % 100000).toInt() + 10, n)
        }
    }

    companion object {
        private const val NOTIF_ID = 1
        const val ACTION_START = "com.linkan.bitlotto.START"
        const val ACTION_STOP = "com.linkan.bitlotto.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, MiningService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, MiningService::class.java).setAction(ACTION_STOP))
        }
    }
}
