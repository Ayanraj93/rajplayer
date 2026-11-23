package com.raj.rajplayer

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

object AppSecurity {
    // Apne final Release Keystore ka SHA-256 (bina colons, uppercase) yahan paste karein
    private const val EXPECTED_SHA256 = ""

    fun isDebug(ctx: Context): Boolean =
        (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    fun getApkSha256(ctx: Context): String {
        return try {
            val pm = ctx.packageManager
            val sigBytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val si = info.signingInfo ?: return ""
                val sigs = if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
                sigs.firstOrNull()?.toByteArray() ?: return ""
            } else {
                @Suppress("DEPRECATION")
                val info = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                info.signatures?.firstOrNull()?.toByteArray() ?: return ""
            }
            val md = MessageDigest.getInstance("SHA-256")
            md.digest(sigBytes).joinToString("") { "%02X".format(it) }
        } catch (_: Exception) {
            ""
        }
    }

    fun verify(ctx: Context): Boolean {
        if (isDebug(ctx)) return true
        if (EXPECTED_SHA256.isBlank()) return true
        val actual = getApkSha256(ctx)
        return actual.equals(EXPECTED_SHA256, ignoreCase = true)
    }
}
