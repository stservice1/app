package com.github.kr328.clash

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.launch

class LauncherActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("LauncherActivity", "Starting launcher activity")
        
        lifecycleScope.launch {
            try {
                Log.d("LauncherActivity", "Checking for existing profiles...")
                val profiles = withProfile { queryAll() }
                if (profiles.isNotEmpty()) {
                    Log.d("LauncherActivity", "Found ${profiles.size} profiles")
                    Log.d("LauncherActivity", "Profile found, launching MainActivity")
                    startActivity(Intent(this@LauncherActivity, MainActivity::class.java))
                } else {
                    Log.d("LauncherActivity", "No profiles found, launching LoginActivity")
                    startActivity(Intent(this@LauncherActivity, LoginActivity::class.java))
                }
                finish()
            } catch (e: Exception) {
                Log.e("LauncherActivity", "Error checking profiles: ${e.message}")
                startActivity(Intent(this@LauncherActivity, LoginActivity::class.java))
                finish()
            }
        }
    }
}