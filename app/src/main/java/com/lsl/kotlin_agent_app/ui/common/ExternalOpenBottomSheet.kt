package com.lsl.kotlin_agent_app.ui.common

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.lsl.kotlin_agent_app.databinding.BottomSheetExternalOpenBinding
import com.lsl.kotlin_agent_app.R

object ExternalOpenBottomSheet {

    fun show(
        activity: Activity,
        baseIntent: Intent,
        title: String,
    ) {
        val pm = activity.packageManager
        val candidates =
            runCatching {
                pm.queryIntentActivities(baseIntent, PackageManager.MATCH_DEFAULT_ONLY)
            }.getOrNull().orEmpty()

        if (candidates.isEmpty()) {
            Toast.makeText(activity, "没有找到可用应用", Toast.LENGTH_SHORT).show()
            return
        }

        val sorted =
            candidates.sortedBy { ri ->
                runCatching { ri.loadLabel(pm).toString() }.getOrNull().orEmpty().lowercase()
            }

        val dialog = BottomSheetDialog(activity)
        val binding = BottomSheetExternalOpenBinding.inflate(activity.layoutInflater)
        binding.textTitle.text = title
        binding.listApps.adapter = AppsAdapter(activity, sorted)

        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)

        binding.listApps.setOnItemClickListener { _, _, position, _ ->
            val ri = sorted.getOrNull(position) ?: return@setOnItemClickListener
            val pkg = ri.activityInfo?.packageName ?: return@setOnItemClickListener
            val cls = ri.activityInfo?.name ?: return@setOnItemClickListener
            val intent =
                Intent(baseIntent).apply {
                    component = ComponentName(pkg, cls)
                }
            runCatching { activity.startActivity(intent) }
                .onFailure { t ->
                    Toast.makeText(activity, t.message ?: "无法打开", Toast.LENGTH_LONG).show()
                }
            dialog.dismiss()
        }
        binding.buttonCancel.setOnClickListener { dialog.dismiss() }

        dialog.setContentView(binding.root)
        dialog.show()
    }

    private class AppsAdapter(
        private val activity: Activity,
        private val items: List<android.content.pm.ResolveInfo>,
    ) : BaseAdapter() {

        private val pm = activity.packageManager
        private val inflater = LayoutInflater.from(activity)

        override fun getCount(): Int = items.size

        override fun getItem(position: Int): Any = items[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view =
                convertView
                    ?: inflater.inflate(R.layout.item_external_open_app, parent, false)
            val icon = view.findViewById<ImageView>(R.id.image_icon)
            val label = view.findViewById<TextView>(R.id.text_label)

            val ri = items[position]
            val appLabel =
                runCatching { ri.loadLabel(pm).toString() }.getOrNull().orEmpty().ifBlank {
                    ri.activityInfo?.packageName.orEmpty()
                }
            label.text = appLabel
            val drawable =
                runCatching { ri.loadIcon(pm) }.getOrNull()
            icon.setImageDrawable(drawable)

            return view
        }
    }
}
