package com.github.kr328.clash

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.ticker
import com.github.kr328.clash.design.MainDesign
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.withProfile
import com.github.kr328.clash.core.bridge.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import com.github.kr328.clash.design.R
import com.github.kr328.clash.service.STServiceOrchestrator
import com.github.kr328.clash.service.ProfileUpdateException
import android.content.Intent

class MainActivity : BaseActivity<MainDesign>() {
    // Traffic monitoring state for validation trigger
    private var initialTrafficTotal: Long = 0L
    private var hasTriggeredValidation: Boolean = false

    // Logo click counter for logout button
    private var logoClickCount: Int = 0
    override suspend fun main() {
        // Check login status first
        val orchestrator = STServiceOrchestrator.getInstance()
        if (!orchestrator.isLoggedIn(this)) {
            // User is not logged in, redirect to login
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        
        val design = MainDesign(this)

        setContentDesign(design)

        // Set up logo click listener to show logout button after 5 clicks
        design.setLogoClickListener {
            logoClickCount++
            if (logoClickCount >= 5) {
                design.showLogoutButton()
            }
        }

        design.fetch()

        val ticker = ticker(TimeUnit.SECONDS.toMillis(1))
        
        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ActivityStart,
                        Event.ServiceRecreated,
                        Event.ClashStop, Event.ClashStart,
                        Event.ProfileLoaded, Event.ProfileChanged -> design.fetch()
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        MainDesign.Request.ToggleStatus -> {
                            if (clashRunning) {
                                stopClashService()
                                // Stop monitoring when VPN service stops
                                STServiceOrchestrator.getInstance().stopMonitoring(this@MainActivity)
                                // Reset traffic validation state when stopping
                                resetTrafficValidation()
                            } else
                                design.startClash()
                        }
                        MainDesign.Request.OpenProxy ->
                            startActivity(ProxyActivity::class.intent)
                        MainDesign.Request.OpenProfiles ->
                            startActivity(ProfilesActivity::class.intent)
                        MainDesign.Request.OpenProviders ->
                            startActivity(ProvidersActivity::class.intent)
                        MainDesign.Request.OpenLogs -> {
                            if (LogcatService.running) {
                                startActivity(LogcatActivity::class.intent)
                            } else {
                                startActivity(LogsActivity::class.intent)
                            }
                        }
                        MainDesign.Request.OpenSettings ->
                            startActivity(SettingsActivity::class.intent)
                        MainDesign.Request.OpenHelp ->
                            startActivity(HelpActivity::class.intent)
                        MainDesign.Request.OpenAbout ->
                            design.showAbout(queryAppVersionName())
                        MainDesign.Request.Logout ->
                            handleLogout()
                    }
                }
                if (clashRunning) {
                    ticker.onReceive {
                        design.fetchTraffic()
                        // Check for traffic-based validation trigger
                        checkTrafficValidationTrigger(design)
                    }
                }
            }
        }
    }

    private suspend fun MainDesign.fetch() {
        setClashRunning(clashRunning)

        val state = withClash {
            queryTunnelState()
        }
        val providers = withClash {
            queryProviders()
        }

        setMode(state.mode)
        setHasProviders(providers.isNotEmpty())

        withProfile {
            setProfileName(queryActive()?.name)
        }
    }

    private suspend fun MainDesign.fetchTraffic() {
        withClash {
            setForwarded(queryTrafficTotal())
        }
    }

    private suspend fun MainDesign.startClash() {
        // Clear previous feedback
        clearValidationError()
        hideFeedbackPanel()

        val orchestrator = STServiceOrchestrator.getInstance()

        // Configure Dynu API key for consumed status updates
        orchestrator.setDynuApiKey(this@MainActivity, BuildConfig.DYNU_API_KEY)

        try {
            // Launch link check asynchronously FIRST - don't wait for it
            // This must happen before profile validation so it can fix broken links
            GlobalScope.launch(Dispatchers.IO) {
                try {
                    Log.d("MainActivity", "Checking for link updates asynchronously...")
                    val txtInfo = orchestrator.fetchTXTRecordWithCache(this@MainActivity)
                    if (txtInfo != null && txtInfo.vpnUrl != null) {
                        Log.d("MainActivity", "Found link in TXT record, checking if update needed...")
                        orchestrator.checkAndUpdateLink(
                            this@MainActivity,
                            txtInfo.vpnUrl,
                            onProfileUpdateStart = {
                                setStartEnabled(false)
                                showFeedbackPanel("Updating profile...", showProgress = true)
                            },
                            onProfileUpdateEnd = {
                                setStartEnabled(true)
                                hideFeedbackPanel()
                            }
                        )
                    }
                } catch (e: ProfileUpdateException) {
                    Log.e("MainActivity", "Failed to rebuild profile after link change", e)
                    showValidationError("Could not update VPN configuration for the new link. Please try again.")
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error during async link check", e)
                }
            }

            // Check if we have a valid active profile
            val active = withProfile { queryActive() }
            if (active == null || !active.imported) {
                showToast("VPN Configuration failed. Please try start again", ToastDuration.Long)
                return
            }

            // Reset traffic validation state when starting
            resetTrafficValidation()

            Log.d("MainActivity", "Starting VPN service...")

            val vpnRequest = startClashService()

            try {
                if (vpnRequest != null) {
                    val result = startActivityForResult(
                        ActivityResultContracts.StartActivityForResult(),
                        vpnRequest
                    )

                    if (result.resultCode != RESULT_OK) {
                        showToast("VPN permission denied. Please grant permission to start the service.", ToastDuration.Long)
                        return
                    }

                    // Start VPN service after permission granted
                    startClashService()
                }
            } catch (e: Exception) {
                // Log full error for debugging
                Log.e("MainActivity", "VPN permission request failed", e)

                // Show detailed error to user (wrapped in try-catch for safety)
                try {
                    val className = e.javaClass.simpleName
                    val stackTrace = e.stackTraceToString().take(500)
                    val errorMessage = "VPN permission error: $className\n$stackTrace"
                    showToast(errorMessage, ToastDuration.Long)
                } catch (inner: Exception) {
                    showToast("Failed to generate error message", ToastDuration.Long)
                }
                return
            }

            // Start background monitoring for ban/expiration checks
            orchestrator.startMonitoring(this@MainActivity)
            
            Log.d("MainActivity", "VPN service started successfully - validation will occur when traffic flows")
            
        } catch (e: Exception) {
            Log.e("MainActivity", "Error during VPN service start", e)
            // If there's an error, make sure VPN is disconnected
            try {
                stopClashService()
            } catch (ex: Exception) {
                Log.e("MainActivity", "Error stopping VPN service", ex)
            }
            
            val errorMessage = when {
                e.message?.contains("HTTP") == true -> "Server error: ${e.message}"
                else -> "Could not start VPN service. Please try again."
            }
            showToast(errorMessage, ToastDuration.Long)
        }
    }

    private suspend fun checkTrafficValidationTrigger(design: MainDesign) {
        if (hasTriggeredValidation) return
        
        try {
            val currentTraffic = withClash { queryTrafficTotal() }
            val trafficThreshold = 1024L // 1 KiB threshold
            
            if (initialTrafficTotal == 0L) {
                // First time checking - set baseline
                initialTrafficTotal = currentTraffic
            } else if (currentTraffic > initialTrafficTotal + trafficThreshold) {
                // Traffic is flowing - trigger validation
                hasTriggeredValidation = true
                Log.d("MainActivity", "Traffic detected (${currentTraffic - initialTrafficTotal} bytes), triggering validation")
                runTrafficTriggeredValidation()
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error checking traffic for validation trigger", e)
        }
    }

    private suspend fun runTrafficTriggeredValidation() {
        try {
            // Show toast notification
            runOnUiThread {
                android.widget.Toast.makeText(this@MainActivity, "Validating subscription...", android.widget.Toast.LENGTH_SHORT).show()
            }
            
            val orchestrator = STServiceOrchestrator.getInstance()
            val txtInfo = orchestrator.fetchTXTRecordWithCache(this@MainActivity)
            
            if (txtInfo == null) {
                Log.w("MainActivity", "Traffic-triggered validation failed to fetch TXT record")
                return
            }
            
            // Check for ban
            if (txtInfo.banned) {
                Log.w("MainActivity", "User is banned - logging out")
                orchestrator.logout(this@MainActivity, STServiceOrchestrator.LOGIN_ERROR_BANNED)
                return
            }
            
            // Check for expiration
            if (com.github.kr328.clash.util.TXTRecordParser.isExpired(txtInfo)) {
                Log.w("MainActivity", "User subscription expired - logging out")
                orchestrator.logout(this@MainActivity, STServiceOrchestrator.LOGIN_ERROR_EXPIRED)
                return
            }

            // Note: Link updates now happen at VPN start, not here during traffic validation

            // Mark as consumed via Dynu API (only if not already consumed)
            if (!txtInfo.consumed) {
                orchestrator.updateConsumedStatus(this@MainActivity, txtInfo)
                Log.d("MainActivity", "Subscription marked as consumed via traffic-triggered validation")
            }
            
            Log.d("MainActivity", "Traffic-triggered validation completed successfully")
            
        } catch (e: Exception) {
            Log.e("MainActivity", "Error during traffic-triggered validation", e)
        }
    }

    private fun resetTrafficValidation() {
        initialTrafficTotal = 0L
        hasTriggeredValidation = false
        Log.d("MainActivity", "Reset traffic validation state")
    }

    private suspend fun queryAppVersionName(): String {
        return withContext(Dispatchers.IO) {
            packageManager.getPackageInfo(packageName, 0).versionName + "\n" + Bridge.nativeCoreVersion().replace("_", "-")
        }
    }

    private fun handleLogout() {
        // Confirmation dialog
        val dialog = android.app.AlertDialog.Builder(this@MainActivity)
            .setTitle("Logout Warning")
            .setMessage("This action cannot be undone. Logging out will permanently delete your profile and all VPN configurations. You will need a new activation code to use this service again.\n\nAre you sure you want to proceed?")
            .setPositiveButton("Yes, Logout") { _, _ ->
                launch {
                    performLogout()
                }
            }
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()
    }

    private suspend fun performLogout() {
        try {
            Log.d("MainActivity", "Starting logout process")

            // Stop VPN service if running
            stopClashService()

            // Stop monitoring
            STServiceOrchestrator.getInstance().stopMonitoring(this@MainActivity)

            // Delete all profiles
            withProfile {
                val profiles = queryAll()
                profiles.forEach { profile ->
                    Log.d("MainActivity", "Deleting profile: ${profile.name}")
                    delete(profile.uuid)
                }
            }

            // Clear any stored credentials/tokens
            val prefs = getSharedPreferences("LoginPrefs", MODE_PRIVATE)
            prefs.edit().clear().apply()

            Log.d("MainActivity", "Logout completed, navigating to login")

            // Navigate back to login activity
            startActivity(Intent(this@MainActivity, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            finish()

        } catch (e: Exception) {
            Log.e("MainActivity", "Error during logout", e)
            withContext(Dispatchers.Main) {
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("Logout Error")
                    .setMessage("An error occurred during logout. Please try again.")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val requestPermissionLauncher =
                registerForActivityResult(RequestPermission()
                ) { isGranted: Boolean ->
                }
            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

val mainActivityAlias = "${MainActivity::class.java.name}Alias"