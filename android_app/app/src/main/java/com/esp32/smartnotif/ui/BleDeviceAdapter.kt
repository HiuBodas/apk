package com.esp32.smartnotif.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.esp32.smartnotif.R
import com.esp32.smartnotif.model.BleDeviceItem
import com.google.android.material.button.MaterialButton

class BleDeviceAdapter(
    private val onConnectClicked: (BleDeviceItem) -> Unit
) : RecyclerView.Adapter<BleDeviceAdapter.ViewHolder>() {

    private val items = mutableListOf<BleDeviceItem>()
    private var connectedAddress: String? = null
    private var connectingAddress: String? = null

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivDeviceIcon: ImageView = view.findViewById(R.id.ivDeviceIcon)
        val tvDeviceName: TextView = view.findViewById(R.id.tvDeviceName)
        val tvEspBadge: TextView = view.findViewById(R.id.tvEspBadge)
        val tvDeviceAddress: TextView = view.findViewById(R.id.tvDeviceAddress)
        val tvDeviceRssi: TextView = view.findViewById(R.id.tvDeviceRssi)
        val btnConnectDevice: MaterialButton = view.findViewById(R.id.btnConnectDevice)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_ble_device, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val context = holder.itemView.context

        holder.tvDeviceName.text = if (item.name.isNotBlank()) item.name else "Perangkat BLE"
        holder.tvDeviceAddress.text = item.address

        // Tampilkan badge ESP32 C3 jika terdeteksi ESP32
        if (item.isEsp32) {
            holder.tvEspBadge.visibility = View.VISIBLE
            holder.tvEspBadge.text = if (item.name.contains("C3", ignoreCase = true)) "ESP32-C3" else "ESP32"
        } else {
            holder.tvEspBadge.visibility = View.GONE
        }

        // Tampilkan indikator RSSI dan warna sinyal
        holder.tvDeviceRssi.text = "${item.rssi} dBm"
        val rssiColorRes = when {
            item.rssi >= -65 -> R.color.status_connected
            item.rssi >= -80 -> R.color.status_connecting
            else -> R.color.text_muted
        }
        holder.tvDeviceRssi.setTextColor(ContextCompat.getColor(context, rssiColorRes))

        val isCurrentConnected = item.address.equals(connectedAddress, ignoreCase = true) || item.isConnected
        val isCurrentConnecting = item.address.equals(connectingAddress, ignoreCase = true) || item.isConnecting

        when {
            isCurrentConnected -> {
                holder.btnConnectDevice.text = "Terhubung"
                holder.btnConnectDevice.setIconResource(R.drawable.ic_check)
                holder.btnConnectDevice.iconTint =
                    ColorStateList.valueOf(ContextCompat.getColor(context, R.color.white))
                holder.btnConnectDevice.backgroundTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(context, R.color.status_connected))
                holder.btnConnectDevice.isEnabled = false
                holder.ivDeviceIcon.imageTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(context, R.color.status_connected))
            }
            isCurrentConnecting -> {
                holder.btnConnectDevice.text = "Menghubungkan..."
                holder.btnConnectDevice.icon = null
                holder.btnConnectDevice.backgroundTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(context, R.color.status_connecting))
                holder.btnConnectDevice.isEnabled = false
                holder.ivDeviceIcon.imageTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(context, R.color.status_connecting))
            }
            else -> {
                holder.btnConnectDevice.text = "Hubungkan"
                holder.btnConnectDevice.icon = null
                holder.btnConnectDevice.backgroundTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(context, R.color.primary))
                holder.btnConnectDevice.isEnabled = true
                holder.ivDeviceIcon.imageTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(context, R.color.primary))
            }
        }

        holder.btnConnectDevice.setOnClickListener {
            onConnectClicked(item)
        }
    }

    override fun getItemCount(): Int = items.size

    fun setDevices(newDevices: List<BleDeviceItem>, currentConnectedMac: String? = null, currentConnectingMac: String? = null) {
        connectedAddress = currentConnectedMac
        connectingAddress = currentConnectingMac
        items.clear()
        items.addAll(newDevices)
        notifyDataSetChanged()
    }

    fun updateConnectionStatus(currentConnectedMac: String?, currentConnectingMac: String?) {
        connectedAddress = currentConnectedMac
        connectingAddress = currentConnectingMac
        for (i in items.indices) {
            val item = items[i]
            val wasConnected = item.isConnected
            val wasConnecting = item.isConnecting
            item.isConnected = item.address.equals(currentConnectedMac, ignoreCase = true)
            item.isConnecting = item.address.equals(currentConnectingMac, ignoreCase = true)
            if (wasConnected != item.isConnected || wasConnecting != item.isConnecting) {
                notifyItemChanged(i)
            }
        }
    }

    fun clear() {
        items.clear()
        notifyDataSetChanged()
    }
}
