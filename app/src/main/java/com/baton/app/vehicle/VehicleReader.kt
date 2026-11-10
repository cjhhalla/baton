package com.baton.app.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.Brake
import ai.pleos.playground.vehicle.api.Door
import ai.pleos.playground.vehicle.api.EvBattery
import ai.pleos.playground.vehicle.api.Fuel
import ai.pleos.playground.vehicle.api.Light
import ai.pleos.playground.vehicle.api.Odometer
import ai.pleos.playground.vehicle.api.Safety
import ai.pleos.playground.vehicle.api.Seat
import ai.pleos.playground.vehicle.api.Tire
import ai.pleos.playground.vehicle.api.Window
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WheelArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WheelValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.reflect.InvocationTargetException
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.full.declaredMemberFunctions

/**
 * Vehicle SDK 조회(get) API를 이름으로 호출한다.
 *
 * 공식 샘플 앱(pleos-sample-vehicle-app)의 VehicleApiExplorer와 같은 방식이다.
 * 문서에 파라미터 시그니처가 공개돼 있지 않아서, 리플렉션으로 파라미터를 채운다.
 * - 영역(area) 파라미터가 있으면 해당 영역 enum의 모든 값으로 각각 호출한다.
 * - 이름에 "fail"이 들어간 콜백은 실패, 나머지 콜백은 결과로 받는다.
 */
class VehicleReader(context: Context) {

    private val vehicle = Vehicle(context.applicationContext)
    private var initialized = false

    fun initialize(): Boolean {
        if (initialized) return true
        return try {
            vehicle.initialize()
            initialized = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "Vehicle initialize failed", e)
            false
        }
    }

    fun release() {
        if (!initialized) return
        runCatching { vehicle.release() }
        initialized = false
    }

    /** 도메인 객체와 그 공개 인터페이스 클래스 (샘플 앱처럼 인터페이스 기준으로 메서드를 찾는다) */
    private fun domainTarget(domain: Domain): Pair<Any, KClass<*>> = when (domain) {
        Domain.EV_BATTERY -> vehicle.getEvBattery() to EvBattery::class
        Domain.FUEL -> vehicle.getFuel() to Fuel::class
        Domain.ODOMETER -> vehicle.getOdometer() to Odometer::class
        Domain.TIRE -> vehicle.getTire() to Tire::class
        Domain.SAFETY -> vehicle.getSafety() to Safety::class
        Domain.DOOR -> vehicle.getDoor() to Door::class
        Domain.WINDOW -> vehicle.getWindow() to Window::class
        Domain.LIGHT -> vehicle.getLight() to Light::class
        Domain.BRAKE -> vehicle.getBrake() to Brake::class
        Domain.SEAT -> vehicle.getSeat() to Seat::class
    }

    /** 신호 하나를 읽는다. 영역이 있는 API면 영역별 값을, 없으면 "-" 키 하나를 돌려준다. */
    suspend fun read(probe: Probe, timeoutMs: Long = 1500): ProbeResult = coroutineScope {
        if (!initialized) return@coroutineScope ProbeResult(probe, emptyMap(), "SDK 미초기화")
        val (target, apiClass) = domainTarget(probe.domain)
        val function = apiClass.declaredMemberFunctions.firstOrNull { it.name == probe.method }
            ?: return@coroutineScope ProbeResult(probe, emptyMap(), "메서드 없음: ${probe.method}")

        val areaParam = function.parameters.firstOrNull {
            it.kind == KParameter.Kind.VALUE && isArea(it.type.classifier as? KClass<*>)
        }
        val areas: List<Pair<String, Any?>> = if (areaParam == null) {
            listOf("-" to null)
        } else {
            areaValues(areaParam.type.classifier as KClass<*>)
        }

        val calls = areas.map { (name, area) ->
            async { name to callOnce(target, function, area, timeoutMs) }
        }
        val results = calls.map { it.await() }
        val values = results.filter { it.second.isSuccess }
            .associate { it.first to it.second.getOrNull() }
        val error = if (values.isEmpty()) {
            results.firstOrNull()?.second?.exceptionOrNull()?.let { describe(it) } ?: "응답 없음"
        } else {
            null
        }
        ProbeResult(probe, values, error)
    }

    private suspend fun callOnce(
        target: Any,
        function: KFunction<*>,
        area: Any?,
        timeoutMs: Long
    ): Result<Any?> {
        val deferred = CompletableDeferred<Result<Any?>>()
        try {
            val args = mutableMapOf<KParameter, Any?>()
            function.parameters.forEach { p ->
                val cls = p.type.classifier as? KClass<*>
                when {
                    p.kind == KParameter.Kind.INSTANCE -> args[p] = target
                    isArea(cls) -> args[p] = area
                    cls != null && isCallback(cls) -> {
                        val isFail = p.name.orEmpty().contains("fail", ignoreCase = true)
                        args[p] = callback(cls) { values ->
                            val first = values.firstOrNull()
                            if (isFail || first is Throwable) {
                                deferred.complete(Result.failure(first as? Throwable ?: Exception("failed")))
                            } else {
                                // 값이 여러 개면(예: 영역, 값) 마지막 값을 쓴다
                                deferred.complete(Result.success(values.lastOrNull()))
                            }
                        }
                    }
                    p.isOptional -> Unit
                    else -> return Result.failure(IllegalArgumentException("지원하지 않는 파라미터: ${p.name}: ${p.type}"))
                }
            }
            val direct = function.callBy(args)
            // 콜백 없이 값을 바로 돌려주는 API(예: hasXxx)를 위한 처리
            if (function.parameters.none { isCallback(it.type.classifier as? KClass<*>) }) {
                return Result.success(direct)
            }
        } catch (e: Exception) {
            val cause = (e as? InvocationTargetException)?.targetException ?: e
            return Result.failure(cause)
        }
        return withTimeoutOrNull(timeoutMs) { deferred.await() }
            ?: Result.failure(Exception("시간 초과"))
    }

    private fun callback(cls: KClass<*>, report: (List<Any?>) -> Unit): Any = when (cls) {
        Function0::class -> { { report(emptyList()) } as () -> Unit }
        Function1::class -> { { a: Any? -> report(listOf(a)) } as (Any?) -> Unit }
        Function2::class -> { { a: Any?, b: Any? -> report(listOf(a, b)) } as (Any?, Any?) -> Unit }
        Function3::class -> {
            { a: Any?, b: Any?, c: Any? -> report(listOf(a, b, c)) } as (Any?, Any?, Any?) -> Unit
        }
        else -> error("Unsupported callback: $cls")
    }

    private fun areaValues(cls: KClass<*>): List<Pair<String, Any?>> = when (cls) {
        DoorArea::class -> DoorValue.entries.map { it.name to DoorArea(it) }
        WindowArea::class -> WindowValue.entries.map { it.name to WindowArea(it) }
        WheelArea::class -> WheelValue.entries.map { it.name to WheelArea(it) }
        SeatArea::class -> SeatValue.entries.map { it.name to SeatArea(it) }
        else -> emptyList()
    }

    private fun isArea(cls: KClass<*>?): Boolean = cls != null && cls in AREA_CLASSES
    private fun isCallback(cls: KClass<*>?): Boolean = cls != null && cls in CALLBACK_CLASSES

    private fun describe(t: Throwable): String = t.message ?: t.javaClass.simpleName

    companion object {
        private const val TAG = "BatonVehicle"
        private val AREA_CLASSES: Set<KClass<*>> = setOf(DoorArea::class, WindowArea::class, WheelArea::class, SeatArea::class)
        private val CALLBACK_CLASSES: Set<KClass<*>> = setOf(Function0::class, Function1::class, Function2::class, Function3::class)
    }
}

enum class Domain { EV_BATTERY, FUEL, ODOMETER, TIRE, SAFETY, DOOR, WINDOW, LIGHT, BRAKE, SEAT }

/** 읽을 신호 정의. kind로 값 해석 방법을 정한다. */
data class Probe(
    val id: String,
    val label: String,
    val domain: Domain,
    val method: String,
    val kind: Kind
) {
    enum class Kind { PERCENT, DISTANCE, OPEN_POSITION, LOCK, ON_OFF, WARNING, OCCUPANCY }
}

data class ProbeResult(
    val probe: Probe,
    /** 영역 이름 → 원시 값 (영역이 없으면 "-") */
    val values: Map<String, Any?>,
    val error: String?
)
