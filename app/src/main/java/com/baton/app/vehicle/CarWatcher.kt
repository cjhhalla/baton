package com.baton.app.vehicle

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log

/**
 * AAOS 표준 API로 기어와 주행 중 화면 제한 상태를 구독한다.
 * - 기어: VehiclePropertyIds.GEAR_SELECTION (P = 0x0004)
 * - 화면 제한: CarUxRestrictionsManager (주행 중이면 requiresDistractionOptimization = true)
 */
class CarWatcher(
    private val context: Context,
    private val onGear: (Gear) -> Unit,
    private val onRestricted: (Boolean) -> Unit
) {
    enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

    private var car: Car? = null
    private var propertyManager: CarPropertyManager? = null
    private var uxManager: CarUxRestrictionsManager? = null

    private val gearCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val raw = value.value as? Int ?: return
            onGear(toGear(raw))
        }

        override fun onErrorEvent(propertyId: Int, areaId: Int) {
            Log.w(TAG, "gear error $propertyId/$areaId")
        }
    }

    fun start() {
        try {
            val c = Car.createCar(context)
            car = c
            propertyManager = (c.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager)?.also { pm ->
                pm.registerCallback(gearCallback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
            }
            uxManager = (c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager)?.also { ux ->
                onRestricted(ux.currentCarUxRestrictions?.isRequiresDistractionOptimization == true)
                ux.registerListener { r -> onRestricted(r.isRequiresDistractionOptimization) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Car API unavailable", e)
        }
    }

    fun stop() {
        runCatching { propertyManager?.unregisterCallback(gearCallback) }
        runCatching { uxManager?.unregisterListener() }
        runCatching { car?.disconnect() }
        car = null
    }

    private fun toGear(raw: Int): Gear = when (raw) {
        0x0004 -> Gear.PARK
        0x0002 -> Gear.REVERSE
        0x0001 -> Gear.NEUTRAL
        0x0008 -> Gear.DRIVE
        else -> if (raw >= 0x0010) Gear.DRIVE else Gear.UNKNOWN // 1단 이상의 수동/자동 단수
    }

    companion object {
        private const val TAG = "BatonCar"
    }
}
