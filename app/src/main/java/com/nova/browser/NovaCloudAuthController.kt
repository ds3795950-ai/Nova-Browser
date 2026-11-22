package com.nova.browser

/** Pure validation and normalization shared by the cloud-auth UI. */
internal object NovaCloudAuthController {
    fun normalizeEmail(value: String): String = value.trim().lowercase(java.util.Locale.ROOT)
    fun isValidEmail(value: String): Boolean =
        value.length <= 254 && android.util.Patterns.EMAIL_ADDRESS.matcher(value).matches()
    fun isValidOtp(value: String): Boolean = value.length == 6 && value.all(Char::isDigit)
    fun isValidPassphrase(value: CharArray): Boolean = value.size >= 10
}
