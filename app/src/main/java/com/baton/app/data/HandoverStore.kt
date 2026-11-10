package com.baton.app.data

import android.content.Context
import com.baton.app.vehicle.GeoPoint
import org.json.JSONArray
import org.json.JSONObject

/** 경고가 처음 켜진 시각과 그때 운전자 */
data class WarningRecord(
    val id: String,
    val label: String,
    val firstSeenAt: Long,
    val firstSeenDriver: String
)

enum class IssueKind { WINDOW, DOOR, LOCK, LIGHT, BATTERY, FUEL, ITEM }

/** 다음 운전자가 불편할 수 있는 항목 (아이콘 종류 + 짧은 제목 + 상세) */
data class Issue(val kind: IssueKind, val title: String, val detail: String)

/** 반납 시점의 차량 상태 기록 */
data class Snapshot(
    val takenAt: Long,
    val driver: String,
    val socPercent: Float?,
    val fuelPercent: Float?,
    val odometerKm: Float?,
    val issues: List<Issue>,
    val warnings: List<WarningRecord>,
    val pickedUpAt: Long? = null,
    val tripKm: Float? = null,
    /** 지정 반납 장소(반경 300m)에서 반납했는지. 위치를 몰랐으면 null */
    val atReturnSpot: Boolean? = null,
    val returnDistanceM: Float? = null,
    /** 반납 예정 시각과, 실제 반납이 몇 분 늦었는지(음수면 일찍) */
    val deadline: Long? = null,
    /** 반납 증명서 QR에 담는 서명된 URL */
    val proof: String? = null
)

/**
 * 기기 안(SharedPreferences)에 운전자, 반납 장소, 진행 중 경고, 반납 기록을 저장한다.
 * MVP 범위라 서버 없이 같은 차 안에서 인수인계한다.
 */
class HandoverStore(context: Context) {

    private val prefs = context.getSharedPreferences("baton", Context.MODE_PRIVATE)

    var currentDriver: String?
        get() = prefs.getString(KEY_DRIVER, null)
        set(value) = prefs.edit().putString(KEY_DRIVER, value).apply()

    /** 현재 운전자가 인수한 시각과 그때 누적 주행거리 */
    var pickup: Pair<Long, Float?>?
        get() = if (!prefs.contains(KEY_PICKUP_AT)) null else
            prefs.getLong(KEY_PICKUP_AT, 0L) to (if (prefs.contains(KEY_PICKUP_ODO)) prefs.getFloat(KEY_PICKUP_ODO, 0f) else null)
        set(value) {
            val e = prefs.edit().remove(KEY_PICKUP_AT).remove(KEY_PICKUP_ODO)
            if (value != null) {
                e.putLong(KEY_PICKUP_AT, value.first)
                value.second?.let { e.putFloat(KEY_PICKUP_ODO, it) }
            }
            e.apply()
        }

    /** 반납 장소 (기본값: 처음 인수한 장소) */
    var returnPlace: GeoPoint?
        get() {
            val lat = prefs.getString(KEY_RETURN_LAT, null)?.toDoubleOrNull() ?: return null
            val lng = prefs.getString(KEY_RETURN_LNG, null)?.toDoubleOrNull() ?: return null
            return GeoPoint(lat, lng)
        }
        set(value) {
            prefs.edit()
                .putString(KEY_RETURN_LAT, value?.lat?.toString())
                .putString(KEY_RETURN_LNG, value?.lng?.toString())
                .apply()
        }

    /** 반납 예정 시각 (인수할 때 정함). 없으면 null */
    var returnDeadline: Long?
        get() = if (prefs.contains(KEY_DEADLINE)) prefs.getLong(KEY_DEADLINE, 0L) else null
        set(value) {
            val e = prefs.edit()
            if (value == null) e.remove(KEY_DEADLINE) else e.putLong(KEY_DEADLINE, value)
            e.apply()
        }

    /** 좌석 감지 값 중 '사람·물건 있음'을 뜻하는 값 (주행 중 운전석 값으로 학습). 모르면 null */
    var occupiedValue: Boolean?
        get() = if (prefs.contains(KEY_OCC)) prefs.getBoolean(KEY_OCC, true) else null
        set(value) {
            val e = prefs.edit()
            if (value == null) e.remove(KEY_OCC) else e.putBoolean(KEY_OCC, value)
            e.apply()
        }

    var drivers: List<String>
        get() = prefs.getString(KEY_DRIVERS, null)?.let { s ->
            JSONArray(s).let { a -> List(a.length()) { a.getString(it) } }
        } ?: listOf("운전자 A", "운전자 B")
        set(value) = prefs.edit().putString(KEY_DRIVERS, JSONArray(value).toString()).apply()

    var activeWarnings: Map<String, WarningRecord>
        get() = prefs.getString(KEY_WARNINGS, null)?.let { s ->
            val a = JSONArray(s)
            List(a.length()) { warningFrom(a.getJSONObject(it)) }.associateBy { it.id }
        } ?: emptyMap()
        set(value) = prefs.edit()
            .putString(KEY_WARNINGS, JSONArray(value.values.map(::warningJson)).toString())
            .apply()

    val snapshots: List<Snapshot>
        get() = prefs.getString(KEY_SNAPSHOTS, null)?.let { s ->
            val a = JSONArray(s)
            List(a.length()) { runCatching { snapshotFrom(a.getJSONObject(it)) }.getOrNull() }.filterNotNull()
        } ?: emptyList()

    val lastSnapshot: Snapshot? get() = snapshots.firstOrNull()

    fun addSnapshot(snapshot: Snapshot) {
        val list = (listOf(snapshot) + snapshots).take(30)
        prefs.edit().putString(KEY_SNAPSHOTS, JSONArray(list.map(::snapshotJson)).toString()).apply()
    }

    fun clearAll() = prefs.edit().clear().apply()

    private fun warningJson(w: WarningRecord) = JSONObject()
        .put("id", w.id).put("label", w.label)
        .put("at", w.firstSeenAt).put("driver", w.firstSeenDriver)

    private fun warningFrom(o: JSONObject) = WarningRecord(
        id = o.getString("id"),
        label = o.getString("label"),
        firstSeenAt = o.getLong("at"),
        firstSeenDriver = o.getString("driver")
    )

    private fun issueJson(i: Issue) = JSONObject().put("kind", i.kind.name).put("title", i.title).put("detail", i.detail)

    private fun issueFrom(o: JSONObject) = Issue(
        kind = IssueKind.valueOf(o.getString("kind")),
        title = o.getString("title"),
        detail = o.optString("detail", "")
    )

    private fun snapshotJson(s: Snapshot) = JSONObject()
        .put("at", s.takenAt)
        .put("driver", s.driver)
        .put("soc", s.socPercent?.toDouble() ?: JSONObject.NULL)
        .put("fuel", s.fuelPercent?.toDouble() ?: JSONObject.NULL)
        .put("odo", s.odometerKm?.toDouble() ?: JSONObject.NULL)
        .put("issues", JSONArray(s.issues.map(::issueJson)))
        .put("warnings", JSONArray(s.warnings.map(::warningJson)))
        .put("pickedUpAt", s.pickedUpAt ?: JSONObject.NULL)
        .put("trip", s.tripKm?.toDouble() ?: JSONObject.NULL)
        .put("atSpot", s.atReturnSpot ?: JSONObject.NULL)
        .put("spotDist", s.returnDistanceM?.toDouble() ?: JSONObject.NULL)
        .put("deadline", s.deadline ?: JSONObject.NULL)
        .put("proof", s.proof ?: JSONObject.NULL)

    private fun snapshotFrom(o: JSONObject): Snapshot {
        fun optFloat(k: String) = if (!o.has(k) || o.isNull(k)) null else o.getDouble(k).toFloat()
        val issues = o.getJSONArray("issues").let { a -> List(a.length()) { issueFrom(a.getJSONObject(it)) } }
        val warnings = o.getJSONArray("warnings").let { a -> List(a.length()) { warningFrom(a.getJSONObject(it)) } }
        return Snapshot(
            takenAt = o.getLong("at"),
            driver = o.getString("driver"),
            socPercent = optFloat("soc"),
            fuelPercent = optFloat("fuel"),
            odometerKm = optFloat("odo"),
            issues = issues,
            warnings = warnings,
            pickedUpAt = if (!o.has("pickedUpAt") || o.isNull("pickedUpAt")) null else o.getLong("pickedUpAt"),
            tripKm = optFloat("trip"),
            atReturnSpot = if (!o.has("atSpot") || o.isNull("atSpot")) null else o.getBoolean("atSpot"),
            returnDistanceM = optFloat("spotDist"),
            deadline = if (!o.has("deadline") || o.isNull("deadline")) null else o.getLong("deadline"),
            proof = if (!o.has("proof") || o.isNull("proof")) null else o.getString("proof")
        )
    }

    companion object {
        private const val KEY_DRIVER = "driver"
        private const val KEY_DRIVERS = "drivers"
        private const val KEY_WARNINGS = "warnings"
        // v2: 항목(issue) 저장 형식이 바뀌어 키를 새로 씀 (예전 문자열 기록은 무시)
        private const val KEY_SNAPSHOTS = "snapshots_v2"
        private const val KEY_PICKUP_AT = "pickup_at"
        private const val KEY_PICKUP_ODO = "pickup_odo"
        private const val KEY_RETURN_LAT = "return_lat"
        private const val KEY_RETURN_LNG = "return_lng"
        private const val KEY_DEADLINE = "return_deadline"
        private const val KEY_OCC = "seat_occupied_value"
    }
}
