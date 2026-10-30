package com.example.privagentcollector

import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.AudioManager
import android.media.ExifInterface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.Settings
import org.json.JSONArray
import java.io.File

/** L1 initialization data: which contacts are 女兒、兒子… Kept only on the phone, never sent. */
object RelationBindings {
    private const val PREFS = "relation_bindings"
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun names(context: Context, relation: String): List<String> {
        val raw = prefs(context).getString(relation, null) ?: return emptyList()
        return try { JSONArray(raw).let { a -> (0 until a.length()).map { a.getString(it) } } } catch (_: Exception) { emptyList() }
    }

    fun add(context: Context, relation: String, name: String) {
        val names = (names(context, relation) + name.trim()).filter { it.isNotEmpty() }.distinct()
        prefs(context).edit().putString(relation, JSONArray(names).toString()).apply()
    }

    fun clear(context: Context, relation: String) { prefs(context).edit().remove(relation).apply() }
}

/** Reads the few system values RESCUE_PHONE needs, as bands; nothing else on the device is touched. */
object DeviceState {
    fun read(context: Context): CoreTasks.RescueState {
        val airplane = try {
            when (Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, -1)) {
                1 -> "on"; 0 -> "off"; else -> "unknown"
            }
        } catch (_: Exception) { "unknown" }
        var network = "unknown"; var validated = "unknown"; var transport = "UNKNOWN"
        try {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val active = cm.activeNetwork
            if (active == null) {
                network = "offline"; validated = "no"; transport = "NONE"
            } else {
                val caps = cm.getNetworkCapabilities(active)
                if (caps != null) {
                    network = "connected"
                    validated = if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) "yes" else "no"
                    transport = when {
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
                        else -> "UNKNOWN"
                    }
                }
            }
        } catch (_: Exception) {}
        val dnd = try {
            when (context.getSystemService(NotificationManager::class.java).currentInterruptionFilter) {
                NotificationManager.INTERRUPTION_FILTER_ALL -> "off"
                NotificationManager.INTERRUPTION_FILTER_PRIORITY, NotificationManager.INTERRUPTION_FILTER_NONE,
                NotificationManager.INTERRUPTION_FILTER_ALARMS -> "on"
                else -> "unknown"
            }
        } catch (_: Exception) { "unknown" }
        var ringer = "unknown"; var band = "unknown"
        try {
            val am = context.getSystemService(AudioManager::class.java)
            ringer = when (am.ringerMode) {
                AudioManager.RINGER_MODE_NORMAL -> "normal"; AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                AudioManager.RINGER_MODE_SILENT -> "silent"; else -> "unknown"
            }
            band = CoreTasks.volumeBand(am.getStreamVolume(AudioManager.STREAM_RING), am.getStreamMaxVolume(AudioManager.STREAM_RING))
        } catch (_: Exception) {}
        return CoreTasks.RescueState(airplane, network, validated, transport, dnd, ringer, band)
    }
}

/**
 * Re-encodes the chosen photo into a fresh JPEG: EXIF (GPS, time, device) is not carried over,
 * so the recipient only gets pixels. The copy lives in cache/shared until the task ends.
 */
object PhotoSanitizer {
    data class Result(val file: File, val thumbnail: Bitmap, val hadLocation: Boolean)
    private const val MAX_EDGE = 2048

    fun dir(context: Context) = File(context.cacheDir, "shared").apply { mkdirs() }

    fun clear(context: Context) { dir(context).listFiles()?.forEach { it.delete() } }

    fun prepare(context: Context, uri: Uri, name: String): Result {
        val resolver = context.contentResolver
        var orientation = ExifInterface.ORIENTATION_NORMAL
        var hadLocation = false
        try {
            resolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                hadLocation = exif.getLatLong(FloatArray(2)) || exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE) != null
            }
        } catch (_: Exception) {}
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // With inJustDecodeBounds the decoder returns null by design; only the measured size counts.
        (resolver.openInputStream(uri) ?: error("無法讀取照片")).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) error("無法讀取照片")
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_EDGE) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("無法讀取照片")
        val rotation = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f; ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f; else -> 0f
        }
        val upright = if (rotation == 0f) decoded else
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(rotation) }, true)
        val file = File(dir(context), "$name.jpg")
        file.outputStream().use { upright.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        val scale = 600f / maxOf(upright.width, upright.height)
        val thumbnail = if (scale >= 1f) upright else
            Bitmap.createScaledBitmap(upright, (upright.width * scale).toInt(), (upright.height * scale).toInt(), true)
        return Result(file, thumbnail, hadLocation)
    }
}
