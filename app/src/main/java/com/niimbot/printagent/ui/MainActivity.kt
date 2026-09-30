package com.niimbot.printagent.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.niimbot.printagent.R
import com.niimbot.printagent.data.AppDatabase
import com.niimbot.printagent.data.PrintStatus
import com.niimbot.printagent.service.PrintForegroundService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject
    lateinit var database: AppDatabase

    private lateinit var bottomNav: BottomNavigationView
    private lateinit var pageHeaderTitle: TextView
    private lateinit var labelNavButton: View

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

}
