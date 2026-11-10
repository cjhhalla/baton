package com.baton.app.vehicle

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.util.Log

/** 위도·경도 한 점 */
data class GeoPoint(val lat: Double, val lng: Double) {
    fun distanceTo(other: GeoPoint): Float {
        val out = FloatArray(1)
        Location.distanceBetween(lat, lng, other.lat, other.lng, out)
        return out[0]
    }
}

/**
 * 차량 위치를 Android 표준 LocationManager로 받는다.
 * (Pleos FusedLocation SDK는 아직 'Coming soon'이라 표준 API를 쓴다)
 * 에뮬레이터에서는 Extended Controls → Location에서 위치를 바꿔 시연한다.
 */
class LocationTracker(
    private val context: Context,
    private val onLocation: (GeoPoint) -> Unit
) {
    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val listener = LocationListener { loc -> onLocation(GeoPoint(loc.latitude, loc.longitude)) }
    private var started = false

    fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        if (started || !hasPermission()) return
        try {
            val providers = manager.getProviders(true)
            providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }
                ?.let { onLocation(GeoPoint(it.latitude, it.longitude)) }
            providers.forEach { p ->
                runCatching { manager.requestLocationUpdates(p, 3000L, 5f, listener, Looper.getMainLooper()) }
            }
            started = true
        } catch (e: Exception) {
            Log.e("BatonLocation", "location unavailable", e)
        }
    }

    fun stop() {
        runCatching { manager.removeUpdates(listener) }
        started = false
    }
}
