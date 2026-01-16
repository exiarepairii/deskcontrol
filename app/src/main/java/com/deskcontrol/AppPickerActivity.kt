package com.deskcontrol

import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.deskcontrol.databinding.ActivityAppPickerBinding
import com.deskcontrol.databinding.ItemAppBinding

class AppPickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppPickerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.appList.layoutManager = LinearLayoutManager(this)
        val entries = loadLaunchableApps()
        binding.appList.adapter = AppAdapter(entries) { entry ->
            val result = AppLauncher.launchOnExternalDisplay(this, entry.packageName)
            if (result.success) {
                Toast.makeText(this, "Launched ${entry.label}", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                val reason = result.reason?.name ?: "Launch failed"
                val message = if (result.detail.isNullOrBlank()) reason else "$reason: ${result.detail}"
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun loadLaunchableApps(): List<AppEntry> {
        val pm = packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        return apps.mapNotNull { appInfo ->
            val launchIntent = pm.getLaunchIntentForPackage(appInfo.packageName)
            if (launchIntent != null) {
                AppEntry(
                    label = pm.getApplicationLabel(appInfo).toString(),
                    packageName = appInfo.packageName,
                    icon = pm.getApplicationIcon(appInfo)
                )
            } else {
                null
            }
        }.sortedBy { it.label.lowercase() }
    }

    data class AppEntry(
        val label: String,
        val packageName: String,
        val icon: android.graphics.drawable.Drawable
    )

    private class AppAdapter(
        private val items: List<AppEntry>,
        private val onClick: (AppEntry) -> Unit
    ) : RecyclerView.Adapter<AppAdapter.AppViewHolder>() {

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): AppViewHolder {
            val binding = ItemAppBinding.inflate(android.view.LayoutInflater.from(parent.context), parent, false)
            return AppViewHolder(binding, onClick)
        }

        override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        class AppViewHolder(
            private val binding: ItemAppBinding,
            private val onClick: (AppEntry) -> Unit
        ) : RecyclerView.ViewHolder(binding.root) {

            fun bind(entry: AppEntry) {
                binding.appName.text = entry.label
                binding.appPackage.text = entry.packageName
                binding.appIcon.setImageDrawable(entry.icon)
                binding.root.setOnClickListener { onClick(entry) }
            }
        }
    }
}
