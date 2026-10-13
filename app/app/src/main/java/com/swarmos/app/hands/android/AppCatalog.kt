package com.swarmos.app.hands.android

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.util.Log
import com.swarmos.app.hands.AppInfo

/**
 * The apps a person can open from the launcher (label and package), and the home apps.
 * Read through PackageManager (the manifest `queries` entries make them visible on Android 11+), kept in memory
 * until refresh is asked for. A failed query returns the last list.
 */
object AppCatalog {
    private const val TAG = "AppCatalog"

    @Volatile private var launchable: List<AppInfo>? = null
    @Volatile private var homes: Set<String>? = null

    fun apps(ctx: Context, refresh: Boolean = false): List<AppInfo> {
        if (!refresh) launchable?.let { return it }
        val pm = ctx.applicationContext.packageManager
        val found = query(pm, Intent.CATEGORY_LAUNCHER) ?: return launchable.orEmpty()
        val list = ScreenMapping.dedupeApps(found.map { AppInfo(it.activityInfo.packageName, it.loadLabel(pm).toString()) })
        launchable = list
        return list
    }

    /** Packages of the home screen apps (CATEGORY_HOME). The core's guard treats them as "no app in front". */
    fun launcherPackages(ctx: Context, refresh: Boolean = false): Set<String> {
        if (!refresh) homes?.let { return it }
        val pm = ctx.applicationContext.packageManager
        val found = query(pm, Intent.CATEGORY_HOME) ?: return homes.orEmpty()
        val set = found.map { it.activityInfo.packageName }.toSet()
        homes = set
        return set
    }

    @Suppress("DEPRECATION")
    private fun query(pm: PackageManager, category: String): List<ResolveInfo>? = try {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            pm.queryIntentActivities(intent, 0)
        }
    } catch (e: Exception) {
        Log.w(TAG, "query failed: ${e.javaClass.simpleName}")
        null
    }
}
