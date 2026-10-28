package app.chocola.agent

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

object BackgroundAccess {
    fun vivoPowerManagement() = Build.MANUFACTURER.equals("vivo", true) || Build.BRAND.equals("iqoo", true)
    fun batteryExempt(context: Context): Boolean = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    fun notificationsEnabled(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()
    fun restricted(context: Context): Boolean = context.getSystemService(ActivityManager::class.java).isBackgroundRestricted
    fun batteryIntent(context: Context) = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
    fun appSettingsIntent(context: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
    fun notificationIntent(context: Context) = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    /** OEM settings are separate from Android's battery exemption; there is no public read/grant API. */
    fun vendorBatteryIntent(context: Context) = Intent().setComponent(ComponentName("com.vivo.abe", "com.vivo.abe.ui.highpower.PowerManagerActivity"))
        .putExtra("package_name", context.packageName).putExtra("packageName", context.packageName)
    fun openVendorBattery(context: Context) {
        val candidates = listOf(vendorBatteryIntent(context), Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS), appSettingsIntent(context))
        for (intent in candidates) if (runCatching { context.startActivity(intent); true }.getOrDefault(false)) return
    }
    fun snapshot(context: Context): Map<String, Any?> = mapOf("batteryExempt" to batteryExempt(context), "backgroundRestricted" to restricted(context),
        "notificationsEnabled" to notificationsEnabled(context), "manufacturer" to Build.MANUFACTURER, "model" to Build.MODEL,
        "vendorPowerManagement" to if (vivoPowerManagement()) "vivo_separate_control" else "system", "vendorPowerGrantReadable" to false)
}
