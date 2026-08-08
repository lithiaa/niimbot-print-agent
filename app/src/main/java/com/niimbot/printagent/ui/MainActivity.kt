package com.niimbot.printagent.ui

import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigation.NavigationBarView
import com.niimbot.printagent.R
import com.niimbot.printagent.data.AppDatabase
import com.niimbot.printagent.data.PrintJob
import com.niimbot.printagent.data.PrintStatus
import com.niimbot.printagent.service.PrintForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var database: AppDatabase
    private lateinit var bottomNav: BottomNavigationView
    
    private val fragments = mapOf(
        R.id.nav_dashboard to DashboardFragment(),
        R.id.nav_printer to PrinterFragment(),
        R.id.nav_queue to PrintQueueFragment(),
        R.id.nav_logs to LogsFragment(),
        R.id.nav_settings to SettingsFragment()
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        database = AppDatabase.getInstance(this)
        bottomNav = findViewById(R.id.bottom_navigation)
        
        setupBottomNavigation()
        observePrintQueue()
        
        // Start foreground service if not running
        startForegroundService()
    }
    
    private fun setupBottomNavigation() {
        bottomNav.setOnItemSelectedListener { item ->
            val fragment = fragments[item.itemId] ?: return@setOnItemSelectedListener false
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .commit()
            true
        }
        
        // Default to dashboard
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, DashboardFragment())
                .commit()
            bottomNav.selectedItemId = R.id.nav_dashboard
        }
    }
    
    private fun observePrintQueue() {
        val pendingJobs = database.printJobDao().getByStatus(PrintStatus.PENDING)
        val printingJobs = database.printJobDao().getByStatus(PrintStatus.PRINTING)
        
        Observer<List<PrintJob>?> { jobs ->
            updateQueueBadge(jobs?.size ?: 0 + printingJobs.value?.size ?: 0)
        }.also { observer ->
            pendingJobs.observe(this, observer)
            printingJobs.observe(this, observer)
        }
    }
    
    private fun updateQueueBadge(count: Int) {
        // Update bottom nav badge
        bottomNav.getOrCreateBadge(R.id.nav_queue).apply {
            isVisible = count > 0
            number = count
        }
    }
    
    private fun startForegroundService() {
        val intent = Intent(this, PrintForegroundService::class.java)
        intent.action = PrintForegroundService.ACTION_START
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
    
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_test_print -> {
                val intent = Intent(this, PrintForegroundService::class.java)
                intent.action = PrintForegroundService.ACTION_TEST_PRINT
                intent.putExtra(PrintForegroundService.EXTRA_TEST_DATA, "MANUAL TEST")
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