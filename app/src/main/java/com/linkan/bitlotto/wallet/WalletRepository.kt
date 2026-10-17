package com.linkan.bitlotto.wallet

import android.content.Context
import com.linkan.bitlotto.Endpoints
import com.linkan.bitlotto.WalletNet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.bitcoindevkit.Address
import org.bitcoindevkit.Amount
import org.bitcoindevkit.ChainPosition
import org.bitcoindevkit.Descriptor
import org.bitcoindevkit.DescriptorSecretKey
import org.bitcoindevkit.EsploraClient
import org.bitcoindevkit.FeeRate
import org.bitcoindevkit.KeychainKind
import org.bitcoindevkit.Mnemonic
import org.bitcoindevkit.Network
import org.bitcoindevkit.NetworkKind
import org.bitcoindevkit.Persister
import org.bitcoindevkit.Transaction
import org.bitcoindevkit.TxBuilder
import org.bitcoindevkit.Wallet
import org.bitcoindevkit.WordCount
import java.io.File

data class TxItem(
    val txid: String,
    val netSats: Long,
    val feeSats: Long?,
    val confirmed: Boolean,
    val height: Long?,
    val timeSec: Long?,
)

data class WalletSnapshot(
    val network: WalletNet,
    val confirmedSats: Long,
    val pendingSats: Long,
    val immatureSats: Long,
    val totalSats: Long,
    val receiveAddress: String,
    val transactions: List<TxItem>,
)

data class FeeOptions(val fast: Long, val normal: Long, val slow: Long)

/** A signed but not yet broadcast transaction, shown to the user for confirmation. */
class PendingSend(
    val tx: Transaction,
    val address: String,
    val amountSats: Long,
    val feeSats: Long,
    val feeRate: Long,
)

/** All BDK work happens here, serialized by a mutex and run on the IO dispatcher. */
class WalletRepository(private val context: Context) {
    private val mutex = Mutex()
    private var wallet: Wallet? = null
    private var persister: Persister? = null
    private var net: WalletNet = WalletNet.TESTNET4

    val isOpen: Boolean get() = wallet != null
    val openNetwork: WalletNet get() = net

    fun newMnemonic(): String = Mnemonic(WordCount.WORDS12).toString()

    /** Throws if the phrase is not a valid BIP39 phrase. */
    fun normalizeMnemonic(phrase: String): String =
        Mnemonic.fromString(phrase.trim().lowercase().split(Regex("\\s+")).joinToString(" ")).toString()

    suspend fun open(phrase: String, network: WalletNet) = io {
        val kind = network.kind()
        val secret = DescriptorSecretKey(kind, Mnemonic.fromString(phrase), null)
        val external = Descriptor.newBip84(secret, KeychainKind.EXTERNAL, kind)
        val internal = Descriptor.newBip84(secret, KeychainKind.INTERNAL, kind)
        val file = dbFile(network)
        val exists = file.exists()
        val p = Persister.newSqlite(file.absolutePath)
        val w = if (exists) {
            Wallet.load(external, internal, p)
        } else {
            Wallet(external, internal, network.bdk(), p)
        }
        wallet = w
        persister = p
        net = network
    }

    fun close() {
        wallet = null
        persister = null
    }

    /** Deletes the local wallet databases (not the seed). */
    fun deleteDatabases() {
        close()
        context.getSharedPreferences("walletmeta", Context.MODE_PRIVATE).edit().clear().apply()
        WalletNet.entries.forEach { n ->
            dbFile(n).delete()
            File(dbFile(n).absolutePath + "-journal").delete()
            File(dbFile(n).absolutePath + "-wal").delete()
            File(dbFile(n).absolutePath + "-shm").delete()
        }
    }

    /**
     * First sync on a network does a full scan; later syncs only check revealed addresses,
     * which needs far fewer requests. Requests run one at a time (public servers rate-limit),
     * with retries and a fallback server.
     */
    suspend fun sync() = io {
        val w = requireWallet()
        val prefs = context.getSharedPreferences("walletmeta", Context.MODE_PRIVATE)
        val key = "scanned_${net.name}"
        val full = !prefs.getBoolean(key, false)
        withServer { client ->
            val update = if (full) {
                client.fullScan(w.startFullScan().build(), 10uL, 1uL)
            } else {
                client.sync(w.startSyncWithRevealedSpks().build(), 1uL)
            }
            w.applyUpdate(update)
        }
        w.persist(requirePersister())
        if (full) prefs.edit().putBoolean(key, true).apply()
    }

    /** Runs [block] against each server in turn, retrying transient failures. */
    private suspend fun <T> withServer(block: (EsploraClient) -> T): T {
        var last: Exception? = null
        for (base in Endpoints.esplora(net)) {
            for (attempt in 0 until 3) {
                try {
                    return block(EsploraClient(base))
                } catch (e: Exception) {
                    last = e
                    kotlinx.coroutines.delay(1500L * (attempt + 1))
                }
            }
        }
        throw IllegalStateException(describeError(last), last)
    }

    suspend fun snapshot(): WalletSnapshot = io {
        val w = requireWallet()
        val b = w.balance()
        val address = w.nextUnusedAddress(KeychainKind.EXTERNAL)
        w.persist(requirePersister())

        val txs = w.transactions().map { ct ->
            val sr = w.sentAndReceived(ct.transaction)
            val fee = runCatching { w.calculateFee(ct.transaction).toSat().toLong() }.getOrNull()
            val sent = sr.sent.toSat().toLong()
            val received = sr.received.toSat().toLong()
            when (val pos = ct.chainPosition) {
                is ChainPosition.Confirmed -> TxItem(
                    txid = ct.transaction.computeTxid().toString(),
                    netSats = received - sent,
                    feeSats = fee,
                    confirmed = true,
                    height = pos.confirmationBlockTime.blockId.height.toLong(),
                    timeSec = pos.confirmationBlockTime.confirmationTime.toLong(),
                )
                is ChainPosition.Unconfirmed -> TxItem(
                    txid = ct.transaction.computeTxid().toString(),
                    netSats = received - sent,
                    feeSats = fee,
                    confirmed = false,
                    height = null,
                    timeSec = pos.timestamp?.toLong(),
                )
            }
        }.sortedWith(compareBy<TxItem> { it.confirmed }.thenByDescending { it.height ?: Long.MAX_VALUE })

        WalletSnapshot(
            network = net,
            confirmedSats = b.confirmed.toSat().toLong(),
            pendingSats = (b.trustedPending.toSat() + b.untrustedPending.toSat()).toLong(),
            immatureSats = b.immature.toSat().toLong(),
            totalSats = b.total.toSat().toLong(),
            receiveAddress = address.address.toString(),
            transactions = txs,
        )
    }

    /** Returns a fresh address on the given network, used as mining payout. */
    suspend fun receiveAddress(): String = io {
        val w = requireWallet()
        val a = w.nextUnusedAddress(KeychainKind.EXTERNAL)
        w.persist(requirePersister())
        a.address.toString()
    }

    suspend fun feeOptions(): FeeOptions = io {
        val est = runCatching { withServer { it.getFeeEstimates() } }.getOrDefault(emptyMap())
        fun at(target: Int, fallback: Long): Long {
            val v = est[target.toUShort()] ?: return fallback
            return kotlin.math.ceil(v).toLong().coerceAtLeast(1L)
        }
        FeeOptions(fast = at(2, 10), normal = at(6, 4), slow = at(144, 1))
    }

    fun isValidAddress(address: String, network: WalletNet): Boolean =
        runCatching { Address(address.trim(), network.bdk()) }.isSuccess

    /** Builds and signs a transaction; nothing is broadcast yet. */
    suspend fun prepareSend(address: String, amountSats: Long, sendAll: Boolean, feeRate: Long): PendingSend = io {
        val w = requireWallet()
        val addr = try {
            Address(address.trim(), net.bdk())
        } catch (e: Exception) {
            throw IllegalArgumentException("Ogiltig adress för ${net.label}")
        }
        val script = addr.scriptPubkey()
        val base = TxBuilder().feeRate(FeeRate.fromSatPerVb(feeRate.toULong()))
        val builder = if (sendAll) {
            base.drainWallet().drainTo(script)
        } else {
            require(amountSats > 0) { "Beloppet måste vara större än 0" }
            base.addRecipient(script, Amount.fromSat(amountSats.toULong()))
        }
        val psbt = builder.finish(w)
        val signed = w.sign(psbt)
        check(signed) { "Transaktionen kunde inte signeras" }
        val tx = psbt.extractTx()
        val fee = w.calculateFee(tx).toSat().toLong()
        val sr = w.sentAndReceived(tx)
        val toRecipient = sr.sent.toSat().toLong() - sr.received.toSat().toLong() - fee
        PendingSend(tx, address.trim(), toRecipient, fee, feeRate)
    }

    suspend fun broadcast(p: PendingSend): String = io {
        withServer { it.broadcast(p.tx) }
        val txid = p.tx.computeTxid().toString()
        runCatching {
            val w = requireWallet()
            withServer { w.applyUpdate(it.sync(w.startSyncWithRevealedSpks().build(), 1uL)) }
            w.persist(requirePersister())
        }
        txid
    }

    fun explorerTxUrl(txid: String): String = when (net) {
        WalletNet.MAINNET -> "https://mempool.space/tx/$txid"
        WalletNet.TESTNET4 -> "https://mempool.space/testnet4/tx/$txid"
    }


    private fun dbFile(n: WalletNet) = File(context.filesDir, "wallet_${n.name.lowercase()}.sqlite")

    private fun requireWallet() = wallet ?: error("Plånboken är inte öppen")
    private fun requirePersister() = persister ?: error("Plånboken är inte öppen")

    private suspend fun <T> io(block: suspend () -> T): T =
        withContext(Dispatchers.IO) { mutex.withLock { block() } }
}

fun WalletNet.bdk(): Network = when (this) {
    WalletNet.MAINNET -> Network.BITCOIN
    WalletNet.TESTNET4 -> Network.TESTNET4
}

fun WalletNet.kind(): NetworkKind = when (this) {
    WalletNet.MAINNET -> NetworkKind.MAIN
    WalletNet.TESTNET4 -> NetworkKind.TEST
}

/** Readable text for BDK/network errors, including the underlying cause. */
fun describeError(e: Throwable?): String {
    if (e == null) return "Okänt fel"
    val text = e.toString().removePrefix("org.bitcoindevkit.")
    return when {
        text.contains("429") -> "Servern begränsar antalet anrop (HTTP 429). Vänta en minut och försök igen."
        text.contains("UnknownHost", true) || text.contains("dns", true) ||
            text.contains("resolve", true) -> "Ingen internetanslutning eller DNS-fel: $text"
        text.contains("timed out", true) || text.contains("timeout", true) -> "Timeout mot servern: $text"
        else -> text.take(300)
    }
}
