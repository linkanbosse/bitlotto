@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.linkan.bitlotto.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.linkan.bitlotto.BuildConfig
import com.linkan.bitlotto.POOL_PRESETS
import com.linkan.bitlotto.WalletNet
import com.linkan.bitlotto.mining.MinerState
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val s by vm.settings.state.collectAsState()
    val miner by MinerState.ui.collectAsState()
    val clipboard = LocalClipboardManager.current
    val cores = Runtime.getRuntime().availableProcessors()

    var confirmMainnet by remember { mutableStateOf(false) }
    var showPhrase by remember { mutableStateOf<String?>(null) }
    var askPhrase by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf(false) }
    var addressDraft by remember(s.payoutAddress) { mutableStateOf(s.payoutAddress) }
    var hostDraft by remember(s.poolHost) { mutableStateOf(s.poolHost) }
    var portDraft by remember(s.poolPort) { mutableStateOf(s.poolPort.toString()) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Inställningar", style = MaterialTheme.typography.headlineMedium)

        SectionCard {
            SectionLabel("Plånbokens nätverk")
            WalletNet.entries.forEach { n ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(n.label, modifier = Modifier.weight(1f))
                    Switch(
                        checked = s.network == n,
                        onCheckedChange = { on ->
                            if (on && n == WalletNet.MAINNET) confirmMainnet = true
                            else if (on) vm.switchNetwork(n)
                        },
                    )
                }
            }
            Text(
                "Samma återställningsfras används på båda nätverken. Testnet-mynt har inget värde.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard {
            SectionLabel("Utbetalningsadress för mining (mainnet)")
            OutlinedTextField(
                value = addressDraft,
                onValueChange = { addressDraft = it.trim() },
                label = { Text("bc1… adress") },
                singleLine = true,
                isError = addressDraft.isNotBlank() && !vm.isValidMainnetAddress(addressDraft),
                supportingText = {
                    if (addressDraft.isNotBlank() && !vm.isValidMainnetAddress(addressDraft)) Text("Inte en giltig mainnet-adress")
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                Button(
                    onClick = { vm.settings.update { it.copy(payoutAddress = addressDraft) } },
                    enabled = addressDraft != s.payoutAddress && (addressDraft.isBlank() || vm.isValidMainnetAddress(addressDraft)),
                ) { Text("Spara") }
                OutlinedButton(onClick = {
                    clipboard.getText()?.text?.let { addressDraft = it.trim().removePrefix("bitcoin:").substringBefore("?") }
                }) { Text("Klistra in") }
                OutlinedButton(
                    onClick = { vm.useWalletAddressForMining() },
                    enabled = s.network == WalletNet.MAINNET,
                ) { Text("Använd plånboken") }
            }
            if (s.network != WalletNet.MAINNET) {
                Text(
                    "Plånboken är på Testnet4. Byt till Mainnet för att använda plånbokens adress, eller klistra in en egen mainnet-adress.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionCard {
            SectionLabel("Solo-pool")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                POOL_PRESETS.forEach { p ->
                    FilterChip(
                        selected = s.poolHost == p.host && s.poolPort == p.port,
                        onClick = { vm.settings.update { it.copy(poolHost = p.host, poolPort = p.port, userSuffix = p.userSuffix) } },
                        label = { Text(p.name) },
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = hostDraft, onValueChange = { hostDraft = it.trim() },
                    label = { Text("Värd") }, singleLine = true, modifier = Modifier.weight(2f),
                )
                OutlinedTextField(
                    value = portDraft, onValueChange = { portDraft = it.filter { c -> c.isDigit() }.take(5) },
                    label = { Text("Port") }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            val port = portDraft.toIntOrNull()
            TextButton(
                onClick = { vm.settings.update { it.copy(poolHost = hostDraft, poolPort = port ?: it.poolPort) } },
                enabled = hostDraft.isNotBlank() && port != null && port in 1..65535 &&
                    (hostDraft != s.poolHost || port != s.poolPort),
            ) { Text("Spara egen pool") }
            Text(
                "Om poolen inte svarar provar minern automatiskt de andra poolerna.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (miner.running) {
                Text("Ändringar gäller nästa gång minern startas.", style = MaterialTheme.typography.bodySmall, color = BtcOrange)
            }
        }

        SectionCard {
            SectionLabel("Prestanda och värme")
            Text("Trådar: ${s.threads} av $cores kärnor")
            Slider(
                value = s.threads.toFloat(),
                onValueChange = { v -> vm.settings.update { it.copy(threads = v.roundToInt().coerceIn(1, cores)) } },
                valueRange = 1f..cores.toFloat(),
                steps = (cores - 2).coerceAtLeast(0),
            )
            Text("Max batteritemperatur: ${s.maxBatteryTempC} °C")
            Slider(
                value = s.maxBatteryTempC.toFloat(),
                onValueChange = { v -> vm.settings.update { it.copy(maxBatteryTempC = v.roundToInt()) } },
                valueRange = 35f..45f,
                steps = 9,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Mina bara när telefonen laddar", modifier = Modifier.weight(1f))
                Switch(checked = s.onlyWhenCharging, onCheckedChange = { on -> vm.settings.update { it.copy(onlyWhenCharging = on) } })
            }
            Text(
                "Fler trådar ger högre hashrate men mer värme. Minern pausar automatiskt vid för hög temperatur.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard {
            SectionLabel("Felsökning")
            val diag by vm.diag.collectAsState()
            val diagRunning by vm.diagRunning.collectAsState()
            Text(
                "Testar exakt de servrar plånboken och minern använder.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { vm.runDiagnostics() }, enabled = !diagRunning, modifier = Modifier.fillMaxWidth()) {
                Text(if (diagRunning) "Testar… (upp till 20 s)" else "Testa anslutningar")
            }
            diag?.forEach { r ->
                Spacer(Modifier.height(8.dp))
                Text((if (r.ok) "✅ " else "❌ ") + r.name, fontWeight = FontWeight.SemiBold)
                Text(
                    r.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (r.ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
            }
        }

        SectionCard {
            SectionLabel("Säkerhet")
            OutlinedButton(onClick = { askPhrase = true }, modifier = Modifier.fillMaxWidth()) { Text("Visa återställningsfras") }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { askDelete = true },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Radera plånboken från telefonen") }
        }

        Text(
            "BitLotto ${BuildConfig.VERSION_NAME} · BDK-plånbok · Stratum v1 solo-miner",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
    }

    if (confirmMainnet) {
        AlertDialog(
            onDismissRequest = { confirmMainnet = false },
            title = { Text("Byta till Mainnet?") },
            text = {
                Text(
                    "Mainnet använder riktiga bitcoin. Se till att du har skrivit ner din återställningsfras. " +
                        "Appen är egenbyggd och inte granskad – förvara bara små belopp här."
                )
            },
            confirmButton = {
                Button(onClick = { confirmMainnet = false; vm.switchNetwork(WalletNet.MAINNET) }) { Text("Byt till Mainnet") }
            },
            dismissButton = { TextButton(onClick = { confirmMainnet = false }) { Text("Avbryt") } },
        )
    }

    if (askPhrase) {
        AlertDialog(
            onDismissRequest = { askPhrase = false },
            title = { Text("Visa återställningsfras?") },
            text = { Text("Se till att ingen annan ser skärmen.") },
            confirmButton = { Button(onClick = { askPhrase = false; showPhrase = vm.revealPhrase() }) { Text("Visa") } },
            dismissButton = { TextButton(onClick = { askPhrase = false }) { Text("Avbryt") } },
        )
    }

    showPhrase?.let { p ->
        AlertDialog(
            onDismissRequest = { showPhrase = null },
            title = { Text("Återställningsfras") },
            text = { WordGrid(p) },
            confirmButton = { TextButton(onClick = { showPhrase = null }) { Text("Stäng") } },
        )
    }

    if (askDelete) {
        var typed by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { askDelete = false },
            title = { Text("Radera plånboken?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Utan återställningsfrasen förlorar du dina pengar för alltid. Skriv RADERA för att bekräfta.")
                    OutlinedTextField(value = typed, onValueChange = { typed = it }, singleLine = true)
                }
            },
            confirmButton = {
                Button(
                    onClick = { askDelete = false; vm.deleteWallet() },
                    enabled = typed.trim() == "RADERA",
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Radera") }
            },
            dismissButton = { TextButton(onClick = { askDelete = false }) { Text("Avbryt") } },
        )
    }
}
