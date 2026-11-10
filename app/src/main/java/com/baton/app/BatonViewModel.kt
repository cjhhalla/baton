package com.baton.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.baton.app.data.HandoverStore
import com.baton.app.data.Issue
import com.baton.app.data.IssueKind
import com.baton.app.data.ReturnProof
import com.baton.app.data.Snapshot
import com.baton.app.data.WarningRecord
import com.baton.app.vehicle.CarWatcher
import com.baton.app.vehicle.GeoPoint
import com.baton.app.vehicle.Interpret
import com.baton.app.vehicle.LocationTracker
import com.baton.app.vehicle.NaviGateway
import com.baton.app.vehicle.NaviTrip
import com.baton.app.vehicle.Probe
import com.baton.app.vehicle.ProbeResult
import com.baton.app.vehicle.Signals
import com.baton.app.vehicle.VehicleReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Screen { HOME, PARK_CHECK, REPORT, PICK_DRIVER, BRIEFING, DIAGNOSTICS }

/** P단에 섰을 때 상황 */
enum class ParkMode {
    /** 반납 장소 근처: 반납 체크 */
    AT_RETURN_SPOT,
    /** 반납 장소가 아닌 곳: 잠깐 정차 */
    STOPOVER,
    /** 위치를 알 수 없음: 운전자에게 묻는다 */
    UNKNOWN
}

enum class TimingLevel { RELAXED, TIGHT, LATE }

/** 반납 시간 판단: 지금 출발하면 언제 도착하고, 예정 시각까지 얼마나 남는지 */
data class ReturnTiming(
    val deadline: Long,
    /** 반납 장소까지 걸리는 시간(초). 위치를 모르면 null */
    val etaSec: Long?,
    /** 내비가 계산한 값이면 true, 직선거리 추정이면 false */
    val fromNavi: Boolean,
    /** 지금 출발했을 때 도착 예상 시각 */
    val arriveAt: Long?,
    /** 예정 시각까지 여유(분). 음수면 늦음 */
    val slackMin: Long,
    /** 늦지 않으려면 이 시각까지 출발 */
    val leaveBy: Long?,
    val level: TimingLevel
)

data class UiState(
    val screen: Screen = Screen.HOME,
    val gear: CarWatcher.Gear = CarWatcher.Gear.UNKNOWN,
    val restricted: Boolean = false,
    val sdkReady: Boolean = false,
    val currentDriver: String? = null,
    val drivers: List<String> = emptyList(),
    val results: Map<String, ProbeResult> = emptyMap(),
    val issues: List<Issue> = emptyList(),
    val activeWarnings: List<WarningRecord> = emptyList(),
    val lastSnapshot: Snapshot? = null,
    val reportSnapshot: Snapshot? = null,
    val lastReadAt: Long = 0L,
    val location: GeoPoint? = null,
    val returnPlace: GeoPoint? = null,
    val parkMode: ParkMode = ParkMode.UNKNOWN,
    val locationPermission: Boolean = false,
    val returnDeadline: Long? = null,
    val naviTrip: NaviTrip? = null,
    val naviState: String = "연결 전",
    val now: Long = System.currentTimeMillis()
) {
    /** 반납 장소까지 거리(m) */
    val distanceToReturnM: Float?
        get() = if (location != null && returnPlace != null) location.distanceTo(returnPlace) else null

    /** 내비가 지금 반납 장소로 안내 중인지 */
    val guidingToReturn: Boolean
        get() {
            val t = naviTrip ?: return false
            val r = returnPlace ?: return false
            return t.point.distanceTo(r) <= BatonViewModel.RETURN_RADIUS_M
        }

    val timing: ReturnTiming?
        get() {
            val deadline = returnDeadline ?: return null
            val dist = distanceToReturnM
            val navi = naviTrip?.takeIf { guidingToReturn && now - it.at < 90_000 }
            val etaSec: Long? = when {
                navi != null -> navi.remainSec
                dist == null -> null
                dist <= BatonViewModel.RETURN_RADIUS_M -> 0L
                // 내비가 없을 때: 직선거리 x1.3 을 시속 30km로 (도심 기준 보수적 추정)
                else -> (dist * 1.3f / (30_000f / 3600f)).toLong()
            }
            val arriveAt = etaSec?.let { now + it * 1000 }
            val slackMin = ((deadline - (arriveAt ?: now)) / 60_000)
            val level = when {
                slackMin < 0 -> TimingLevel.LATE
                slackMin < BatonViewModel.TIGHT_MIN -> TimingLevel.TIGHT
                else -> TimingLevel.RELAXED
            }
            return ReturnTiming(
                deadline, etaSec, navi != null, arriveAt, slackMin,
                etaSec?.let { deadline - it * 1000 }, level
            )
        }
}

class BatonViewModel(app: Application) : AndroidViewModel(app) {

    private val store = HandoverStore(app)
    private val reader = VehicleReader(app)
    private val watcher = CarWatcher(app, ::onGear, ::onRestricted)
    /** 내비 위치를 한 번이라도 받으면 그 뒤로는 내비 위치를 기준으로 쓴다 */
    @Volatile private var naviLocated = false
    private val locator: LocationTracker = LocationTracker(app) { p ->
        if (!naviLocated) _state.update { it.copy(location = p) }
    }
    private val navi: NaviGateway = NaviGateway(
        app,
        onTrip = { t: NaviTrip? -> _state.update { it.copy(naviTrip = t) } },
        onState = { m: String -> _state.update { it.copy(naviState = m) } },
        onLocation = { p: GeoPoint ->
            naviLocated = true
            _state.update { it.copy(location = p) }
        }
    )

    private val _state: MutableStateFlow<UiState> = MutableStateFlow(
        UiState(
            currentDriver = store.currentDriver,
            drivers = store.drivers,
            lastSnapshot = store.lastSnapshot,
            activeWarnings = store.activeWarnings.values.toList(),
            returnPlace = store.returnPlace,
            returnDeadline = store.returnDeadline,
            locationPermission = locator.hasPermission()
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        locator.start()
        navi.start()
        viewModelScope.launch(Dispatchers.IO) {
            val ready = reader.initialize()
            _state.update { it.copy(sdkReady = ready) }
            watcher.start()
            while (true) {
                navi.refresh()
                refresh()
                val fast = _state.value.screen in setOf(Screen.PARK_CHECK, Screen.DIAGNOSTICS)
                delay(if (fast) 1500 else 5000)
            }
        }
    }

    override fun onCleared() {
        watcher.stop()
        locator.stop()
        navi.stop()
        reader.release()
    }

    /** 위치 권한이 허용된 뒤 MainActivity가 호출 */
    fun onLocationPermissionGranted() {
        _state.update { it.copy(locationPermission = true) }
        locator.start()
    }

    // ---------- 차량 이벤트 ----------

    private fun onGear(gear: CarWatcher.Gear) {
        val prev = _state.value.gear
        _state.update { it.copy(gear = gear) }
        val parkedAfterDriving = gear == CarWatcher.Gear.PARK &&
            prev != CarWatcher.Gear.PARK && prev != CarWatcher.Gear.UNKNOWN
        if (parkedAfterDriving && _state.value.currentDriver != null) {
            val dist = _state.value.distanceToReturnM
            val mode = when {
                dist == null -> ParkMode.UNKNOWN
                dist <= RETURN_RADIUS_M -> ParkMode.AT_RETURN_SPOT
                else -> ParkMode.STOPOVER
            }
            _state.update { it.copy(parkMode = mode, screen = Screen.PARK_CHECK) }
        }
    }

    private fun onRestricted(restricted: Boolean) {
        _state.update { it.copy(restricted = restricted) }
    }

    // ---------- 신호 읽기 ----------

    suspend fun refresh() {
        val fresh = kotlinx.coroutines.coroutineScope {
            Signals.ALL.map { p -> async { reader.read(p) } }.awaitAll()
        }.associateBy { it.probe.id }
        // 한 번 읽기 실패(시간 초과)로 화면 값이 '—'로 깜빡이지 않도록 직전 값을 유지
        val prev = _state.value.results
        val results = fresh.mapValues { (k, r) ->
            if (r.values.isEmpty() && prev[k]?.values?.isNotEmpty() == true) prev.getValue(k) else r
        }

        learnSeatSemantics(results)
        trackWarnings(results)
        _state.update {
            it.copy(
                results = results,
                issues = computeIssues(results),
                activeWarnings = store.activeWarnings.values.sortedBy { w -> w.firstSeenAt },
                lastReadAt = System.currentTimeMillis(),
                now = System.currentTimeMillis()
            )
        }
    }

    /**
     * 좌석 감지 값의 의미는 차량(또는 에뮬레이터)마다 반대일 수 있다.
     * 주행 중에는 운전석에 반드시 사람이 있으므로, 그때 운전석 값을 '있음'으로 학습한다.
     */
    private fun learnSeatSemantics(results: Map<String, ProbeResult>) {
        val g = _state.value.gear
        if (g != CarWatcher.Gear.DRIVE && g != CarWatcher.Gear.REVERSE) return
        val v = results[Signals.SEAT_OCCUPANCY.id]?.values?.get("ROW_1_LEFT") as? Boolean ?: return
        if (store.occupiedValue != v) store.occupiedValue = v
    }

    private fun seatOccupied(v: Any?): Boolean? {
        val learned = store.occupiedValue
        return if (learned != null && v is Boolean) v == learned else Interpret.isOccupied(v)
    }

    /** 경고가 새로 켜지면 그 시각과 현재 운전자를 남기고, 꺼지면 지운다 */
    private fun trackWarnings(results: Map<String, ProbeResult>) {
        val active = store.activeWarnings
            .filterKeys { key -> Signals.WARNINGS.any { key == it.id || key.startsWith(it.id + ":") } }
            .toMutableMap()
        val driver = store.currentDriver ?: "인수자 없음(주차 중)"
        val now = System.currentTimeMillis()
        Signals.WARNINGS.forEach { probe ->
            val r = results[probe.id] ?: return@forEach
            if (r.values.isEmpty()) return@forEach
            r.values.forEach { (area, v) ->
                val key = if (area == "-") probe.id else "${probe.id}:$area"
                val label = if (area == "-") probe.label else "${probe.label} · ${areaName(area)}"
                when (Interpret.isWarning(v)) {
                    true -> if (key !in active) active[key] = WarningRecord(key, label, now, driver)
                    false -> active.remove(key)
                    null -> Unit
                }
            }
        }
        store.activeWarnings = active
    }

    private fun computeIssues(results: Map<String, ProbeResult>): List<Issue> {
        val out = mutableListOf<Issue>()
        fun each(p: Probe, block: (String, Any?) -> Unit) = results[p.id]?.values?.forEach { (a, v) -> block(a, v) }

        val openWindows = mutableListOf<String>()
        each(Signals.WINDOW) { a, v -> if (Interpret.isOpen(v) == true) openWindows += areaName(a) }
        if (openWindows.isNotEmpty()) out += Issue(IssueKind.WINDOW, "창문 열림", openWindows.joinToString(", "))

        val openDoors = mutableListOf<String>()
        each(Signals.DOOR_POSITION) { a, v -> if (Interpret.isOpen(v) == true) openDoors += areaName(a) }
        if (openDoors.isNotEmpty()) out += Issue(IssueKind.DOOR, "문 열림", openDoors.joinToString(", "))

        var unlocked = false
        each(Signals.DOOR_LOCK) { _, v -> if (Interpret.isLocked(v) == false) unlocked = true }
        if (unlocked) out += Issue(IssueKind.LOCK, "잠금 해제", "")

        val lightsOn = listOf(Signals.CABIN_LIGHT, Signals.READING_LIGHT, Signals.LOW_BEAM).filter { p ->
            var on = false
            each(p) { _, v -> if (Interpret.isOn(v) == true) on = true }
            on
        }
        if (lightsOn.isNotEmpty()) out += Issue(IssueKind.LIGHT, "조명 켜짐", lightsOn.joinToString(", ") { it.label })

        // 분실물: 운전석을 뺀 좌석에 무언가 감지되면 (가방·아이 등) 내리기 전에 알려 준다
        val occupied = mutableListOf<String>()
        each(Signals.SEAT_OCCUPANCY) { a, v ->
            if (a != "ROW_1_LEFT" && seatOccupied(v) == true) occupied += areaName(a)
        }
        if (occupied.isNotEmpty()) out += Issue(IssueKind.ITEM, "좌석에 뭔가 있어요", occupied.joinToString(", "))

        val soc = percent(results, Signals.SOC)
        val fuel = percent(results, Signals.FUEL)
        if (soc != null && soc < LOW_SOC) out += Issue(IssueKind.BATTERY, "배터리 부족", "${soc.toInt()}%")
        else if (soc == null && fuel != null && fuel < LOW_SOC) out += Issue(IssueKind.FUEL, "연료 부족", "${fuel.toInt()}%")
        return out
    }

    // ---------- 사용자 동작 ----------

    fun go(screen: Screen) = _state.update { it.copy(screen = screen) }

    /** 수동 반납 (홈의 반납 버튼, 또는 잠깐 정차 화면의 '여기서 반납') */
    fun startReturn() {
        val dist = _state.value.distanceToReturnM
        val mode = if (dist != null && dist <= RETURN_RADIUS_M) ParkMode.AT_RETURN_SPOT else ParkMode.UNKNOWN
        _state.update { it.copy(parkMode = mode, screen = Screen.PARK_CHECK) }
    }

    /** 반납 완료: 지금 상태를 스냅샷으로 남기고 인수자를 비운다 */
    fun completeReturn() {
        val s = _state.value
        val driver = s.currentDriver ?: return
        val odo = distance(s.results)
        val pickup = store.pickup
        val dist = s.distanceToReturnM
        val snapshot = Snapshot(
            takenAt = System.currentTimeMillis(),
            driver = driver,
            socPercent = percent(s.results, Signals.SOC),
            fuelPercent = percent(s.results, Signals.FUEL),
            odometerKm = odo,
            issues = s.issues,
            warnings = s.activeWarnings,
            pickedUpAt = pickup?.first,
            tripKm = if (odo != null && pickup?.second != null) (odo - pickup.second!!).coerceAtLeast(0f) else null,
            atReturnSpot = dist?.let { it <= RETURN_RADIUS_M },
            returnDistanceM = dist,
            deadline = store.returnDeadline
        )
            .let { it.copy(proof = ReturnProof.create(it, CAR_NAME)) }
        store.addSnapshot(snapshot)
        store.currentDriver = null
        store.pickup = null
        store.returnDeadline = null
        _state.update {
            it.copy(currentDriver = null, returnDeadline = null, lastSnapshot = snapshot, reportSnapshot = snapshot, screen = Screen.REPORT)
        }
    }

    /** 인수: 운전자를 정하고, 반납 장소가 없으면 지금 위치(인수 장소)를 반납 장소로 정한다 */
    fun pickDriver(name: String, deadline: Long? = null) {
        val clean = name.trim().ifEmpty { return }
        if (clean !in store.drivers) store.drivers = store.drivers + clean
        store.currentDriver = clean
        store.pickup = System.currentTimeMillis() to distance(_state.value.results)
        val here = _state.value.location
        if (store.returnPlace == null && here != null) store.returnPlace = here
        store.returnDeadline = deadline
        _state.update {
            it.copy(currentDriver = clean, returnDeadline = deadline, drivers = store.drivers, returnPlace = store.returnPlace, screen = Screen.BRIEFING)
        }
    }

    /** 지금 위치를 반납 장소로 지정 (렌터카 지점 등) */
    fun setReturnPlaceHere() {
        val here = _state.value.location ?: return
        store.returnPlace = here
        _state.update { it.copy(returnPlace = here) }
    }

    /** 내비로 반납 장소 안내 시작 */
    fun guideToReturn(): Boolean {
        val place = _state.value.returnPlace ?: return false
        val ok = navi.guideTo(place, "바톤 반납 장소")
        if (!ok) _state.update { it.copy(naviState = "내비 연결 안 됨") }
        return ok
    }

    /** 시연용: 반납 장소를 지금 위치에서 북쪽으로 약 3 km 떨어진 곳으로 옮긴다 */
    fun demoMoveReturnPlace() {
        val here = _state.value.location ?: return
        val moved = GeoPoint(here.lat + 0.027, here.lng)
        store.returnPlace = moved
        _state.update { it.copy(returnPlace = moved) }
    }

    fun openReport(snapshot: Snapshot) = _state.update { it.copy(reportSnapshot = snapshot, screen = Screen.REPORT) }

    /** 시연 준비용: 기록 초기화 */
    fun resetDemo() {
        store.clearAll()
        _state.update {
            UiState(
                sdkReady = it.sdkReady, gear = it.gear, restricted = it.restricted, drivers = store.drivers,
                results = it.results, location = it.location, locationPermission = it.locationPermission
            )
        }
    }

    // ---------- 도우미 ----------

    fun percent(results: Map<String, ProbeResult>, p: Probe): Float? =
        results[p.id]?.values?.values?.firstNotNullOfOrNull { Interpret.number(it) }

    fun distance(results: Map<String, ProbeResult>): Float? =
        results[Signals.ODOMETER.id]?.values?.values?.firstNotNullOfOrNull { Interpret.number(it) }

    companion object {
        const val LOW_SOC = 20f
        /** 증명서에 찍히는 차량 이름 (실서비스에서는 차량번호) */
        const val CAR_NAME = "시연 차량 12가 3456"
        /** 반납 장소로 인정하는 반경 */
        const val RETURN_RADIUS_M = 300f
        /** 이 분 수보다 여유가 적으면 '곧 출발' 경고 */
        const val TIGHT_MIN = 15L

        fun areaName(area: String): String = when (area) {
            "-" -> ""
            "ROW_1_LEFT", "FRONT_LEFT" -> "운전석"
            "ROW_1_RIGHT", "FRONT_RIGHT" -> "조수석"
            "ROW_2_LEFT", "REAR_LEFT" -> "뒷좌석 왼쪽"
            "ROW_2_RIGHT", "REAR_RIGHT" -> "뒷좌석 오른쪽"
            "ROW_2_CENTER" -> "뒷좌석 가운데"
            "ROW_3_LEFT" -> "3열 왼쪽"
            "ROW_3_RIGHT" -> "3열 오른쪽"
            "HOOD" -> "후드"
            "REAR" -> "트렁크"
            else -> area
        }
    }
}
