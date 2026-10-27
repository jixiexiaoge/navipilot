package com.navipilot.amap2tesla.membership

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.JsonParseException
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.jixiexiaoge.drivingassist.core.ErrorCode
import com.jixiexiaoge.drivingassist.core.Result
import com.jixiexiaoge.drivingassist.core.SecurePrefs
import com.jixiexiaoge.drivingassist.core.runSafely
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.util.UUID

internal fun projectionMembershipHttpClient(): OkHttpClient =
    OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

internal fun projectionMembershipUrl(baseUrl: String, path: String): HttpUrl {
    val url = "$baseUrl$path".toHttpUrl()
    if (!url.isHttps) throw IOException("会员服务必须使用 HTTPS")
    return url
}

data class ProjectionPlan(
    val code: String,
    val name: String,
    val priceCents: Int,
    val durationDays: Int?,
)

data class ProjectionEntitlement(
    val enabled: Boolean,
    val isLifetime: Boolean,
    val expiresAt: String?,
)

data class ProjectionMembership(
    val plans: List<ProjectionPlan>,
    val entitlement: ProjectionEntitlement,
)

data class ProjectionCheckout(
    val orderNo: String,
    val checkoutUrl: String,
)

object ProjectionPermanentUnlock {
    private const val PREF_PERMANENT_UNLOCK = "projection_permanent_unlock"
    private const val UNLOCK_CODE = "xiaoge"

    fun isActive(context: Context): Boolean =
        SecurePrefs.get(context.applicationContext).getBoolean(PREF_PERMANENT_UNLOCK, false)

    fun redeem(context: Context, code: String): Boolean {
        if (code.trim() != UNLOCK_CODE) return false
        SecurePrefs.get(context.applicationContext)
            .edit()
            .putBoolean(PREF_PERMANENT_UNLOCK, true)
            .apply()
        return true
    }

    fun entitlement(): ProjectionEntitlement =
        ProjectionEntitlement(enabled = true, isLifetime = true, expiresAt = null)
}

class ProjectionMembershipClient(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = SecurePrefs.get(appContext)

    /**
     * 敏感凭证（device_token）专用存储：只在 EncryptedSharedPreferences 真正可用时返回实例。
     * Keystore 初始化失败被降级成明文时返回 null —— 此时宁可每轮重新注册设备，
     * 也不把长期凭证明文落盘。
     */
    private val securePreferences: SharedPreferences?
        get() = SecurePrefs.getEncryptedOnly(appContext)

    suspend fun loadMembership(): Result<ProjectionMembership> = withContext(Dispatchers.IO) {
        if (ProjectionPermanentUnlock.isActive(appContext)) {
            return@withContext Result.Success(
                ProjectionMembership(emptyList(), ProjectionPermanentUnlock.entitlement())
            )
        }
        runSafely(ErrorCode.NETWORK_CONNECTION_FAILED) {
            val plans = fetchPlans()
            ProjectionMembership(plans, fetchEntitlement())
        }
    }

    suspend fun createOrder(planCode: String): Result<ProjectionCheckout> = withContext(Dispatchers.IO) {
        runSafely(ErrorCode.NETWORK_CONNECTION_FAILED) {
            val response = authenticatedRequest(
                path = "/api/v1/orders",
                method = "POST",
                // 用 JsonObject 构造，不要字符串拼接：planCode 会被原样拼进 JSON
                body = JsonObject().apply {
                    addProperty("plan_code", planCode)
                    addProperty("payment_type", "alipay")
                }.toString(),
            )
            ProjectionCheckout(
                orderNo = response.requiredString("order_no"),
                checkoutUrl = response.requiredString("checkout_url"),
            )
        }
    }

    suspend fun refreshOrder(orderNo: String): Result<ProjectionEntitlement> = withContext(Dispatchers.IO) {
        runSafely(ErrorCode.NETWORK_CONNECTION_FAILED) {
            val response = authenticatedRequest(
                path = "/api/v1/orders/$orderNo/refresh",
                method = "POST",
            )
            response.getAsJsonObject("license").toEntitlement()
        }
    }

    suspend fun createTransferCode(): Result<String> = withContext(Dispatchers.IO) {
        runSafely(ErrorCode.NETWORK_CONNECTION_FAILED) {
            authenticatedRequest("/api/v1/licenses/transfer-codes", "POST")
                .requiredString("transfer_code")
        }
    }

    suspend fun createRecoveryCode(): Result<String> = withContext(Dispatchers.IO) {
        runSafely(ErrorCode.NETWORK_CONNECTION_FAILED) {
            authenticatedRequest("/api/v1/licenses/recovery-code", "POST")
                .requiredString("recovery_code")
        }
    }

    suspend fun transferLicense(transferCode: String): Result<ProjectionEntitlement> =
        submitLicenseCode("/api/v1/licenses/transfer", "transfer_code", transferCode)

    suspend fun recoverLicense(recoveryCode: String): Result<ProjectionEntitlement> =
        submitLicenseCode("/api/v1/licenses/recover", "recovery_code", recoveryCode)

    private suspend fun submitLicenseCode(
        path: String,
        key: String,
        value: String,
    ): Result<ProjectionEntitlement> = withContext(Dispatchers.IO) {
        runSafely(ErrorCode.NETWORK_CONNECTION_FAILED) {
            val response = authenticatedRequest(
                path = path,
                method = "POST",
                // 兑换/恢复码来自键盘输入，字符串拼接会被引号/反斜杠破坏或注入字段
                body = JsonObject().apply { addProperty(key, value.trim()) }.toString(),
            )
            response.getAsJsonObject("license").toEntitlement()
        }
    }

    private fun fetchPlans(): List<ProjectionPlan> {
        val root = request("/api/v1/plans").also { requireSuccessful(it) }
        return root.getAsJsonArray("plans").map { item ->
            item.asJsonObject.let {
                ProjectionPlan(
                    code = it.requiredString("code"),
                    name = it.requiredString("name"),
                    priceCents = it.requiredInt("price_cents"),
                    durationDays = it.get("duration_days").takeUnless { value -> value.isJsonNull }
                        ?.asInt,
                )
            }
        }
    }

    private fun fetchEntitlement(retryAfterRegistration: Boolean = true): ProjectionEntitlement {
        val root = request("/api/v1/entitlements/projection", authorizationToken())
        if (root.requiredInt("http_status") == 401 && retryAfterRegistration) {
            securePreferences?.edit()?.remove(PREF_DEVICE_TOKEN)?.apply()
            registerDevice()
            return fetchEntitlement(retryAfterRegistration = false)
        }
        requireSuccessful(root)
        return root.toEntitlement()
    }

    private fun authenticatedRequest(path: String, method: String, body: String? = null): JsonObject {
        var response = request(path, authorizationToken(), method, body)
        if (response.requiredInt("http_status") == 401) {
            securePreferences?.edit()?.remove(PREF_DEVICE_TOKEN)?.apply()
            registerDevice()
            response = request(path, authorizationToken(), method, body)
        }
        requireSuccessful(response)
        return response
    }

    private fun authorizationToken(): String {
        return securePreferences?.getString(PREF_DEVICE_TOKEN, null) ?: registerDevice()
    }

    private fun registerDevice(): String {
        val installationId = preferences.getString(PREF_INSTALLATION_ID, null)
            ?: UUID.randomUUID().toString().also {
                preferences.edit().putString(PREF_INSTALLATION_ID, it).apply()
            }
        val root = request(
            path = "/api/v1/devices/register",
            method = "POST",
            body = JsonObject().apply { addProperty("installation_id", installationId) }.toString(),
        )
        requireSuccessful(root)
        return root.requiredString("device_token").also { token ->
            securePreferences?.edit()?.putString(PREF_DEVICE_TOKEN, token)?.apply()
        }
    }

    private fun request(
        path: String,
        bearerToken: String? = null,
        method: String = "GET",
        body: String? = null,
    ): JsonObject {
        try {
            val response = requestAt(PRIMARY_BASE_URL, path, bearerToken, method, body)
            if (response.requiredInt("http_status") < 500) return response
            throw IOException("会员服务暂时不可用（${response.requiredInt("http_status")}）")
        } catch (error: JsonParseException) {
            throw IOException("会员服务返回了无效数据", error)
        }
    }

    private fun requestAt(
        baseUrl: String,
        path: String,
        bearerToken: String?,
        method: String,
        body: String?,
    ): JsonObject {
        val requestBuilder = Request.Builder().url(projectionMembershipUrl(baseUrl, path))
        bearerToken?.let { requestBuilder.header("Authorization", "Bearer $it") }
        when (method) {
            "GET" -> requestBuilder.get()
            "POST" -> requestBuilder.post(
                (body ?: "").toRequestBody(JSON_MEDIA_TYPE)
            )
            else -> error("Unsupported HTTP method: $method")
        }
        return httpClient.newCall(requestBuilder.build()).execute().use { response ->
            val responseBody = response.body?.string()
                ?: throw IOException("会员服务未返回响应内容")
            val root = JsonParser.parseString(responseBody).asJsonObject
            root.addProperty("http_status", response.code)
            root
        }
    }

    private fun requireSuccessful(root: JsonObject) {
        val status = root.requiredInt("http_status")
        if (status !in 200..299) {
            throw IOException(root.get("error")?.asString ?: "会员服务请求失败（$status）")
        }
    }

    private fun JsonObject.requiredString(name: String): String =
        get(name)?.asString?.takeIf { it.isNotBlank() }
            ?: throw IOException("会员服务未返回 $name")

    private fun JsonObject.requiredInt(name: String): Int =
        get(name)?.asInt ?: throw IOException("会员服务未返回 $name")

    private fun JsonObject.toEntitlement(): ProjectionEntitlement = ProjectionEntitlement(
        enabled = get("enabled")?.asBoolean ?: false,
        isLifetime = get("is_lifetime")?.asBoolean ?: false,
        expiresAt = get("expires_at").takeUnless { it == null || it.isJsonNull }?.asString,
    )

    private companion object {
        const val PRIMARY_BASE_URL = "https://cp.jixiexiaoge.online"
        const val PREF_INSTALLATION_ID = "projection_membership_installation_id"
        const val PREF_DEVICE_TOKEN = "projection_membership_device_token"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val httpClient = projectionMembershipHttpClient()
    }
}
