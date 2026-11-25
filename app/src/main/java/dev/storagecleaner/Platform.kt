package dev.storagecleaner

import android.Manifest
import android.app.AppOpsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.Process
import android.os.storage.StorageManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.File
import java.util.concurrent.TimeUnit

data class AppStat(
    val pkg: String, val name: String,
    val appBytes: Long, val dataBytes: Long, val cacheBytes: Long, // -1 = unknown (no usage access)
    val lastUsed: Long, val installed: Long,
) {
    val total get() = if (appBytes < 0) -1 else appBytes + dataBytes // dataBytes already includes the cache
    fun unusedDays(now: Long) = if (installed > now - 30 * DAY) 0 else ((now - maxOf(lastUsed, installed)) / DAY).toInt()
}

fun hasFilesAccess(ctx: Context): Boolean =
    if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
    else ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

fun hasUsageAccess(ctx: Context): Boolean {
    val ops = ctx.getSystemService(AppOpsManager::class.java)
    val mode = if (Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
    else @Suppress("DEPRECATION") ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
    return mode == AppOpsManager.MODE_ALLOWED
}

private fun PackageInfo.code(): Long = if (Build.VERSION.SDK_INT >= 28) longVersionCode else @Suppress("DEPRECATION") versionCode.toLong()

private fun PackageManager.infoOrNull(pkg: String): PackageInfo? =
    try { getPackageInfo(pkg, 0) } catch (e: PackageManager.NameNotFoundException) { null }

/** What an APK file is to this phone: already installed (safe to drop), an update, or a new app. */
fun apkNote(ctx: Context, f: File): String {
    val pm = ctx.packageManager
    val apk = pm.getPackageArchiveInfo(f.path, 0) ?: return "Broken or partial installer"
    val have = pm.infoOrNull(apk.packageName)
    return when {
        have == null -> "Installer for an app you don't have (v${apk.versionName})"
        have.code() >= apk.code() -> "Already installed (v${have.versionName}); this installer isn't needed"
        else -> "Newer than your installed v${have.versionName}"
    }
}

fun scannerFor(ctx: Context): Scanner {
    val pm = ctx.packageManager
    return Scanner(Environment.getExternalStorageDirectory(), isInstalled = { pm.infoOrNull(it) != null }, apkNote = { apkNote(ctx, it) })
}

fun historyFor(ctx: Context) = History(File(ctx.filesDir, "history.tsv"))

/** (total, free) bytes of internal storage, system included. */
fun storageTotals(ctx: Context): Pair<Long, Long> = try {
    val s = ctx.getSystemService(StorageStatsManager::class.java)
    s.getTotalBytes(StorageManager.UUID_DEFAULT) to s.getFreeBytes(StorageManager.UUID_DEFAULT)
} catch (e: Exception) {
    val d = Environment.getDataDirectory()
    d.totalSpace to d.usableSpace
}

/** User-installed (and updated system) apps with their storage; sizes need usage access. */
fun loadApps(ctx: Context): List<AppStat> {
    val pm = ctx.packageManager
    val stats = ctx.getSystemService(StorageStatsManager::class.java)
    val now = System.currentTimeMillis()
    val last = ctx.getSystemService(UsageStatsManager::class.java)
        .queryAndAggregateUsageStats(now - 365 * DAY, now).mapValues { it.value.lastTimeUsed }
    @Suppress("DEPRECATION") val pkgs = pm.getInstalledPackages(0) // one call: app info + install time together
    return pkgs.mapNotNull { pi ->
        val ai = pi.applicationInfo ?: return@mapNotNull null
        val user = ai.flags and ApplicationInfo.FLAG_SYSTEM == 0 || ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
        if (!user || pi.packageName == ctx.packageName) return@mapNotNull null
        val s = try { stats.queryStatsForPackage(ai.storageUuid, pi.packageName, Process.myUserHandle()) } catch (e: Exception) { null }
        AppStat(pi.packageName, pm.getApplicationLabel(ai).toString(), s?.appBytes ?: -1, s?.dataBytes ?: -1, s?.cacheBytes ?: -1,
            last[pi.packageName] ?: 0L, pi.firstInstallTime)
    }.sortedByDescending { it.total }
}

/** Weekly Safe-only cleanup, while charging. Logs to history and posts one notification. */
class CleanWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!hasFilesAccess(ctx)) return Result.success()
        val scanner = scannerFor(ctx)
        val safe = scanner.scan().items.filter { it.level == Level.SAFE }
        val freed = scanner.delete(safe)
        historyFor(ctx).add(Cleanup(System.currentTimeMillis(), freed, safe.size, "Weekly"))
        notify(ctx, "Weekly cleanup freed ${fmt(freed)}")
        return Result.success()
    }

    companion object {
        private const val NAME = "weekly-safe-clean"

        fun schedule(ctx: Context, on: Boolean) {
            val wm = WorkManager.getInstance(ctx)
            if (!on) {
                wm.cancelUniqueWork(NAME)
                return
            }
            val req = PeriodicWorkRequestBuilder<CleanWorker>(7, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiresBatteryNotLow(true).build())
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        private fun notify(ctx: Context, text: String) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("cleanup", "Automatic cleanup", NotificationManager.IMPORTANCE_LOW))
            nm.notify(1, android.app.Notification.Builder(ctx, "cleanup")
                .setSmallIcon(R.drawable.ic_launcher).setContentTitle("Storage Cleaner").setContentText(text).setAutoCancel(true).build())
        }
    }
}
