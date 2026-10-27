package com.jixiexiaoge.drivingassist.data

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val MAX_STATUS_BYTES = 64L * 1024
private const val STATUS_PORT = 7000

internal class JetlinkStatusClient(private val client: OkHttpClient = defaultClient) {
    suspend fun read(host: String): JetlinkStatus {
        val url = runCatching {
            HttpUrl.Builder().scheme("http").host(host).port(STATUS_PORT)
                .addPathSegments("api/egpu/model").build()
        }.getOrElse { throw IOException("Invalid Jetlink endpoint", it) }
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(Request.Builder().url(url).header("Cache-Control", "no-cache").build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            check(it.isSuccessful) { "Jetlink HTTP ${it.code}" }
                            val body = checkNotNull(it.body) { "Empty Jetlink response" }
                            val source = body.source()
                            check(!source.request(MAX_STATUS_BYTES + 1)) { "Jetlink response too large" }
                            parseJetlinkStatus(source.readUtf8())
                        }
                    }
                    result.fold(continuation::resume) {
                        continuation.resumeWithException(IOException("Jetlink status unavailable", it))
                    }
                }
            })
        }
    }

    private companion object {
        val defaultClient = OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(1, TimeUnit.SECONDS)
            .callTimeout(1, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}
