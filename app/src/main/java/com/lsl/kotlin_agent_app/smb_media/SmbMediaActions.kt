package com.lsl.kotlin_agent_app.smb_media

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import com.lsl.kotlin_agent_app.media.MusicPlayerController

object SmbMediaActions {

    data class ContentRef(
        val uri: Uri,
        val mime: String,
    )

    fun playNasSmbMp3(
        context: Context,
        agentsPath: String,
        displayName: String,
        musicController: MusicPlayerController,
    ) {
        playNasSmbAudio(
            context = context,
            agentsPath = agentsPath,
            displayName = displayName,
            musicController = musicController,
        )
    }

    fun playNasSmbAudio(
        context: Context,
        agentsPath: String,
        displayName: String,
        musicController: MusicPlayerController,
    ) {
        if (Build.VERSION.SDK_INT < 26) {
            Toast.makeText(context, "系统版本过低：SMB 音频串流需要 Android 8.0+（API 26）", Toast.LENGTH_LONG).show()
            return
        }

        val ref = SmbMediaAgentsPath.parseNasSmbFile(agentsPath) ?: return
        val mime =
            SmbMediaMime.fromFileNameOrNull(displayName)
                ?.takeIf { SmbMediaMime.isAudioMime(it) }
                ?: "audio/*"

        val ticket =
            SmbMediaRuntime.ticketStore(context).issue(
                SmbMediaTicketSpec(
                    mountName = ref.mountName,
                    remotePath = ref.relPath,
                    mime = mime,
                    sizeBytes = -1L,
                )
            )
        val uri = Uri.parse(SmbMediaUri.build(token = ticket.token, displayName = displayName))

        musicController.playNasSmbContentMp3(
            agentsPath = agentsPath,
            contentUriString = uri.toString(),
            displayName = displayName,
        )
    }

    fun createNasSmbVideoContent(
        context: Context,
        agentsPath: String,
        displayName: String,
    ): ContentRef? {
        if (Build.VERSION.SDK_INT < 26) {
            Toast.makeText(context, "系统版本过低：SMB 视频串流需要 Android 8.0+（API 26）", Toast.LENGTH_LONG).show()
            return null
        }

        val ref = SmbMediaAgentsPath.parseNasSmbFile(agentsPath) ?: return null
        val mime =
            SmbMediaMime.fromFileNameOrNull(displayName)
                ?.takeIf { SmbMediaMime.isVideoMime(it) }
                ?: "video/*"

        val ticket =
            SmbMediaRuntime.ticketStore(context).issue(
                SmbMediaTicketSpec(
                    mountName = ref.mountName,
                    remotePath = ref.relPath,
                    mime = mime,
                    sizeBytes = -1L,
                )
            )
        val uri = Uri.parse(SmbMediaUri.build(token = ticket.token, displayName = displayName))
        return ContentRef(uri = uri, mime = mime)
    }

    fun createNasSmbMp4Content(
        context: Context,
        agentsPath: String,
        displayName: String,
    ): ContentRef? {
        return createNasSmbVideoContent(
            context = context,
            agentsPath = agentsPath,
            displayName = displayName,
        )
    }

    fun openNasSmbMp4External(
        context: Context,
        agentsPath: String,
        displayName: String,
    ) {
        openNasSmbVideoExternal(
            context = context,
            agentsPath = agentsPath,
            displayName = displayName,
        )
    }

    fun openNasSmbVideoExternal(
        context: Context,
        agentsPath: String,
        displayName: String,
    ) {
        if (Build.VERSION.SDK_INT < 26) {
            Toast.makeText(context, "系统版本过低：外部播放器 seek 串流需要 Android 8.0+（API 26）", Toast.LENGTH_LONG).show()
            return
        }

        val ref = SmbMediaAgentsPath.parseNasSmbFile(agentsPath) ?: return
        val mime =
            SmbMediaMime.fromFileNameOrNull(displayName)
                ?.takeIf { SmbMediaMime.isVideoMime(it) }
                ?: "video/*"

        val ticket =
            SmbMediaRuntime.ticketStore(context).issue(
                SmbMediaTicketSpec(
                    mountName = ref.mountName,
                    remotePath = ref.relPath,
                    mime = mime,
                    sizeBytes = -1L,
                )
            )
        val uri = Uri.parse(SmbMediaUri.build(token = ticket.token, displayName = displayName))

        SmbMediaStreamingService.requestPrepare(context)

        openContentExternal(
            context = context,
            uri = uri,
            mime = mime,
            chooserTitle = "打开视频",
        )
    }

    fun openNasSmbImageExternal(
        context: Context,
        agentsPath: String,
        displayName: String,
    ) {
        if (Build.VERSION.SDK_INT < 26) {
            Toast.makeText(context, "系统版本过低：SMB 图片预览需要 Android 8.0+（API 26）", Toast.LENGTH_LONG).show()
            return
        }

        val ref = SmbMediaAgentsPath.parseNasSmbFile(agentsPath) ?: return
        val mime = SmbMediaMime.fromFileNameOrNull(displayName) ?: "image/*"

        val ticket =
            SmbMediaRuntime.ticketStore(context).issue(
                SmbMediaTicketSpec(
                    mountName = ref.mountName,
                    remotePath = ref.relPath,
                    mime = mime,
                    sizeBytes = -1L,
                )
            )
        val uri = Uri.parse(SmbMediaUri.build(token = ticket.token, displayName = displayName))

        openContentExternal(
            context = context,
            uri = uri,
            mime = mime,
            chooserTitle = "打开图片",
        )
    }

    fun openNasSmbFileExternal(
        context: Context,
        agentsPath: String,
        displayName: String,
        chooserTitle: String = "打开文件",
    ) {
        if (Build.VERSION.SDK_INT < 26) {
            Toast.makeText(context, "系统版本过低：SMB 外部打开需要 Android 8.0+（API 26）", Toast.LENGTH_LONG).show()
            return
        }

        val ref = SmbMediaAgentsPath.parseNasSmbFile(agentsPath) ?: return
        val mime =
            SmbMediaMime.fromFileNameOrNull(displayName)
                ?: guessMimeFromName(displayName)
                ?: "application/octet-stream"

        val ticket =
            SmbMediaRuntime.ticketStore(context).issue(
                SmbMediaTicketSpec(
                    mountName = ref.mountName,
                    remotePath = ref.relPath,
                    mime = mime,
                    sizeBytes = -1L,
                )
            )
        val uri = Uri.parse(SmbMediaUri.build(token = ticket.token, displayName = displayName))

        openContentExternal(
            context = context,
            uri = uri,
            mime = mime,
            chooserTitle = chooserTitle,
        )
    }

    fun createNasSmbImageContent(
        context: Context,
        agentsPath: String,
        displayName: String,
    ): ContentRef? {
        if (Build.VERSION.SDK_INT < 26) {
            Toast.makeText(context, "系统版本过低：SMB 图片预览需要 Android 8.0+（API 26）", Toast.LENGTH_LONG).show()
            return null
        }

        val ref = SmbMediaAgentsPath.parseNasSmbFile(agentsPath) ?: return null
        val mime = SmbMediaMime.fromFileNameOrNull(displayName) ?: "image/*"

        val ticket =
            SmbMediaRuntime.ticketStore(context).issue(
                SmbMediaTicketSpec(
                    mountName = ref.mountName,
                    remotePath = ref.relPath,
                    mime = mime,
                    sizeBytes = -1L,
                )
            )
        val uri = Uri.parse(SmbMediaUri.build(token = ticket.token, displayName = displayName))
        return ContentRef(uri = uri, mime = mime)
    }

    fun createNasSmbPdfContent(
        context: Context,
        agentsPath: String,
        displayName: String,
    ): ContentRef? {
        if (Build.VERSION.SDK_INT < 26) {
            Toast.makeText(context, "系统版本过低：SMB PDF 预览需要 Android 8.0+（API 26）", Toast.LENGTH_LONG).show()
            return null
        }

        val ref = SmbMediaAgentsPath.parseNasSmbFile(agentsPath) ?: return null
        val mime = SmbMediaMime.APPLICATION_PDF

        val ticket =
            SmbMediaRuntime.ticketStore(context).issue(
                SmbMediaTicketSpec(
                    mountName = ref.mountName,
                    remotePath = ref.relPath,
                    mime = mime,
                    sizeBytes = -1L,
                )
            )
        val uri = Uri.parse(SmbMediaUri.build(token = ticket.token, displayName = displayName))
        return ContentRef(uri = uri, mime = mime)
    }

    fun openContentExternal(
        context: Context,
        uri: Uri,
        mime: String,
        chooserTitle: String,
    ) {
        val intent =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newRawUri("media", uri)
            }

        grantToAllCandidates(context, intent, uri)

        try {
            context.startActivity(Intent.createChooser(intent, chooserTitle))
        } catch (t: Throwable) {
            Toast.makeText(context, t.message ?: "无法打开", Toast.LENGTH_LONG).show()
        }
    }

    private fun guessMimeFromName(name: String): String? {
        val ext = name.trim().substringAfterLast('.', missingDelimiterValue = "").lowercase().trim()
        if (ext.isBlank()) return null
        return when (ext) {
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "ppt" -> "application/vnd.ms-powerpoint"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "zip" -> "application/zip"
            "tar" -> "application/x-tar"
            "gz" -> "application/gzip"
            "tgz" -> "application/gzip"
            "7z" -> "application/x-7z-compressed"
            "rar" -> "application/vnd.rar"
            else -> null
        }
    }

    private fun grantToAllCandidates(
        context: Context,
        intent: Intent,
        uri: Uri,
    ) {
        val pm = context.packageManager ?: return
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val candidates =
            runCatching {
                pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            }.getOrNull().orEmpty()
        for (ri in candidates) {
            val pkg = ri.activityInfo?.packageName ?: continue
            runCatching { context.grantUriPermission(pkg, uri, flags) }
        }
    }
}
