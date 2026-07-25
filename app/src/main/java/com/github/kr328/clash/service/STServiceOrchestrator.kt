package com.github.kr328.clash.service

import android.content.Context
import android.content.SharedPreferences
import android.content.Intent
import android.util.Log
import androidx.work.*
import com.github.kr328.clash.LoginActivity
import com.github.kr328.clash.util.withProfile
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.stopClashService
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.TXTRecordParser
import com.github.kr328.clash.service.model.Profile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture

class ProfileUpdateException(message: String) : Exception(message)

class STServiceOrchestrator private constructor() {

    companion object {
        @Volatile
        private var INSTANCE: STServiceOrchestrator? = null

        fun getInstance(): STServiceOrchestrator {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: STServiceOrchestrator().also { INSTANCE = it }
            }
        }

        private const val WORK_TAG = "STServiceMonitoring"
        private const val PREFS_NAME = "st_service_prefs"
        private const val PREF_LOGIN_STATUS = "login_status"
        private const val PREF_LAST_CHECK = "last_check"
        private const val PREF_ERROR_MESSAGE = "error_message"
        private const val PREF_CACHED_TXT_RECORD = "cached_txt_record"
        private const val PREF_DYNU_API_KEY = "dynu_api_key"

        private const val DYNU_API_BASE_URL = "https://api.dynu.com/v2/dns"
        private const val CHECK_INTERVAL_MINUTES = 15L // WorkManager minimum
        private const val TEST_INTERVAL_MINUTES = 1L // For testing only

        // Retry policy for profile delete/create during link updates
        private const val PROFILE_OP_MAX_ATTEMPTS = 5
        private const val PROFILE_OP_RETRY_DELAY_MS = 1000L

        const val LOGIN_ERROR_EXPIRED = "Your node code has expired, please contact ST Service to buy a new node code for a new time period."
        const val LOGIN_ERROR_BANNED = "You have been banned"
        const val LOGIN_ERROR_NETWORK = "Network error during validation"
    }
    
    
    fun startMonitoring(context: Context) {
        Log.d("STServiceOrchestrator", "Starting ST Service monitoring")
        
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        
        val workRequest = PeriodicWorkRequestBuilder<MonitoringWorker>(
            CHECK_INTERVAL_MINUTES, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .addTag(WORK_TAG)
            .build()
        
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_TAG,
            ExistingPeriodicWorkPolicy.REPLACE,
            workRequest
        )
    }
    
    fun stopMonitoring(context: Context) {
        Log.d("STServiceOrchestrator", "Stopping ST Service monitoring")
        WorkManager.getInstance(context).cancelAllWorkByTag(WORK_TAG)
    }
    
    fun setLoginStatus(context: Context, isLoggedIn: Boolean) {
        val prefs = getPrefs(context)
        prefs.edit().putBoolean(PREF_LOGIN_STATUS, isLoggedIn).apply()
        
        // No longer using WorkManager background monitoring
        // Checks are performed on service start instead
    }
    
    fun isLoggedIn(context: Context): Boolean {
        return getPrefs(context).getBoolean(PREF_LOGIN_STATUS, false)
    }
    
    fun setErrorMessage(context: Context, message: String?) {
        val prefs = getPrefs(context)
        if (message != null) {
            prefs.edit().putString(PREF_ERROR_MESSAGE, message).apply()
        } else {
            prefs.edit().remove(PREF_ERROR_MESSAGE).apply()
        }
    }
    
    fun getErrorMessage(context: Context): String? {
        return getPrefs(context).getString(PREF_ERROR_MESSAGE, null)
    }
    
    fun clearErrorMessage(context: Context) {
        setErrorMessage(context, null)
    }
    
    fun getCachedTxtRecordForLogin(context: Context): com.github.kr328.clash.util.TXTRecordInfo? {
        return getCachedTxtRecord(context)
    }
    
    fun cacheTxtRecordForLogin(context: Context, txtInfo: com.github.kr328.clash.util.TXTRecordInfo) {
        cacheTxtRecord(context, txtInfo)
    }
    
    fun setDynuApiKey(context: Context, apiKey: String) {
        val prefs = getPrefs(context)
        prefs.edit().putString(PREF_DYNU_API_KEY, apiKey).apply()
        Log.d("STServiceOrchestrator", "Dynu API key stored")
    }
    
    private fun getDynuApiKey(context: Context): String? {
        return getPrefs(context).getString(PREF_DYNU_API_KEY, null)
    }
    
    suspend fun updateConsumedStatus(context: Context, txtInfo: com.github.kr328.clash.util.TXTRecordInfo) {
        try {
            val nodeCode = getNodeCodeFromActiveProfile()
            if (nodeCode == null) {
                Log.w("STServiceOrchestrator", "No node code found for consumed update")
                return
            }
            
            val apiKey = getDynuApiKey(context)
            if (apiKey == null) {
                Log.w("STServiceOrchestrator", "No Dynu API key configured - skipping consumed update")
                return
            }
            
            if (txtInfo.domainId == 0L || txtInfo.recordId == 0L) {
                Log.w("STServiceOrchestrator", "Missing domain_id or record_id - cannot update consumed status")
                return
            }
            
            Log.d("STServiceOrchestrator", "Updating consumed=true via Dynu API for node: $nodeCode")
            
            // Create updated JSON with consumed=true
            val updatedJson = createUpdatedTxtRecord(txtInfo, nodeCode, consumed = true)
            
            // Call Dynu API
            val success = callDynuApiToUpdateRecord(apiKey, txtInfo.domainId, txtInfo.recordId, nodeCode, updatedJson)
            
            if (success) {
                Log.d("STServiceOrchestrator", "Successfully updated consumed=true via Dynu API")
                // Update local cache with consumed=true
                val updatedTxtInfo = txtInfo.copy(consumed = true, modified = System.currentTimeMillis() / 1000L)
                cacheTxtRecord(context, updatedTxtInfo)
            } else {
                Log.w("STServiceOrchestrator", "Failed to update consumed status via Dynu API")
            }
            
        } catch (e: Exception) {
            Log.e("STServiceOrchestrator", "Error updating consumed status", e)
        }
    }
    
    private fun createUpdatedTxtRecord(txtInfo: com.github.kr328.clash.util.TXTRecordInfo, nodeCode: String, consumed: Boolean): String {
        val json = org.json.JSONObject()
        json.put("v", txtInfo.version)
        json.put("b", txtInfo.banned)
        json.put("e", txtInfo.expiration?.let { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(it) })
        json.put("c", consumed)
        json.put("m", System.currentTimeMillis() / 1000L) // Update modified timestamp
        json.put("u", txtInfo.vpnUrl)
        json.put("d", txtInfo.domainId)
        json.put("r", txtInfo.recordId)
        return json.toString()
    }
    
    private suspend fun callDynuApiToUpdateRecord(apiKey: String, domainId: Long, recordId: Long, nodeCode: String, textData: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL("$DYNU_API_BASE_URL/$domainId/record/$recordId")
            val connection = url.openConnection() as HttpURLConnection
            
            connection.requestMethod = "POST"
            connection.setRequestProperty("API-Key", apiKey)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 30000
            connection.readTimeout = 30000
            
            val requestBody = org.json.JSONObject().apply {
                put("nodeName", nodeCode)
                put("recordType", "TXT")
                put("ttl", 60)
                put("state", true)
                put("textData", textData)
            }
            
            Log.d("STServiceOrchestrator", "Dynu API request: ${requestBody}")
            
            connection.outputStream.use { os ->
                os.write(requestBody.toString().toByteArray())
            }
            
            val responseCode = connection.responseCode
            val responseBody = if (responseCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream.bufferedReader().readText()
            } else {
                connection.errorStream?.bufferedReader()?.readText() ?: "No error details"
            }
            
            Log.d("STServiceOrchestrator", "Dynu API response code: $responseCode")
            Log.d("STServiceOrchestrator", "Dynu API response body: $responseBody")
            
            connection.disconnect()
            
            responseCode == HttpURLConnection.HTTP_OK
            
        } catch (e: Exception) {
            Log.e("STServiceOrchestrator", "Error calling Dynu API", e)
            false
        }
    }
    
    private fun cacheTxtRecord(context: Context, txtInfo: com.github.kr328.clash.util.TXTRecordInfo) {
        val prefs = getPrefs(context)
        val jsonString = serializeTxtRecord(txtInfo)
        prefs.edit().putString(PREF_CACHED_TXT_RECORD, jsonString).apply()
        Log.d("STServiceOrchestrator", "Cached TXT record with modified timestamp: ${txtInfo.modified}")
    }
    
    private fun getCachedTxtRecord(context: Context): com.github.kr328.clash.util.TXTRecordInfo? {
        val prefs = getPrefs(context)
        val jsonString = prefs.getString(PREF_CACHED_TXT_RECORD, null) ?: return null
        return deserializeTxtRecord(jsonString)
    }
    
    private fun serializeTxtRecord(txtInfo: com.github.kr328.clash.util.TXTRecordInfo): String {
        val json = org.json.JSONObject()
        json.put("v", txtInfo.version)
        json.put("b", txtInfo.banned)
        json.put("e", txtInfo.expiration?.let { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(it) })
        json.put("c", txtInfo.consumed)
        json.put("m", txtInfo.modified)
        json.put("u", txtInfo.vpnUrl)
        json.put("d", txtInfo.domainId)
        json.put("r", txtInfo.recordId)
        return json.toString()
    }
    
    private fun deserializeTxtRecord(jsonString: String): com.github.kr328.clash.util.TXTRecordInfo? {
        return try {
            val json = org.json.JSONObject(jsonString)
            val version = json.optInt("v", 1)
            val banned = json.optBoolean("b", false)
            val consumed = json.optBoolean("c", false)
            val modified = json.optLong("m", 0L)
            val vpnUrl = json.optString("u", "")
            val domainId = json.optLong("d", 0L)
            val recordId = json.optLong("r", 0L)
            
            var expiration: Date? = null
            val expirationStr = json.optString("e", "")
            if (!expirationStr.isNullOrEmpty() && expirationStr != "null") {
                expiration = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(expirationStr)
            }
            
            com.github.kr328.clash.util.TXTRecordInfo(
                version = version,
                banned = banned,
                expiration = expiration,
                consumed = consumed,
                modified = modified,
                vpnUrl = if (vpnUrl.isNotEmpty()) vpnUrl else null,
                domainId = domainId,
                recordId = recordId
            )
        } catch (e: Exception) {
            Log.e("STServiceOrchestrator", "Error deserializing cached TXT record", e)
            null
        }
    }

    private suspend fun getNodeCodeFromActiveProfile(): String? {
        return try {
            withProfile {
                // First try to get node code from active profile
                val activeProfile = queryActive()
                val nodeCodeFromActive = activeProfile?.name?.let { name ->
                    // Extract node code from profile name like "ST VPN Service (DU536623)"
                    val regex = "\\(([^)]+)\\)".toRegex()
                    regex.find(name)?.groupValues?.get(1)
                }

                if (nodeCodeFromActive != null) {
                    return@withProfile nodeCodeFromActive
                }

                // If no active profile or node code not found, try all profiles
                // This handles the case where profile failed and became inactive
                val allProfiles = queryAll()
                for (profile in allProfiles) {
                    val regex = "\\(([^)]+)\\)".toRegex()
                    val nodeCode = regex.find(profile.name)?.groupValues?.get(1)
                    if (nodeCode != null) {
                        Log.d("STServiceOrchestrator", "Found node code '$nodeCode' from inactive profile: ${profile.name}")
                        return@withProfile nodeCode
                    }
                }

                null
            }
        } catch (e: Exception) {
            Log.e("STServiceOrchestrator", "Error getting node code from active profile", e)
            null
        }
    }
    
    private fun buildTXTCheckURL(nodeCode: String): String {
        val lastTwoDigits = nodeCode.takeLast(2)
        val domain = "$nodeCode.stservice$lastTwoDigits.ddnsfree.com"
        return "https://dns.alidns.com/resolve?name=$domain&type=TXT"
    }
    
    suspend fun performImmediateCheck(context: Context) {
        Log.d("STServiceOrchestrator", "Performing immediate check for testing")
        
        if (!isLoggedIn(context)) {
            Log.d("STServiceOrchestrator", "User not logged in, skipping immediate check")
            return
        }
        
        val txtInfo = fetchTXTRecordWithCache(context)
        if (txtInfo == null) {
            Log.w("STServiceOrchestrator", "Failed to check TXT record in immediate check")
            return
        }
        
        // Check for ban
        if (txtInfo.banned) {
            Log.w("STServiceOrchestrator", "User has been banned (immediate check)")
            try {
                logout(context, LOGIN_ERROR_BANNED)
            } catch (e: Exception) {
                Log.e("STServiceOrchestrator", "Error during logout for ban (immediate)", e)
            }
            return
        }
        
        // Check for expiration
        txtInfo.expiration?.let { expirationDate ->
            val currentDate = Date()
            if (currentDate.after(expirationDate)) {
                Log.w("STServiceOrchestrator", "Node code has expired (immediate check): $expirationDate")
                try {
                    logout(context, LOGIN_ERROR_EXPIRED)
                } catch (e: Exception) {
                    Log.e("STServiceOrchestrator", "Error during logout for expiration (immediate)", e)
                }
                return
            }
        }
        
        Log.d("STServiceOrchestrator", "Immediate check passed - no issues found")
    }
    
    suspend fun logout(context: Context, errorMessage: String? = null) {
        Log.d("STServiceOrchestrator", "Logging out user, stopping service and deleting all profiles")
        
        try {
            // Stop monitoring first
            stopMonitoring(context)
            
            // Stop the Clash service
            Log.d("STServiceOrchestrator", "Stopping Clash service")
            context.stopClashService()
            
            withProfile {
                // Get all profiles and delete them
                val profiles = queryAll()
                Log.d("STServiceOrchestrator", "Found ${profiles.size} profiles to delete")
                for (profile in profiles) {
                    try {
                        Log.d("STServiceOrchestrator", "Deleting profile: ${profile.name} (${profile.uuid})")
                        delete(profile.uuid)
                        Log.d("STServiceOrchestrator", "Successfully deleted profile: ${profile.uuid}")
                    } catch (e: Exception) {
                        Log.e("STServiceOrchestrator", "Failed to delete profile: ${profile.uuid}", e)
                    }
                }
                
                // Verify all profiles were deleted
                val remainingProfiles = queryAll()
                if (remainingProfiles.isEmpty()) {
                    Log.d("STServiceOrchestrator", "All profiles deleted successfully")
                } else {
                    Log.w("STServiceOrchestrator", "Warning: ${remainingProfiles.size} profiles still remain after logout")
                    for (remaining in remainingProfiles) {
                        Log.w("STServiceOrchestrator", "Remaining profile: ${remaining.name} (${remaining.uuid})")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("STServiceOrchestrator", "Error during logout process", e)
        }
        
        // Set logged out status and error message
        setLoginStatus(context, false)
        if (errorMessage != null) {
            setErrorMessage(context, errorMessage)
        }
        
        // Redirect to login screen immediately
        redirectToLogin(context)
    }
    
    fun redirectToLogin(context: Context) {
        val intent = Intent(context, com.github.kr328.clash.LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        context.startActivity(intent)
    }
    
    /**
     * Rebuilds all local profiles to point at [newLink] when a mismatch is detected.
     * Throws [ProfileUpdateException] if a delete or create cannot be confirmed against
     * the actual profile store after [PROFILE_OP_MAX_ATTEMPTS] attempts; callers should
     * surface that to the user.
     *
     * [onProfileUpdateStart]/[onProfileUpdateEnd] fire only when a rebuild is actually
     * happening, so callers can drive UI feedback (e.g. disable the start/stop button)
     * without this class depending on any UI layer.
     */
    suspend fun checkAndUpdateLink(
        context: Context,
        newLink: String,
        onProfileUpdateStart: (suspend () -> Unit)? = null,
        onProfileUpdateEnd: (suspend () -> Unit)? = null
    ): Boolean {
        Log.d("LINK_UPDATE", "===== START LINK CHECK =====")
        Log.d("LINK_UPDATE", "New link from AliDNS: $newLink")

        var wasServiceRunning = false
        withClash {
            wasServiceRunning = queryTunnelState() != null
        }
        Log.d("LINK_UPDATE", "Service running status: $wasServiceRunning")

        val profiles = withProfile { queryAll() }
        Log.d("LINK_UPDATE", "Total profiles found: ${profiles.size}")
        for (profile in profiles) {
            Log.d("LINK_UPDATE", "  Profile: ${profile.name} (${profile.uuid}) source=${profile.source}")
        }

        if (profiles.isNotEmpty() && profiles.all { it.source == newLink }) {
            Log.d("LINK_UPDATE", "Profile link matches - no update needed")
            Log.d("LINK_UPDATE", "===== END LINK CHECK =====")
            return false
        }

        Log.d("LINK_UPDATE", "LINK MISMATCH DETECTED - rebuilding profile(s)")
        Log.d("LINK_UPDATE", "  New link: $newLink")

        // Preserve name/interval from the profile being replaced
        val template = profiles.firstOrNull { it.active } ?: profiles.firstOrNull()
        val profileName = template?.name ?: "ST VPN Service"
        val profileInterval = template?.interval ?: 0L

        onProfileUpdateStart?.invoke()
        try {
            if (wasServiceRunning) {
                Log.d("LINK_UPDATE", "Stopping service for link update...")
                context.stopClashService()
                kotlinx.coroutines.delay(1000)
            }

            // Delete every existing profile, confirming each deletion against the real profile store
            for (profile in profiles) {
                deleteProfileWithRetry(profile.uuid)
            }

            // Create the replacement profile, confirming it against the real profile store
            val newUuid = createProfileWithRetry(profileName, newLink, profileInterval)

            val updatedProfile = withProfile { queryByUUID(newUuid) }
            Log.d("LINK_UPDATE", "===== PROFILE AFTER UPDATE =====")
            Log.d("LINK_UPDATE", "Profile name: ${updatedProfile?.name}")
            Log.d("LINK_UPDATE", "Profile UUID: ${updatedProfile?.uuid}")
            Log.d("LINK_UPDATE", "Updated profile.source: ${updatedProfile?.source}")
            Log.d("LINK_UPDATE", "Profile imported: ${updatedProfile?.imported}")

            if (updatedProfile != null) {
                withProfile { setActive(updatedProfile) }
                Log.d("LINK_UPDATE", "Profile set as active")
            }

            // Restart service if it was running before AND profile imported successfully
            if (wasServiceRunning) {
                if (updatedProfile?.imported == true) {
                    Log.d("LINK_UPDATE", "Profile imported successfully, restarting service...")
                    context.startClashService()
                    Log.d("LINK_UPDATE", "Service restarted successfully")
                } else {
                    Log.w("LINK_UPDATE", "Profile not imported (fetch failed), service will remain stopped")
                    Log.w("LINK_UPDATE", "User can manually start VPN once link is fixed")
                }
            }

            Log.d("LINK_UPDATE", "===== END LINK CHECK =====")
            Log.d("LINK_UPDATE", "Profile was updated: SUCCESS")
            return true
        } catch (e: ProfileUpdateException) {
            Log.e("LINK_UPDATE", "ERROR during link check/update: ${e.message}", e)
            throw e
        } finally {
            onProfileUpdateEnd?.invoke()
        }
    }

    /**
     * Deletes [uuid] and confirms the deletion by re-querying the actual profile store
     * (not just trusting that delete() returned without throwing).
     */
    private suspend fun deleteProfileWithRetry(uuid: UUID) {
        repeat(PROFILE_OP_MAX_ATTEMPTS) { attempt ->
            Log.d("LINK_UPDATE", "Delete attempt ${attempt + 1}/$PROFILE_OP_MAX_ATTEMPTS for profile $uuid")

            try {
                withProfile { delete(uuid) }
            } catch (e: Exception) {
                Log.e("LINK_UPDATE", "delete() threw on attempt ${attempt + 1} for profile $uuid", e)
            }

            kotlinx.coroutines.delay(PROFILE_OP_RETRY_DELAY_MS)

            if (isProfileDeleted(uuid)) {
                Log.d("LINK_UPDATE", "Profile $uuid confirmed deleted")
                return
            }

            Log.w("LINK_UPDATE", "Profile $uuid still present after attempt ${attempt + 1}")
        }

        throw ProfileUpdateException("Failed to delete profile $uuid after $PROFILE_OP_MAX_ATTEMPTS attempts")
    }

    /** Queries the real profile store to confirm [uuid] no longer exists. */
    private suspend fun isProfileDeleted(uuid: UUID): Boolean {
        return withProfile { queryByUUID(uuid) } == null
    }

    /**
     * Creates a profile pointed at [link] and confirms it via [isProfileCreatedWithLink]
     * against the actual profile store before trusting it, retrying on failure.
     */
    private suspend fun createProfileWithRetry(name: String, link: String, interval: Long): UUID {
        repeat(PROFILE_OP_MAX_ATTEMPTS) { attempt ->
            Log.d("LINK_UPDATE", "Create attempt ${attempt + 1}/$PROFILE_OP_MAX_ATTEMPTS for link $link")

            val uuid = withProfile { create(Profile.Type.Url, name, link) }
            withProfile { patch(uuid, name, link, interval) }

            try {
                withProfile { commit(uuid) }
                withProfile { update(uuid) }
            } catch (e: Exception) {
                Log.e("LINK_UPDATE", "commit/update failed on attempt ${attempt + 1} for profile $uuid", e)
            }

            kotlinx.coroutines.delay(PROFILE_OP_RETRY_DELAY_MS)

            if (isProfileCreatedWithLink(uuid, link)) {
                Log.d("LINK_UPDATE", "Profile $uuid confirmed created with correct link")
                return uuid
            }

            Log.w("LINK_UPDATE", "Profile $uuid failed validation after attempt ${attempt + 1}, discarding before retry")
            try {
                withProfile { delete(uuid) }
            } catch (e: Exception) {
                Log.e("LINK_UPDATE", "Failed to clean up invalid profile $uuid", e)
            }
        }

        throw ProfileUpdateException("Failed to create profile for link $link after $PROFILE_OP_MAX_ATTEMPTS attempts")
    }

    /** Queries the real profile store to confirm [uuid] exists with the expected link. */
    private suspend fun isProfileCreatedWithLink(uuid: UUID, expectedLink: String): Boolean {
        val profile = withProfile { queryByUUID(uuid) } ?: return false
        return profile.source == expectedLink
    }


    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    suspend fun fetchTXTRecordWithMandatoryCheck(context: Context): com.github.kr328.clash.util.TXTRecordInfo? {
        val cachedTxt = getCachedTxtRecord(context)
        val freshTxt = checkTXTRecordMandatory()
        
        if (cachedTxt != null && freshTxt != null) {
            if (freshTxt.modified <= cachedTxt.modified) {
                Log.d("STServiceOrchestrator", "DNS returned stale data (${freshTxt.modified} <= ${cachedTxt.modified}), using cached")
                return cachedTxt
            } else {
                Log.d("STServiceOrchestrator", "DNS returned fresh data (${freshTxt.modified} > ${cachedTxt.modified}), updating cache")
                cacheTxtRecord(context, freshTxt)
                return freshTxt
            }
        } else if (freshTxt != null) {
            Log.d("STServiceOrchestrator", "No cached data, using fresh DNS data (${freshTxt.modified})")
            cacheTxtRecord(context, freshTxt)
            return freshTxt
        } else if (cachedTxt != null) {
            Log.w("STServiceOrchestrator", "DNS fetch failed, but this is mandatory - not using cached data")
            return null // Don't use cached data for mandatory checks
        } else {
            Log.w("STServiceOrchestrator", "No cached or fresh data available for mandatory check")
            return null
        }
    }
    
    suspend fun fetchTXTRecordWithCache(context: Context): com.github.kr328.clash.util.TXTRecordInfo? {
        val cachedTxt = getCachedTxtRecord(context)
        val freshTxt = checkTXTRecord()
        
        if (cachedTxt != null && freshTxt != null) {
            if (freshTxt.modified <= cachedTxt.modified) {
                Log.d("STServiceOrchestrator", "DNS returned stale data (${freshTxt.modified} <= ${cachedTxt.modified}), using cached")
                return cachedTxt
            } else {
                Log.d("STServiceOrchestrator", "DNS returned fresh data (${freshTxt.modified} > ${cachedTxt.modified}), updating cache")
                cacheTxtRecord(context, freshTxt)
                return freshTxt
            }
        } else if (freshTxt != null) {
            Log.d("STServiceOrchestrator", "No cached data, using fresh DNS data (${freshTxt.modified})")
            cacheTxtRecord(context, freshTxt)
            return freshTxt
        } else if (cachedTxt != null) {
            Log.d("STServiceOrchestrator", "DNS fetch failed, using cached data (${cachedTxt.modified})")
            return cachedTxt
        } else {
            Log.w("STServiceOrchestrator", "No cached or fresh data available")
            return null
        }
    }
    
    private suspend fun checkTXTRecordMandatory(): com.github.kr328.clash.util.TXTRecordInfo? = withContext(Dispatchers.IO) {
        try {
            val nodeCode = getNodeCodeFromActiveProfile()
            if (nodeCode == null) {
                Log.w("STServiceOrchestrator", "No node code found in active profile")
                return@withContext null
            }
            
            val txtCheckURL = buildTXTCheckURL(nodeCode)
            Log.d("STServiceOrchestrator", "Mandatory TXT record check for: $txtCheckURL")
            
            val result = withTimeoutOrNull(30000) {
                val url = URL(txtCheckURL)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                
                val responseCode = connection.responseCode
                Log.d("STServiceOrchestrator", "Mandatory check response code: $responseCode")
                
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().readText()
                    connection.disconnect()
                    response
                } else {
                    connection.disconnect()
                    throw Exception("HTTP $responseCode")
                }
            }
            
            result?.let { parseTXTResponse(it) }
        } catch (e: Exception) {
            Log.e("STServiceOrchestrator", "Error in mandatory TXT record check", e)
            throw e // Re-throw for mandatory checks
        }
    }
    
    private suspend fun checkTXTRecord(): com.github.kr328.clash.util.TXTRecordInfo? = withContext(Dispatchers.IO) {
        try {
            val nodeCode = getNodeCodeFromActiveProfile()
            if (nodeCode == null) {
                Log.w("STServiceOrchestrator", "No node code found in active profile")
                return@withContext null
            }
            
            val txtCheckURL = buildTXTCheckURL(nodeCode)
            Log.d("STServiceOrchestrator", "Checking TXT record for: $txtCheckURL")
            
            val result = withTimeoutOrNull(30000) {
                val url = URL(txtCheckURL)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                
                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().readText()
                    connection.disconnect()
                    response
                } else {
                    connection.disconnect()
                    null
                }
            }
            
            result?.let { parseTXTResponse(it) }
        } catch (e: Exception) {
            Log.e("STServiceOrchestrator", "Error checking TXT record", e)
            null
        }
    }
    
    private fun parseTXTResponse(response: String): com.github.kr328.clash.util.TXTRecordInfo? {
        try {
            val json = JSONObject(response)
            val answers = json.optJSONArray("Answer") ?: return null
            
            for (i in 0 until answers.length()) {
                val answer = answers.getJSONObject(i)
                if (answer.optInt("type") == 16) { // TXT record
                    val data = answer.optString("data", "")
                    return TXTRecordParser.parseTXTData(data)
                }
            }
        } catch (e: Exception) {
            Log.e("STServiceOrchestrator", "Error parsing TXT response", e)
        }
        return null
    }
    
    
    class MonitoringWorker(
        context: Context,
        params: WorkerParameters
    ) : CoroutineWorker(context, params) {
        
        override suspend fun doWork(): Result {
            Log.d("STServiceOrchestrator", "Running monitoring check")
            
            val orchestrator = getInstance()
            if (!orchestrator.isLoggedIn(applicationContext)) {
                Log.d("STServiceOrchestrator", "User not logged in, skipping check")
                return Result.success()
            }
            
            // Double check - ensure we have user profiles (means user is actually logged in)
            try {
                val hasProfiles = withProfile { 
                    queryAll().isNotEmpty() 
                }
                if (!hasProfiles) {
                    Log.d("STServiceOrchestrator", "No user profiles found, user appears logged out - skipping check")
                    // User is logged out, stop monitoring to prevent future unnecessary checks
                    orchestrator.stopMonitoring(applicationContext)
                    orchestrator.setLoginStatus(applicationContext, false)
                    return Result.success()
                }
            } catch (e: Exception) {
                Log.e("STServiceOrchestrator", "Error checking profiles, skipping monitoring", e)
                return Result.success()
            }
            
            val txtInfo = orchestrator.fetchTXTRecordWithCache(applicationContext)
            if (txtInfo == null) {
                Log.w("STServiceOrchestrator", "Failed to check TXT record")
                return Result.retry()
            }
            
            val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putLong(PREF_LAST_CHECK, System.currentTimeMillis()).apply()
            
            // Check for ban
            if (txtInfo.banned) {
                Log.w("STServiceOrchestrator", "User has been banned")
                try {
                    orchestrator.logout(applicationContext, LOGIN_ERROR_BANNED)
                } catch (e: Exception) {
                    Log.e("STServiceOrchestrator", "Error during logout for ban", e)
                }
                return Result.success()
            }
            
            // Check for expiration
            txtInfo.expiration?.let { expirationDate ->
                val currentDate = Date()
                if (currentDate.after(expirationDate)) {
                    Log.w("STServiceOrchestrator", "Node code has expired: $expirationDate")
                    try {
                        orchestrator.logout(applicationContext, LOGIN_ERROR_EXPIRED)
                    } catch (e: Exception) {
                        Log.e("STServiceOrchestrator", "Error during logout for expiration", e)
                    }
                    return Result.success()
                }
            }
            
            // Check for link changes and update if needed
            txtInfo.link?.let { newLink ->
                try {
                    val linkChanged = orchestrator.checkAndUpdateLink(applicationContext, newLink)
                    if (linkChanged) {
                        Log.d("STServiceOrchestrator", "Profile link updated successfully")
                    }
                } catch (e: Exception) {
                    Log.e("STServiceOrchestrator", "Error updating profile link", e)
                }
            }
            
            Log.d("STServiceOrchestrator", "Monitoring check passed")
            return Result.success()
        }
    }
}