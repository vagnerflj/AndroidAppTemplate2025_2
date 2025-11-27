package com.ifpr.androidapptemplate.ui.run

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.*
import com.ifpr.androidapptemplate.databinding.ActivityRunBinding
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.round

class RunActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRunBinding

    // Location
    private lateinit var fusedClient: FusedLocationProviderClient
    private var locationRequest: LocationRequest =
        LocationRequest.Builder(2000L)
            .setMinUpdateIntervalMillis(1000L)
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .build()

    private var lastLocation: Location? = null
    private var totalDistanceMeters = 0.0

    // Estado
    private var running = false
    private var pauseOffset = 0L
    private var usingMockDistance = false

    // Shared prefs
    private val PREFS_NAME = "runs_prefs"
    private val KEY_RUNS = "runs_json"

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            lastLocation?.let {
                totalDistanceMeters += it.distanceTo(loc)
            }
            lastLocation = loc
            updateDistanceText()
        }
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
            val fine = perms[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
            val coarse = perms[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
            if (!fine && !coarse) {
                Toast.makeText(this, "Sem permissão de localização. Usando modo simulado.", Toast.LENGTH_LONG).show()
                usingMockDistance = true
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityRunBinding.inflate(layoutInflater)
        setContentView(binding.root)

        fusedClient = LocationServices.getFusedLocationProviderClient(this)

        binding.btnStart.setOnClickListener { onStartRun() }
        binding.btnPause.setOnClickListener { onPauseRun() }
        binding.btnStop.setOnClickListener { onStopRun() }
        binding.btnSave.setOnClickListener { onSaveRun() }

        loadSavedCount()
    }

    private fun onStartRun() {
        if (!hasLocationPermission()) {
            requestPermissionLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }

        if (!running) {
            binding.chronometer.base = SystemClock.elapsedRealtime() - pauseOffset
            binding.chronometer.start()
            running = true

            if (hasLocationPermission()) {
                startLocationUpdates()
                usingMockDistance = false
            } else {
                usingMockDistance = true
                startMockDistance()
            }
        }
    }

    private fun onPauseRun() {
        if (running) {
            binding.chronometer.stop()
            pauseOffset = SystemClock.elapsedRealtime() - binding.chronometer.base
            running = false
            stopLocationUpdates()
            stopMockDistance()
        }
    }

    private fun onStopRun() {
        if (running) {
            binding.chronometer.stop()
            pauseOffset = 0L
            running = false
            stopLocationUpdates()
            stopMockDistance()
        }

        binding.btnSave.visibility = android.view.View.VISIBLE
    }

    private fun onSaveRun() {
        val elapsedMillis = SystemClock.elapsedRealtime() - binding.chronometer.base
        val durationText = formatDuration(elapsedMillis)
        val distanceKm = totalDistanceMeters / 1000.0

        val run = JSONObject().apply {
            put("time", System.currentTimeMillis())
            put("duration_ms", elapsedMillis)
            put("duration_text", durationText)
            put("distance_km", round(distanceKm * 100) / 100.0)
        }

        saveRunToPrefs(run)

        binding.btnSave.visibility = android.view.View.GONE

        binding.chronometer.base = SystemClock.elapsedRealtime()
        pauseOffset = 0L
        totalDistanceMeters = 0.0
        lastLocation = null
        updateDistanceText()
        loadSavedCount()
    }

    private fun formatDuration(ms: Long): String {
        val sec = ms / 1000
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return "%02d:%02d:%02d".format(h, m, s)
    }

    private fun updateDistanceText() {
        val km = totalDistanceMeters / 1000.0
        binding.tvDistance.text = String.format("Distância: %.2f km", km)
    }

    private fun startLocationUpdates() {
        if (!hasLocationPermission()) return
        try {
            // use Looper.getMainLooper() explicitamente
            fusedClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    private fun stopLocationUpdates() {
        fusedClient.removeLocationUpdates(locationCallback)
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private var mockRunnable: Runnable? = null
    private val handler = Handler(Looper.getMainLooper())

    private fun startMockDistance() {
        mockRunnable = object : Runnable {
            override fun run() {
                totalDistanceMeters += 3.0
                updateDistanceText()
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(mockRunnable!!)
    }

    private fun stopMockDistance() {
        mockRunnable?.let { handler.removeCallbacks(it) }
        mockRunnable = null
    }

    private fun saveRunToPrefs(obj: JSONObject) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val json = prefs.getString(KEY_RUNS, null)
        val arr = if (json != null) JSONArray(json) else JSONArray()
        arr.put(obj)
        prefs.edit().putString(KEY_RUNS, arr.toString()).apply()
    }

    private fun loadSavedCount() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val json = prefs.getString(KEY_RUNS, null)
        val count = if (json != null) JSONArray(json).length() else 0
        binding.tvSavedCount.text = "Corridas salvas: $count"
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLocationUpdates()
        stopMockDistance()
    }
}
