package com.amua.audiodownloader.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.amua.audiodownloader.R
import com.amua.audiodownloader.ble.ScannedDevice

/**
 * RecyclerView adapter for displaying scanned BLE devices.
 */
class DeviceAdapter(
    private val onDeviceClick: (ScannedDevice) -> Unit
) : ListAdapter<ScannedDevice, DeviceAdapter.DeviceViewHolder>(DeviceDiffCallback()) {

    private var selectedAddress: String? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_device, parent, false)
        return DeviceViewHolder(view)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        val device = getItem(position)
        holder.bind(device, device.address == selectedAddress)
        holder.itemView.setOnClickListener {
            val previousSelected = selectedAddress
            selectedAddress = device.address
            onDeviceClick(device)

            // Update visual selection
            if (previousSelected != null) {
                val prevIndex = currentList.indexOfFirst { it.address == previousSelected }
                if (prevIndex >= 0) notifyItemChanged(prevIndex)
            }
            notifyItemChanged(position)
        }
    }

    fun setSelectedDevice(address: String?) {
        val previousSelected = selectedAddress
        selectedAddress = address

        if (previousSelected != null) {
            val prevIndex = currentList.indexOfFirst { it.address == previousSelected }
            if (prevIndex >= 0) notifyItemChanged(prevIndex)
        }
        if (address != null) {
            val newIndex = currentList.indexOfFirst { it.address == address }
            if (newIndex >= 0) notifyItemChanged(newIndex)
        }
    }

    class DeviceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameText: TextView = itemView.findViewById(R.id.deviceName)
        private val addressText: TextView = itemView.findViewById(R.id.deviceAddress)
        private val rssiText: TextView = itemView.findViewById(R.id.deviceRssi)

        fun bind(device: ScannedDevice, isSelected: Boolean) {
            nameText.text = device.name
            addressText.text = device.address
            rssiText.text = "${device.rssi} dBm"

            // Highlight selected item
            itemView.setBackgroundColor(
                if (isSelected) {
                    itemView.context.getColor(R.color.primary)
                } else {
                    itemView.context.getColor(android.R.color.transparent)
                }
            )
            nameText.setTextColor(
                if (isSelected) {
                    itemView.context.getColor(R.color.white)
                } else {
                    itemView.context.getColor(R.color.text_primary)
                }
            )
            addressText.setTextColor(
                if (isSelected) {
                    itemView.context.getColor(R.color.white)
                } else {
                    itemView.context.getColor(R.color.text_secondary)
                }
            )
            rssiText.setTextColor(
                if (isSelected) {
                    itemView.context.getColor(R.color.white)
                } else {
                    itemView.context.getColor(R.color.text_secondary)
                }
            )
        }
    }
}

class DeviceDiffCallback : DiffUtil.ItemCallback<ScannedDevice>() {
    override fun areItemsTheSame(oldItem: ScannedDevice, newItem: ScannedDevice): Boolean {
        return oldItem.address == newItem.address
    }

    override fun areContentsTheSame(oldItem: ScannedDevice, newItem: ScannedDevice): Boolean {
        return oldItem == newItem
    }
}
