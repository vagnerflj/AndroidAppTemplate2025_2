package com.ifpr.androidapptemplate.ui.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ifpr.androidapptemplate.databinding.ItemRunBinding
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class RunsAdapter(
    private val items: List<JSONObject>,
    private val onClick: (JSONObject) -> Unit
) : RecyclerView.Adapter<RunsAdapter.ViewHolder>() {

    private val dateFmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

    inner class ViewHolder(private val b: ItemRunBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(obj: JSONObject) {
            val time = obj.optLong("time", 0L)
            val duration = obj.optString("duration_text", "-")
            val distance = obj.optDouble("distance_km", 0.0)

            b.tvRunDate.text = if (time > 0) dateFmt.format(Date(time)) else "-"
            b.tvDuration.text = duration
            b.tvDistance.text = String.format(Locale.getDefault(), "%.2f km", distance)

            // Se quiser calcular ritmo (min/km) pode fazer aqui depois
            b.root.setOnClickListener { onClick(obj) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRunBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size
}
