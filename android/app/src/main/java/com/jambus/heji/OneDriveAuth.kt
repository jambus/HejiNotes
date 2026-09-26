package com.jambus.heji

import android.app.Activity
import android.content.Context
import com.microsoft.identity.client.AuthenticationCallback
import com.microsoft.identity.client.AcquireTokenParameters
import com.microsoft.identity.client.AcquireTokenSilentParameters
import com.microsoft.identity.client.IAccount
import com.microsoft.identity.client.IAuthenticationResult
import com.microsoft.identity.client.IMultipleAccountPublicClientApplication
import com.microsoft.identity.client.PublicClientApplication
import com.microsoft.identity.client.exception.MsalException
import com.microsoft.identity.client.exception.MsalServiceException
import com.microsoft.identity.client.exception.MsalUiRequiredException
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

data class OneDriveAccount(val id: String, val displayName: String)

internal object OneDriveSignaturePolicy {
    fun normalizeRawBase64(value: String): String {
        val trimmed = value.trim()
        return if (trimmed.contains('%')) {
            runCatching { URLDecoder.decode(trimmed, StandardCharsets.UTF_8.name()) }.getOrDefault(trimmed)
        } else {
            trimmed
        }
    }

    fun encodeForRedirectUri(value: String): String {
        val raw = normalizeRawBase64(value)
        return URLEncoder.encode(raw, StandardCharsets.UTF_8.name())
    }
}

/** MSAL owns credential persistence; only stable, non-secret account IDs leave this class. */
class OneDriveAuth(private val context: Context) {
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var cachedApplication: IMultipleAccountPublicClientApplication? = null

    val isConfigured: Boolean
        get() = BuildConfig.ONEDRIVE_CLIENT_ID.isNotBlank() && BuildConfig.ONEDRIVE_SIGNATURE_HASH.isNotBlank()

    fun signIn(activity: Activity, callback: (Result<OneDriveAccount>) -> Unit) {
        if (!isConfigured) {
            callback(Result.failure(IllegalStateException("OneDrive application registration is not configured")))
            return
        }
        executor.execute {
            val application = runCatching(::application).getOrElse { failure ->
                activity.runOnUiThread { callback(Result.failure(failure)) }
                return@execute
            }
            activity.runOnUiThread {
                val parameters = AcquireTokenParameters.Builder()
                    .startAuthorizationFromActivity(activity)
                    .withScopes(SCOPES)
                    .withCallback(object : AuthenticationCallback {
                    override fun onSuccess(authenticationResult: IAuthenticationResult) {
                        val identity = authenticationResult.account.publicIdentity()
                        activity.runOnUiThread { callback(Result.success(identity)) }
                    }

                    override fun onError(exception: MsalException) {
                        activity.runOnUiThread { callback(Result.failure(exception)) }
                    }

                    override fun onCancel() {
                        activity.runOnUiThread { callback(Result.failure(OneDriveSignInCancelled())) }
                    }
                }).build()
                application.acquireToken(parameters)
            }
        }
    }

    /** Worker-thread only. MSAL may refresh its secure token cache. */
    fun accessToken(accountId: String, forceRefresh: Boolean = false): String {
        require(isConfigured) { "OneDrive application registration is not configured" }
        val account = application().accounts.firstOrNull { it.id == accountId }
            ?: throw OneDriveReloginRequired()
        val parameters = AcquireTokenSilentParameters.Builder()
            .withScopes(SCOPES)
            .forAccount(account)
            .fromAuthority(account.authority)
            .forceRefresh(forceRefresh)
            .build()
        return try {
            application().acquireTokenSilent(parameters).accessToken
        } catch (_: MsalUiRequiredException) {
            throw OneDriveReloginRequired()
        } catch (serviceEx: MsalServiceException) {
            if (serviceEx.errorCode in setOf("invalid_grant", "interaction_required", "insufficient_resources")) {
                throw OneDriveReloginRequired()
            }
            throw serviceEx
        }
    }

    /** Worker-thread only. */
    fun account(accountId: String): OneDriveAccount? = if (!isConfigured) null else
        application().accounts.firstOrNull { it.id == accountId }?.publicIdentity()

    @Synchronized
    private fun application(): IMultipleAccountPublicClientApplication {
        cachedApplication?.let { return it }
        val created = PublicClientApplication.createMultipleAccountPublicClientApplication(context, configurationFile())
        cachedApplication = created
        return created
    }

    private fun configurationFile(): File {
        val encodedSignature = OneDriveSignaturePolicy.encodeForRedirectUri(BuildConfig.ONEDRIVE_SIGNATURE_HASH)
        val json = org.json.JSONObject().apply {
            put("client_id", BuildConfig.ONEDRIVE_CLIENT_ID)
            put("redirect_uri", "msauth://com.jambus.heji/$encodedSignature")
            put("authorization_user_agent", "DEFAULT")
            put("broker_redirect_uri_registered", false)
            put("account_mode", "MULTIPLE")
            put("authorities", org.json.JSONArray().put(org.json.JSONObject().apply {
                put("type", "AAD")
                put("default", true)
                put("audience", org.json.JSONObject().apply {
                    put("type", "AzureADandPersonalMicrosoftAccount")
                    put("tenant_id", "common")
                })
            }))
            put("logging", org.json.JSONObject().apply {
                put("pii_enabled", false)
                put("log_level", "ERROR")
            })
        }.toString()
        return File(context.cacheDir, "onedrive-msal-config.json").also { file ->
            file.outputStream().use { it.write(json.toByteArray(StandardCharsets.UTF_8)) }
        }
    }

    private fun IAccount.publicIdentity(): OneDriveAccount = OneDriveAccount(id, username)

    companion object {
        private val SCOPES = listOf("Files.ReadWrite")
    }
}

class OneDriveSignInCancelled : Exception("Microsoft sign-in was cancelled")
class OneDriveReloginRequired : Exception("Microsoft sign-in interaction is required")
