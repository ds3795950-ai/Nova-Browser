package com.nova.browser

/** Pure validation and normalization shared by the cloud-auth UI. */
internal object NovaCloudAuthController {
    fun normalizeEmail(value: String): String = value.trim().lowercase(java.util.Locale.ROOT)
    fun isValidEmail(value: String): Boolean =
        value.length <= 254 && android.util.Patterns.EMAIL_ADDRESS.matcher(value).matches()
    fun isValidOtp(value: String): Boolean = value.length == 6 && value.all(Char::isDigit)
    fun isValidPassphrase(value: CharArray): Boolean = value.size >= 10

    /** Runs blocking network/auth work away from the UI thread and returns its result to the caller. */
    fun <T> runAsync(task: () -> T, completion: (Result<T>) -> Unit) {
        Thread { completion(runCatching(task)) }.apply {
            name = "nova-cloud-auth"
            isDaemon = true
            start()
        }
    }
}
