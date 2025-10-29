package com.ifpr.androidapptemplate.ui.home

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.content.Context
import android.content.Intent
import android.location.Geocoder
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import com.bumptech.glide.Glide
import com.google.android.gms.location.*
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.database.*
import com.ifpr.androidapptemplate.R
import com.ifpr.androidapptemplate.baseclasses.Item
import com.ifpr.androidapptemplate.databinding.FragmentHomeBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!! // (não usamos binding no inflate atual, mas mantive a referência)

    // ===== UI existentes =====
    private lateinit var currentAddressTextView: TextView

    // ===== UI novas =====
    private lateinit var tvFixedPoints: TextView
    private lateinit var tvTripDistance: TextView
    private lateinit var tvWeekTotal: TextView
    private lateinit var btnStartStop: Button
    private lateinit var btnOpenInMaps: Button
    private lateinit var btnOpenInWaze: Button

    // ===== Localização =====
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var locationRequest: LocationRequest

    // ===== Tracking sessão + semanal =====
    private var isTracking = false
    private var lastLocationForTrip: Location? = null
    private var sessionDistanceMeters = 0f
    private val prefs by lazy { requireContext().getSharedPreferences("distance_prefs", Context.MODE_PRIVATE) }

    // Pontos fixos (altere para endereços/locais reais do seu caso)
    private val fixedPoints = listOf(
        NamedPoint("Supermercado X", -23.567890, -46.654321),
        NamedPoint("Farmácia Y",     -23.563210, -46.660000)
    )
    data class NamedPoint(val name: String, val lat: Double, val lng: Double)

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_home, container, false)

        // === Referências de UI ===
        currentAddressTextView = view.findViewById(R.id.currentAddressTextView)

        tvFixedPoints   = view.findViewById(R.id.tvFixedPoints)
        tvTripDistance  = view.findViewById(R.id.tvTripDistance)
        tvWeekTotal     = view.findViewById(R.id.tvWeekTotal)
        btnStartStop    = view.findViewById(R.id.btnStartStop)
        btnOpenInMaps   = view.findViewById(R.id.btnOpenInMaps)
        btnOpenInWaze   = view.findViewById(R.id.btnOpenInWaze)

        tvTripDistance.text = "Distância percorrida (sessão): 0,00 m"
        updateWeekTotalUI(getWeekTotal())

        // === Inicializa localização e começa a ouvir updates ===
        inicializaGerenciamentoLocalizacao(view)

        // === Botões: iniciar/parar, Maps, Waze ===
        btnStartStop.setOnClickListener {
            isTracking = !isTracking
            if (isTracking) {
                lastLocationForTrip = null
                sessionDistanceMeters = 0f
                btnStartStop.text = "Parar"
                Toast.makeText(requireContext(), "Rastreamento iniciado", Toast.LENGTH_SHORT).show()
            } else {
                btnStartStop.text = "Iniciar"
                Toast.makeText(
                    requireContext(),
                    "Rastreamento parado. Total sessão: %.2f m".format(sessionDistanceMeters),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        btnOpenInMaps.setOnClickListener {
            // usa a última localização conhecida para abrir rapidamente
            if (::fusedLocationClient.isInitialized) {
                if (temPermissao()) {
                    fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                        val l = loc ?: return@addOnSuccessListener
                        val uri = "geo:${l.latitude},${l.longitude}?q=${l.latitude},${l.longitude}(Minha+Localização)"
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
                            setPackage("com.google.android.apps.maps")
                        }
                        try {
                            startActivity(intent)
                        } catch (_: ActivityNotFoundException) {
                            Toast.makeText(requireContext(), "Google Maps não encontrado", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else requestLocationPermission()
            }
        }

        btnOpenInWaze.setOnClickListener {
            if (::fusedLocationClient.isInitialized) {
                if (temPermissao()) {
                    fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                        val l = loc ?: return@addOnSuccessListener
                        val uri = "https://waze.com/ul?ll=${l.latitude},${l.longitude}&navigate=yes"
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
                            setPackage("com.waze")
                        }
                        if (intent.resolveActivity(requireActivity().packageManager) != null) {
                            startActivity(intent)
                        } else {
                            Toast.makeText(requireContext(), "Waze não está instalado", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else requestLocationPermission()
            }
        }

        // === Carrega itens do marketplace como já fazia ===
        val itemContainer = view.findViewById<LinearLayout>(R.id.itemContainer)
        carregarItensMarketplace(itemContainer)

        return view
    }

    // =================== Localização ===================
    private fun inicializaGerenciamentoLocalizacao(view: View) {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())

        if (!temPermissao()) {
            requestLocationPermission()
        } else {
            getCurrentLocation()
        }
    }

    private fun temPermissao(): Boolean {
        val c = requireContext()
        val fine = ActivityCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ActivityCompat.checkSelfPermission(c, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun requestLocationPermission() {
        requestPermissions(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            LOCATION_PERMISSION_REQUEST_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                getCurrentLocation()
            } else {
                Snackbar.make(
                    requireView(),
                    "Permissão negada. Não é possível acessar a localização.",
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun getCurrentLocation() {
        if (!temPermissao()) return

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    displayAddress(location)  // mantém sua lógica de endereço
                    onNewLocation(location)   // novas funcionalidades (1,2,3)
                }
            }
        }

        // Atualizações a cada ~5s para uma experiência mais fluida no tracking
        locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000L)
            .setMinUpdateIntervalMillis(2_000L)
            .setWaitForAccurateLocation(true)
            .build()

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
    }

    private fun displayAddress(location: Location) {
        val geocoder = Geocoder(requireContext(), Locale.getDefault())
        val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val address = addresses?.firstOrNull()?.getAddressLine(0) ?: "Address not found"
                withContext(Dispatchers.Main) {
                    currentAddressTextView.text = address
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    currentAddressTextView.text = "Error: ${e.message}"
                }
            }
        }
    }

    // ======= Novas funcionalidades usando a localização em tempo real =======
    private fun onNewLocation(location: Location) {
        // (1) Distância até pontos fixos
        val sb = StringBuilder()
        fixedPoints.forEach { p ->
            val t = Location("fixed").apply { latitude = p.lat; longitude = p.lng }
            val distM = location.distanceTo(t)
            val distKm = distM / 1000f
            sb.append("• ${p.name}: %.2f km\n".format(distKm))
        }
        tvFixedPoints.text = "Distância até pontos fixos:\n$sb"

        // (2) Tracking da sessão + (3) Acúmulo semanal
        if (isTracking) {
            lastLocationForTrip?.let { last ->
                val delta = last.distanceTo(location)
                // filtro anti-pulos (ignora saltos irreais)
                if (!delta.isNaN() && delta >= 0f && delta < 200f) {
                    sessionDistanceMeters += delta
                    addToWeekTotal(delta)
                    updateWeekTotalUI(getWeekTotal())
                    tvTripDistance.text = "Distância percorrida (sessão): %.2f m".format(sessionDistanceMeters)
                }
            }
            lastLocationForTrip = location
        }
    }

    // ======= Persistência semanal (SharedPreferences) =======
    private fun weekKey(): String {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val wf = WeekFields.of(Locale.getDefault())
        val year = today.get(wf.weekBasedYear())
        val week = today.get(wf.weekOfWeekBasedYear())
        return "%04d-W%02d".format(year, week)
    }

    private fun getWeekTotal(): Float {
        val key = weekKey()
        val savedKey = prefs.getString("week_key", "")
        if (savedKey != key) {
            prefs.edit()
                .putString("week_key", key)
                .putFloat("week_total_m", 0f)
                .apply()
            return 0f
        }
        return prefs.getFloat("week_total_m", 0f)
    }

    private fun addToWeekTotal(deltaMeters: Float) {
        val key = weekKey()
        val savedKey = prefs.getString("week_key", "")
        if (savedKey != key) {
            prefs.edit()
                .putString("week_key", key)
                .putFloat("week_total_m", 0f)
                .apply()
        }
        val current = prefs.getFloat("week_total_m", 0f)
        prefs.edit().putFloat("week_total_m", current + deltaMeters).apply()
    }

    private fun updateWeekTotalUI(totalMeters: Float) {
        val km = totalMeters / 1000f
        tvWeekTotal.text = "Total na semana: %.2f km".format(km)
    }

    // =================== Marketplace (como já estava) ===================
    fun carregarItensMarketplace(container: LinearLayout) {
        val databaseRef = FirebaseDatabase.getInstance().getReference("itens")

        databaseRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                container.removeAllViews()

                for (userSnapshot in snapshot.children) {
                    for (itemSnapshot in userSnapshot.children) {
                        val item = itemSnapshot.getValue(Item::class.java) ?: continue

                        val itemView = LayoutInflater.from(container.context)
                            .inflate(R.layout.item_template, container, false)

                        val imageView = itemView.findViewById<ImageView>(R.id.item_image)
                        val enderecoView = itemView.findViewById<TextView>(R.id.item_endereco)

                        enderecoView.text = "Endereço: ${item.endereco ?: "Não informado"}"

                        if (!item.imageUrl.isNullOrEmpty()) {
                            Glide.with(container.context).load(item.imageUrl).into(imageView)
                        } else if (!item.base64Image.isNullOrEmpty()) {
                            try {
                                val bytes = Base64.decode(item.base64Image, Base64.DEFAULT)
                                val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                imageView.setImageBitmap(bitmap)
                            } catch (_: Exception) {}
                        }

                        container.addView(itemView)
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(container.context, "Erro ao carregar dados", Toast.LENGTH_SHORT).show()
            }
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (::fusedLocationClient.isInitialized && ::locationCallback.isInitialized) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
        _binding = null
    }
}
