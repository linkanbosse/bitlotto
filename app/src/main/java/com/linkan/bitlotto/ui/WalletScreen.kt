@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.linkan.bitlotto.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.linkan.bitlotto.WalletNet
import com.linkan.bitlotto.wallet.TxItem
import com.linkan.bitlotto.wallet.WalletSnapshot

@Composable
fun WalletScreen(vm: MainViewModel) {
    val state by vm.wallet.collectAsState()
    when (val phase = state.phase) {
        WalletPhase.Starting, WalletPhase.Opening, WalletPhase.NoWallet -> Box(
            Modifier.fillMaxSize(), contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text("Öppnar plånboken…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        is WalletPhase.Failed -> Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Plånboken kunde inte öppnas", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(phase.message, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(16.dp))
            Button(onClick = { vm.openWallet() }) { Text("Försök igen") }
        }

        is WalletPhase.Ready -> WalletContent(vm, phase.snapshot, state)
    }
}

@Composable
private fun WalletContent(vm: MainViewModel, snap: WalletSnapshot, state: WalletUiState) {
    var showReceive by remember { mutableStateOf(false) }
    var showSend by remember { mutableStateOf(false) }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Plånbok", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                NetworkBadge(snap.network)
                Spacer(Modifier.width(4.dp))
                if (state.syncing) {
                    CircularProgressIndicator(Modifier.size(24.dp).padding(2.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                } else {
                    IconButton(onClick = { vm.sync() }) { Icon(Icons.Filled.Refresh, "Synka") }
                }
            }
        }
        item {
            SectionCard {
                SectionLabel("Saldo")
                Text(formatBtc(snap.totalSats), style = MaterialTheme.typography.headlineMedium, fontFamily = Mono)
                Text(formatSats(snap.totalSats), color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (snap.pendingSats != 0L) {
                    Spacer(Modifier.height(6.dp))
                    Text("Väntar på bekräftelse: ${formatSats(snap.pendingSats)}", color = BtcOrange)
                }
                if (snap.immatureSats != 0L) {
                    Text("Omogen blockbelöning: ${formatSats(snap.immatureSats)} (låses i 100 block)", color = BtcOrange)
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(onClick = { showReceive = true }, modifier = Modifier.weight(1f).height(48.dp)) {
                        Icon(Icons.Filled.ArrowDownward, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Ta emot")
                    }
                    Button(
                        onClick = { vm.resetSend(); vm.loadFees(); showSend = true },
                        modifier = Modifier.weight(1f).height(48.dp),
                    ) {
                        Icon(Icons.Filled.ArrowUpward, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Skicka")
                    }
                }
            }
        }
        state.lastSyncError?.let { err ->
            item { Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
        if (snap.network == WalletNet.TESTNET4) {
            item {
                Text(
                    "Testnet4: hämta gratis test-bitcoin från en testnet4-faucet till din mottagaradress.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item { SectionLabel("Transaktioner") }
        if (snap.transactions.isEmpty()) {
            item {
                Text(
                    if (state.syncing) "Synkar…" else "Inga transaktioner än.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(snap.transactions, key = { it.txid }) { tx -> TxRow(tx, vm) }
    }

    if (showReceive) ReceiveDialog(snap.receiveAddress) { showReceive = false }
    if (showSend) SendDialog(vm, snap) { showSend = false; vm.resetSend() }
}

@Composable
fun NetworkBadge(net: WalletNet) {
    val (text, color) = when (net) {
        WalletNet.MAINNET -> "MAINNET" to BtcOrange
        WalletNet.TESTNET4 -> "TESTNET4" to Color(0xFF6EA8FE)
    }
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun TxRow(tx: TxItem, vm: MainViewModel) {
    val uri = LocalUriHandler.current
    val incoming = tx.netSats >= 0
    SectionCard(Modifier.clickable {
        runCatching { uri.openUri(vmExplorer(vm, tx.txid)) }
    }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(36.dp).clip(CircleShape)
                    .background((if (incoming) Positive else Negative).copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (incoming) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
                    null,
                    tint = if (incoming) Positive else Negative,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (incoming) "Mottaget" else "Skickat", fontWeight = FontWeight.SemiBold)
                Text(
                    when {
                        tx.confirmed && tx.timeSec != null -> formatTimestamp(tx.timeSec) + " · block ${tx.height}"
                        tx.confirmed -> "Bekräftad"
                        else -> "Obekräftad"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (tx.confirmed) MaterialTheme.colorScheme.onSurfaceVariant else BtcOrange,
                )
            }
            Text(
                (if (incoming) "+" else "") + formatSats(tx.netSats),
                color = if (incoming) Positive else Negative,
                fontFamily = Mono,
            )
        }
    }
}

private fun vmExplorer(vm: MainViewModel, txid: String): String =
    when (vm.settings.current.network) {
        WalletNet.MAINNET -> "https://mempool.space/tx/$txid"
        WalletNet.TESTNET4 -> "https://mempool.space/testnet4/tx/$txid"
    }

@Composable
private fun ReceiveDialog(address: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    val qr = remember(address) { qrBitmap("bitcoin:$address") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ta emot bitcoin") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    qr, contentDescription = "QR-kod",
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)),
                )
                Spacer(Modifier.height(12.dp))
                Text(address, fontFamily = Mono, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            TextButton(onClick = { clipboard.setText(AnnotatedString(address)); copied = true }) {
                Icon(Icons.Filled.ContentCopy, null)
                Spacer(Modifier.width(6.dp))
                Text(if (copied) "Kopierad" else "Kopiera")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Stäng") } },
    )
}

@Composable
private fun SendDialog(vm: MainViewModel, snap: WalletSnapshot, onDismiss: () -> Unit) {
    val send by vm.send.collectAsState()
    val fees by vm.fees.collectAsState()
    val clipboard = LocalClipboardManager.current
    val uri = LocalUriHandler.current

    var address by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("") }
    var sendAll by remember { mutableStateOf(false) }
    var feeChoice by remember { mutableStateOf(1) }
    var customFee by remember { mutableStateOf("") }

    val feeRate: Long? = when (feeChoice) {
        0 -> fees?.fast
        1 -> fees?.normal
        2 -> fees?.slow
        else -> customFee.toLongOrNull()?.takeIf { it >= 1 }
    } ?: if (feeChoice in 0..2) listOf(10L, 4L, 1L)[feeChoice] else null

    when (val s = send) {
        is SendState.Review -> {
            val p = s.pending
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Bekräfta betalning") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (snap.network == WalletNet.MAINNET) {
                            Text("Riktiga bitcoin – kan inte ångras.", color = BtcOrange, fontWeight = FontWeight.SemiBold)
                        }
                        Text("Till", style = MaterialTheme.typography.labelSmall)
                        Text(p.address, fontFamily = Mono)
                        Text("Belopp: ${formatSats(p.amountSats)}")
                        Text("Avgift: ${formatSats(p.feeSats)} (${p.feeRate} sat/vB)")
                        Text("Totalt: ${formatSats(p.amountSats + p.feeSats)}", fontWeight = FontWeight.Bold)
                    }
                },
                confirmButton = { Button(onClick = { vm.confirmSend(p) }) { Text("Skicka nu") } },
                dismissButton = { TextButton(onClick = { vm.resetSend() }) { Text("Ändra") } },
            )
            return
        }

        is SendState.Done -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Skickat ✓") },
                text = {
                    Column {
                        Text("Transaktions-ID:")
                        Text(s.txid, fontFamily = Mono, style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = { TextButton(onClick = { runCatching { uri.openUri(s.explorerUrl) } }) { Text("Visa i mempool") } },
                dismissButton = { TextButton(onClick = onDismiss) { Text("Klar") } },
            )
            return
        }

        else -> Unit
    }

    val amount = amountText.filter { it.isDigit() }.toLongOrNull() ?: 0L
    val addressOk = address.isNotBlank()
    val canReview = addressOk && (sendAll || amount > 0) && feeRate != null && send !is SendState.Working

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Skicka") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Tillgängligt: ${formatSats(snap.confirmedSats + snap.pendingSats)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it.trim() },
                    label = { Text("Mottagarens adress") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        TextButton(onClick = {
                            clipboard.getText()?.text?.let { t ->
                                address = t.trim().removePrefix("bitcoin:").substringBefore("?")
                            }
                        }) { Text("Klistra") }
                    },
                )
                OutlinedTextField(
                    value = if (sendAll) "" else amountText,
                    onValueChange = { amountText = it.filter { c -> c.isDigit() } },
                    label = { Text("Belopp i sats") },
                    enabled = !sendAll,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = { if (!sendAll && amount > 0) Text(formatBtc(amount)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = sendAll, onCheckedChange = { sendAll = it })
                    Text("Skicka allt (avgiften dras från beloppet)")
                }
                Text("Avgift", style = MaterialTheme.typography.labelSmall)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    val labels = listOf(
                        "Snabb ${fees?.fast ?: "…"}", "Normal ${fees?.normal ?: "…"}",
                        "Låg ${fees?.slow ?: "…"}", "Egen",
                    )
                    labels.forEachIndexed { i, l ->
                        FilterChip(selected = feeChoice == i, onClick = { feeChoice = i }, label = {
                            Text(l, maxLines = 1, overflow = TextOverflow.Clip)
                        })
                    }
                }
                Text("sat/vB", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (feeChoice == 3) {
                    OutlinedTextField(
                        value = customFee,
                        onValueChange = { customFee = it.filter { c -> c.isDigit() } },
                        label = { Text("Egen avgift (sat/vB)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (send is SendState.Error) {
                    Text((send as SendState.Error).message, color = MaterialTheme.colorScheme.error)
                }
                if (send is SendState.Working) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Arbetar…")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = canReview,
                onClick = { vm.prepareSend(address, amount, sendAll, feeRate ?: 1L) },
            ) { Text("Granska") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Avbryt") } },
    )
}
