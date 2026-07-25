package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import androidx.appcompat.app.AlertDialog
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.core.util.trafficTotal
import com.github.kr328.clash.design.databinding.DesignAboutBinding
import com.github.kr328.clash.design.databinding.DesignMainBinding
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.resolveThemedColor
import com.github.kr328.clash.design.util.root
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainDesign(context: Context) : Design<MainDesign.Request>(context) {
    enum class Request {
        ToggleStatus,
        OpenProxy,
        OpenProfiles,
        OpenProviders,
        OpenLogs,
        OpenSettings,
        OpenHelp,
        OpenAbout,
        Logout,
    }

    private val binding = DesignMainBinding
        .inflate(context.layoutInflater, context.root, false)

    override val root: View
        get() = binding.root

    suspend fun setProfileName(name: String?) {
        withContext(Dispatchers.Main) {
            binding.profileName = name
        }
    }

    suspend fun setClashRunning(running: Boolean) {
        withContext(Dispatchers.Main) {
            binding.clashRunning = running
        }
    }

    suspend fun setForwarded(value: Long) {
        withContext(Dispatchers.Main) {
            binding.forwarded = value.trafficTotal()
        }
    }

    suspend fun setMode(mode: TunnelState.Mode) {
        withContext(Dispatchers.Main) {
            binding.mode = when (mode) {
                TunnelState.Mode.Direct -> context.getString(R.string.direct_mode)
                TunnelState.Mode.Global -> context.getString(R.string.global_mode)
                TunnelState.Mode.Rule -> context.getString(R.string.rule_mode)
                else -> context.getString(R.string.rule_mode)
            }
        }
    }

    suspend fun setHasProviders(has: Boolean) {
        withContext(Dispatchers.Main) {
            binding.hasProviders = has
        }
    }

    suspend fun setStartEnabled(enabled: Boolean) {
        withContext(Dispatchers.Main) {
            binding.startEnabled = enabled
        }
    }

    suspend fun showValidationError(message: String) {
        withContext(Dispatchers.Main) {
            binding.validationMessageText = message
        }
    }

    suspend fun clearValidationError() {
        withContext(Dispatchers.Main) {
            binding.validationMessageText = null
        }
    }

    suspend fun showFeedbackPanel(message: String, showProgress: Boolean = false) {
        withContext(Dispatchers.Main) {
            binding.feedbackPanel.visibility = View.VISIBLE
            binding.feedbackMessage.text = message
            binding.progressBar.visibility = if (showProgress) View.VISIBLE else View.GONE
        }
    }

    suspend fun hideFeedbackPanel() {
        withContext(Dispatchers.Main) {
            binding.feedbackPanel.visibility = View.GONE
            binding.progressBar.visibility = View.GONE
        }
    }

    suspend fun updateFeedbackMessage(message: String, showProgress: Boolean = false) {
        withContext(Dispatchers.Main) {
            binding.feedbackMessage.text = message
            binding.progressBar.visibility = if (showProgress) View.VISIBLE else View.GONE
        }
    }

    suspend fun showAbout(versionName: String) {
        withContext(Dispatchers.Main) {
            val binding = DesignAboutBinding.inflate(context.layoutInflater).apply {
                this.versionName = versionName
            }

            AlertDialog.Builder(context)
                .setView(binding.root)
                .show()
        }
    }

    fun showLogoutButton() {
        binding.logoutButton.visibility = View.VISIBLE
    }

    fun hideLogoutButton() {
        binding.logoutButton.visibility = View.GONE
    }

    fun setLogoClickListener(listener: () -> Unit) {
        binding.logoText.setOnClickListener { listener() }
    }

    init {
        binding.self = this

        binding.colorClashStarted = context.resolveThemedColor(com.google.android.material.R.attr.colorPrimary)
        binding.colorClashStopped = context.resolveThemedColor(R.attr.colorClashStopped)
        // Enable start action by default
        binding.startEnabled = true
    }

    fun request(request: Request) {
        requests.trySend(request)
    }
}