package com.example.blackbox.sharing

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

fun Context.hasSharingNetworkPermissions(): Boolean {
    val internet = ContextCompat.checkSelfPermission(this, Manifest.permission.INTERNET)
    val networkState = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_NETWORK_STATE)
    return internet == PackageManager.PERMISSION_GRANTED && networkState == PackageManager.PERMISSION_GRANTED
}
