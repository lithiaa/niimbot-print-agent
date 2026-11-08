package com.niimbot.printagent.ui

import android.content.Intent
import android.content.ActivityNotFoundException
import android.os.Bundle
import android.os.Build
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.niimbot.printagent.R
import com.niimbot.printagent.data.AppDatabase
import com.niimbot.printagent.data.PrintStatus
import com.niimbot.printagent.service.PrintForegroundService
import com.niimbot.printagent.update.ApkDownloadResult
import com.niimbot.printagent.update.AppUpdateClient
import com.niimbot.printagent.update.AppUpdateManifest
import com.niimbot.printagent.update.InstalledAppVersion
import com.niimbot.printagent.update.UpdateCheckResult
import com.niimbot.printagent.update.UpdateFailureReason
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject
    lateinit var database: AppDatabase

    @Inject
    lateinit var appUpdateClient: AppUpdateClient

    private lateinit var bottomNav: BottomNavigationView
    private lateinit var pageHeaderTitle: TextView
    private lateinit var labelNavButton: View
    private var updateJob: Job? = null
    private var updateDialog: AlertDialog? = null
    private var pendingUpdateFile: File? = null

    private val unknownSourcesLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val file = pendingUpdateFile ?: return@registerForActivityResult
        if (appUpdateClient.canRequestPackageInstalls()) {
            launchInstaller(file)
        } else {
            Toast.makeText(this, R.string.app_update_install_permission_denied, Toast.LENGTH_LONG)
                .show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        pageHeaderTitle = findViewById(R.id.tv_page_header_title)
        bottomNav = findViewById(R.id.bottom_navigation)
        labelNavButton = findViewById(R.id.btn_nav_label)
        labelNavButton.bringToFront()
        setupBottomNavigation(savedInstanceState)
        observePrintQueue()

        // Check permissions before starting service
        checkAndRequestPermissions()

        if (savedInstanceState == null) {
            window.decorView.post { checkForAppUpdate(manual = false) }
        }
    }

    private val PERMISSION_REQUEST_CODE = 1001

    private fun checkAndRequestPermissions() {
        val requiredPermissions = mutableListOf<String>()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        
        // Always request location for BLE scanning on some vendors
        requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        requiredPermissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)

        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            startPrintService()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            // Check if all permissions were granted
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startPrintService()
            } else {
                Log.w("MainActivity", "Permissions not fully granted, service not started.")
            }
        }
    }

    private fun setupBottomNavigation(savedInstanceState: Bundle?) {
        bottomNav.setOnItemSelectedListener { item ->
            showDestination(item.itemId)
        }
        bottomNav.setOnItemReselectedListener { item -> showDestination(item.itemId) }
        labelNavButton.setOnClickListener {
            if (bottomNav.selectedItemId == R.id.nav_label) {
                showDestination(R.id.nav_label)
            } else {
                bottomNav.selectedItemId = R.id.nav_label
            }
        }

        // Default to dashboard on first launch
        if (savedInstanceState == null) {
            bottomNav.selectedItemId = R.id.nav_dashboard
        }
    }

    private fun showDestination(itemId: Int): Boolean {
        val fragment = when (itemId) {
            R.id.nav_dashboard -> DashboardFragment().also {
                pageHeaderTitle.setText(R.string.dashboard_header_title)
            }
            R.id.nav_printer -> PrinterFragment().also {
                pageHeaderTitle.setText(R.string.printer_title)
            }
            R.id.nav_product_info -> ProductInfoFragment().also {
                pageHeaderTitle.setText(R.string.product_info_title)
            }
            R.id.nav_label -> LabelFragment().also {
                pageHeaderTitle.setText(R.string.create_label_nav)
            }
            R.id.nav_settings -> SettingsFragment().also {
                pageHeaderTitle.setText(R.string.settings_title)
            }
            else -> return false
        }
        labelNavButton.isSelected = itemId == R.id.nav_label
        supportFragmentManager.popBackStack(
            null,
            androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE
        )
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
        return true
    }

    fun selectLabelTab() {
        bottomNav.selectedItemId = R.id.nav_label
    }

    private fun observePrintQueue() {
        // Observe pending + printing jobs for badge count
        database.printJobDao().getByStatuses(
            listOf(PrintStatus.PENDING, PrintStatus.PRINTING)
        ).observe(this) { jobs ->
            val count = jobs?.size ?: 0
            bottomNav.getOrCreateBadge(R.id.nav_printer).apply {
                isVisible = count > 0
                number = count
            }
        }
    }

    private fun startPrintService() {
        val intent = Intent(this, PrintForegroundService::class.java).apply {
            action = PrintForegroundService.ACTION_START
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Log.i("MainActivity", "Print service start requested")
    }

    fun checkForAppUpdate(manual: Boolean) {
        if (updateJob?.isActive == true) {
            if (manual) {
                Toast.makeText(this, R.string.app_update_check_in_progress, Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (manual) {
            Toast.makeText(this, R.string.app_update_checking, Toast.LENGTH_SHORT).show()
        }
        updateJob = lifecycleScope.launch {
            when (val result = appUpdateClient.checkForUpdate(InstalledAppVersion.code(this@MainActivity))) {
                is UpdateCheckResult.Available -> showUpdatePrompt(
                    result.manifest,
                    result.required
                )
                UpdateCheckResult.UpToDate -> if (manual) {
                    Toast.makeText(
                        this@MainActivity,
                        R.string.app_update_up_to_date,
                        Toast.LENGTH_LONG
                    ).show()
                }
                UpdateCheckResult.NoPublishedRelease -> if (manual) {
                    Toast.makeText(
                        this@MainActivity,
                        R.string.app_update_no_release,
                        Toast.LENGTH_LONG
                    ).show()
                }
                is UpdateCheckResult.Failure -> if (manual) {
                    Toast.makeText(
                        this@MainActivity,
                        updateFailureMessage(result.reason),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            updateJob = null
        }
    }

    private fun showUpdatePrompt(manifest: AppUpdateManifest, required: Boolean) {
        if (isFinishing || isDestroyed) return
        val notes = manifest.releaseNotes
            .joinToString(separator = "\n") { note -> "• $note" }
            .ifBlank { getString(R.string.app_update_no_release_notes) }
        val message = getString(
            R.string.app_update_available_message,
            InstalledAppVersion.name(this),
            manifest.versionName,
            notes
        )
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(
                if (required) R.string.app_update_required_title
                else R.string.app_update_available_title
            )
            .setMessage(message)
            .setPositiveButton(R.string.app_update_download) { _, _ ->
                downloadUpdate(manifest, required)
            }
        if (!required) {
            builder.setNegativeButton(R.string.app_update_later, null)
        }
        updateDialog?.dismiss()
        updateDialog = builder
            .setCancelable(!required)
            .create()
            .also { dialog ->
                dialog.setCanceledOnTouchOutside(!required)
                dialog.show()
            }
    }

    private fun downloadUpdate(manifest: AppUpdateManifest, required: Boolean) {
        val progressDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.app_update_downloading_title)
            .setMessage(R.string.app_update_downloading_message)
            .setCancelable(!required)
            .apply {
                if (!required) {
                    setNegativeButton(R.string.cancel) { _, _ -> updateJob?.cancel() }
                }
            }
            .create()
        progressDialog.setCanceledOnTouchOutside(false)
        progressDialog.show()

        updateJob = lifecycleScope.launch {
            try {
                when (val result = appUpdateClient.downloadAndVerify(manifest)) {
                    is ApkDownloadResult.Success -> requestInstall(result.file, required)
                    is ApkDownloadResult.Failure -> showDownloadFailure(
                        manifest,
                        required,
                        result.reason
                    )
                }
            } finally {
                progressDialog.dismiss()
                updateJob = null
            }
        }
    }

    private fun showDownloadFailure(
        manifest: AppUpdateManifest,
        required: Boolean,
        reason: UpdateFailureReason
    ) {
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.app_update_failed_title)
            .setMessage(updateFailureMessage(reason))
            .setPositiveButton(R.string.app_update_retry) { _, _ ->
                downloadUpdate(manifest, required)
            }
        if (!required) {
            builder.setNegativeButton(R.string.cancel, null)
        }
        builder.setCancelable(!required).show()
    }

    private fun requestInstall(apkFile: File, required: Boolean) {
        pendingUpdateFile = apkFile
        if (appUpdateClient.canRequestPackageInstalls()) {
            launchInstaller(apkFile)
            return
        }
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.app_update_install_permission_title)
            .setMessage(R.string.app_update_install_permission_message)
            .setPositiveButton(R.string.app_update_open_settings) { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    unknownSourcesLauncher.launch(appUpdateClient.createUnknownSourcesIntent())
                }
            }
        if (!required) {
            builder.setNegativeButton(R.string.cancel, null)
        }
        builder.setCancelable(!required).show()
    }

    private fun launchInstaller(apkFile: File) {
        try {
            startActivity(appUpdateClient.createInstallerIntent(apkFile))
            pendingUpdateFile = null
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.app_update_installer_unavailable, Toast.LENGTH_LONG)
                .show()
        }
    }

    private fun updateFailureMessage(reason: UpdateFailureReason): Int = when (reason) {
        UpdateFailureReason.NETWORK -> R.string.app_update_error_network
        UpdateFailureReason.SERVER -> R.string.app_update_error_server
        UpdateFailureReason.INVALID_METADATA -> R.string.app_update_error_metadata
        UpdateFailureReason.DOWNLOAD -> R.string.app_update_error_download
        UpdateFailureReason.INTEGRITY -> R.string.app_update_error_integrity
        UpdateFailureReason.INVALID_PACKAGE -> R.string.app_update_error_package
        UpdateFailureReason.SIGNER_MISMATCH -> R.string.app_update_error_signer
    }

}
