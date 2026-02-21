package com.lsl.kotlin_agent_app.ui.common

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.ArrayAdapter
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.lsl.kotlin_agent_app.databinding.BottomSheetExternalOpenBinding

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

        val labels =
            sorted.map { ri ->
                runCatching { ri.loadLabel(pm).toString() }.getOrNull().orEmpty().ifBlank {
                    ri.activityInfo?.packageName.orEmpty()
                }
            }

        val dialog = BottomSheetDialog(activity)
        val binding = BottomSheetExternalOpenBinding.inflate(activity.layoutInflater)
        binding.textTitle.text = title
        binding.listApps.adapter =
            ArrayAdapter(
                activity,
                android.R.layout.simple_list_item_1,
                labels,
            )

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
}

