package com.codexrefresh.app

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class ExpressiveHomeState(
    val statusText: String = "● 尚未连接 Codex",
    val statusTone: StatusTone = StatusTone.NEUTRAL,
    val statusVisible: Boolean = false,
    val actionFeedback: String? = null,
    val actionFeedbackTone: StatusTone = StatusTone.NEUTRAL,
    val actionFeedbackId: Long = 0L,
    val connected: Boolean = false,
    val busy: Boolean = false,
    val deviceCode: String? = null,
    val usage: Usage? = null,
    val fiveResetText: String = "连接 Codex 后读取",
    val weeklyResetText: String = "连接 Codex 后读取",
    val countdown: String = "—",
    val timeline: DayTimelineState = DayTimelineState(),
    val timelineTitle: String = "今日额度窗口",
    val nextActivationTime: String = "--:--",
    val nextActivationDate: String = "未启用",
    val autoEnabled: Boolean = false,
    val autoSuccesses: Int = 0,
    val autoAttempts: Int = 0,
    val schedule: WorkSchedule = WorkSchedule(),
    val workPlan: String = "工作时间优化未启用",
    val contextSummary: String = "等待连接 · 暂无数据",
    val contextDetails: String = "",
    val contextAvailable: Boolean = false,
    val contextExpanded: Boolean = false,
    val connectLabel: String = "连接 Codex",
    val connectEnabled: Boolean = true,
    val probeVisible: Boolean = false,
    val probeEnabled: Boolean = false,
    val logoutVisible: Boolean = false,
)

enum class StatusTone { NEUTRAL, SUCCESS, ACCENT, ERROR }

data class ExpressiveHomeActions(
    val connect: () -> Unit,
    val probe: () -> Unit,
    val copyCode: () -> Unit,
    val logout: () -> Unit,
    val toggleAuto: (Boolean) -> Unit,
    val toggleWorkSchedule: (Boolean) -> Unit,
    val pickWorkStart: () -> Unit,
    val pickWorkEnd: () -> Unit,
    val toggleContext: () -> Unit,
    val openBackgroundSettings: () -> Unit,
)

@Composable
fun CodexExpressiveTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        dark -> darkColorScheme(
            primary = Color(0xffffb0c7),
            secondary = Color(0xffeab8c8),
            tertiary = Color(0xfff4bb80),
            surface = Color(0xff1b1115),
            surfaceVariant = Color(0xff33262b),
        )
        else -> lightColorScheme(
            primary = Color(0xffa73561),
            secondary = Color(0xff7b5261),
            tertiary = Color(0xff80552c),
            surface = Color(0xfffff8f8),
            surfaceVariant = Color(0xfff0e3e8),
        )
    }
    val typography = Typography(
        displayLarge = MaterialTheme.typography.displayLarge.copy(
            fontSize = 68.sp,
            lineHeight = 68.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = (-2).sp,
        ),
        headlineLarge = MaterialTheme.typography.headlineLarge.copy(
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.7).sp,
        ),
        titleLarge = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
    )
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}

@Composable
fun ExpressiveHomeScreen(state: ExpressiveHomeState, actions: ExpressiveHomeActions) {
    val snackbarHostState = remember { SnackbarHostState() }
    val feedback = state.actionFeedback
    LaunchedEffect(state.actionFeedbackId) {
        if (feedback != null && state.actionFeedbackId > 0L) {
            snackbarHostState.showSnackbar(
                message = feedback,
                withDismissAction = true,
                duration = if (state.actionFeedbackTone == StatusTone.ERROR) {
                    SnackbarDuration.Long
                } else {
                    SnackbarDuration.Short
                },
            )
        }
    }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(WindowInsets.safeDrawing.asPaddingValues())
                    .padding(horizontal = 18.dp, vertical = 12.dp),
            ) {
                ExpressiveHeader(state)
                Spacer(Modifier.height(18.dp))
                HeroWindowCard(state)
                Spacer(Modifier.height(12.dp))
                QuotaGroup(state)
                Spacer(Modifier.height(26.dp))
                SectionLabel("自动化", "PLUS")
                Spacer(Modifier.height(10.dp))
                AutomationCard(state, actions)
                Spacer(Modifier.height(26.dp))
                SectionLabel("手动备用", "FALLBACK")
                Spacer(Modifier.height(10.dp))
                ManualFallbackCard(state, actions)
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = actions.openBackgroundSettings, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("后台运行与通知设置  ↗")
                }
                Spacer(Modifier.height(28.dp))
            }
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(WindowInsets.safeDrawing.asPaddingValues())
                    .padding(horizontal = 18.dp, vertical = 12.dp),
            ) { data ->
                val scheme = MaterialTheme.colorScheme
                val (container, content) = when (state.actionFeedbackTone) {
                    StatusTone.SUCCESS -> scheme.tertiaryContainer to scheme.onTertiaryContainer
                    StatusTone.ACCENT -> scheme.secondaryContainer to scheme.onSecondaryContainer
                    StatusTone.ERROR -> scheme.errorContainer to scheme.onErrorContainer
                    StatusTone.NEUTRAL -> scheme.inverseSurface to scheme.inverseOnSurface
                }
                Snackbar(
                    snackbarData = data,
                    shape = RoundedCornerShape(18.dp),
                    containerColor = container,
                    contentColor = content,
                    dismissActionContentColor = content,
                )
            }
        }
    }
}

@Composable
private fun ExpressiveHeader(state: ExpressiveHomeState) {
    val statusColor = when (state.statusTone) {
        StatusTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
        StatusTone.SUCCESS -> MaterialTheme.colorScheme.primary
        StatusTone.ACCENT -> MaterialTheme.colorScheme.tertiary
        StatusTone.ERROR -> MaterialTheme.colorScheme.error
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            modifier = Modifier.size(52.dp),
            shape = RoundedCornerShape(topStart = 19.dp, topEnd = 26.dp, bottomEnd = 19.dp, bottomStart = 8.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("C", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "WINDOW RHYTHM",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                color = MaterialTheme.colorScheme.primary,
            )
            Text("Codex Refresh", style = MaterialTheme.typography.titleLarge)
        }
        Surface(
            shape = CircleShape,
            color = if (state.connected) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Text(
                if (state.connected) "已连接" else "离线",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (state.connected) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    AnimatedVisibility(
        visible = state.statusVisible,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        Text(
            state.statusText,
            modifier = Modifier.padding(top = 13.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = statusColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun HeroWindowCard(state: ExpressiveHomeState) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 38.dp, topEnd = 38.dp, bottomEnd = 38.dp, bottomStart = 14.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Column(modifier = Modifier.padding(horizontal = 22.dp, vertical = 20.dp)) {
            Text(
                "5 小时窗口倒计时",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                state.countdown,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.displayLarge,
                maxLines = 1,
            )
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(state.timelineTitle, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    "24 小时",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .68f),
                )
            }
            Spacer(Modifier.height(8.dp))
            ExpressiveTimeline(state.timeline)
            AnimatedVisibility(state.timeline.markers.isEmpty()) {
                Text(
                    "刷新额度后显示窗口点",
                    modifier = Modifier.padding(top = 7.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .68f),
                )
            }
        }
    }
}

@Composable
private fun ExpressiveTimeline(state: DayTimelineState) {
    val scheme = MaterialTheme.colorScheme
    val hasWorkBand = state.workLabel != null && state.workRanges.isNotEmpty()
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (hasWorkBand) 108.dp else 70.dp)
            .semantics { contentDescription = state.accessibilityText },
    ) {
        val trackPadding = 7.dp
        val trackWidth = maxWidth - trackPadding * 2
        fun markerX(minute: Float) = trackPadding + trackWidth *
            (minute.coerceIn(0f, MINUTES_PER_DAY.toFloat()) / MINUTES_PER_DAY)

        Canvas(modifier = Modifier.fillMaxSize()) {
            val railY = 32.dp.toPx()
            val startX = trackPadding.toPx()
            val endX = size.width - trackPadding.toPx()
            fun x(minute: Float) = startX +
                (minute.coerceIn(0f, MINUTES_PER_DAY.toFloat()) / MINUTES_PER_DAY) * (endX - startX)

            drawLine(
                color = scheme.onPrimaryContainer.copy(alpha = .25f),
                start = Offset(startX, railY),
                end = Offset(endX, railY),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round,
            )
            state.nowMinute?.let { nowMinute ->
                drawLine(
                    color = scheme.onPrimaryContainer.copy(alpha = .42f),
                    start = Offset(startX, railY),
                    end = Offset(x(nowMinute), railY),
                    strokeWidth = 7.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
            state.markers.forEach { marker ->
                val center = Offset(x(marker.minuteOfDay), railY)
                val markerColor = when (marker.kind) {
                    TimelineMarkerKind.ESTIMATED -> scheme.onPrimaryContainer.copy(alpha = .48f)
                    TimelineMarkerKind.PLANNED -> scheme.onPrimaryContainer.copy(alpha = .76f)
                    else -> scheme.primary
                }
                drawLine(
                    color = markerColor.copy(alpha = .34f),
                    start = Offset(center.x, railY - 9.dp.toPx()),
                    end = Offset(center.x, railY + 9.dp.toPx()),
                    strokeWidth = 1.5.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                when (marker.kind) {
                    TimelineMarkerKind.ESTIMATED -> drawCircle(
                        markerColor,
                        4.5.dp.toPx(),
                        center,
                        style = Stroke(1.8.dp.toPx()),
                    )
                    TimelineMarkerKind.CONFIRMED,
                    TimelineMarkerKind.PLANNED,
                    -> drawCircle(markerColor, 5.5.dp.toPx(), center)
                    TimelineMarkerKind.NEXT -> {
                        drawCircle(markerColor, 9.dp.toPx(), center, style = Stroke(2.5.dp.toPx()))
                        drawCircle(markerColor, 3.5.dp.toPx(), center)
                    }
                    TimelineMarkerKind.COMPLETED -> {
                        drawCircle(markerColor, 8.5.dp.toPx(), center)
                        drawLine(
                            scheme.onPrimary,
                            Offset(center.x - 4.dp.toPx(), center.y),
                            Offset(center.x - 1.dp.toPx(), center.y + 3.dp.toPx()),
                            2.dp.toPx(),
                            StrokeCap.Round,
                        )
                        drawLine(
                            scheme.onPrimary,
                            Offset(center.x - 1.dp.toPx(), center.y + 3.dp.toPx()),
                            Offset(center.x + 4.5.dp.toPx(), center.y - 4.dp.toPx()),
                            2.dp.toPx(),
                            StrokeCap.Round,
                        )
                    }
                }
            }
            if (hasWorkBand) {
                val workY = 78.dp.toPx()
                drawLine(
                    color = scheme.onPrimaryContainer.copy(alpha = .09f),
                    start = Offset(startX, workY),
                    end = Offset(endX, workY),
                    strokeWidth = 8.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                state.workRanges.forEach { range ->
                    drawRoundRect(
                        color = scheme.tertiary.copy(alpha = .34f),
                        topLeft = Offset(x(range.startMinute), workY - 5.dp.toPx()),
                        size = Size(
                            (x(range.endMinute) - x(range.startMinute)).coerceAtLeast(0f),
                            10.dp.toPx(),
                        ),
                        cornerRadius = CornerRadius(5.dp.toPx()),
                    )
                }
            }
        }

        val labelWidth = 58.dp
        val maxLabelX = maxWidth - labelWidth
        state.markers
            .filter { it.kind != TimelineMarkerKind.ESTIMATED }
            .forEach { marker ->
                val rawX = markerX(marker.minuteOfDay) - labelWidth / 2
                val labelX = rawX.coerceIn(0.dp, maxLabelX)
                Text(
                    formatTimelineMinute(marker.minuteOfDay),
                    modifier = Modifier.offset(x = labelX).width(labelWidth),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (marker.kind == TimelineMarkerKind.NEXT) FontWeight.ExtraBold else FontWeight.Medium,
                    color = if (marker.kind == TimelineMarkerKind.NEXT) scheme.primary else scheme.onPrimaryContainer,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
                if (marker.kind == TimelineMarkerKind.NEXT) {
                    Text(
                        "下一次",
                        modifier = Modifier.offset(x = labelX, y = 47.dp).width(labelWidth),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = scheme.primary,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
        Text(
            "00",
            modifier = Modifier.align(Alignment.TopStart).offset(y = 47.dp),
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onPrimaryContainer.copy(alpha = .62f),
        )
        Text(
            "24",
            modifier = Modifier.align(Alignment.TopEnd).offset(y = 47.dp),
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onPrimaryContainer.copy(alpha = .62f),
        )
        state.workLabel?.let { label ->
            Text(
                label,
                modifier = Modifier.align(Alignment.TopCenter).offset(y = 88.dp),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = scheme.onPrimaryContainer.copy(alpha = .72f),
            )
        }
    }
}

private fun formatTimelineMinute(minute: Float): String {
    val total = minute.toInt().coerceIn(0, MINUTES_PER_DAY - 1)
    return String.format(java.util.Locale.US, "%02d:%02d", total / 60, total % 60)
}

@Composable
private fun TimeBlock(
    label: String,
    value: String,
    modifier: Modifier,
    shape: RoundedCornerShape,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = shape,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = .72f))
            Text(value, style = MaterialTheme.typography.headlineLarge)
        }
    }
}

@Composable
private fun SectionLabel(title: String, eyebrow: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
        Text(eyebrow, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
    }
}

@Composable
private fun QuotaGroup(state: ExpressiveHomeState) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
        QuotaCard(
            title = "5 小时",
            percent = state.usage?.fiveHour?.percent,
            reset = state.fiveResetText,
            modifier = Modifier.weight(1.12f).height(184.dp),
            shape = RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp, bottomEnd = 12.dp, bottomStart = 34.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        QuotaCard(
            title = "7 天",
            percent = state.usage?.weekly?.percent,
            reset = state.weeklyResetText,
            modifier = Modifier.weight(.88f).height(164.dp),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomEnd = 28.dp, bottomStart = 10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun QuotaCard(
    title: String,
    percent: Double?,
    reset: String,
    modifier: Modifier,
    shape: RoundedCornerShape,
    color: Color,
    contentColor: Color,
) {
    val remaining = QuotaPresentation.remainingPercent(percent)
    Surface(modifier = modifier, shape = shape, color = color, contentColor = contentColor) {
        Column(modifier = Modifier.padding(17.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Column {
                Text(
                    remaining?.let { "${kotlin.math.round(it).toInt()}%" } ?: "—",
                    style = MaterialTheme.typography.headlineLarge.copy(fontSize = 40.sp),
                )
                Text("剩余", style = MaterialTheme.typography.labelMedium)
            }
            ExpressiveProgress((remaining ?: 0.0).toFloat() / 100f, contentColor)
            Text(reset, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ExpressiveProgress(progress: Float, color: Color) {
    val value by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = .72f, stiffness = Spring.StiffnessLow),
        label = "quota-progress",
    )
    Canvas(modifier = Modifier.fillMaxWidth().height(15.dp)) {
        val y = size.height / 2
        drawLine(color.copy(alpha = .16f), Offset(0f, y), Offset(size.width, y), 7.dp.toPx(), StrokeCap.Round)
        if (value > 0f) {
            val end = size.width * value
            drawLine(color, Offset(0f, y), Offset(end, y), 7.dp.toPx(), StrokeCap.Round)
            drawCircle(color, 6.dp.toPx(), Offset(end, y))
        }
    }
}

@Composable
private fun AutomationCard(state: ExpressiveHomeState, actions: ExpressiveHomeActions) {
    Surface(
        modifier = Modifier.fillMaxWidth().animateContentSize(spring(stiffness = Spring.StiffnessMediumLow)),
        shape = RoundedCornerShape(topStart = 14.dp, topEnd = 34.dp, bottomEnd = 34.dp, bottomStart = 34.dp),
        color = if (state.autoEnabled) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (state.autoEnabled) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Column(modifier = Modifier.padding(19.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("自动激活", style = MaterialTheme.typography.headlineLarge)
                    Text(
                        if (state.autoEnabled) "下一额度窗口已纳入计划" else "自动续接 Codex 五小时额度窗口",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = state.autoEnabled, onCheckedChange = actions.toggleAuto)
            }
            AnimatedVisibility(
                visible = state.autoEnabled,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column {
                    Spacer(Modifier.height(16.dp))
                    NextActivationPanel(state)
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AutoMetric("已启用", "状态", Modifier.weight(1.15f))
                        AutoMetric("${state.autoSuccesses}/6", "今日成功", Modifier.weight(1f))
                        AutoMetric("${state.autoAttempts}/12", "今日尝试", Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(14.dp))
                    WorkSchedulePanel(state, actions)
                }
            }
        }
    }
}

@Composable
private fun NextActivationPanel(state: ExpressiveHomeState) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomEnd = 24.dp, bottomStart = 9.dp),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("下次自动激活", style = MaterialTheme.typography.labelMedium)
                Text(state.nextActivationDate, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                state.nextActivationTime,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun WorkSchedulePanel(state: ExpressiveHomeState, actions: ExpressiveHomeActions) {
    Surface(
        modifier = Modifier.fillMaxWidth().animateContentSize(spring(stiffness = Spring.StiffnessMediumLow)),
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 12.dp, bottomEnd = 26.dp, bottomStart = 26.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = .72f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("工作时间优化", style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (state.schedule.enabled) "让更多额度窗口覆盖工作时段" else "按上下班时间计算激活点",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.schedule.enabled, onCheckedChange = actions.toggleWorkSchedule)
            }
            AnimatedVisibility(
                visible = state.schedule.enabled,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column {
                    Spacer(Modifier.height(13.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TimeBlock(
                            label = "上班",
                            value = WorkSchedulePolicy.formatMinute(state.schedule.startMinute),
                            modifier = Modifier.weight(1.08f),
                            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 10.dp, bottomEnd = 10.dp, bottomStart = 24.dp),
                            onClick = actions.pickWorkStart,
                        )
                        TimeBlock(
                            label = "下班",
                            value = WorkSchedulePolicy.formatMinute(state.schedule.endMinute),
                            modifier = Modifier.weight(.92f),
                            shape = RoundedCornerShape(topStart = 10.dp, topEnd = 24.dp, bottomEnd = 24.dp, bottomStart = 10.dp),
                            onClick = actions.pickWorkEnd,
                        )
                    }
                    Spacer(Modifier.height(11.dp))
                    Text(state.workPlan, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun AutoMetric(value: String, label: String, modifier: Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = .62f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 11.dp)) {
            Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

@Composable
private fun ContextCard(state: ExpressiveHomeState, onToggle: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).animateContentSize(spring(stiffness = Spring.StiffnessMediumLow)),
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomEnd = 22.dp, bottomStart = 9.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                "最近一次手动请求",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(state.contextSummary, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(if (state.contextExpanded) "收起 ↑" else "详情 ↓", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            AnimatedVisibility(state.contextExpanded) {
                Text(state.contextDetails, modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ManualFallbackCard(state: ExpressiveHomeState, actions: ExpressiveHomeActions) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 14.dp, bottomEnd = 30.dp, bottomStart = 30.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("需要时再使用", style = MaterialTheme.typography.titleLarge)
            Text(
                if (state.connected) {
                    "额度未更新或自动计划异常时，可在这里重新读取或手动激活。"
                } else {
                    "连接 Codex 后读取额度，并在自动流程异常时手动恢复。"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (state.deviceCode != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomEnd = 9.dp, bottomStart = 24.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("设备验证码", style = MaterialTheme.typography.labelMedium)
                            Text(state.deviceCode, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
                        }
                        Button(onClick = actions.copyCode) { Text("复制") }
                    }
                }
            }
            Button(
                onClick = actions.connect,
                enabled = state.connectEnabled,
                modifier = Modifier.fillMaxWidth().height(58.dp),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomEnd = 12.dp, bottomStart = 28.dp),
            ) {
                Text(state.connectLabel, style = MaterialTheme.typography.titleMedium)
            }
            if (state.probeVisible) {
                OutlinedButton(
                    onClick = actions.probe,
                    enabled = state.probeEnabled,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(topStart = 12.dp, topEnd = 28.dp, bottomEnd = 28.dp, bottomStart = 28.dp),
                ) {
                    Text("手动发送激活请求", style = MaterialTheme.typography.titleMedium)
                }
            }
            if (state.contextAvailable) {
                ContextCard(state, actions.toggleContext)
            }
            if (state.logoutVisible) {
                TextButton(onClick = actions.logout, modifier = Modifier.fillMaxWidth()) {
                    Text("退出登录并停用自动任务", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
