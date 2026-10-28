package com.example.privagentcollector

import android.Manifest
import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.database.Cursor
import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import android.os.SystemClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.CalendarContract.Events as CalendarEvents
import android.provider.ContactsContract.Data as ContactData
import android.provider.MediaStore
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.TimeZone

object Collectors {
    private const val CHUNK_SIZE = 25
    private const val MAX_TEXT = 512
    private fun granted(c: Context, p: String) = c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    private fun emit(c: Context, s: DataSource, session: String, p: JSONObject, event: String = s.name.lowercase()) =
        ResearchLog.append(c, s, session, event, p)
    private fun status(c: Context, s: DataSource, session: String, value: String): String {
        emit(c, s, session, JSONObject().put("status", value))
        return value
    }

    fun collectAll(c: Context, session: String, progress: (String) -> Unit): List<Pair<DataSource, String>> {
        val actions: List<Pair<DataSource, () -> String>> = listOf(
            DataSource.DEVICE to { device(c, session) },
            DataSource.BATTERY to { battery(c, session) },
            DataSource.NETWORK to { network(c, session) },
            DataSource.SENSORS to { sensors(c, session) },
            DataSource.APPS to { apps(c, session) },
            DataSource.CONTACTS to { contacts(c, session) },
            DataSource.CALENDAR to { calendar(c, session) },
            DataSource.IMAGES to { media(c, session, DataSource.IMAGES) },
            DataSource.VIDEOS to { media(c, session, DataSource.VIDEOS) },
            DataSource.AUDIO to { media(c, session, DataSource.AUDIO) },
            DataSource.USAGE to { usage(c, session) },
            DataSource.LOCATION to { location(c, session) }
        )
        val results = mutableListOf<Pair<DataSource, String>>()
        for ((source, action) in actions) {
            if (CollectionControl.session() != session) break
            if (!CollectionControl.allowed(c, source, session)) continue
            progress(source.title)
            val result = try { action() } catch (e: Exception) {
                runCatching { emit(c, source, session, JSONObject().put("status", "error")
                    .put("errorType", e.javaClass.simpleName)) }
                "error"
            }
            results.add(source to result)
        }
        return results
    }

    /** Lightweight state used after the one-time baseline scan. */
    fun collectRealtimeSelected(
        c: Context,
        session: String,
        progress: (String) -> Unit = {}
    ): List<Pair<DataSource, String>> {
        val actions: List<Pair<DataSource, () -> String>> = listOf(
            DataSource.DEVICE to { device(c, session) },
            DataSource.BATTERY to { battery(c, session) },
            DataSource.NETWORK to { network(c, session) },
            DataSource.LOCATION to { location(c, session) }
        )
        val results = mutableListOf<Pair<DataSource, String>>()
        for ((source, action) in actions) {
            if (CollectionControl.session() != session) break
            if (!CollectionControl.allowed(c, source, session)) continue
            progress(source.title)
            val result = try { action() } catch (e: Exception) {
                runCatching {
                    emit(c, source, session, JSONObject().put("status", "error")
                        .put("errorType", e.javaClass.simpleName))
                }
                "error"
            }
            results.add(source to result)
        }
        return results
    }

    private fun device(c: Context, session: String): String {
        val display = c.resources.displayMetrics
        val memory = ActivityManager.MemoryInfo()
        c.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        val disk = StatFs(c.filesDir.absolutePath)
        emit(c, DataSource.DEVICE, session, JSONObject()
            .put("status", "ok").put("manufacturer", Build.MANUFACTURER).put("brand", Build.BRAND)
            .put("model", Build.MODEL).put("device", Build.DEVICE).put("product", Build.PRODUCT)
            .put("sdkInt", Build.VERSION.SDK_INT).put("release", Build.VERSION.RELEASE)
            .put("securityPatch", Build.VERSION.SECURITY_PATCH)
            .put("androidId", Settings.Secure.getString(c.contentResolver, Settings.Secure.ANDROID_ID))
            .put("locale", Locale.getDefault().toLanguageTag()).put("timezone", TimeZone.getDefault().id)
            .put("screenWidthPx", display.widthPixels).put("screenHeightPx", display.heightPixels)
            .put("densityDpi", display.densityDpi).put("supportedAbis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("cpuCount", Runtime.getRuntime().availableProcessors())
            .put("totalRamBytes", memory.totalMem).put("availableRamBytes", memory.availMem)
            .put("lowMemory", memory.lowMemory).put("internalTotalBytes", disk.totalBytes)
            .put("internalAvailableBytes", disk.availableBytes).put("elapsedRealtimeMs", SystemClock.elapsedRealtime()))
        return "ok"
    }

    private fun battery(c: Context, session: String): String {
        val bm = c.getSystemService(BatteryManager::class.java)
        val intent = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        fun property(id: Int): Any = bm.getIntProperty(id).let { if (it == Int.MIN_VALUE) JSONObject.NULL else it }
        emit(c, DataSource.BATTERY, session, JSONObject().put("status", "ok")
            .put("capacityPercent", property(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            .put("chargeCounterMicroAh", property(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER))
            .put("currentNowMicroA", property(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW))
            .put("charging", bm.isCharging)
            .put("temperatureTenthsC", intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: JSONObject.NULL)
            .put("voltageMv", intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: JSONObject.NULL)
            .put("healthCode", intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: JSONObject.NULL)
            .put("pluggedCode", intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: JSONObject.NULL))
        return "ok"
    }

    @Suppress("DEPRECATION")
    private fun network(c: Context, session: String): String {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        val active = cm.activeNetwork
        val caps = active?.let { cm.getNetworkCapabilities(it) }
        val types = JSONArray()
        listOf(NetworkCapabilities.TRANSPORT_WIFI to "wifi",
            NetworkCapabilities.TRANSPORT_CELLULAR to "cellular",
            NetworkCapabilities.TRANSPORT_ETHERNET to "ethernet",
            NetworkCapabilities.TRANSPORT_VPN to "vpn",
            NetworkCapabilities.TRANSPORT_BLUETOOTH to "bluetooth").forEach { (type, name) ->
            if (caps?.hasTransport(type) == true) types.put(name)
        }
        val p = JSONObject().put("status", "ok").put("connected", active != null).put("transports", types)
            .put("internetValidated", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
            .put("metered", cm.isActiveNetworkMetered)
            .put("downstreamKbps", caps?.linkDownstreamBandwidthKbps ?: JSONObject.NULL)
            .put("upstreamKbps", caps?.linkUpstreamBandwidthKbps ?: JSONObject.NULL)
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
            val wifi = c.applicationContext.getSystemService(WifiManager::class.java).connectionInfo
            val ssid = wifi?.ssid
            val bssid = wifi?.bssid
            p.put("wifiSsid", if (ssid == null || ssid == WifiManager.UNKNOWN_SSID) JSONObject.NULL else ssid)
                .put("wifiBssid", if (bssid == null || bssid == "02:00:00:00:00:00") JSONObject.NULL else bssid)
                .put("wifiRssi", wifi?.rssi ?: JSONObject.NULL)
                .put("wifiLinkSpeedMbps", wifi?.linkSpeed ?: JSONObject.NULL)
                .put("wifiIdentityStatus", if (ssid == null || ssid == WifiManager.UNKNOWN_SSID) "redacted_or_unavailable" else "available")
        }
        emit(c, DataSource.NETWORK, session, p)
        return "ok"
    }

    private fun sensors(c: Context, session: String): String {
        val list = c.getSystemService(SensorManager::class.java).getSensorList(Sensor.TYPE_ALL)
        val arr = JSONArray()
        list.take(256).forEach { sensor ->
            arr.put(JSONObject().put("name", sensor.name).put("vendor", sensor.vendor).put("type", sensor.type)
                .put("stringType", sensor.stringType).put("version", sensor.version).put("powerMa", sensor.power)
                .put("maxRange", sensor.maximumRange).put("resolution", sensor.resolution))
        }
        emit(c, DataSource.SENSORS, session, JSONObject().put("status", if (list.isEmpty()) "empty" else "ok")
            .put("items", arr).put("count", arr.length()).put("truncated", list.size > 256))
        return if (list.isEmpty()) "empty" else "ok"
    }

    @Suppress("DEPRECATION")
    private fun apps(c: Context, session: String): String {
        val pm = c.packageManager
        val list = if (Build.VERSION.SDK_INT >= 33)
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
            else pm.getInstalledApplications(0)
        var chunk = JSONArray()
        var index = 0
        for (app in list.take(2000)) {
            if (!CollectionControl.allowed(c, DataSource.APPS, session)) return "stopped"
            val label = runCatching { pm.getApplicationLabel(app).toString() }.getOrDefault(app.packageName)
            chunk.put(JSONObject().put("package", app.packageName).put("label", label.take(MAX_TEXT))
                .put("systemApp", (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
                .put("launchable", pm.getLaunchIntentForPackage(app.packageName) != null))
            if (chunk.length() == CHUNK_SIZE) {
                emit(c, DataSource.APPS, session, JSONObject().put("status", "ok")
                    .put("chunkIndex", index++).put("visibility", "filtered_by_android").put("items", chunk))
                chunk = JSONArray()
            }
        }
        emit(c, DataSource.APPS, session, JSONObject().put("status", "ok").put("chunkIndex", index)
            .put("visibility", "filtered_by_android").put("count", minOf(list.size, 2000))
            .put("truncated", list.size > 2000).put("items", chunk))
        return if (list.size > 2000) "truncated" else "ok"
    }

    private fun cursorValue(cursor: Cursor, index: Int): Any = when (cursor.getType(index)) {
        Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
        else -> cursor.getString(index).take(MAX_TEXT)
    }

    /** Chunked writes keep a large address book from becoming one enormous JSON record. */
    private fun query(c: Context, source: DataSource, session: String, uri: Uri,
        fields: List<Pair<String, String>>, sort: String?, maxRows: Int,
        selection: String? = null, args: Array<String>? = null, access: String = "full"): String {
        val projection = fields.map { it.first }.toTypedArray()
        val cursor = c.contentResolver.query(uri, projection, selection, args, sort)
            ?: return status(c, source, session, "provider_unavailable")
        var count = 0
        var chunkIndex = 0
        var chunk = JSONArray()
        var truncated = false
        cursor.use {
            val indices = projection.map { col -> it.getColumnIndexOrThrow(col) }
            while (it.moveToNext()) {
                if (!CollectionControl.allowed(c, source, session)) return "stopped"
                if (count >= maxRows) { truncated = true; break }
                val row = JSONObject()
                fields.forEachIndexed { i, field -> row.put(field.second, cursorValue(it, indices[i])) }
                chunk.put(row)
                count++
                if (chunk.length() >= CHUNK_SIZE) {
                    emit(c, source, session, JSONObject().put("status", "ok").put("access", access)
                        .put("chunkIndex", chunkIndex++).put("items", chunk))
                    chunk = JSONArray()
                }
            }
        }
        val value = when { truncated -> "truncated"; count == 0 -> "empty"; access == "partial" -> "partial"; else -> "ok" }
        emit(c, source, session, JSONObject().put("status", value).put("access", access)
            .put("chunkIndex", chunkIndex).put("count", count).put("truncated", truncated)
            .put("maxRows", maxRows).put("items", chunk))
        return value
    }

    private fun contacts(c: Context, session: String): String {
        if (!granted(c, Manifest.permission.READ_CONTACTS)) return status(c, DataSource.CONTACTS, session, "permission_missing")
        return query(c, DataSource.CONTACTS, session, ContactData.CONTENT_URI,
            listOf(ContactData.CONTACT_ID to "contactId", ContactData.DISPLAY_NAME to "displayName", ContactData.MIMETYPE to "mimeType",
                ContactData.DATA1 to "value", ContactData.DATA2 to "type", ContactData.DATA3 to "label", ContactData.DATA4 to "additionalValue"),
            ContactData.CONTACT_ID + " ASC", 10000, ContactData.MIMETYPE + " IN (?,?,?,?)",
            arrayOf(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE))
    }

    private fun calendar(c: Context, session: String): String {
        if (!granted(c, Manifest.permission.READ_CALENDAR)) return status(c, DataSource.CALENDAR, session, "permission_missing")
        return query(c, DataSource.CALENDAR, session, CalendarEvents.CONTENT_URI,
            listOf(CalendarEvents._ID to "id", CalendarEvents.TITLE to "title", CalendarEvents.DESCRIPTION to "description",
                CalendarEvents.EVENT_LOCATION to "location", CalendarEvents.DTSTART to "startMs", CalendarEvents.DTEND to "endMs",
                CalendarEvents.CALENDAR_DISPLAY_NAME to "calendar", CalendarEvents.RRULE to "recurrenceRule"),
            CalendarEvents.DTSTART + " DESC", 1000)
    }

    private fun media(c: Context, session: String, source: DataSource): String {
        val full = if (Build.VERSION.SDK_INT >= 33) granted(c, when (source) {
            DataSource.IMAGES -> Manifest.permission.READ_MEDIA_IMAGES
            DataSource.VIDEOS -> Manifest.permission.READ_MEDIA_VIDEO
            else -> Manifest.permission.READ_MEDIA_AUDIO
        })
            else granted(c, Manifest.permission.READ_EXTERNAL_STORAGE)
        val partial = source != DataSource.AUDIO && Build.VERSION.SDK_INT >= 34 &&
            granted(c, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        if (!full && !partial) return status(c, source, session, "permission_missing")
        val uri = when (source) {
            DataSource.IMAGES -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            DataSource.VIDEOS -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val fields = mutableListOf(MediaStore.MediaColumns._ID to "id",
            MediaStore.MediaColumns.DISPLAY_NAME to "displayName", MediaStore.MediaColumns.MIME_TYPE to "mimeType",
            MediaStore.MediaColumns.DATE_ADDED to "dateAddedSeconds", MediaStore.MediaColumns.SIZE to "sizeBytes")
        if (source != DataSource.AUDIO) fields.addAll(listOf(
            MediaStore.Images.ImageColumns.DATE_TAKEN to "dateTakenMs",
            MediaStore.MediaColumns.WIDTH to "width", MediaStore.MediaColumns.HEIGHT to "height"))
        if (source == DataSource.VIDEOS) fields.add(MediaStore.Video.VideoColumns.DURATION to "durationMs")
        if (source == DataSource.AUDIO) fields.add(MediaStore.Audio.AudioColumns.DURATION to "durationMs")
        return query(c, source, session, uri, fields, MediaStore.MediaColumns.DATE_ADDED + " DESC",
            500, access = if (full) "full" else "partial")
    }

    @Suppress("DEPRECATION")
    fun usageGranted(c: Context): Boolean {
        val ops = c.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), c.packageName)
            else ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), c.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun usage(c: Context, session: String): String {
        if (!usageGranted(c)) return status(c, DataSource.USAGE, session, "special_access_missing")
        val manager = c.getSystemService(UsageStatsManager::class.java)
        val end = System.currentTimeMillis()
        val start = end - 7L * 24 * 60 * 60 * 1000
        val stats = manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end).orEmpty()
        val arr = JSONArray()
        stats.sortedByDescending { it.lastTimeUsed }.take(300).forEach { s ->
            arr.put(JSONObject().put("package", s.packageName).put("firstTimeStamp", s.firstTimeStamp)
                .put("lastTimeStamp", s.lastTimeStamp).put("lastTimeUsed", s.lastTimeUsed)
                .put("totalTimeVisibleMs", if (Build.VERSION.SDK_INT >= 29) s.totalTimeVisible else JSONObject.NULL)
                .put("totalTimeForegroundMs", s.totalTimeInForeground))
        }
        emit(c, DataSource.USAGE, session, JSONObject().put("status", if (stats.isEmpty()) "empty" else "ok")
            .put("startMs", start).put("endMs", end).put("items", arr).put("truncated", stats.size > 300), "usage_stats")
        val events = manager.queryEvents(end - 24L * 60 * 60 * 1000, end)
            ?: return "provider_unavailable"
        val event = UsageEvents.Event()
        var chunk = JSONArray()
        var count = 0
        var index = 0
        while (events.hasNextEvent() && count < 5000) {
            if (!CollectionControl.allowed(c, DataSource.USAGE, session)) return "stopped"
            events.getNextEvent(event)
            chunk.put(JSONObject().put("package", event.packageName).put("class", event.className)
                .put("type", event.eventType).put("timestampMs", event.timeStamp))
            count++
            if (chunk.length() == CHUNK_SIZE) {
                emit(c, DataSource.USAGE, session, JSONObject().put("status", "ok")
                    .put("chunkIndex", index++).put("items", chunk), "usage_events")
                chunk = JSONArray()
            }
        }
        emit(c, DataSource.USAGE, session, JSONObject().put("status", if (count == 0) "empty" else "ok")
            .put("chunkIndex", index).put("count", count).put("items", chunk)
            .put("truncated", events.hasNextEvent()), "usage_events")
        return if (count == 0 && stats.isEmpty()) "empty" else "ok"
    }

    private fun location(c: Context, session: String): String {
        val fine = granted(c, Manifest.permission.ACCESS_FINE_LOCATION)
        if (!fine && !granted(c, Manifest.permission.ACCESS_COARSE_LOCATION))
            return status(c, DataSource.LOCATION, session, "permission_missing")
        val manager = c.getSystemService(LocationManager::class.java)
        val arr = JSONArray()
        for (provider in manager.getProviders(true)) {
            try {
                val loc = manager.getLastKnownLocation(provider) ?: continue
                arr.put(JSONObject().put("provider", provider).put("lat", loc.latitude).put("lon", loc.longitude)
                    .put("accuracyM", if (loc.hasAccuracy()) loc.accuracy else JSONObject.NULL)
                    .put("altitudeM", if (loc.hasAltitude()) loc.altitude else JSONObject.NULL)
                    .put("speedMps", if (loc.hasSpeed()) loc.speed else JSONObject.NULL)
                    .put("bearingDegrees", if (loc.hasBearing()) loc.bearing else JSONObject.NULL)
                    .put("timeMs", loc.time)
                    .put("ageMs", maxOf(0L, (SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1000000))
                    .put("mock", if (Build.VERSION.SDK_INT >= 31) loc.isMock else @Suppress("DEPRECATION") loc.isFromMockProvider))
            } catch (_: SecurityException) { /* Not every provider permits coarse access. */ }
        }
        val value = if (arr.length() == 0) "no_cached_location" else "ok"
        emit(c, DataSource.LOCATION, session, JSONObject().put("status", value)
            .put("access", if (fine) "fine" else "coarse").put("items", arr))
        return value
    }
}
