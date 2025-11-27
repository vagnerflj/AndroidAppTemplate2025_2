package com.ifpr.androidapptemplate.ui.home

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.location.Geocoder
import android.location.Location
import android.os.Looper
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import com.bumptech.glide.Glide
import com.google.android.gms.location.*
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.ifpr.androidapptemplate.R
import com.ifpr.androidapptemplate.baseclasses.Item
import com.ifpr.androidapptemplate.databinding.FragmentHomeBinding
import com.ifpr.androidapptemplate.ui.ai.AiLogicActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.*

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    // Location
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var locationRequest: LocationRequest

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1

        // SharedPreferences keys (mesma configuração do RunActivity)
        private const val PREFS_NAME = "runs_prefs"
        private const val KEY_RUNS = "runs_json"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: android.os.Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        val view = binding.root

        // Inicializa localização (mostra endereço)
        inicializaGerenciamentoLocalizacao()

        // Carrega marketplace (seu código existente) e depois carrega corridas salvas
        val itemContainer = view.findViewById<LinearLayout>(R.id.itemContainer)
        carregarItensMarketplace(itemContainer) // mantém comportamento atual
        // adiciona separador e corridas
        val separator = TextView(requireContext()).apply {
            text = "Corridas salvas"
            textSize = 16f
            setPadding(12, 24, 12, 6)
        }
        itemContainer.addView(separator)
        carregarCorridasSalvas(itemContainer)

        // FAB da IA (mantive seu comportamento)
        val fab = view.findViewById<FloatingActionButton>(R.id.fab_ai)
        fab.setOnClickListener {
            val intent = Intent(requireContext(), AiLogicActivity::class.java)
            startActivity(intent)
        }

        return view
    }

    // --- Location helpers (preservado / atualizado) ---
    private fun inicializaGerenciamentoLocalizacao() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())

        if (ActivityCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestLocationPermission()
        } else {
            getCurrentLocation()
        }
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
                Snackbar.make(requireView(), "Permissão negada. Não é possível acessar a localização.", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun getCurrentLocation() {
        if (ActivityCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    displayAddress(location)
                }
            }
        }

        // Cria LocationRequest (compatível com play-services-location 21.x)
        locationRequest = LocationRequest.create().apply {
            interval = 30_000L
            fastestInterval = 30_000L
            priority = Priority.PRIORITY_HIGH_ACCURACY
        }

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
    }

    private fun displayAddress(location: Location) {
        val geocoder = Geocoder(requireContext(), Locale.getDefault())
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                val address = addresses?.firstOrNull()?.getAddressLine(0) ?: "Endereço não encontrado"
                withContext(Dispatchers.Main) {
                    binding.currentAddressTextView.text = address
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.currentAddressTextView.text = "Erro: ${e.message}"
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        // remover updates de localização
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        } catch (_: Exception) { }
    }

    // --- Marketplace (seu código existente) ---
    fun carregarItensMarketplace(container: LinearLayout) {
        val databaseRef = FirebaseDatabase.getInstance().getReference("itens")

        databaseRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                // remove apenas as views de marketplace (se quiser manter corridas, trate separadamente)
                // aqui, como vamos adicionar separator e corridas depois, só removemos o que havia antes
                // container.removeAllViews() // NÃO remove para manter separador que será adicionado pelo chamador

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
                                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
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

    // --- Corridas salvas (SharedPreferences) ---
    private fun carregarCorridasSalvas(container: LinearLayout) {
        // Não remover todo o container aqui porque queremos manter marketplace + separador.
        // Se preferir apenas mostrar corridas, chame container.removeAllViews() antes.
        // Vamos remover apenas as views após o separador (simples estratégia):
        // Encontra índice do separador "Corridas salvas" e remove tudo depois dele.
        val childCount = container.childCount
        var sepIndex = -1
        for (i in 0 until childCount) {
            val v = container.getChildAt(i)
            if (v is TextView && v.text == "Corridas salvas") {
                sepIndex = i
                break
            }
        }
        if (sepIndex >= 0) {
            // remove views após separator
            val removeFrom = sepIndex + 1
            val toRemove = mutableListOf<View>()
            for (i in removeFrom until container.childCount) toRemove.add(container.getChildAt(i))
            toRemove.forEach { container.removeView(it) }
        } else {
            // se não achou separator, adiciona um
            val separator = TextView(requireContext()).apply {
                text = "Corridas salvas"
                textSize = 16f
                setPadding(12, 24, 12, 6)
            }
            container.addView(separator)
        }

        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_RUNS, null)
        if (json.isNullOrEmpty()) {
            val tv = TextView(requireContext()).apply {
                text = "Nenhuma corrida salva"
                setPadding(16, 16, 16, 16)
            }
            container.addView(tv)
            return
        }

        try {
            val arr = JSONArray(json)
            // adiciona do mais recente para o mais antigo
            for (i in arr.length() - 1 downTo 0) {
                val obj = arr.getJSONObject(i)
                val itemView = layoutInflater.inflate(R.layout.item_run, container, false)

                val tvDate = itemView.findViewById<TextView>(R.id.tvRunDate)
                val tvDuration = itemView.findViewById<TextView>(R.id.tvDuration)
                val tvDistance = itemView.findViewById<TextView>(R.id.tvDistance)
                val tvPace = itemView.findViewById<TextView>(R.id.tvPace)

                val time = obj.optLong("time", 0L)
                val durationText = obj.optString("duration_text", "-")
                val distanceKm = obj.optDouble("distance_km", 0.0)

                val df = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
                tvDate.text = if (time > 0L) df.format(Date(time)) else "-"
                tvDuration.text = durationText
                tvDistance.text = String.format(Locale.getDefault(), "%.2f km", distanceKm)

                // calcula pace (min:sec por km) — se distância > 0
                if (distanceKm > 0.0) {
                    val totalSeconds = (obj.optLong("duration_ms", 0L) / 1000.0)
                    val paceSecondsPerKm = if (distanceKm > 0.0) totalSeconds / distanceKm else 0.0
                    val mins = paceSecondsPerKm.toInt() / 60
                    val secs = paceSecondsPerKm.toInt() % 60
                    tvPace.text = String.format("%d:%02d min/km", mins, secs)
                } else {
                    tvPace.text = "—"
                }

                itemView.setOnClickListener {
                    Toast.makeText(requireContext(),
                        "Corrida: ${durationText} - ${String.format("%.2f km", distanceKm)}",
                        Toast.LENGTH_SHORT).show()
                }

                container.addView(itemView)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            val tv = TextView(requireContext()).apply {
                text = "Erro ao carregar corridas"
                setPadding(16, 16, 16, 16)
            }
            container.addView(tv)
        }
    }

    override fun onResume() {
        super.onResume()
        // recarrega marketplace + corridas (simples): remove tudo e recarrega
        val container = view?.findViewById<LinearLayout>(R.id.itemContainer) ?: return
        container.removeAllViews()
        carregarItensMarketplace(container)
        val separator = TextView(requireContext()).apply {
            text = "Corridas salvas"
            textSize = 16f
            setPadding(12, 24, 12, 6)
        }
        container.addView(separator)
        carregarCorridasSalvas(container)
    }
}
