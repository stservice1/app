package com.github.kr328.clash

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.view.View
import android.text.TextWatcher
import android.text.Editable
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.github.kr328.clash.util.withProfile
import com.github.kr328.clash.service.model.Profile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import com.github.kr328.clash.service.STServiceOrchestrator
import com.github.kr328.clash.util.TXTRecordParser

class LoginActivity : AppCompatActivity() {

    data class ValidationResult(val isValid: Boolean, val message: String, val subscriptionUrl: String? = null)
    
    private var failedAttempts = 0
    private var cooldownEndTime = 0L
    private var countdownTimer: CountDownTimer? = null

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

    companion object {
        private const val PREFS_NAME = "login_lockout_prefs"
        private const val KEY_FAILED_ATTEMPTS = "failed_attempts"
        private const val KEY_COOLDOWN_END = "cooldown_end"
        private const val COOLDOWN_DURATION_MS = 120_000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("LoginActivity", "Creating LoginActivity")
        setContentView(R.layout.activity_login)

        val nodeCodeInput = findViewById<EditText>(R.id.nodeCodeInput)
        val loginButton = findViewById<Button>(R.id.loginButton)
        val progressBar = findViewById<ProgressBar>(R.id.progressBar)
        val cooldownText = findViewById<TextView>(R.id.cooldownText)
        val errorMessageView = findViewById<TextView>(R.id.errorMessage)

        // Restore lockout state
        failedAttempts = prefs.getInt(KEY_FAILED_ATTEMPTS, 0)
        cooldownEndTime = prefs.getLong(KEY_COOLDOWN_END, 0L)
        val now = System.currentTimeMillis()
        if (cooldownEndTime > now) {
            loginButton.isEnabled = false
            loginButton.visibility = View.INVISIBLE
            cooldownText.visibility = View.VISIBLE
            startCooldownTimer(errorMessageView, loginButton, cooldownText, cooldownEndTime - now)
        } else {
            // Ensure clean state
            loginButton.isEnabled = true
            loginButton.visibility = View.VISIBLE
            cooldownText.visibility = View.GONE
            cancelCooldownTimer()
        }
        
        // Check for existing error messages from orchestrator
        val orchestrator = STServiceOrchestrator.getInstance()
        val errorMessage = orchestrator.getErrorMessage(this)
        if (errorMessage != null) {
            Log.d("LoginActivity", "Showing orchestrator error message: $errorMessage")
            showError(errorMessageView, errorMessage)
            orchestrator.clearErrorMessage(this)
        }
        
        // Clear error message when user starts typing and convert to uppercase
        nodeCodeInput.addTextChangedListener(object : TextWatcher {
            private var isUpdating = false
            
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (s?.isNotEmpty() == true) {
                    clearError(errorMessageView)
                }
                
                // Convert to uppercase
                if (!isUpdating && s != null) {
                    val upperText = s.toString().uppercase()
                    if (upperText != s.toString()) {
                        isUpdating = true
                        s.replace(0, s.length, upperText)
                        isUpdating = false
                    }
                }
            }
        })

        loginButton.setOnClickListener {
            val nodeCode = nodeCodeInput.text.toString().trim()
            Log.d("LoginActivity", "Login button clicked with node code: $nodeCode")
            
            if (nodeCode.isEmpty()) {
                showError(errorMessageView, "Please enter a node code")
                return@setOnClickListener
            }
            
            // Clear any existing error before starting validation
            clearError(errorMessageView)

            Log.d("LoginActivity", "Starting validation for node code: $nodeCode")
            lifecycleScope.launch {
                // Hide button without collapsing its space to prevent layout jump
                loginButton.visibility = View.INVISIBLE
                cooldownText.visibility = View.GONE
                progressBar.visibility = View.VISIBLE
                
                // Add 1-minute timeout
                val result = withTimeoutOrNull(60000L) { // 60 seconds
                    try {
                        val validationResult = validateNodeCode(nodeCode)
                        Log.d("LoginActivity", "Validation result: ${validationResult.isValid}, message: ${validationResult.message}")
                        
                        if (validationResult.isValid && validationResult.subscriptionUrl != null) {
                            Log.d("LoginActivity", "Creating profile with URL: ${validationResult.subscriptionUrl}")
                            createProfile(nodeCode, validationResult.subscriptionUrl, errorMessageView)
                            return@withTimeoutOrNull "success"
                        } else {
                            Log.w("LoginActivity", "Validation failed: ${validationResult.message}")
                            showError(errorMessageView, validationResult.message)
                            handleValidationFailure(validationResult.message, errorMessageView, loginButton)
                            return@withTimeoutOrNull "validation_failed"
                        }
                    } catch (e: Exception) {
                        Log.e("LoginActivity", "Login error: ${e.message}", e)
                        showError(errorMessageView, "Login error: ${e.message}")
                        return@withTimeoutOrNull "error"
                    }
                }
                
                // Handle timeout
                if (result == null) {
                    Log.w("LoginActivity", "Login process timed out after 60 seconds")
                    showError(errorMessageView, "Something went wrong, please try again")
                }
                
                // Always restore UI
                progressBar.visibility = View.GONE
                // If cooldown is active, keep button hidden and show cooldown instead
                if (cooldownEndTime > System.currentTimeMillis()) {
                    loginButton.visibility = View.INVISIBLE
                    cooldownText.visibility = View.VISIBLE
                } else {
                    loginButton.visibility = View.VISIBLE
                    cooldownText.visibility = View.GONE
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelCooldownTimer()
    }
    
    private fun showError(errorMessageView: TextView, message: String) {
        errorMessageView.text = message
        errorMessageView.visibility = View.VISIBLE
    }
    
    private fun clearError(errorMessageView: TextView) {
        errorMessageView.visibility = View.INVISIBLE
    }

    private fun handleValidationFailure(message: String, errorMessageView: TextView, loginButton: Button) {
        // Count attempts for all validation failures that are not network/timeouts
        val countsTowardsLimit =
            message == STServiceOrchestrator.LOGIN_ERROR_BANNED ||
            message.contains("already been used", ignoreCase = true) ||
            message == "Node code not found" ||
            message.startsWith("Invalid node code", ignoreCase = true)

        if (countsTowardsLimit) {
            failedAttempts += 1
            prefs.edit().putInt(KEY_FAILED_ATTEMPTS, failedAttempts).apply()
            if (failedAttempts >= 3) {
                failedAttempts = 0
                prefs.edit().putInt(KEY_FAILED_ATTEMPTS, failedAttempts).apply()
                startCooldownTimer(
                    errorMessageView,
                    loginButton,
                    findViewById(R.id.cooldownText),
                    COOLDOWN_DURATION_MS
                )
            }
        }
    }

    private fun startCooldownTimer(errorMessageView: TextView, loginButton: Button, cooldownText: TextView, durationMs: Long) {
        cancelCooldownTimer()
        cooldownEndTime = System.currentTimeMillis() + durationMs
        prefs.edit().putLong(KEY_COOLDOWN_END, cooldownEndTime).apply()

        loginButton.isEnabled = false
        loginButton.visibility = View.INVISIBLE
        cooldownText.visibility = View.VISIBLE
        // Keep the error message visible, do not overwrite it

        countdownTimer = object : CountDownTimer(durationMs, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val mm = (millisUntilFinished / 1000L) / 60L
                val ss = (millisUntilFinished / 1000L) % 60L
                cooldownText.text = String.format(Locale.getDefault(), "Please wait %02d:%02d to try again", mm, ss)
            }

            override fun onFinish() {
                loginButton.isEnabled = true
                cooldownEndTime = 0L
                prefs.edit().putLong(KEY_COOLDOWN_END, cooldownEndTime).apply()
                cooldownText.visibility = View.GONE
                countdownTimer = null
            }
        }.start()
    }

    private fun cancelCooldownTimer() {
        countdownTimer?.cancel()
        countdownTimer = null
    }

    private suspend fun validateNodeCode(nodeCode: String): ValidationResult {
        return withContext(Dispatchers.IO) {
            try {
                val lastTwoDigits = nodeCode.takeLast(2)
                val domain = "$nodeCode.stservice$lastTwoDigits.ddnsfree.com"
                Log.d("LoginActivity", "Making DNS query for: $domain")
                val url = URL("https://dns.alidns.com/resolve?name=$domain&type=TXT")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                
                Log.d("LoginActivity", "DNS query response code: ${connection.responseCode}")
                if (connection.responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().readText()
                    connection.disconnect()
                    
                    Log.d("LoginActivity", "DNS response: $response")
                    val jsonResponse = JSONObject(response)
                    val status = jsonResponse.getInt("Status")
                    
                    if (status == 0 && jsonResponse.has("Answer")) {
                        val answers = jsonResponse.getJSONArray("Answer")
                        if (answers.length() > 0) {
                            val txtData = answers.getJSONObject(0).getString("data")
                            Log.d("LoginActivity", "TXT record data: $txtData")
                            
                            val txtInfo = TXTRecordParser.parseTXTData(txtData)
                            
                            // Use cache-aware logic through orchestrator
                            val orchestrator = STServiceOrchestrator.getInstance()
                            val cachedTxtInfo = orchestrator.getCachedTxtRecordForLogin(this@LoginActivity, nodeCode)

                            val finalTxtInfo = if (cachedTxtInfo != null && txtData.isNotEmpty()) {
                                if (txtInfo.modified <= cachedTxtInfo.modified) {
                                    Log.d("LoginActivity", "DNS returned stale data (${txtInfo.modified} <= ${cachedTxtInfo.modified}), using cached")
                                    cachedTxtInfo
                                } else {
                                    Log.d("LoginActivity", "DNS returned fresh data (${txtInfo.modified} > ${cachedTxtInfo.modified}), updating cache")
                                    orchestrator.cacheTxtRecordForLogin(this@LoginActivity, txtInfo, nodeCode)
                                    txtInfo
                                }
                            } else {
                                Log.d("LoginActivity", "No cached data available, using fresh DNS data")
                                orchestrator.cacheTxtRecordForLogin(this@LoginActivity, txtInfo, nodeCode)
                                txtInfo
                            }
                            
                            return@withContext validateTxtRecord(finalTxtInfo)
                        }
                    }
                }
                connection.disconnect()
                Log.w("LoginActivity", "Node code not found in DNS")
                ValidationResult(false, "Node code not found")
            } catch (e: Exception) {
                Log.e("LoginActivity", "Network error during validation: ${e.message}", e)
                ValidationResult(false, "Network error: ${e.message}")
            }
        }
    }

    private fun validateTxtRecord(txtInfo: com.github.kr328.clash.util.TXTRecordInfo): ValidationResult {
        Log.d("LoginActivity", "Validating TXT record: $txtInfo")
        
        // Check if consumed
        if (txtInfo.consumed) {
            Log.w("LoginActivity", "Node code has already been used")
            return ValidationResult(false, "This node code has already been used")
        }
        
        // Check for ban status
        if (txtInfo.banned) {
            Log.w("LoginActivity", "Node code is banned")
            return ValidationResult(false, STServiceOrchestrator.LOGIN_ERROR_BANNED)
        }
        
        // Check for expiration
        if (TXTRecordParser.isExpired(txtInfo)) {
            Log.w("LoginActivity", "Node code has expired: ${txtInfo.expiration}")
            return ValidationResult(false, STServiceOrchestrator.LOGIN_ERROR_EXPIRED)
        }
        
        // Get subscription URL
        if (txtInfo.vpnUrl.isNullOrEmpty()) {
            Log.w("LoginActivity", "No subscription URL found in TXT record")
            return ValidationResult(false, "Invalid node code: missing subscription URL")
        }
        
        Log.d("LoginActivity", "Node code validation successful")
        return ValidationResult(true, "Success", txtInfo.vpnUrl)
    }

    private suspend fun createProfile(nodeCode: String, subscriptionUrl: String, errorMessageView: TextView) {
        try {
            Log.d("LoginActivity", "Creating profile with subscription URL: $subscriptionUrl")
            withProfile {
                // Clean up any existing profiles first to prevent conflicts
                Log.d("LoginActivity", "Cleaning up existing profiles...")
                val existingProfiles = queryAll()
                for (profile in existingProfiles) {
                    Log.d("LoginActivity", "Deleting existing profile: ${profile.name} (${profile.uuid})")
                    delete(profile.uuid)
                }
                
                Log.d("LoginActivity", "Creating new profile...")
                val profileName = "ST VPN Service ($nodeCode)"
                val uuid = create(Profile.Type.Url, profileName)
                Log.d("LoginActivity", "Profile created with UUID: $uuid")
                
                Log.d("LoginActivity", "Setting profile URL and updating...")
                patch(uuid, profileName, subscriptionUrl, 0)
                
                Log.d("LoginActivity", "Committing profile to save it...")
                commit(uuid)
                
                Log.d("LoginActivity", "Updating profile to import configuration...")
                // Retry update up to 3 times with increasing delays
                var updateSuccess = false
                var lastError: Exception? = null
                val errorDetails = StringBuilder()

                for (attempt in 1..3) {
                    try {
                        Log.d("LoginActivity", "Attempt $attempt: Fetching from $subscriptionUrl")
                        update(uuid)
                        updateSuccess = true
                        Log.d("LoginActivity", "Attempt $attempt: Success!")
                        break
                    } catch (e: Exception) {
                        lastError = e
                        val errorMsg = "Attempt $attempt/${3}:\n" +
                                      "Error: ${e.javaClass.simpleName}\n" +
                                      "Message: ${e.message}\n" +
                                      "URL: $subscriptionUrl\n\n"
                        errorDetails.append(errorMsg)
                        Log.w("LoginActivity", errorMsg, e)

                        if (attempt < 3) {
                            val delayMs = attempt * 2000L
                            Log.d("LoginActivity", "Waiting ${delayMs}ms before retry...")
                            kotlinx.coroutines.delay(delayMs) // 2s, 4s delays
                        }
                    }
                }

                if (!updateSuccess) {
                    val debugInfo = buildString {
                        appendLine("Subscription fetch failed after 3 attempts\n")
                        appendLine("=== Debug Information ===")
                        appendLine(errorDetails.toString())
                        appendLine("=== System Info ===")
                        appendLine("Android: ${android.os.Build.VERSION.RELEASE}")
                        appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                        appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
                    }

                    Log.e("LoginActivity", debugInfo)

                    // Show detailed error dialog
                    runOnUiThread {
                        android.app.AlertDialog.Builder(this@LoginActivity)
                            .setTitle("Connection Failed")
                            .setMessage(debugInfo)
                            .setPositiveButton("Copy Details") { _, _ ->
                                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                val clip = android.content.ClipData.newPlainText("Error Details", debugInfo)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(this@LoginActivity, "Error details copied to clipboard", Toast.LENGTH_SHORT).show()
                            }
                            .setNegativeButton("Close", null)
                            .show()
                    }

                    throw Exception("Failed to fetch subscription: ${lastError?.message}")
                }

                // Wait a moment for the update to complete
                kotlinx.coroutines.delay(2000)

                val profile = queryByUUID(uuid)
                if (profile == null) {
                    throw Exception("Failed to create a profile")
                }

                // Set the profile as active
                if (profile.imported) {
                    Log.d("LoginActivity", "Setting profile as active")
                    setActive(profile)
                } else {
                    Log.w("LoginActivity", "Profile not imported, cannot set as active")
                }
            }

            Log.d("LoginActivity", "Profile setup complete, launching MainActivity")
            
            // Set login status and start monitoring
            val orchestrator = STServiceOrchestrator.getInstance()
            orchestrator.setLoginStatus(this@LoginActivity, true)
            orchestrator.clearErrorMessage(this@LoginActivity)
            
            runOnUiThread {
                Toast.makeText(this@LoginActivity, "Login successful!", Toast.LENGTH_SHORT).show()
                startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                finish()
            }
        } catch (e: Exception) {
            Log.e("LoginActivity", "Error creating profile: ${e.message}", e)
            runOnUiThread {
                showError(errorMessageView, "Error creating profile: ${e.message}")
            }
        }
    }
}