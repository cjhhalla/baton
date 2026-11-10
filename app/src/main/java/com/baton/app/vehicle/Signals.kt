package com.baton.app.vehicle

/**
 * 바톤이 읽는 신호 목록.
 *
 * 메서드 이름은 Pleos Vehicle SDK API Reference(v2.3.x)의 공개 메서드 목록에서 가져왔다.
 * Tire.getTirePressure, Safety.getTelltalesInfo(경고등)는 공식 샘플 앱에서 비공개로 막혀 있어 쓰지 않는다.
 */
object Signals {
    val SOC = Probe("soc", "배터리 잔량", Domain.EV_BATTERY, "getEvBatteryStateOfCharge", Probe.Kind.PERCENT)
    val FUEL = Probe("fuel", "연료 잔량", Domain.FUEL, "getFuelPercent", Probe.Kind.PERCENT)
    val ODOMETER = Probe("odo", "누적 주행거리", Domain.ODOMETER, "getCurrentDistanceDriven", Probe.Kind.DISTANCE)

    val DOOR_POSITION = Probe("door_pos", "도어", Domain.DOOR, "getCurrentDoorPosition", Probe.Kind.OPEN_POSITION)
    val DOOR_LOCK = Probe("door_lock", "도어 잠금", Domain.DOOR, "getDoorLockState", Probe.Kind.LOCK)
    val WINDOW = Probe("window", "창문", Domain.WINDOW, "getCurrentWindowPosition", Probe.Kind.OPEN_POSITION)

    val CABIN_LIGHT = Probe("cabin_light", "실내등", Domain.LIGHT, "getCurrentCabinLightState", Probe.Kind.ON_OFF)
    val READING_LIGHT = Probe("reading_light", "독서등", Domain.LIGHT, "getCurrentReadingLightState", Probe.Kind.ON_OFF)
    val LOW_BEAM = Probe("low_beam", "전조등", Domain.LIGHT, "getCurrentLowBeamLightState", Probe.Kind.ON_OFF)

    val LOW_TIRE = Probe("low_tire", "타이어 공기압 부족", Domain.TIRE, "getLowTirePressure", Probe.Kind.WARNING)
    val WASHER = Probe("washer", "워셔액 부족", Domain.SAFETY, "hasLowWasherFluidWarning", Probe.Kind.WARNING)
    val ENGINE_OIL = Probe("engine_oil", "엔진오일 부족", Domain.SAFETY, "getEngineOilLevelState", Probe.Kind.WARNING)
    val BRAKE_FLUID = Probe("brake_fluid", "브레이크액 부족", Domain.SAFETY, "getBrakeFluidLevelState", Probe.Kind.WARNING)
    val POWER_DOWN = Probe("power_down", "출력 제한 경고", Domain.SAFETY, "hasPowerDownWarningIndicator", Probe.Kind.WARNING)

    /** 좌석 착석 감지 (무게 센서). 운전자가 내린 뒤에도 감지되면 물건·사람이 남은 것 */
    val SEAT_OCCUPANCY = Probe("seat_occupancy", "좌석 감지", Domain.SEAT, "getCurrentSeatOccupancy", Probe.Kind.OCCUPANCY)

    val ALL = listOf(
        SEAT_OCCUPANCY,
        SOC, FUEL, ODOMETER,
        DOOR_POSITION, DOOR_LOCK, WINDOW,
        CABIN_LIGHT, READING_LIGHT, LOW_BEAM,
        LOW_TIRE, WASHER, ENGINE_OIL, BRAKE_FLUID, POWER_DOWN
    )

    /**
     * 주행 중 최초 발생 시각을 추적하는 경고 신호.
     * Tire.getLowTirePressure는 에뮬레이터에서 바퀴별 기준 압력값(Float, 137.0)을 돌려줘
     * 경고 여부가 아니므로 제외한다 (진단 화면에는 남겨둠).
     */
    val WARNINGS = listOf(POWER_DOWN, WASHER, ENGINE_OIL, BRAKE_FLUID)
}

/**
 * 원시 값 해석.
 * SDK의 반환 타입이 문서에 없어서, Boolean / 숫자 / enum 이름을 모두 받아들인다.
 * 에뮬레이터 진단 화면에서 실제 타입을 확인한 뒤 필요하면 여기만 고친다.
 */
object Interpret {

    fun number(v: Any?): Float? = when (v) {
        is Number -> v.toFloat()
        is String -> v.toFloatOrNull()
        else -> null
    }

    /** 창문·도어 위치: 0이면 닫힘, 0보다 크면 열림 */
    fun isOpen(v: Any?): Boolean? = when (v) {
        is Number -> v.toFloat() > 0f
        is Boolean -> v
        is Enum<*> -> v.name.contains("OPEN")
        else -> null
    }

    /** 잠금 상태: true 또는 LOCK이면 잠김 (UNLOCK은 풀림) */
    fun isLocked(v: Any?): Boolean? = when (v) {
        is Boolean -> v
        is Number -> v.toInt() != 0
        is Enum<*> -> !v.name.contains("UNLOCK") && v.name.contains("LOCK")
        else -> null
    }

    /** 좌석 감지: true / 0이 아닌 수 / OCCUPIED 계열 enum 이면 무언가 있음 */
    fun isOccupied(v: Any?): Boolean? = when (v) {
        is Boolean -> v
        is Number -> v.toInt() != 0
        is Enum<*> -> {
            val n = v.name.uppercase()
            when {
                n.contains("UN") || n.contains("NOT") || n.contains("VACANT") || n.contains("EMPTY") || n == "NONE" -> false
                n.contains("OCCUP") || n.contains("ADULT") || n.contains("CHILD") || n.contains("OBJECT") -> true
                else -> null
            }
        }
        else -> null
    }

    fun isOn(v: Any?): Boolean? = when (v) {
        is Boolean -> v
        is Number -> v.toInt() != 0
        is Enum<*> -> v.name == "ON" || v.name.endsWith("_ON") || v.name.startsWith("ON_")
        else -> null
    }

    /** 경고: true, 0이 아닌 정수, LOW/WARNING이 들어간 enum이면 경고. 실수(압력값 등)는 판단하지 않음 */
    fun isWarning(v: Any?): Boolean? = when (v) {
        is Boolean -> v
        is Float, is Double -> null
        is Number -> v.toInt() != 0
        is Enum<*> -> listOf("LOW", "WARN", "ABNORMAL", "EMPTY").any { v.name.contains(it) }
        else -> null
    }

    fun raw(v: Any?): String = when (v) {
        null -> "null"
        is Enum<*> -> "${v.name} (${v.javaClass.simpleName})"
        else -> "$v (${v.javaClass.simpleName})"
    }
}
