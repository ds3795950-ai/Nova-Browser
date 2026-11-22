package com.nova.browser

import androidx.activity.ComponentActivity
import android.app.AlertDialog
import android.text.InputFilter
import android.widget.EditText

/** Owns email sign-in and OTP dialogs; all blocking auth calls use NovaCloudAuthController. */
internal class NovaCloudAuthUi(
    private val activity: ComponentActivity,
    private val cloudSync: NovaCloudSync,
    private val showMessage: (String) -> Unit,
    private val onSignedIn: () -> Unit
) {
    fun showSignIn() {
        val input = EditText(activity).apply {
            hint = "Email address"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            filters = arrayOf(InputFilter.LengthFilter(254))
            isSingleLine = true
        }
        AlertDialog.Builder(activity)
            .setTitle("Sign in to Nova Cloud")
            .setMessage("We'll email you a one-time code. No password is required.")
            .setView(input)
            .setPositiveButton("Send code") { _, _ ->
                val address = NovaCloudAuthController.normalizeEmail(input.text.toString())
                if (!NovaCloudAuthController.isValidEmail(address)) {
                    showMessage("Enter a valid email address")
                    return@setPositiveButton
                }
                showMessage("Requesting sign-in code…")
                NovaCloudAuthController.runAsync({ cloudSync.sendEmailOtp(address) }) { result ->
                    activity.runOnUiThread {
                        result.onSuccess { showOtp(address) }
                            .onFailure { showMessage("Could not send code: ${it.message ?: "Network error"}") }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showOtp(email: String) {
        val input = EditText(activity).apply {
            hint = "6-digit email code"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(6))
            isSingleLine = true
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Verify email")
            .setMessage("Enter the six-digit code sent to $email")
            .setView(input)
            .setPositiveButton("Verify", null)
            .setNeutralButton("Resend code", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            val verifyButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            verifyButton.setOnClickListener {
                val code = input.text.toString().trim()
                if (!NovaCloudAuthController.isValidOtp(code)) {
                    input.error = "Enter all six digits"
                    return@setOnClickListener
                }
                verifyButton.isEnabled = false
                showMessage("Verifying code…")
                NovaCloudAuthController.runAsync({ cloudSync.verifyEmailOtp(email, code) }) { result ->
                    activity.runOnUiThread {
                        verifyButton.isEnabled = true
                        result.onSuccess {
                            dialog.dismiss()
                            showMessage("Signed in to Nova Cloud")
                            onSignedIn()
                        }.onFailure {
                            showMessage("Sign-in failed: ${it.message ?: "Check the code and try again"}")
                        }
                    }
                }
            }
            val resendButton = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
            resendButton.setOnClickListener {
                resendButton.isEnabled = false
                NovaCloudAuthController.runAsync({ cloudSync.sendEmailOtp(email) }) { result ->
                    activity.runOnUiThread {
                        resendButton.isEnabled = true
                        result.onSuccess {
                            dialog.dismiss()
                            showOtp(email)
                            showMessage("A new code was requested")
                        }.onFailure {
                            showMessage("Could not resend: ${it.message ?: "Try again shortly"}")
                        }
                    }
                }
            }
        }
        dialog.show()
    }
}
