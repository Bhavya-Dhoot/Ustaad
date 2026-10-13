package com.swarmos.app.hands.android

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.util.Log
import com.swarmos.app.hands.AppInfo

/**
 * The apps a person can open from the launcher (label and package), the current home app, and who would open an intent.
 * Read through PackageManager (the manifest `queries` entries make them visible on Android 11+). The launcher list is
 * kept in memory until refresh is asked for; the home app and link handlers are asked every time. A failed query
 * returns the last list, or nothing.
 */
object AppCatalog {
    private const val TAG = "AppCatalog"

    @Volatile private var launchable: List<AppInfo>? = null

    fun apps(ctx: Context, refresh: Boolean = false): List<AppInfo> {
        if (!refresh) launchable?.let { return it }
        val pm = ctx.applicationContext.packageManager
        val found = query(pm, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0) ?: return launchable.orEmpty()
        val list = ScreenMapping.dedupeApps(found.map { AppInfo(it.activityInfo.packageName, it.loadLabel(pm).toString()) })
        launchable = list
        return list
    }

    /**
     * The person's current home app and no other: an app that merely declares a home activity is not in the set.
     * Empty when no default home app is set (the system would ask). The core's guard treats it as "no app in front".
     */
    fun launcherPackages(ctx: Context): Set<String> {
        val home = handlerOf(ctx, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
        return if (home.isEmpty()) emptySet() else setOf(home)
    }

    /**
     * The package that would open the intent: its single handler or the person's default for it. "" when there is none,
     * when the system would show a chooser, or when the query failed. Nothing is started.
     */
    fun handlerOf(ctx: Context, intent: Intent): String {
        val pm = ctx.applicationContext.packageManager
        val handlers = query(pm, intent, PackageManager.MATCH_DEFAULT_ONLY) ?: return ""
        val best = resolve(pm, intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
        return ScreenMapping.realHandler(best?.packageName, best?.name, handlers.map { it.activityInfo.packageName to it.activityInfo.name })
    }

    @Suppress("DEPRECATION")
    private fun query(pm: PackageManager, intent: Intent, flags: Int): List<ResolveInfo>? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(flags.toLong()))
        } else {
            pm.queryIntentActivities(intent, flags)
        }
    } catch (e: Exception) {
        Log.w(TAG, "query failed: ${e.javaClass.simpleName}")
        null
    }

    @Suppress("DEPRECATION")
    private fun resolve(pm: PackageManager, intent: Intent, flags: Int): ResolveInfo? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(flags.toLong()))
        } else {
            pm.resolveActivity(intent, flags)
        }
    } catch (e: Exception) {
        Log.w(TAG, "resolve failed: ${e.javaClass.simpleName}")
        null
    }
}
