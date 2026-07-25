package com.github.kr328.clash.util

import android.util.Log
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

data class TXTRecordInfo(
    val version: Int,
    val banned: Boolean,
    val expiration: Date?,
    val consumed: Boolean,
    val modified: Long,
    val vpnUrl: String?,
    val domainId: Long,
    val recordId: Long,
    // Legacy fields for backward compatibility
    val message: String? = null,
    val link: String? = vpnUrl
)

object TXTRecordParser {
    private const val TAG = "TXTRecordParser"
    
    fun parseTXTData(txtData: String): TXTRecordInfo {
        try {
            // Remove outer quotes and unescape internal quotes
            var cleanData = txtData.removePrefix("\"").removeSuffix("\"")
            cleanData = cleanData.replace("\\\"", "\"")
            Log.d(TAG, "Parsing TXT JSON data: '$cleanData'")
            
            // Parse as JSON
            val json = JSONObject(cleanData)
            
            // Extract fields using shortened names
            val version = json.optInt("v", 1)
            val banned = json.optBoolean("b", false)
            val consumed = json.optBoolean("c", false)
            val modified = json.optLong("m", 0L)
            val vpnUrl = json.optString("u", "")
            val domainId = json.optLong("d", 0L)
            val recordId = json.optLong("r", 0L)
            
            // Parse expiration date
            var expiration: Date? = null
            val expirationStr = json.optString("e", "")
            if (!expirationStr.isNullOrEmpty()) {
                try {
                    val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                    expiration = dateFormat.parse(expirationStr)
                    Log.d(TAG, "Parsed expiration date: $expirationStr -> $expiration")
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing expiration date: $expirationStr", e)
                }
            }
            
            Log.d(TAG, "Parsed JSON TXT record: v=$version, b=$banned, e=$expiration, c=$consumed, m=$modified, u=$vpnUrl, d=$domainId, r=$recordId")
            
            return TXTRecordInfo(
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
            Log.e(TAG, "Error parsing TXT record JSON data", e)
            // Return default values if parsing fails
            return TXTRecordInfo(
                version = 1,
                banned = false,
                expiration = null,
                consumed = false,
                modified = 0L,
                vpnUrl = null,
                domainId = 0L,
                recordId = 0L
            )
        }
    }
    
    fun isExpired(txtInfo: TXTRecordInfo): Boolean {
        return txtInfo.expiration?.let { expirationDate ->
            val currentDate = Date()
            currentDate.after(expirationDate)
        } ?: false
    }
}