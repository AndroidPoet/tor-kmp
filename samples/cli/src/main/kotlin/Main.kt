package io.github.androidpoet.tor.sample

import io.github.androidpoet.tor.Bridge
import io.github.androidpoet.tor.Isolation
import io.github.androidpoet.tor.Tor
import io.github.androidpoet.tor.TorConfig
import io.github.androidpoet.tor.TorState
import io.github.androidpoet.tor.ktor.TorEngine
import io.github.androidpoet.tor.lyrebird.Lyrebird
import io.github.androidpoet.tor.lyrebird.transport
import io.github.androidpoet.tor.okhttp.tor
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

private const val CHECK_URL = "https://check.torproject.org/api/ip"

fun main(args: Array<String>) =
    runBlocking {
        val bridgeFile =
            args
                .toList()
                .windowed(2)
                .firstOrNull { it[0] == "--bridges" }
                ?.get(1)
        val bridges = bridgeFile?.let { File(it).readLines().filter(String::isNotBlank).map(Bridge::parse) }.orEmpty()
        val config =
            TorConfig(
                dataDirectory = File(System.getProperty("user.home"), ".tor-kmp-sample").path,
                bridges = bridges,
                transports = if (bridges.any { it.transport != null }) listOf(Lyrebird.transport()) else emptyList(),
            )
        println("data directory: ${config.dataDirectory}; bridges: ${bridges.size}")

        Tor(config).use { tor ->
            val progress =
                launch {
                    tor.state.collect { state ->
                        when (state) {
                            is TorState.Bootstrapping ->
                                println("bootstrapping ${(state.progress * 100).toInt()}%" + (state.blockage?.let { "  (${it.kind}: ${it.message})" } ?: ""))
                            else -> println("state: $state")
                        }
                    }
                }
            tor.bootstrap()
            progress.cancel()

            // 1. Raw stream with TLS done inside the Tor client.
            val raw =
                withContext(Dispatchers.IO) {
                    tor.connectTls("check.torproject.org", isolation = Isolation("raw")).use { stream ->
                        stream.write("GET /api/ip HTTP/1.1\r\nHost: check.torproject.org\r\nConnection: close\r\n\r\n".encodeToByteArray())
                        val buffer = ByteArray(16 * 1024)
                        buildString {
                            while (true) {
                                val count = stream.read(buffer)
                                if (count < 0) break
                                append(buffer.decodeToString(0, count))
                            }
                        }
                    }
                }
            println("stream:  " + raw.substringAfterLast("\n").trim())

            // 2. Ktor, no proxy.
            HttpClient(TorEngine) {
                engine {
                    this.tor = tor
                    isolation = Isolation.random()
                }
            }.use { client ->
                println("ktor:    " + client.get(CHECK_URL).bodyAsText().trim())
            }

            // 3. OkHttp, no proxy.
            val okHttp = OkHttpClient.Builder().tor(tor, Isolation("okhttp")).build()
            okHttp.newCall(Request.Builder().url(CHECK_URL).build()).execute().use { response ->
                println("okhttp:  " + response.body.string().trim())
            }
            okHttp.dispatcher.executorService.shutdown()
            okHttp.connectionPool.evictAll()
        }
    }
