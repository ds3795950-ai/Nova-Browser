package com.nova.browser

import org.json.JSONObject
import java.net.URI
import java.util.Locale
import java.util.UUID

/** Strict validation for credentials received from cloud sync or an imported backup. */
internal object PasswordVaultValidation {
    data class LoginFields(
        val guid: String,
        val origin: String,
        val formActionOrigin: String?,
        val httpRealm: String?,
        val username: String,
        val password: String
    )

    fun fromJson(item: JSONObject): LoginFields {
        val guid = item.optString("guid").takeIf { it.isNotBlank() && it != "null" } ?: UUID.randomUUID().toString()
        val origin = item.getString("origin")
        val formAction = item.optString("formActionOrigin").takeIf { it.isNotBlank() && it != "null" }
        val realm = item.optString("httpRealm").takeIf { it.isNotBlank() && it != "null" }
        val username = item.getString("username")
        val password = item.getString("password")
        require(guid.length <= 256) { "Saved login identifier is too long" }
        validateWebOrigin(origin)
        if (formAction != null) validateWebOrigin(formAction)
        require(realm == null || realm.length <= 4096) { "Saved login HTTP realm is too long" }
        require(username.length <= 4096) { "Saved login username is too long" }
        require(password.length <= 16_384) { "Saved login password is too long" }
        return LoginFields(guid, origin, formAction, realm, username, password)
    }

    fun validateWebOrigin(value: String) {
        require(value.isNotBlank() && value.length <= 2048) { "Invalid saved login origin" }
        val uri = try {
            URI(value)
        } catch (error: Exception) {
            throw IllegalArgumentException("Invalid saved login origin", error)
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        require(scheme == "https" || scheme == "http") { "Saved logins must use HTTP or HTTPS origins" }
        require(!uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "Saved login origin must be a host origin without credentials, query, or fragment"
        }
        require(uri.path.isNullOrEmpty() || uri.path == "/") { "Saved login origin cannot contain a page path" }
        require(uri.port == -1 || uri.port in 1..65535) { "Saved login origin has an invalid port" }
    }
}
