package com.ifpr.androidapptemplate

import android.content.Intent
import android.os.Bundle
import com.google.android.material.bottomnavigation.BottomNavigationView
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.ifpr.androidapptemplate.databinding.ActivityMainBinding
import com.ifpr.androidapptemplate.ui.run.RunActivity

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // ⭐ IMPORTANTE — define o Toolbar como ActionBar
        setSupportActionBar(binding.toolbar)

        // ⭐ FAB abre a tela de corrida
        binding.fabRun.setOnClickListener {
            val intent = Intent(this, RunActivity::class.java)
            startActivity(intent)
        }

        val navView: BottomNavigationView = binding.navView
        val navController = findNavController(R.id.nav_host_fragment_activity_main)

        val appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.navigation_home,
                R.id.navigation_dashboard,
                R.id.navigation_notifications,
                R.id.navigation_profile
            )
        )

        // Conecta Navigation com a ActionBar
        setupActionBarWithNavController(navController, appBarConfiguration)

        // Conecta Navigation com BottomNav
        navView.setupWithNavController(navController)
    }
}
