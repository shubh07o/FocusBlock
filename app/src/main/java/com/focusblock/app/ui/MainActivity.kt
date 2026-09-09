package com.focusblock.app.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.focusblock.app.R
import com.focusblock.app.data.PreferencesHelper
import com.focusblock.app.databinding.ActivityMainBinding
import com.focusblock.app.service.FocusBlockAccessibilityService

/**
 * Minimalist UI for FocusBlock.
 * Displays protection status, Master ON/OFF toggle, and Accessibility permission deep-link.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var preferencesHelper: PreferencesHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preferencesHelper = PreferencesHelper.getInstance(this)

        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        updateUIState()
    }

    private fun setupListeners() {
        binding.switchMaster.setOnCheckedChangeListener { _, isChecked ->
            preferencesHelper.isProtectionEnabled = isChecked
            updateUIState()
        }

        binding.btnEnableAccessibility.setOnClickListener {
            openAccessibilitySettings()
        }
    }

    private fun updateUIState() {
        val isServiceEnabled = isAccessibilityServiceEnabled()
        val isMasterOn = preferencesHelper.isProtectionEnabled

        // Update Master switch without re-triggering listener
        binding.switchMaster.setOnCheckedChangeListener(null)
        binding.switchMaster.isChecked = isMasterOn
        binding.switchMaster.setOnCheckedChangeListener { _, isChecked ->
            preferencesHelper.isProtectionEnabled = isChecked
            updateUIState()
        }

        if (!isServiceEnabled) {
            // Permission needed
            binding.tvStatusBadge.text = getString(R.string.status_permission_required)
            binding.tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_red))
            binding.ivAppIcon.setImageResource(R.drawable.ic_shield_pause)

            binding.cardPermission.strokeColor = ContextCompat.getColor(this, R.color.status_amber)
            binding.ivPermissionIcon.setImageResource(R.drawable.ic_shield_pause)
            binding.tvPermissionTitle.text = getString(R.string.permission_card_title)
            binding.tvPermissionDesc.text = getString(R.string.permission_card_desc_disabled)
            binding.btnEnableAccessibility.text = getString(R.string.btn_enable_accessibility)
            binding.btnEnableAccessibility.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(this, R.color.accent_primary))
        } else if (isMasterOn) {
            // Fully Active
            binding.tvStatusBadge.text = getString(R.string.status_active)
            binding.tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_green))
            binding.ivAppIcon.setImageResource(R.drawable.ic_shield_check)

            binding.cardPermission.strokeColor = ContextCompat.getColor(this, R.color.stroke_subtle)
            binding.ivPermissionIcon.setImageResource(R.drawable.ic_shield_check)
            binding.tvPermissionTitle.text = getString(R.string.permission_card_title)
            binding.tvPermissionDesc.text = getString(R.string.permission_card_desc_enabled)
            binding.btnEnableAccessibility.text = getString(R.string.btn_service_settings)
            binding.btnEnableAccessibility.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(this, R.color.bg_surface_variant))
        } else {
            // Paused by Master toggle
            binding.tvStatusBadge.text = getString(R.string.status_paused)
            binding.tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_amber))
            binding.ivAppIcon.setImageResource(R.drawable.ic_shield_pause)

            binding.cardPermission.strokeColor = ContextCompat.getColor(this, R.color.stroke_subtle)
            binding.ivPermissionIcon.setImageResource(R.drawable.ic_shield_check)
            binding.tvPermissionTitle.text = getString(R.string.permission_card_title)
            binding.tvPermissionDesc.text = getString(R.string.permission_card_desc_enabled)
            binding.btnEnableAccessibility.text = getString(R.string.btn_service_settings)
            binding.btnEnableAccessibility.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(this, R.color.bg_surface_variant))
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC or AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val expectedServiceName = "${packageName}/${FocusBlockAccessibilityService::class.java.name}"
        
        return enabledServices.any { serviceInfo ->
            val id = serviceInfo.id
            id.equals(expectedServiceName, ignoreCase = true) || id.contains("FocusBlockAccessibilityService", ignoreCase = true)
        } || FocusBlockAccessibilityService.isServiceRunning
    }

    private fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
