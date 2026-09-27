package com.codexrefresh.app

import android.os.Build
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.zIndex
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

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
    val countdownLabel: String = "5 小时窗口状态",
    val countdown: String = "—",
    val timeline: DayTimelineState = DayTimelineState(),
    val timelineTitle: String = "窗口状态",
    val timelineHint: String = "刷新额度后确认窗口状态",
    val nextActivationTime: String = "--:--",
    val nextActivationDate: String = "未启用",
    val autoEnabled: Boolean = false,
    val autoSuccesses: Int = 0,
    val autoAttempts: Int = 0,
    val exactAlarmReady: Boolean = true,
    val schedule: WorkSchedule = WorkSchedule(),
    val workPlan: String = "工作时间优化未启用",
    val contextSummary: String = "等待连接 · 暂无数据",
    val contextDetails: String = "",
    val contextAvailable: Boolean = false,
    val contextExpanded: Boolean = false,
    val quotaDiagnosticsSummary: String = "尚无观测记录",
    val quotaDiagnosticsDetails: String = "",
    val quotaDiagnosticsAvailable: Boolean = false,
    val quotaDiagnosticsExpanded: Boolean = false,
    val connectLabel: String = "连接 Codex",
    val connectEnabled: Boolean = true,
    val probeVisible: Boolean = false,
    val probeEnabled: Boolean = false,
    val manualModels: List<ProbeModel> = emptyList(),
    val manualModelId: String = DEFAULT_PROBE_MODEL_ID,
    val manualModelsLoading: Boolean = false,
    val manualModelsError: String? = null,
    val logoutVisible: Boolean = false,
)

enum class StatusTone { NEUTRAL, SUCCESS, ACCENT, ERROR }

data class ExpressiveHomeActions(
    val connect: () -> Unit,
    val probe: () -> Unit,
    val refreshManualModels: () -> Unit,
    val selectManualModel: (String) -> Unit,
    val copyCode: () -> Unit,
    val logout: () -> Unit,
    val toggleAuto: (Boolean) -> Unit,
    val toggleWorkSchedule: (Boolean) -> Unit,
    val pickWorkStart: () -> Unit,
    val pickWorkEnd: () -> Unit,
    val setWorkRange: (startMinute: Int, endMinute: Int) -> Unit,
    val toggleContext: () -> Unit,
    val toggleQuotaDiagnostics: () -> Unit,
    val copyQuotaDiagnostics: () -> Unit,
    val openBackgroundSettings: () -> Unit,
    val requestExactAlarm: () -> Unit,
)

// 硬核数据仪表盘主题 + 动态背景
@Composable
fun CodexExpressiveTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()

    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        dark -> darkColorScheme(
            primary = Color(0xFF00E676),         // 亮绿（终端绿）
            secondary = Color(0xFF00BCD4),       // 青色
            tertiary = Color(0xFFFFAB00),        // 琥珀色
            surface = Color(0xFF1A1A1A),
            surfaceVariant = Color(0xFF2A2A2A),
            background = Color(0xFF0A0A0A),
            error = Color(0xFFFF5252),
        )
        else -> lightColorScheme(
            primary = Color(0xFF00C853),
            secondary = Color(0xFF00ACC1),
            tertiary = Color(0xFFFF6F00),
            surface = Color(0xFFFFFFFF),
            surfaceVariant = Color(0xFFF5F5F5),
            background = Color(0xFFFAFAFA),
            error = Color(0xFFD32F2F),
        )
    }

    MaterialTheme(colorScheme = scheme, content = content)
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

    val backdrop = rememberBackdrop()

    CompositionLocalProvider(LocalBackdrop provides backdrop) {
        Box(modifier = Modifier.fillMaxSize()) {
            AuroraBackground(backdrop)

            // 内容层（带毛玻璃效果）
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(WindowInsets.safeDrawing.asPaddingValues())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 顶部状态栏
                StatusBar(state)

                // 设备码登录：浏览器页只有输入框，验证码必须在这里看到
                state.deviceCode?.let { DeviceCodePanel(it, actions.copyCode) }

                // 主数据区
                MainDataPanel(state)

                // 控制区
                ControlSection(state, actions)

                // 工作时间优化（含可拖动功能）
                WorkScheduleSection(state, actions)

                // 高级选项
                if (state.contextAvailable || state.quotaDiagnosticsAvailable || state.deviceCode != null || state.logoutVisible) {
                    AdvancedOptions(state, actions)
                }
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
            )
        }
    }
}

// Gemini 风格极光背景 + 毛玻璃卡片
//
// Compose 没有现成的背板模糊：卡片在自己的区域里按同一相位重画一份极光再模糊，
// 效果等同于模糊背后的背景。点阵只画在外面，被玻璃盖住的地方点阵消失，磨砂感就出来了。

private const val AURORA_PERIOD_MS = 24_000
private val GLASS_BLUR = 28.dp
private val GRID_SPACING = 22.dp

private class Orb(val color: Color, val fx: Int, val fy: Int, val px: Float, val py: Float, val radius: Float)

@Stable
private class Backdrop(val phase: State<Float>, val dark: Boolean, val base: Color, val orbs: List<Orb>) {
    var area by mutableStateOf(Size.Zero)
    var coordinates: LayoutCoordinates? = null

    fun offsetOf(child: LayoutCoordinates): Offset {
        val own = coordinates?.takeIf { it.isAttached } ?: return child.positionInRoot()
        return own.localPositionOf(child, Offset.Zero)
    }
}

private val LocalBackdrop = staticCompositionLocalOf<Backdrop?> { null }

private val blurSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

@Composable
private fun rememberBackdrop(): Backdrop {
    val dark = isSystemInDarkTheme()
    val scheme = MaterialTheme.colorScheme
    val phase = rememberInfiniteTransition(label = "aurora").animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(AURORA_PERIOD_MS, easing = LinearEasing)),
        label = "aurora-phase"
    )
    return remember(phase, dark, scheme.primary, scheme.secondary, scheme.tertiary) {
        // 频率取整数，相位走完 2π 正好回到原位，循环无接缝
        val k = if (dark) 1f else 0.7f
        Backdrop(
            phase = phase,
            dark = dark,
            base = if (dark) Color(0xFF06080B) else Color(0xFFF2F4F8),
            orbs = listOf(
                Orb(scheme.primary.copy(alpha = 0.50f * k), 1, 1, 0.0f, 1.2f, 0.55f),
                Orb(Color(0xFF7C4DFF).copy(alpha = 0.50f * k), 1, 2, 2.1f, 0.3f, 0.50f),
                Orb(scheme.secondary.copy(alpha = 0.45f * k), 2, 1, 4.2f, 2.5f, 0.42f),
                Orb(scheme.tertiary.copy(alpha = 0.28f * k), 1, 1, 3.3f, 4.8f, 0.38f),
            )
        )
    }
}

private fun DrawScope.drawAurora(backdrop: Backdrop) {
    val area = backdrop.area
    val phase = backdrop.phase.value
    drawRect(backdrop.base, size = area)
    val span = maxOf(area.width, area.height)
    backdrop.orbs.forEach { orb ->
        val center = Offset(
            area.width * (0.5f + 0.42f * sin(phase * orb.fx + orb.px)),
            area.height * (0.5f + 0.40f * sin(phase * orb.fy + orb.py))
        )
        val radius = span * orb.radius
        drawCircle(
            brush = Brush.radialGradient(
                0f to orb.color,
                0.45f to orb.color.copy(alpha = orb.color.alpha * 0.45f),
                1f to Color.Transparent,
                center = center,
                radius = radius
            ),
            radius = radius,
            center = center
        )
    }
}

@Composable
private fun AuroraBackground(backdrop: Backdrop) {
    val dotColor = if (backdrop.dark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.07f)
    Spacer(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { backdrop.area = it.toSize() }
            .onGloballyPositioned { backdrop.coordinates = it }
            .drawWithCache {
                val step = GRID_SPACING.toPx()
                val area = size
                val points = buildList {
                    var y = step / 2
                    while (y < area.height) {
                        var x = step / 2
                        while (x < area.width) {
                            add(Offset(x, y))
                            x += step
                        }
                        y += step
                    }
                }
                val dot = 1.6.dp.toPx()
                onDrawBehind {
                    drawAurora(backdrop)
                    drawPoints(points, PointMode.Points, dotColor, strokeWidth = dot, cap = StrokeCap.Round)
                }
            }
    )
}

/** 细颗粒噪点，叠在玻璃上形成磨砂质感；全局共用一张平铺贴图。 */
private val grainBrush: ShaderBrush by lazy {
    val side = 128
    val random = java.util.Random(7)
    val pixels = IntArray(side * side) {
        val v = random.nextInt(256)
        android.graphics.Color.argb(random.nextInt(256), v, v, v)
    }
    val bitmap = android.graphics.Bitmap.createBitmap(pixels, side, side, android.graphics.Bitmap.Config.ARGB_8888)
    ShaderBrush(ImageShader(bitmap.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
}

@Composable
private fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    content: @Composable () -> Unit,
) {
    val backdrop = LocalBackdrop.current
    val dark = backdrop?.dark ?: isSystemInDarkTheme()
    var offset by remember { mutableStateOf(Offset.Zero) }

    // 不支持模糊（Android 12 以下）时背后是清晰的背景，调暗一些保证文字可读
    val tint = when {
        dark && blurSupported -> Color(0xFF0B0F14).copy(alpha = 0.42f)
        dark -> Color(0xFF0B0F14).copy(alpha = 0.72f)
        blurSupported -> Color.White.copy(alpha = 0.45f)
        else -> Color.White.copy(alpha = 0.75f)
    }
    val sheen = Color.White.copy(alpha = if (dark) 0.07f else 0.35f)
    val rim = Brush.linearGradient(
        if (dark) {
            listOf(Color.White.copy(alpha = 0.32f), Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.16f))
        } else {
            listOf(Color.White.copy(alpha = 0.95f), Color.White.copy(alpha = 0.35f), Color.White.copy(alpha = 0.70f))
        }
    )

    Box(
        modifier = modifier
            .onGloballyPositioned { offset = backdrop?.offsetOf(it) ?: Offset.Zero }
            .clip(shape)
            .border(1.dp, rim, shape),
        propagateMinConstraints = true
    ) {
        if (backdrop != null && blurSupported) {
            Spacer(
                Modifier
                    .matchParentSize()
                    .blur(GLASS_BLUR, BlurredEdgeTreatment.Rectangle)
                    .drawBehind { translate(-offset.x, -offset.y) { drawAurora(backdrop) } }
            )
        }
        Spacer(
            Modifier
                .matchParentSize()
                .drawBehind {
                    drawRect(tint)
                    drawRect(grainBrush, alpha = if (dark) 0.06f else 0.05f)
                    drawRect(
                        Brush.verticalGradient(listOf(sheen, Color.Transparent), endY = size.height * 0.45f)
                    )
                }
        )
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            content()
        }
    }
}

@Composable
private fun StatusBar(state: ExpressiveHomeState) {
    GlassPanel(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 状态指示
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            when {
                                !state.connected -> Color.Gray
                                state.countdown == "等待激活" -> Color(0xFFFFAB00)
                                else -> Color(0xFF00E676)
                            },
                            RoundedCornerShape(50)
                        )
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "CODEX REFRESH",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }

            Text(
                if (state.connected) "ONLINE" else "OFFLINE",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun DeviceCodePanel(code: String, onCopy: () -> Unit) {
    GlassPanel(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onCopy)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "DEVICE CODE",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(12.dp))
            Text(
                code,
                style = MaterialTheme.typography.headlineMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 4.sp
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "在浏览器中输入此验证码 · 点按复制",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun MainDataPanel(state: ExpressiveHomeState) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 倒计时大数字 - 毛玻璃卡片
        GlassPanel(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "5H WINDOW",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    state.countdown,
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontSize = 52.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = (-1).sp
                    )
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    when {
                        state.countdown == "等待激活" -> "STANDBY"
                        state.countdown == "—" -> "DISCONNECTED"
                        else -> "ACTIVE"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }

        // 额度数据
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            QuotaBlock(
                "5H",
                state.usage?.fiveHour?.percent,
                state.fiveResetText,
                Modifier.weight(1f)
            )
            QuotaBlock(
                "7D",
                state.usage?.weekly?.percent,
                state.weeklyResetText,
                Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun QuotaBlock(
    label: String,
    percent: Double?,
    reset: String,
    modifier: Modifier
) {
    val remaining = QuotaPresentation.remainingPercent(percent)

    GlassPanel(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Text(
                    remaining?.let { "${it.roundToInt()}%" } ?: "—",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(8.dp))
            // 进度条
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                        RoundedCornerShape(2.dp)
                    )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth((remaining?.toFloat() ?: 0f) / 100f)
                        .fillMaxHeight()
                        .background(
                            MaterialTheme.colorScheme.primary,
                            RoundedCornerShape(2.dp)
                        )
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                reset,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ControlSection(state: ExpressiveHomeState, actions: ExpressiveHomeActions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // 自动续接
        GlassPanel(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "AUTO RENEW",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    if (state.autoEnabled) {
                        Text(
                            "Next: ${state.nextActivationTime}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    // 未授权时只能近似唤醒，夜间 Doze 下可能晚点
                    if (state.autoEnabled && !state.exactAlarmReady) {
                        Text(
                            "未授权准时唤醒，夜间可能延迟 · 点按设置",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .clickable(onClick = actions.requestExactAlarm)
                        )
                    }
                }
                Switch(
                    checked = state.autoEnabled,
                    onCheckedChange = actions.toggleAuto
                )
            }
        }

        // 统计
        if (state.autoEnabled) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatBox("${state.autoSuccesses}/6", "SUCCESS", Modifier.weight(1f))
                StatBox("${state.autoAttempts}/12", "ATTEMPTS", Modifier.weight(1f))
            }
        }

        // 按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = actions.connect,
                enabled = state.connectEnabled,
                modifier = Modifier.weight(1f).height(44.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(if (state.connected) "REFRESH" else "CONNECT", fontWeight = FontWeight.Bold)
            }

            if (state.probeVisible) {
                Button(
                    onClick = actions.probe,
                    enabled = state.probeEnabled,
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("ACTIVATE", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun StatBox(value: String, label: String, modifier: Modifier) {
    GlassPanel(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 0.5.sp
            )
        }
    }
}

internal enum class WorkThumb { START, END }

/** 时间轴几何：轨道绘制、滑块位置和手势换算共用一套映射，避免三者错位。 */
internal object WorkScheduleSlider {
    const val SNAP_MINUTES = 15

    fun x(minute: Int, width: Float, inset: Float): Float =
        inset + (width - 2 * inset) * minute / MINUTES_PER_DAY

    fun minuteAt(x: Float, width: Float, inset: Float): Int {
        val raw = (x - inset) / (width - 2 * inset).coerceAtLeast(1f) * MINUTES_PER_DAY
        return ((raw / SNAP_MINUTES).roundToInt() * SNAP_MINUTES)
            .coerceIn(0, MINUTES_PER_DAY - SNAP_MINUTES)
    }

    /**
     * 按下点离两个滑块都超过 [hitRadius] 时返回 null，不接管手势（留给外层滚动）。
     * 两个滑块几乎一样近（叠在一起）时按拖动方向取外侧那个，保证总能把它们拉开。
     */
    fun pick(downX: Float, dragDx: Float, startX: Float, endX: Float, hitRadius: Float): WorkThumb? {
        val toStart = abs(downX - startX)
        val toEnd = abs(downX - endX)
        return when {
            minOf(toStart, toEnd) > hitRadius -> null
            abs(toStart - toEnd) < hitRadius / 3 ->
                if ((dragDx > 0) == (endX >= startX)) WorkThumb.END else WorkThumb.START
            toStart < toEnd -> WorkThumb.START
            else -> WorkThumb.END
        }
    }
}

private val THUMB_SIZE = 36.dp
private val TRACK_CENTER_Y = 24.dp

// 工作时间优化（含拖动功能）
@Composable
private fun WorkScheduleSection(state: ExpressiveHomeState, actions: ExpressiveHomeActions) {
    var isExpanded by remember { mutableStateOf(false) }
    val schedule = state.schedule

    // 拖动中的草稿值，松手才提交；持久化值变化时（提交、时间选择器）重新同步
    var draftStart by remember(schedule.startMinute) { mutableIntStateOf(schedule.startMinute) }
    var draftEnd by remember(schedule.endMinute) { mutableIntStateOf(schedule.endMinute) }
    val draft = if (draftStart != draftEnd) {
        schedule.copy(startMinute = draftStart, endMinute = draftEnd)
    } else {
        schedule
    }

    GlassPanel(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .animateContentSize()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "WORK SCHEDULE",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Text(
                        if (schedule.enabled) TimelinePresentation.workPlanText(draft) else "Optimize for work hours",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Switch(
                        checked = schedule.enabled,
                        onCheckedChange = actions.toggleWorkSchedule
                    )
                    Text(
                        if (isExpanded && schedule.enabled) "▲" else "▼",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            AnimatedVisibility(visible = isExpanded && schedule.enabled) {
                Column(
                    modifier = Modifier.padding(top = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    WorkScheduleTimeline(
                        startMinute = draftStart,
                        endMinute = draftEnd,
                        activationMinutes = WorkSchedulePolicy.activationMinutes(draft),
                        onStartChange = { draftStart = it },
                        onEndChange = { draftEnd = it },
                        onDragFinished = { actions.setWorkRange(draftStart, draftEnd) },
                        onDragCancelled = {
                            draftStart = schedule.startMinute
                            draftEnd = schedule.endMinute
                        },
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TimeDisplay("START", WorkSchedulePolicy.formatMinute(draftStart), actions.pickWorkStart)
                        Text(
                            "DRAG TO ADJUST\nTAP TIME TO EDIT",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelSmall,
                            letterSpacing = 1.sp,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                        TimeDisplay("END", WorkSchedulePolicy.formatMinute(draftEnd), actions.pickWorkEnd)
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkScheduleTimeline(
    startMinute: Int,
    endMinute: Int,
    activationMinutes: List<Int>,
    onStartChange: (Int) -> Unit,
    onEndChange: (Int) -> Unit,
    onDragFinished: () -> Unit,
    onDragCancelled: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = onSurface.copy(alpha = 0.5f))
    val textMeasurer = rememberTextMeasurer()

    // 手势协程只启动一次，靠 rememberUpdatedState 读最新值，不能直接捕获参数
    val latestStart by rememberUpdatedState(startMinute)
    val latestEnd by rememberUpdatedState(endMinute)
    val latestOnStartChange by rememberUpdatedState(onStartChange)
    val latestOnEndChange by rememberUpdatedState(onEndChange)
    val latestOnDragFinished by rememberUpdatedState(onDragFinished)
    val latestOnDragCancelled by rememberUpdatedState(onDragCancelled)
    var activeThumb by remember { mutableStateOf<WorkThumb?>(null) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val width = size.width.toFloat()
                    val inset = THUMB_SIZE.toPx() / 2
                    val hitRadius = THUMB_SIZE.toPx()
                    val startX = WorkScheduleSlider.x(latestStart, width, inset)
                    val endX = WorkScheduleSlider.x(latestEnd, width, inset)
                    if (WorkScheduleSlider.pick(down.position.x, 0f, startX, endX, hitRadius) == null) {
                        return@awaitEachGesture
                    }
                    var thumb: WorkThumb? = null
                    // 只认水平拖动，竖向滑动留给页面滚动
                    val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, overSlop ->
                        change.consume()
                        thumb = WorkScheduleSlider.pick(down.position.x, overSlop, startX, endX, hitRadius)
                    } ?: return@awaitEachGesture
                    val picked = thumb ?: return@awaitEachGesture
                    val originX = if (picked == WorkThumb.START) startX else endX

                    fun follow(change: PointerInputChange) {
                        val minute = WorkScheduleSlider.minuteAt(
                            originX + change.position.x - down.position.x,
                            width,
                            inset,
                        )
                        // 上下班时间不能重合（WorkSchedule 不允许），重合时停在上一个值
                        when (picked) {
                            WorkThumb.START -> if (minute != latestEnd) latestOnStartChange(minute)
                            WorkThumb.END -> if (minute != latestStart) latestOnEndChange(minute)
                        }
                    }

                    activeThumb = picked
                    var completed = false
                    try {
                        follow(drag)
                        completed = horizontalDrag(drag.id) { change ->
                            change.consume()
                            follow(change)
                        }
                    } finally {
                        activeThumb = null
                        if (completed) latestOnDragFinished() else latestOnDragCancelled()
                    }
                }
            }
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val insetPx = with(LocalDensity.current) { THUMB_SIZE.toPx() / 2 }

        Canvas(modifier = Modifier.matchParentSize()) {
            val trackY = TRACK_CENTER_Y.toPx()
            fun xOf(minute: Int) = WorkScheduleSlider.x(minute, size.width, insetPx)
            fun segment(from: Int, to: Int, color: Color, stroke: Float) = drawLine(
                color = color,
                start = Offset(xOf(from), trackY),
                end = Offset(xOf(to), trackY),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )

            // 背景轨道
            segment(0, MINUTES_PER_DAY, onSurface.copy(alpha = 0.12f), 4.dp.toPx())

            // 工作时段高亮；跨夜班拆成两段
            val workColor = primary.copy(alpha = 0.45f)
            if (startMinute < endMinute) {
                segment(startMinute, endMinute, workColor, 6.dp.toPx())
            } else {
                segment(startMinute, MINUTES_PER_DAY, workColor, 6.dp.toPx())
                segment(0, endMinute, workColor, 6.dp.toPx())
            }

            // 刻度与小时标签（每 4 小时）
            for (hour in 0..24 step 4) {
                val x = xOf(hour * 60)
                drawLine(
                    color = onSurface.copy(alpha = 0.25f),
                    start = Offset(x, trackY + 6.dp.toPx()),
                    end = Offset(x, trackY + 10.dp.toPx()),
                    strokeWidth = 1.dp.toPx()
                )
                val label = textMeasurer.measure(hour.toString(), labelStyle)
                drawText(label, topLeft = Offset(x - label.size.width / 2f, trackY + 20.dp.toPx()))
            }

            // 随拖动实时更新的推荐激活点
            activationMinutes.forEach { minute ->
                drawCircle(tertiary, radius = 4.dp.toPx(), center = Offset(xOf(minute), trackY))
            }
        }

        WorkThumbHandle(
            label = "▶",
            centerX = WorkScheduleSlider.x(startMinute, widthPx, insetPx),
            active = activeThumb == WorkThumb.START
        )
        WorkThumbHandle(
            label = "◀",
            centerX = WorkScheduleSlider.x(endMinute, widthPx, insetPx),
            active = activeThumb == WorkThumb.END
        )
    }
}

@Composable
private fun WorkThumbHandle(label: String, centerX: Float, active: Boolean) {
    val scale by animateFloatAsState(
        targetValue = if (active) 1.25f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "thumb-scale"
    )
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary

    Box(
        modifier = Modifier
            .zIndex(if (active) 1f else 0f)
            .offset {
                IntOffset(
                    (centerX - THUMB_SIZE.toPx() / 2).roundToInt(),
                    (TRACK_CENTER_Y - THUMB_SIZE / 2).roundToPx()
                )
            }
            .size(THUMB_SIZE)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .background(primary, CircleShape)
            .border(2.dp, onPrimary.copy(alpha = 0.6f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = onPrimary
        )
    }
}

@Composable
private fun TimeDisplay(label: String, time: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 1.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Text(
            time,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun AdvancedOptions(state: ExpressiveHomeState, actions: ExpressiveHomeActions) {
    if (state.logoutVisible) {
        TextButton(
            onClick = actions.logout,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("LOGOUT", color = MaterialTheme.colorScheme.error)
        }
    }
}
