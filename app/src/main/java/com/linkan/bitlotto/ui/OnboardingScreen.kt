package com.linkan.bitlotto.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private enum class OnbStep { Welcome, ShowNew, Restore }

@Composable
fun OnboardingScreen(vm: MainViewModel) {
    var step by rememberSaveable { mutableStateOf(OnbStep.Welcome) }
    var phrase by rememberSaveable { mutableStateOf("") }
    val state by vm.wallet.collectAsState()

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Text("₿", fontSize = 64.sp, color = BtcOrange, fontWeight = FontWeight.Bold)
            Text("BitLotto", style = MaterialTheme.typography.displaySmall)

            when (step) {
                OnbStep.Welcome -> {
                    Text(
                        "En egen bitcoin-plånbok och en solo-miner i samma app. " +
                            "Minern är ett lotteri: chansen att hitta ett block är extremt liten, men om det händer " +
                            "går hela blockbelöningen till din adress.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { phrase = vm.newPhrase(); step = OnbStep.ShowNew },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text("Skapa ny plånbok") }
                    OutlinedButton(
                        onClick = { phrase = ""; step = OnbStep.Restore },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text("Återställ med återställningsfras") }
                    Text(
                        "Plånboken startar på Testnet4 (låtsaspengar). Byt till Mainnet under Inställningar.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                OnbStep.ShowNew -> {
                    var confirmed by remember { mutableStateOf(false) }
                    Text("Din återställningsfras", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Skriv ner de 12 orden på papper i rätt ordning. Den som har orden har dina pengar. " +
                            "Ta aldrig skärmdump och dela dem aldrig med någon.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    WordGrid(phrase)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = confirmed, onCheckedChange = { confirmed = it })
                        Text("Jag har skrivit ner orden")
                    }
                    Button(
                        onClick = { vm.saveAndOpen(phrase) },
                        enabled = confirmed,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text("Skapa plånboken") }
                    TextButton(onClick = { step = OnbStep.Welcome }) { Text("Tillbaka") }
                }

                OnbStep.Restore -> {
                    Text("Återställ plånbok", style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(
                        value = phrase,
                        onValueChange = { phrase = it; vm.consumeMessage() },
                        label = { Text("12 eller 24 ord, separerade med mellanslag") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val valid = vm.validatePhrase(phrase) != null
                    if (phrase.isNotBlank() && !valid) {
                        Text("Frasen är inte giltig ännu", color = MaterialTheme.colorScheme.error)
                    }
                    state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(
                        onClick = { vm.saveAndOpen(phrase) },
                        enabled = valid,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text("Återställ") }
                    TextButton(onClick = { step = OnbStep.Welcome }) { Text("Tillbaka") }
                }
            }
        }
    }
}

@Composable
fun WordGrid(phrase: String) {
    val words = phrase.trim().split(Regex("\\s+"))
    SectionCard {
        words.chunked(2).forEachIndexed { row, pair ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                pair.forEachIndexed { col, w ->
                    val n = row * 2 + col + 1
                    Row(Modifier.weight(1f)) {
                        Text(
                            "$n.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(32.dp),
                            fontFamily = Mono,
                        )
                        Text(w, fontFamily = Mono, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
