package com.deskcontrol

import android.app.ActivityOptions
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.deskcontrol.databinding.ActivityAppDrawerBinding
import com.deskcontrol.databinding.ItemAppDrawerBinding

class AppDrawerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppDrawerBinding
    private lateinit var adapter: AppAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (DisplaySessionManager.getExternalDisplayInfo() == null) {
            DiagnosticsLog.add("Drawer: no external display, closing")
            finish()
            return
        }
        binding = ActivityAppDrawerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        applyEdgeToEdgePadding(binding.root)

        binding.appDrawerToolbar.title = getString(R.string.app_drawer_title)
        binding.appDrawerToolbar.setNavigationOnClickListener { finish() }

        val spanCount = 4
        binding.appGrid.layoutManager = GridLayoutManager(this, spanCount)
        adapter = AppAdapter(loadLaunchableApps()) { entry ->
            DiagnosticsLog.add("Drawer: launch request package=${entry.packageName}")
            val result = AppLauncher.launchOnExternalDisplay(this, entry.packageName)
            if (result.success) {
                DiagnosticsLog.add("Drawer: launch success package=${entry.packageName}")
                finish()
            } else {
                val message = AppLauncher.buildFailureMessage(this, result)
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                DiagnosticsLog.add(
                    "Drawer: launch failure package=${entry.packageName} reason=${result.reason}"
                )
            }
        }
        binding.appGrid.adapter = adapter
    }

    private fun loadLaunchableApps(): List<AppEntry> {
        return LaunchableAppCatalog.load(this).map { app ->
            AppEntry(
                label = app.label,
                packageName = app.packageName,
                icon = app.icon
            )
        }
    }

    data class AppEntry(
        val label: String,
        val packageName: String,
        val icon: android.graphics.drawable.Drawable
    )

    private class AppAdapter(
        private var items: List<AppEntry>,
        private val onClick: (AppEntry) -> Unit
    ) : RecyclerView.Adapter<AppAdapter.AppViewHolder>() {

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): AppViewHolder {
            val binding = ItemAppDrawerBinding.inflate(
                android.view.LayoutInflater.from(parent.context),
                parent,
                false
            )
            return AppViewHolder(binding, onClick)
        }

        override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        class AppViewHolder(
            private val binding: ItemAppDrawerBinding,
            private val onClick: (AppEntry) -> Unit
        ) : RecyclerView.ViewHolder(binding.root) {

            fun bind(entry: AppEntry) {
                binding.appIcon.setImageDrawable(entry.icon)
                binding.appIcon.contentDescription = entry.label
                binding.appLabel.text = entry.label
                binding.root.setOnClickListener { onClick(entry) }
            }
        }
    }

    companion object {
        fun launchOnExternalDisplay(context: Context, displayId: Int) {
            val intent = android.content.Intent(context, AppDrawerActivity::class.java).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
            context.startActivity(intent, options.toBundle())
        }
    }
}
