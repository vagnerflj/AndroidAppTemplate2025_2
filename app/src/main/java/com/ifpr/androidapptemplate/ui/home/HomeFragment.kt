package com.ifpr.androidapptemplate.ui.home

import android.Manifest
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import com.google.android.gms.location.*
import com.ifpr.androidapptemplate.R
import com.ifpr.androidapptemplate.databinding.FragmentHomeBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var locationRequest: LocationRequest

    private val PREFS_NAME = "runs_prefs"
    private val KEY_RUNS = "runs_json"

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        val root = binding.root

        // iniciar localização para mostrar endereço (opcional)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())
        initLocation()

        // set click do FAB iniciar corrida (abre RunActivity)
        binding.fabRun.setOnClickListener {
            // abrir RunActivity
            val ctx = requireContext()
            val intent = android.content.Intent(ctx, com.ifpr.androidapptemplate.ui.run.RunActivity::class.java)
            ctx.startActivity(intent)
        }

        binding.fabAi.setOnClickListener {
            val ctx = requireContext()
            val intent = android.content.Intent(ctx, com.ifpr.androidapptemplate.ui.ai.AiLogicActivity::class.java)
            ctx.startActivity(intent)
        }

        // popula lista de corridas salvas
        populateRunsFromPrefs(binding.itemContainer)

        return root
    }

    private fun initLocation() {
        if (ActivityCompat.checkSelfPermission(requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(requireContext(),
                Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE)
            return
        }

        locationRequest = LocationRequest.Builder(30000L)
            .setMinUpdateIntervalMillis(30000L)
            .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                displayAddress(loc)
            }
        }

        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
    }

    private fun displayAddress(location: Location) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val geo = Geocoder(requireContext(), Locale.getDefault())
                val list = geo.getFromLocation(location.latitude, location.longitude, 1)
                val address = list?.firstOrNull()?.getAddressLine(0) ?: "Endereço não encontrado"
                withContext(Dispatchers.Main) {
                    binding.currentAddressTextView.text = address
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.currentAddressTextView.text = "Erro ao obter endereço"
                }
            }
        }
    }

    private fun populateRunsFromPrefs(container: LinearLayout) {
        container.removeAllViews()
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, 0)
        val json = prefs.getString(KEY_RUNS, null) ?: return
        try {
            val arr = JSONArray(json)
            for (i in arr.length() - 1 downTo 0) {
                val obj = arr.getJSONObject(i)
                addRunCard(container, obj)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun addRunCard(container: LinearLayout, run: JSONObject) {
        val v = layoutInflater.inflate(R.layout.item_run, container, false)
        val tvRunDate = v.findViewById<TextView>(R.id.tvRunDate)
        val tvDuration = v.findViewById<TextView>(R.id.tvDuration)
        val tvDistance = v.findViewById<TextView>(R.id.tvDistance)
        val tvPace = v.findViewById<TextView>(R.id.tvPace)

        val ts = run.optLong("time", 0L)
        tvRunDate.text = if (ts > 0) {
            val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
            sdf.format(Date(ts))
        } else "—"

        val durationMs = run.optLong("duration_ms", 0L)
        tvDuration.text = formatDuration(durationMs)

        val distKm = run.optDouble("distance_km", 0.0)
        tvDistance.text = String.format(Locale.getDefault(), "%.2f km", distKm)

        if (distKm > 0.0 && durationMs > 0) {
            val minutes = durationMs / 60000.0
            val pace = minutes / distKm
            val minPart = pace.toInt()
            val secPart = ((pace - minPart) * 60).toInt()
            tvPace.text = String.format(Locale.getDefault(), "%d:%02d min/km", minPart, secPart)
        } else {
            tvPace.text = "—"
        }

        container.addView(v)
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val hh = totalSec / 3600
        val mm = (totalSec % 3600) / 60
        val ss = totalSec % 60
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", hh, mm, ss)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try { fusedLocationClient.removeLocationUpdates(locationCallback) } catch (_: Exception) {}
        _binding = null
    }
}
