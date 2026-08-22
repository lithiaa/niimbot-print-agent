package com.niimbot.printagent.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bottomNav = findViewById(R.id.bottom_navigation)

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
            val fragment = when (item.itemId) {
                R.id.nav_dashboard -> DashboardFragment()
                R.id.nav_printer   -> PrinterFragment()
                R.id.nav_queue     -> PrintQueueFragment()
                R.id.nav_label     -> LabelFragment()
                R.id.nav_settings  -> SettingsFragment()
                else -> return@setOnItemSelectedListener false
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .commit()
            true
        }

        // Default to dashboard on first launch
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, DashboardFragment())
                .commit()
            bottomNav.selectedItemId = R.id.nav_dashboard
        }
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
            bottomNav.getOrCreateBadge(R.id.nav_queue).apply {
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

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_test_print -> {
                val intent = Intent(this, PrintForegroundService::class.java).apply {
                    action = PrintForegroundService.ACTION_TEST_PRINT
                    putExtra(PrintForegroundService.EXTRA_TEST_DATA, "MANUAL TEST")
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
