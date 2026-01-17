package com.deskcontrol

import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.deskcontrol.databinding.ActivityAppPickerBinding
import com.deskcontrol.databinding.ItemAppBinding

class AppPickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppPickerBinding
    private lateinit var adapter: AppAdapter
    private var allEntries: List<AppEntry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        applyEdgeToEdgePadding(binding.root)

        binding.appList.layoutManager = LinearLayoutManager(this)
        allEntries = loadLaunchableApps()
        adapter = AppAdapter(allEntries) { entry ->
            val result = AppLauncher.launchOnExternalDisplay(this, entry.packageName)
            if (result.success) {
                Toast.makeText(
                    this,
                    getString(R.string.app_launched_toast, entry.label),
                    Toast.LENGTH_SHORT
                ).show()
                startActivity(android.content.Intent(this, TouchpadActivity::class.java))
                finish()
            } else {
                val message = buildLaunchFailureMessage(result)
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
        binding.appList.adapter = adapter

        binding.appPickerToolbar.title = getString(R.string.app_picker_title)
        binding.appPickerToolbar.setNavigationOnClickListener { finish() }

        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                filterApps(s?.toString().orEmpty())
            }
        })
    }

    private fun buildLaunchFailureMessage(result: AppLauncher.Result): String {
        val reason = result.reason
        if (reason == null) {
            return getString(R.string.app_launch_failed_generic)
        }
        val reasonLabel = getString(AppLauncher.reasonLabelResId(reason))
        val detailResId = result.detailResId
        return if (detailResId == null) {
            getString(R.string.app_launch_failed_reason_only, reasonLabel)
        } else {
            getString(
                R.string.app_launch_failed_with_detail,
                reasonLabel,
                getString(detailResId)
            )
        }
    }

    private fun loadLaunchableApps(): List<AppEntry> {
        val pm = packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        }
        val apps = pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        return apps.map { resolveInfo ->
            val appInfo = resolveInfo.activityInfo.applicationInfo
            AppEntry(
                label = resolveInfo.loadLabel(pm).toString(),
                packageName = appInfo.packageName,
                icon = resolveInfo.loadIcon(pm),
                launchCount = AppLaunchHistory.getCount(this, appInfo.packageName)
            )
        }.distinctBy { it.packageName }
            .sortedWith(
                compareByDescending<AppEntry> { it.launchCount }
                    .thenBy { it.label.lowercase() }
            )
    }

    private fun filterApps(query: String) {
        val trimmed = query.trim().lowercase()
        val filtered = if (trimmed.isBlank()) {
            allEntries
        } else {
            allEntries.filter {
                it.label.lowercase().contains(trimmed) || it.packageName.lowercase().contains(trimmed)
            }
        }
        adapter.updateItems(filtered)
    }

    data class AppEntry(
        val label: String,
        val packageName: String,
        val icon: android.graphics.drawable.Drawable,
        val launchCount: Int
    )

    private class AppAdapter(
        private var items: List<AppEntry>,
        private val onClick: (AppEntry) -> Unit
    ) : RecyclerView.Adapter<AppAdapter.AppViewHolder>() {

        fun updateItems(newItems: List<AppEntry>) {
            items = newItems
            notifyDataSetChanged()
        }

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
