package com.linkan.bitlotto.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.linkan.bitlotto.mining.MinerState
import com.linkan.bitlotto.mining.MiningService

private const val TWO_32 = 4294967296.0
private const val BLOCK_SUBSIDY_SATS = 312_500_000.0 // 3,125 BTC (gäller till halveringen 2028)

@Composable
fun MinerScreen(vm: MainViewModel) {
    val ui by MinerState.ui.collectAsState()
    val settings by vm.settings.state.collectAsState()
    val context = LocalContext.current
    val payoutOk = settings.payoutAddress.isNotBlank() && vm.isValidMainnetAddress(settings.payoutAddress)

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        MiningService.start(context)
    }

    fun start() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            MiningService.start(context)
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item { Text("Solo-miner", style = MaterialTheme.typography.headlineMedium) }

        item {
            SectionCard {
                SectionLabel(if (ui.running) (ui.pauseReason?.let { "Pausad" } ?: "Hashrate") else "Status")
                Text(
                    if (ui.running) formatHashrate(ui.hashrate) else "Stoppad",
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = Mono,
                    color = if (ui.running && ui.pauseReason == null) BtcOrange else MaterialTheme.colorScheme.onSurface,
                )
                Text(ui.pauseReason ?: ui.status, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (ui.running) {
                    Text(
                        "${ui.threads} trådar · ${ui.pool} · ${formatDuration(ui.sessionSeconds)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(16.dp))
                if (ui.running) {
                    Button(
                        onClick = { MiningService.stop(context) },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurface),
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text("Stoppa") }
                } else {
                    Button(
                        onClick = { start() },
                        enabled = payoutOk,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text("Starta mining") }
                    if (!payoutOk) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Ange en mainnet-utbetalningsadress under Inställningar. Solo-poolen betalar direkt till den om du hittar ett block.",
                            color = BtcOrange,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        if (ui.blockCandidateFound) {
            item {
                SectionCard {
                    Text("🎉 Ett möjligt block har hittats!", fontWeight = FontWeight.Bold, color = Positive)
                    Text("Kontrollera din utbetalningsadress i en block explorer.")
                }
            }
        }

        item {
            SectionCard {
                SectionLabel("Lotteriet")
                val h = ui.hashrate
                val nd = ui.networkDifficulty
                if (h > 0 && nd > 0) {
                    val pHash = 1.0 / (nd * TWO_32)
                    val perHour = h * 3600 * pHash
                    val years = 1.0 / (h * pHash) / 31_557_600.0
                    StatRow("Chans för block per timme", "1 på ${formatSci(1 / perHour)}")
                    StatRow("Chans för block per dygn", "1 på ${formatSci(1 / (perHour * 24))}")
                    StatRow("Förväntad tid till block", "${formatSci(years)} år")
                    StatRow("Förväntat värde per timme", "${formatSci(perHour * BLOCK_SUBSIDY_SATS)} sats")
                } else {
                    Text(
                        "Starta minern för att se dina odds. Beräknas från din hashrate och nätverkets svårighet.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            SectionCard {
                SectionLabel("Statistik")
                StatRow("Bästa svårighet (session)", formatDifficulty(ui.bestSession))
                StatRow("Bästa svårighet (totalt)", formatDifficulty(maxOf(ui.bestEver, ui.bestSession)))
                StatRow("Pool-svårighet (krav för andel)", formatDifficulty(ui.poolDifficulty))
                StatRow("Nätverkets svårighet", formatDifficulty(ui.networkDifficulty))
                StatRow("Hashar denna session", formatCount(ui.sessionHashes))
                StatRow("Andelar hittade / godkända / nekade", "${ui.sharesFound} / ${ui.sharesAccepted} / ${ui.sharesRejected}")
                if (ui.hashrate > 0 && ui.poolDifficulty > 0) {
                    val sec = (ui.poolDifficulty * TWO_32 / ui.hashrate).toLong()
                    StatRow("Förväntad tid per andel", formatDuration(sec))
                }
                ui.batteryTempC?.let { t ->
                    StatRow(
                        "Batteri",
                        "${"%.1f".format(t)} °C · " + if (ui.charging) "laddar" else "laddar inte",
                        valueColor = if (t >= settings.maxBatteryTempC) Negative else null,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "\"Bästa svårighet\" visar hur nära du kommit. Ett block kräver ${formatDifficulty(ui.networkDifficulty)}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (ui.log.isNotEmpty()) {
            item { SectionLabel("Logg") }
            items(ui.log) { line ->
                Text(
                    line,
                    fontFamily = Mono,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun StatRow(label: String, value: String, valueColor: Color? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Column {
            Text(value, fontFamily = Mono, color = valueColor ?: MaterialTheme.colorScheme.onSurface)
        }
    }
}
