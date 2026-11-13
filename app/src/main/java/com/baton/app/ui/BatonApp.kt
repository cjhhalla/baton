package com.baton.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.LocalParking
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.MeetingRoom
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.Window
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.baton.app.BatonViewModel
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.baton.app.ParkMode
import com.baton.app.TimingLevel
import com.baton.app.Screen
import com.baton.app.UiState
import com.baton.app.data.Issue
import com.baton.app.data.IssueKind
import com.baton.app.data.Snapshot
import com.baton.app.vehicle.Interpret
import com.baton.app.vehicle.Signals
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ---------------- 색 (어두운 차량 화면 기준, 대비 4.5:1 이상) ----------------

private val Bg = Color(0xFF0E1116)
private val Card = Color(0xFF191E26)
private val CardHi = Color(0xFF222934)
private val Ink = Color(0xFFF3F5F8)
private val Quiet = Color(0xFFA3ADB9)
private val Accent = Color(0xFF4C9EFF)
private val Warn = Color(0xFFFFB547)
private val Bad = Color(0xFFFF6B6B)
private val Good = Color(0xFF3DD68C)
private val OnAccent = Color(0xFF0B1220)

@Composable
fun BatonApp(vm: BatonViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Card)) {
        // 화면은 1280x720dp 기준으로 설계. 차량 화면(예: 2560x1440, 분할 화면)에 맞춰 통째로 키운다
        BoxWithConstraints(Modifier.fillMaxSize().background(Bg)) {
            val base = LocalDensity.current
            val factor = minOf(maxWidth / 1280.dp, maxHeight / 720.dp).coerceIn(0.8f, 2.5f)
            CompositionLocalProvider(LocalDensity provides Density(base.density * factor, base.fontScale)) {
        Box(Modifier.fillMaxSize().padding(28.dp)) {
            if (s.restricted && s.screen != Screen.DIAGNOSTICS) {
                DrivingScreen(s, vm)
            } else {
                when (s.screen) {
                    Screen.HOME -> HomeScreen(s, vm)
                    Screen.PARK_CHECK -> ParkCheckScreen(s, vm)
                    Screen.REPORT -> ReportScreen(s, vm)
                    Screen.PICK_DRIVER -> PickDriverScreen(s, vm)
                    Screen.BRIEFING -> BriefingScreen(s, vm)
                    Screen.DIAGNOSTICS -> DiagnosticsScreen(s, vm)
                }
            }
        }
            }
        }
    }
}

// ---------------- 형식 도우미 ----------------

private val timeFmt = SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA)
private val hmFmt = SimpleDateFormat("HH:mm", Locale.KOREA)
private fun t(ms: Long) = timeFmt.format(Date(ms))
private fun hm(ms: Long) = hmFmt.format(Date(ms))
private fun duration(ms: Long): String {
    val min = (ms / 60_000).coerceAtLeast(0)
    return when {
        min < 1 -> "방금"
        min < 60 -> "${min}분"
        min < 60 * 24 -> "${min / 60}시간 ${min % 60}분"
        else -> "${min / (60 * 24)}일"
    }
}
private fun km(v: Float?) = v?.let { String.format(Locale.KOREA, "%,.1f km", it) } ?: "—"
private fun meters(m: Float?) = when {
    m == null -> "—"
    m < 1000 -> "${m.toInt()} m"
    else -> String.format(Locale.KOREA, "%.1f km", m / 1000f)
}
private const val MOVE_THRESHOLD_KM = 0.5f

private fun issueIcon(kind: IssueKind): ImageVector = when (kind) {
    IssueKind.WINDOW -> Icons.Rounded.Window
    IssueKind.DOOR -> Icons.Rounded.MeetingRoom
    IssueKind.LOCK -> Icons.Rounded.LockOpen
    IssueKind.LIGHT -> Icons.Rounded.Lightbulb
    IssueKind.BATTERY -> Icons.Rounded.BatteryAlert
    IssueKind.FUEL -> Icons.Rounded.LocalGasStation
    IssueKind.ITEM -> Icons.Rounded.Work
}

// ---------------- 공통 부품 ----------------

@Composable
private fun Header(
    icon: ImageVector,
    title: String,
    sub: String? = null,
    tint: Color = Accent,
    trailing: @Composable () -> Unit = {}
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        IconBubble(icon, tint, 64.dp)
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            if (sub != null) Text(sub, color = Quiet, fontSize = 20.sp)
        }
        trailing()
    }
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun IconBubble(icon: ImageVector, tint: Color, size: Dp = 52.dp) {
    Box(
        Modifier.size(size).background(tint.copy(alpha = 0.16f), CircleShape),
        contentAlignment = Alignment.Center
    ) { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.55f)) }
}

@Composable
private fun CardBox(
    modifier: Modifier = Modifier,
    border: Color? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(20.dp)
    var m = modifier.background(Card, shape)
    if (border != null) m = m.border(2.dp, border, shape)
    if (onClick != null) m = m.clickable(onClick = onClick)
    Column(m.padding(22.dp), content = content)
}

/** 상태 타일: 아이콘 + 이름 + 값. 정상은 초록, 문제는 주황 */
@Composable
private fun RowScope.StatusTile(icon: ImageVector, label: String, value: String, ok: Boolean?, horizontal: Boolean = false) {
    val tint = when (ok) {
        true -> Good
        false -> Warn
        null -> Quiet
    }
    CardBox(Modifier.weight(1f).fillMaxHeight()) {
        if (horizontal) {
            // 홈 화면처럼 세로 공간이 좁을 때: 아이콘 옆에 글자
            Row(Modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                IconBubble(icon, tint, 56.dp)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(label, color = Quiet, fontSize = 20.sp)
                    Text(value, color = Ink, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        } else {
            IconBubble(icon, tint)
            Spacer(Modifier.height(14.dp))
            Text(label, color = Quiet, fontSize = 20.sp)
            Text(value, color = Ink, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** 문제 항목 타일 */
@Composable
private fun IssueTile(issue: Issue, modifier: Modifier = Modifier) {
    CardBox(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBubble(issueIcon(issue.kind), Warn)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(issue.title, color = Ink, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                if (issue.detail.isNotEmpty()) Text(issue.detail, color = Quiet, fontSize = 19.sp)
            }
        }
    }
}

@Composable
private fun AllClear(text: String = "모두 정상") {
    CardBox(Modifier.fillMaxWidth(), border = Good) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBubble(Icons.Rounded.CheckCircle, Good)
            Spacer(Modifier.width(16.dp))
            Text(text, color = Ink, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun IssueGrid(issues: List<Issue>) {
    if (issues.isEmpty()) {
        AllClear()
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        issues.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                row.forEach { IssueTile(it, Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PrimaryButton(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Accent
) {
    Row(
        modifier
            .height(84.dp)
            .background(color, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(icon, null, tint = OnAccent, modifier = Modifier.size(34.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, color = OnAccent, fontSize = 26.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SecondaryButton(icon: ImageVector, text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .height(84.dp)
            .background(CardHi, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(icon, null, tint = Ink, modifier = Modifier.size(30.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, color = Ink, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** 배터리(전기차) 또는 연료(내연기관) 게이지 */
@Composable
private fun EnergyGauge(percent: Float?, isFuel: Boolean, size: Dp = 240.dp) {
    val p = (percent ?: 0f).coerceIn(0f, 100f)
    val color = when {
        percent == null -> Quiet
        p < 20f -> Bad
        p < 40f -> Warn
        else -> Good
    }
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = this.size.width * 0.08f
            val inset = stroke / 2
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(
                color = Color(0xFF2A313C), startAngle = 135f, sweepAngle = 270f, useCenter = false,
                topLeft = Offset(inset, inset), size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round)
            )
            drawArc(
                color = color, startAngle = 135f, sweepAngle = 270f * p / 100f, useCenter = false,
                topLeft = Offset(inset, inset), size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                if (isFuel) Icons.Rounded.LocalGasStation else Icons.Rounded.BatteryFull, null,
                tint = color, modifier = Modifier.size(36.dp)
            )
            Text(percent?.let { "${it.toInt()}%" } ?: "—", color = Ink, fontSize = 52.sp, fontWeight = FontWeight.Bold)
            Text(if (isFuel) "연료" else "배터리", color = Quiet, fontSize = 20.sp)
        }
    }
}

private fun energy(s: UiState, vm: BatonViewModel): Pair<Float?, Boolean> {
    val soc = vm.percent(s.results, Signals.SOC)
    return if (soc != null) soc to false else vm.percent(s.results, Signals.FUEL) to true
}

private fun openCountStatus(s: UiState, probeId: String): Pair<String, Boolean?> {
    val r = s.results[probeId] ?: return "—" to null
    if (r.values.isEmpty()) return "—" to null
    val open = r.values.values.count { Interpret.isOpen(it) == true }
    return (if (open == 0) "닫힘" else "${open}개 열림") to (open == 0)
}

private fun lightStatus(s: UiState): Pair<String, Boolean?> {
    val known = listOf(Signals.CABIN_LIGHT, Signals.READING_LIGHT, Signals.LOW_BEAM)
        .any { s.results[it.id]?.values?.isNotEmpty() == true }
    if (!known) return "—" to null
    val on = s.issues.any { it.kind == IssueKind.LIGHT }
    return (if (on) "켜짐" else "꺼짐") to !on
}

private fun returnStatus(s: UiState): Pair<String, Boolean?> {
    val d = s.distanceToReturnM
    return when {
        s.returnPlace == null -> "미설정" to null
        d == null -> "위치 확인 중" to null
        d <= BatonViewModel.RETURN_RADIUS_M -> "도착" to true
        else -> meters(d) to null
    }
}

// ---------------- 반납 시간 ----------------

private fun minutes(sec: Long) = when {
    sec < 60 -> "1분 이내"
    sec < 3600 -> "${sec / 60}분"
    else -> "${sec / 3600}시간 ${(sec % 3600) / 60}분"
}

/** 분 → "42분" / "3시간 5분" / "2일" */
private fun span(min: Long): String = when {
    min < 60 -> "${min}분"
    min < 60 * 24 -> if (min % 60 == 0L) "${min / 60}시간" else "${min / 60}시간 ${min % 60}분"
    else -> "${min / (60 * 24)}일"
}

private fun levelColor(l: TimingLevel) = when (l) {
    TimingLevel.RELAXED -> Good
    TimingLevel.TIGHT -> Warn
    TimingLevel.LATE -> Bad
}

/** 반납 시간 한 줄 요약: (큰 글씨, 작은 글씨, 색) */
private fun timingText(s: UiState): Triple<String, String, Color>? {
    val dist = s.distanceToReturnM
    val atSpot = dist != null && dist <= BatonViewModel.RETURN_RADIUS_M
    val t = s.timing
    if (t == null) {
        if (s.returnPlace == null) return null
        if (atSpot) return Triple("반납 장소에 있어요", "반납 예정 시각 없음", Good)
        val eta = s.naviTrip?.takeIf { s.guidingToReturn }?.remainSec
            ?: dist?.let { (it * 1.3f / (30_000f / 3600f)).toLong() }
        return Triple(
            "반납 장소까지 ${meters(dist)}",
            eta?.let { "약 ${minutes(it)} 걸려요" + if (s.guidingToReturn) " · 내비 기준" else "" } ?: "위치 확인 중",
            Accent
        )
    }
    val due = hm(t.deadline)
    val src = if (t.fromNavi) "내비 기준" else "예상"
    if (atSpot) {
        return if (t.slackMin >= 0) Triple("$due 반납 · 제시간 도착", "반납 장소에 있어요", Good)
        else Triple("$due 반납 · ${span(-t.slackMin)} 지났어요", "반납 장소에 있어요", Bad)
    }
    return when (t.level) {
        TimingLevel.RELAXED -> Triple(
            "$due 반납 · 여유 ${span(t.slackMin)}",
            t.arriveAt?.let { "지금 출발하면 ${hm(it)} 도착 ($src)" } ?: "반납 장소 위치 확인 중",
            Good
        )
        TimingLevel.TIGHT -> Triple(
            t.leaveBy?.let { "${hm(it)}까지 출발하세요" } ?: "곧 반납 시간이에요",
            "$due 반납 · 지금 출발하면 ${t.arriveAt?.let(::hm) ?: "—"} 도착 ($src)",
            Warn
        )
        TimingLevel.LATE -> Triple(
            "지금 출발해도 ${span(-t.slackMin)} 늦어요",
            "$due 반납 · 늦을 것 같으면 미리 연락하세요",
            Bad
        )
    }
}

/** 반납 시간 카드 + 반납 장소 안내 버튼 */
@Composable
private fun ReturnCard(s: UiState, vm: BatonViewModel, modifier: Modifier = Modifier) {
    val (big, small, color) = timingText(s) ?: return
    val dist = s.distanceToReturnM
    val showGuide = s.returnPlace != null && (dist == null || dist > BatonViewModel.RETURN_RADIUS_M)
    CardBox(modifier.fillMaxWidth(), border = if (color == Good || color == Accent) null else color) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBubble(Icons.Rounded.Schedule, color, 60.dp)
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(big, color = Ink, fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(small, color = Quiet, fontSize = 19.sp, maxLines = 1)
            }
            if (showGuide) {
                Spacer(Modifier.width(16.dp))
                if (s.guidingToReturn) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Navigation, null, tint = Accent, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("안내 중", color = Accent, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    PrimaryButton(Icons.Rounded.Navigation, "반납 장소로 안내", { vm.guideToReturn() }, color = color.takeIf { it != Good } ?: Accent)
                }
            }
        }
    }
}

/** 인수할 때 고르는 반납 예정 시각 */
private fun deadlineOptions(now: Long = System.currentTimeMillis()): List<Pair<String, Long?>> {
    fun at(daysAhead: Int, hour: Int): Long = Calendar.getInstance().apply {
        timeInMillis = now
        add(Calendar.DAY_OF_YEAR, daysAhead)
        set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val out = mutableListOf<Pair<String, Long?>>(
        "30분 뒤" to now + 30 * 60_000L,
        "1시간 뒤" to now + 60 * 60_000L,
        "3시간 뒤" to now + 3 * 60 * 60_000L
    )
    val today18 = at(0, 18)
    if (today18 - now > 60 * 60_000L) out += "오늘 18:00" to today18
    out += "내일 10:00" to at(1, 10)
    out += "정하지 않음" to null
    return out
}

// ---------------- 주행 중 ----------------

@Composable
private fun DrivingScreen(s: UiState, vm: BatonViewModel) {
    val (pct, isFuel) = energy(s, vm)
    Row(
        Modifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        EnergyGauge(pct, isFuel, 260.dp)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconBubble(Icons.Rounded.DirectionsCar, Accent, 96.dp)
            Spacer(Modifier.height(16.dp))
            Text("주행 중", color = Ink, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            val tt = timingText(s)
            if (tt != null && s.currentDriver != null) {
                Spacer(Modifier.height(20.dp))
                Row(
                    Modifier.background(tt.third.copy(alpha = 0.16f), RoundedCornerShape(16.dp)).padding(horizontal = 22.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Schedule, null, tint = tt.third, modifier = Modifier.size(30.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(tt.first, color = Ink, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                }
            } else {
                Text("바톤이 기록하고 있어요", color = Quiet, fontSize = 22.sp)
            }
        }
    }
}

// ---------------- 홈 ----------------

@Composable
private fun HomeScreen(s: UiState, vm: BatonViewModel) {
    val (pct, isFuel) = energy(s, vm)
    Column(Modifier.fillMaxSize()) {
        Header(
            icon = Icons.Rounded.Person,
            title = s.currentDriver ?: "인수 대기",
            sub = if (s.currentDriver != null) "운행 중" else "반납 완료된 차량",
            tint = if (s.currentDriver != null) Accent else Quiet
        ) {
            Box(
                Modifier.size(56.dp).background(CardHi, CircleShape).clickable { vm.go(Screen.DIAGNOSTICS) },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Rounded.Tune, "신호 진단", tint = Quiet) }
        }

        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            CardBox(Modifier.fillMaxHeight()) {
                Box(Modifier.fillMaxHeight(), contentAlignment = Alignment.Center) { EnergyGauge(pct, isFuel) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    val (w, wOk) = openCountStatus(s, Signals.WINDOW.id)
                    StatusTile(Icons.Rounded.Window, "창문", w, wOk, horizontal = true)
                    val (d, dOk) = openCountStatus(s, Signals.DOOR_POSITION.id)
                    StatusTile(Icons.Rounded.MeetingRoom, "문", d, dOk, horizontal = true)
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    val (l, lOk) = lightStatus(s)
                    StatusTile(Icons.Rounded.Lightbulb, "조명", l, lOk, horizontal = true)
                    val (r, rOk) = returnStatus(s)
                    StatusTile(Icons.Rounded.Flag, "반납 장소", r, rOk, horizontal = true)
                }
            }
        }

        if (s.currentDriver != null) {
            Spacer(Modifier.height(20.dp))
            ReturnCard(s, vm)
        }

        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            val last = s.lastSnapshot
            if (last != null) {
                SecondaryButton(Icons.Rounded.History, "직전 반납 ${hm(last.takenAt)}", { vm.openReport(last) })
            }
            if (s.currentDriver != null && s.location != null) {
                SecondaryButton(Icons.Rounded.Place, "여기를 반납 장소로", vm::setReturnPlaceHere)
            }
            if (s.currentDriver == null) {
                PrimaryButton(Icons.Rounded.SwapHoriz, "차 인수하기", { vm.go(Screen.PICK_DRIVER) }, Modifier.weight(1f))
            } else {
                PrimaryButton(Icons.Rounded.Flag, "반납하기", vm::startReturn, Modifier.weight(1f))
            }
        }
    }
}

// ---------------- P단: 반납 체크 / 잠깐 정차 ----------------

@Composable
private fun ParkCheckScreen(s: UiState, vm: BatonViewModel) {
    Column(Modifier.fillMaxSize()) {
        when (s.parkMode) {
            ParkMode.AT_RETURN_SPOT ->
                Header(Icons.Rounded.Flag, "반납 장소에 도착했어요", "내리기 전에 확인해 주세요", Good)
            ParkMode.STOPOVER ->
                Header(
                    Icons.Rounded.LocalParking, "잠깐 정차 중이에요",
                    "반납 장소까지 ${meters(s.distanceToReturnM)} · 자리를 비우기 전에 확인하세요", Accent
                )
            ParkMode.UNKNOWN ->
                Header(Icons.Rounded.Flag, "반납하시나요?", "반납이면 아래 항목을 확인해 주세요", Accent)
        }

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (s.parkMode == ParkMode.STOPOVER) ReturnCard(s, vm)
            IssueGrid(s.issues)
            s.activeWarnings.forEach {
                CardBox(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBubble(Icons.Rounded.WarningAmber, Bad)
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(it.label, color = Ink, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                            Text("${hm(it.firstSeenAt)} 처음 켜짐 · ${it.firstSeenDriver}", color = Quiet, fontSize = 19.sp)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (s.parkMode == ParkMode.STOPOVER) {
                PrimaryButton(Icons.Rounded.DirectionsCar, "계속 이용", { vm.go(Screen.HOME) }, Modifier.weight(1f))
                SecondaryButton(Icons.Rounded.Flag, "여기서 반납", vm::completeReturn)
            } else {
                PrimaryButton(Icons.Rounded.Flag, "반납 완료", vm::completeReturn, Modifier.weight(1f), color = Good)
                SecondaryButton(Icons.Rounded.DirectionsCar, "계속 이용", { vm.go(Screen.HOME) })
            }
        }
    }
}

// ---------------- 반납 리포트 ----------------

@Composable
private fun ReportScreen(s: UiState, vm: BatonViewModel) {
    val r = s.reportSnapshot ?: return HomeScreen(s, vm)
    Column(Modifier.fillMaxSize()) {
        Header(Icons.Rounded.CheckCircle, "반납 완료", "${r.driver} · ${t(r.takenAt)}", Good)

        Row(Modifier.height(190.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            StatusTile(Icons.Rounded.Schedule, "이용 시간", r.pickedUpAt?.let { duration(r.takenAt - it) } ?: "—", null)
            StatusTile(Icons.Rounded.Timeline, "운행 거리", km(r.tripKm), null)
            val soc = r.socPercent
            val fuel = r.fuelPercent
            if (soc != null) {
                StatusTile(Icons.Rounded.BatteryFull, "배터리", "${soc.toInt()}%", soc >= BatonViewModel.LOW_SOC)
            } else {
                StatusTile(
                    Icons.Rounded.LocalGasStation, "연료", fuel?.let { "${it.toInt()}%" } ?: "—",
                    fuel?.let { it >= BatonViewModel.LOW_SOC }
                )
            }
            StatusTile(
                Icons.Rounded.Place, "반납 위치",
                when (r.atReturnSpot) {
                    true -> "지정 장소"
                    false -> "다른 곳 ${meters(r.returnDistanceM)}"
                    null -> "—"
                },
                r.atReturnSpot
            )
            val dl = r.deadline
            if (dl != null) {
                val lateMin = (r.takenAt - dl) / 60_000
                StatusTile(
                    Icons.Rounded.Schedule, "반납 시간",
                    if (lateMin <= 0) "제시간" else "${span(lateMin)} 늦음",
                    lateMin <= 0
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1f)) {
                Text("다음 운전자에게 넘어가는 항목", color = Quiet, fontSize = 20.sp)
                Spacer(Modifier.height(10.dp))
                IssueGrid(r.issues)
            }
            if (r.proof != null) ProofCard(r.proof)
            else CardBox(Modifier.width(300.dp).fillMaxHeight()) {
                Text("반납 증명서", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Text(
                    "증명서를 만들지 못했어요" + (com.baton.app.data.ReturnProof.lastError?.let { "\n$it" } ?: "\n(이전 버전에서 만든 기록이에요)"),
                    color = Quiet, fontSize = 16.sp
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        PrimaryButton(Icons.Rounded.CheckCircle, "확인", { vm.go(Screen.HOME) }, Modifier.fillMaxWidth())
    }
}

// ---------------- 반납 증명서 QR ----------------

/** 차가 서명한 반납 기록을 폰으로 가져가는 QR */
@Composable
private fun ProofCard(url: String) {
    val matrix = remember(url) {
        runCatching {
            QRCodeWriter().encode(
                url, BarcodeFormat.QR_CODE, 0, 0,
                mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.MARGIN to 2)
            )
        }.getOrNull()
    }
    CardBox(Modifier.width(300.dp).fillMaxHeight()) {
        if (matrix == null) { Text("QR을 그리지 못했어요", color = Quiet, fontSize = 16.sp); return@CardBox }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Verified, null, tint = Good, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(8.dp))
            Text("반납 증명서", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier.fillMaxWidth().weight(1f),
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.fillMaxHeight().aspectRatio(1f).background(Color.White, RoundedCornerShape(8.dp))) {
                val n = matrix.width
                val cell = size.width / n
                for (y in 0 until n) for (x in 0 until n) {
                    if (matrix[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("폰 카메라로 찍으면 차가 서명한 반납 기록을 받아요", color = Quiet, fontSize = 17.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

// ---------------- 인수자 선택 ----------------

@Composable
private fun PickDriverScreen(s: UiState, vm: BatonViewModel) {
    var newName by remember { mutableStateOf("") }
    val options = remember { deadlineOptions() }
    var picked by remember { mutableStateOf(1) }
    val deadline = options.getOrNull(picked)?.second
    Column(Modifier.fillMaxSize()) {
        Header(Icons.Rounded.SwapHoriz, "누가 운전하나요?", "선택하면 직전 반납과 비교해 드려요")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Rounded.Schedule, null, tint = Quiet, modifier = Modifier.size(30.dp))
            Text("반납", color = Quiet, fontSize = 22.sp)
            options.forEachIndexed { i, (label, _) ->
                val on = i == picked
                Text(
                    label,
                    color = if (on) OnAccent else Ink,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .background(if (on) Accent else CardHi, RoundedCornerShape(30.dp))
                        .clickable { picked = i }
                        .padding(horizontal = 20.dp, vertical = 14.dp)
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            s.drivers.forEach { name ->
                Column(
                    Modifier
                        .width(180.dp)
                        .background(Card, RoundedCornerShape(24.dp))
                        .clickable { vm.pickDriver(name, deadline) }
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        Modifier.size(88.dp).background(Accent.copy(alpha = 0.2f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(name.takeLast(1), color = Accent, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(name, color = Ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                }
            }
        }
        Spacer(Modifier.height(28.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text("새 운전자 이름") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            PrimaryButton(Icons.Rounded.Add, "추가", { vm.pickDriver(newName, deadline) })
        }
        Spacer(Modifier.weight(1f))
        SecondaryButton(Icons.Rounded.History, "뒤로", { vm.go(Screen.HOME) })
    }
}

// ---------------- 인수 브리핑 ----------------

@Composable
private fun BriefingScreen(s: UiState, vm: BatonViewModel) {
    val last: Snapshot? = s.lastSnapshot
    val (pct, isFuel) = energy(s, vm)
    val odoNow = vm.distance(s.results)
    val lastOdo = last?.odometerKm
    val moved = if (odoNow != null && lastOdo != null) odoNow - lastOdo else null
    val usedAfterReturn = moved != null && moved >= MOVE_THRESHOLD_KM

    Column(Modifier.fillMaxSize()) {
        Header(
            Icons.Rounded.SwapHoriz,
            "${s.currentDriver}님, 인수 브리핑",
            last?.let { "직전 반납 ${it.driver} · ${t(it.takenAt)} · ${duration(System.currentTimeMillis() - it.takenAt)} 전" } ?: "첫 인수예요"
        )

        if (last != null) {
            val heroColor = if (usedAfterReturn) Warn else Good
            CardBox(Modifier.fillMaxWidth(), border = heroColor) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBubble(if (usedAfterReturn) Icons.Rounded.WarningAmber else Icons.Rounded.CheckCircle, heroColor, 72.dp)
                    Spacer(Modifier.width(20.dp))
                    Column {
                        Text(
                            if (usedAfterReturn) "반납 후 ${"%.1f".format(moved)} km 이동했어요" else "반납 후 그대로예요",
                            color = Ink, fontSize = 32.sp, fontWeight = FontWeight.Bold
                        )
                        Text(
                            if (usedAfterReturn) "누적 ${km(lastOdo)} → ${km(odoNow)}" else "이동 기록 없음",
                            color = Quiet, fontSize = 20.sp
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }

        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            CardBox(Modifier.fillMaxHeight()) {
                Box(Modifier.fillMaxHeight(), contentAlignment = Alignment.Center) { EnergyGauge(pct, isFuel, 200.dp) }
            }
            Column(Modifier.weight(1f)) {
                Text("직전 운전자가 남긴 항목", color = Quiet, fontSize = 20.sp)
                Spacer(Modifier.height(10.dp))
                IssueGrid(last?.issues ?: emptyList())
            }
        }

        Spacer(Modifier.height(20.dp))
        PrimaryButton(Icons.Rounded.DirectionsCar, "확인하고 출발", { vm.go(Screen.HOME) }, Modifier.fillMaxWidth())
    }
}

// ---------------- 신호 진단 (개발·검증용) ----------------

@Composable
private fun DiagnosticsScreen(s: UiState, vm: BatonViewModel) {
    val readAt = if (s.lastReadAt > 0) SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date(s.lastReadAt)) else "-"
    val loc = s.location?.let { "%.5f, %.5f".format(it.lat, it.lng) } ?: if (s.locationPermission) "수신 대기" else "권한 없음"
    Column(Modifier.fillMaxSize()) {
        Header(Icons.Rounded.Tune, "신호 진단", "읽기 $readAt · 위치 $loc · 반납 장소까지 ${meters(s.distanceToReturnM)} · 내비 ${s.naviState}${s.naviTrip?.let { " (${it.name} ${it.remainSec / 60}분)" } ?: ""}", Quiet)
        LazyColumn(Modifier.weight(1f)) {
            items(Signals.ALL) { p ->
                val r = s.results[p.id]
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text("${p.label}  ·  ${p.domain}.${p.method}", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    when {
                        r == null -> Text("읽는 중…", color = Quiet, fontSize = 18.sp)
                        r.values.isEmpty() -> Text("✕ ${r.error}", color = Warn, fontSize = 18.sp)
                        else -> r.values.forEach { (a, v) ->
                            Text("✓ ${if (a == "-") "" else "$a = "}${Interpret.raw(v)}", color = Good, fontSize = 18.sp)
                        }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PrimaryButton(Icons.Rounded.History, "돌아가기", { vm.go(Screen.HOME) }, Modifier.weight(1f))
            SecondaryButton(Icons.Rounded.Flag, "반납 장소 3km 옮기기", vm::demoMoveReturnPlace)
            SecondaryButton(Icons.Rounded.Add, "시연 기록 초기화", vm::resetDemo)
        }
    }
}
