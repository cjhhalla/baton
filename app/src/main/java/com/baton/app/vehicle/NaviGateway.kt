package com.baton.app.vehicle

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener

/** 내비가 알려 준 현재 목적지와 남은 시간·거리 */
data class NaviTrip(
    val name: String,
    val point: GeoPoint,
    val remainSec: Long,
    val remainM: Double,
    val at: Long = System.currentTimeMillis()
)

/**
 * Pleos NaviHelper 연결.
 * - 반납 장소로 경로 안내 요청 (requestRoute)
 * - 안내 중인 목적지의 남은 시간을 받아 반납 시간과 비교 (getDestinationInfo)
 * 내비가 없거나 응답이 없어도 앱은 직선거리 추정으로 계속 동작한다.
 */
class NaviGateway(
    context: Context,
    private val onTrip: (NaviTrip?) -> Unit,
    private val onState: (String) -> Unit,
    /** 차량 내비가 알고 있는 현재 위치 (Pleos 차량에서는 이것이 기준 위치) */
    private val onLocation: (GeoPoint) -> Unit = {}
) {
    private val main = Handler(Looper.getMainLooper())
    private val helper = runCatching { NaviHelper(context.applicationContext) }.getOrNull()
    @Volatile var connected = false
        private set

    private val listener = object : NaviHelperEventListener {
        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            val d = destinationInfo
            val trip = runCatching {
                NaviTrip(
                    name = d.name ?: "",
                    point = GeoPoint(d.latitude, d.longitude),
                    remainSec = d.remainTime.toLong(),
                    remainM = d.remainDistance.toDouble()
                )
            }.getOrNull()
            onTrip(trip)
        }

        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            val c = currentLocationInfo
            if (c.latitude != 0.0 || c.longitude != 0.0) onLocation(GeoPoint(c.latitude, c.longitude))
        }

        override fun onDestinationChanged() = refresh()
        override fun onRouteCancelled() = onTrip(null)
    }

    fun start() {
        val h = helper ?: return onState("내비 SDK 없음")
        runCatching {
            h.initialize(
                {
                    main.post {
                        connected = true
                        h.addListener(listener)
                        onState("연결됨")
                        refresh()
                    }
                },
                { e -> main.post { onState("연결 실패: ${e.javaClass.simpleName}") } }
            )
        }.onFailure { onState("연결 불가: ${it.javaClass.simpleName}") }
    }

    /** 반납 장소로 안내를 시작한다. 성공 여부는 이후 onDestinationInfo로 확인된다. */
    fun guideTo(place: GeoPoint, name: String): Boolean {
        val h = helper ?: return false
        if (!connected) return false
        return runCatching {
            main.post {
                runCatching {
                    h.requestRoute(
                        RouteInfo(
                            latitude = place.lat, longitude = place.lng,
                            displayLatitude = null, displayLongitude = null,
                            poiName = name, poiId = "", address = "", poiSubId = "0"
                        )
                    )
                }.onFailure { onState("안내 요청 실패: ${it.javaClass.simpleName}") }
            }
            true
        }.getOrDefault(false)
    }

    /** 남은 시간은 차가 움직이며 바뀌므로 주기적으로 다시 묻는다 */
    fun refresh() {
        if (!connected) return
        main.post {
            runCatching { helper?.getDestinationInfo() }.onFailure { Log.w(TAG, "dest", it) }
            runCatching { helper?.getCurrentLocationInfo() }.onFailure { Log.w(TAG, "loc", it) }
        }
    }

    fun stop() {
        runCatching { helper?.removeListener(listener); helper?.release() }
        connected = false
    }

    companion object { private const val TAG = "Baton.Navi" }
}
