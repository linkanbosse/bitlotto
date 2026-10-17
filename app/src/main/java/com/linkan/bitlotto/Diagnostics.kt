package com.linkan.bitlotto

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

data class DiagResult(val name: String, val ok: Boolean, val detail: String)

/** Tests the exact network paths the wallet and the miner use. */
object Diagnostics {

    suspend fun run(settings: SettingsState): List<DiagResult> = withContext(Dispatchers.IO) {
        coroutineScope {
            val http = Endpoints.esplora(settings.network).map { base ->
                async { httpTest("Plånbok: $base", "$base/blocks/tip/height") }
            }
            val pools = Endpoints.pools(settings).map { (host, port) ->
                async { stratumTest(host, port) }
            }
            (http + pools).awaitAll()
        }
    }

    private fun httpTest(name: String, url: String): DiagResult = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        c.setRequestProperty("User-Agent", "BitLotto")
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)
            ?.bufferedReader()?.use { it.readText().take(80) } ?: ""
        c.disconnect()
        if (code in 200..299) DiagResult(name, true, "OK – blockhöjd $body")
        else DiagResult(name, false, "HTTP $code ${if (code == 429) "(för många anrop – vänta en stund)" else body}")
    } catch (e: Exception) {
        DiagResult(name, false, describe(e))
    }

    private fun stratumTest(host: String, port: Int): DiagResult {
        val name = "Pool: $host:$port"
        return try {
            Socket().use { s ->
                val t0 = System.currentTimeMillis()
                s.connect(InetSocketAddress(host, port), 8_000)
                s.soTimeout = 8_000
                val w = OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8)
                w.write("{\"id\":1,\"method\":\"mining.subscribe\",\"params\":[\"BitLotto/1.0\"]}\n")
                w.flush()
                val line = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8)).readLine()
                val ms = System.currentTimeMillis() - t0
                if (line != null && line.contains("result")) DiagResult(name, true, "OK – poolen svarade ($ms ms)")
                else DiagResult(name, false, "Ansluten men oväntat svar: ${line?.take(60)}")
            }
        } catch (e: Exception) {
            DiagResult(name, false, describe(e))
        }
    }

    fun describe(e: Throwable): String = when (e) {
        is java.net.UnknownHostException -> "Hittar inte servern (DNS / ingen internet)"
        is java.net.SocketTimeoutException -> "Timeout – inget svar (porten kan vara blockerad av nätet)"
        is java.net.ConnectException -> "Anslutning nekad: ${e.message}"
        is javax.net.ssl.SSLException -> "TLS-fel: ${e.message}"
        else -> e.toString()
    }
}

/** Server lists shared by the wallet, the miner and diagnostics. */
object Endpoints {
    fun esplora(net: WalletNet): List<String> = when (net) {
        WalletNet.MAINNET -> listOf("https://mempool.space/api", "https://blockstream.info/api")
        WalletNet.TESTNET4 -> listOf("https://mempool.space/testnet4/api")
    }

    /** The chosen pool first, then the other presets (and ckpool's alternative port) as fallbacks. */
    fun pools(s: SettingsState): List<Pair<String, Int>> {
        val list = mutableListOf(s.poolHost to s.poolPort)
        POOL_PRESETS.forEach { list += it.host to it.port }
        list += "solo.ckpool.org" to 4334
        return list.distinct()
    }
}
