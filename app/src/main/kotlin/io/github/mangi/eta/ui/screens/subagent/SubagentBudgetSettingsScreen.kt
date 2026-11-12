package io.github.mangi.eta.ui.screens.subagent

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.itos.coui.layout.Divider
import com.itos.coui.layout.Group
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.Text as CouiText
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.data.model.SubagentBudgetSettings
import io.github.mangi.eta.data.model.SubagentSettings
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.StatusError
import io.github.mangi.eta.ui.components.coui.CouiButton
import io.github.mangi.eta.ui.components.coui.CouiButtonRole
import io.github.mangi.eta.ui.components.coui.CouiInputDialog
import io.github.mangi.eta.ui.components.coui.CouiPageBottomSpacer
import io.github.mangi.eta.ui.components.coui.CouiScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiSliderPreference
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import kotlin.math.roundToInt

/**
 * 子代理预算管理：时间、调用次数与并发的六项数值预算。
 *
 * 预算与 Subagent 模型、思考强度一起序列化在 `subagent_settings_v1`，本页只改
 * [SubagentSettings.budget]，不改模型选择；写入统一走
 * [RuntimeConfigRepository.setSubagentSettings]，读到的是归一化后的
 * [SubagentSettings.normalizedBudget]。
 *
 * 数值控件沿用全局统一的滑块行（`CouiSliderPreference` / `SliderPreference`），
 * 点按行标题可直接输入精确值，与“外观与主题 → 界面缩放”的交互保持一致。
 */
@Composable
internal fun SubagentBudgetSettingsScreen(
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val settingsFlow = remember { RuntimeConfigRepository.subagentSettingsFlow() }
    var settings by remember { mutableStateOf(SubagentSettings()) }
    LaunchedEffect(settingsFlow) {
        settingsFlow.collect { settings = it }
    }
    val budget = remember(settings) { settings.normalizedBudget }
    var error by remember { mutableStateOf<String?>(null) }

    val saveBudget: (SubagentBudgetSettings) -> Unit = { next ->
        val normalized = next.normalized
        scope.launch {
            runCatching {
                RuntimeConfigRepository.setSubagentSettings(settings.copy(budget = normalized))
            }
                .onSuccess { error = null }
                .onFailure { error = "保存失败：${it.javaClass.simpleName}" }
        }
    }

    // 拖动中的草稿值不落盘，松手（或精确输入确认）时才提交，避免每帧读写 DataStore。
    var drafts by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }
    LaunchedEffect(budget) {
        drafts = BUDGET_FIELDS.associate { it.key to it.read(budget).toFloat() }
    }

    fun currentValue(field: BudgetField): Int {
        val ceiling = field.ceiling(budget)
        val raw = drafts[field.key] ?: field.read(budget).toFloat()
        return raw.roundToInt().coerceIn(field.min, ceiling)
    }

    var editingKey by remember { mutableStateOf<String?>(null) }
    var editingText by remember { mutableStateOf("") }
    val editingField = BUDGET_FIELDS.firstOrNull { it.key == editingKey }
    val editingValue = editingText.toIntOrNull()
    val editingValid = editingField != null && editingValue != null &&
        editingValue in editingField.min..editingField.ceiling(budget)

    val onExactInput: (BudgetField) -> Unit = { field ->
        editingKey = field.key
        editingText = currentValue(field).toString()
    }
    val dismissExactInput = { editingKey = null }
    val confirmExactInput = {
        val field = editingField
        val value = editingValue
        if (field != null && value != null && value in field.min..field.ceiling(budget)) {
            saveBudget(field.write(budget, value))
        }
        editingKey = null
    }

    val budgetSummary = "预算只约束 Subagent，不影响主代理。更改立即保存，下一次主代理任务开始时生效；" +
        "正在运行的任务沿用开始时的预算。"

    @Composable
    fun BudgetRow(field: BudgetField) {
        val ceiling = field.ceiling(budget)
        val draft = (drafts[field.key] ?: field.read(budget).toFloat())
        BudgetSliderRow(
            field = field,
            draft = draft,
            ceiling = ceiling,
            displayValue = currentValue(field),
            onDraftChange = { raw -> drafts = drafts + (field.key to raw) },
            onCommit = { saveBudget(field.write(budget, currentValue(field))) },
            onExactInput = { onExactInput(field) },
        )
    }

    @Composable
    fun ResetContent() {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
                CouiText(
                    text = DEFAULT_BUDGET_HINT,
                    kind = CouiTextKind.PreferenceSummary,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                CouiButton(
                    text = "恢复默认预算",
                    role = CouiButtonRole.Secondary,
                    enabled = !budget.isDefault,
                    onClick = { saveBudget(SubagentBudgetSettings()) },
                )
                error?.let {
                    CouiText(
                        text = it,
                        kind = CouiTextKind.PreferenceSummary,
                        color = StatusError,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            } else {
                Text(
                    text = DEFAULT_BUDGET_HINT,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                TextButton(
                    text = "恢复默认预算",
                    enabled = !budget.isDefault,
                    onClick = { saveBudget(SubagentBudgetSettings()) },
                )
                error?.let {
                    Text(
                        text = it,
                        style = MiuixTheme.textStyles.footnote2,
                        color = StatusError,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiScaffoldPage(
                title = "子代理预算管理",
                onBack = onBack,
            ) {
                CouiText(
                    text = budgetSummary,
                    kind = CouiTextKind.PreferenceSummary,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
                )

                BUDGET_GROUPS.forEach { group ->
                    Group(title = group.title) {
                        group.fields.forEachIndexed { index, field ->
                            if (index > 0) Divider()
                            BudgetRow(field)
                        }
                    }
                    CouiText(
                        text = group.hint,
                        kind = CouiTextKind.PreferenceSummary,
                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 6.dp),
                    )
                }

                CouiText(
                    text = "点按任一行标题可直接输入精确数值。",
                    kind = CouiTextKind.PreferenceSummary,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
                )

                Group(title = "恢复默认") { ResetContent() }

                CouiPageBottomSpacer()
            }

            CouiInputDialog(
                visible = editingField != null,
                title = editingField?.let { "${it.title}（${it.unit}）" },
                message = editingField?.let { "范围 ${it.min}～${it.ceiling(budget)} ${it.unit}" },
                value = editingText,
                onValueChange = { editingText = it.filter(Char::isDigit).take(5) },
                placeholder = editingField?.let { it.read(budget).toString() },
                confirmText = "保存",
                confirmEnabled = editingValid,
                onConfirm = confirmExactInput,
                onDismissRequest = dismissExactInput,
            )
        }

        AppearanceUiStyle.MIUIX -> {
            MiuixScaffoldPage(
                title = "子代理预算管理",
                onBack = onBack,
            ) {
                item(key = "budget_intro") {
                    Text(
                        text = budgetSummary,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }

                BUDGET_GROUPS.forEach { group ->
                    item(key = "budget_group_${group.title}") {
                        SmallTitle(group.title)
                        Card(
                            modifier = Modifier
                                .padding(horizontal = 12.dp)
                                .padding(bottom = 12.dp),
                        ) {
                            group.fields.forEach { field -> BudgetRow(field) }
                        }
                        Text(
                            text = group.hint,
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                        )
                    }
                }

                item(key = "budget_hint") {
                    Text(
                        text = "点按任一行标题可直接输入精确数值。",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                }

                item(key = "budget_reset") {
                    SmallTitle("恢复默认")
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        ResetContent()
                    }
                }
            }

            WindowDialog(
                show = editingField != null,
                title = editingField?.let { "${it.title}（${it.unit}）" },
                summary = editingField?.let { "范围 ${it.min}～${it.ceiling(budget)} ${it.unit}" },
                onDismissRequest = dismissExactInput,
            ) {
                Column {
                    TextField(
                        value = editingText,
                        onValueChange = { editingText = it.filter(Char::isDigit).take(5) },
                        label = "数值",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                    )
                    MiuixDialogActions(
                        confirmText = "保存",
                        confirmEnabled = editingValid,
                        onCancel = dismissExactInput,
                        onConfirm = confirmExactInput,
                    )
                }
            }
        }
    }
}

/**
 * 两种界面风格共享同一行数值控件：左标题、右当前值，下方滑块。
 * 标题可点，打开精确输入弹窗（对应 `onExactInput`）。
 */
@Composable
private fun BudgetSliderRow(
    field: BudgetField,
    draft: Float,
    ceiling: Int,
    displayValue: Int,
    onDraftChange: (Float) -> Unit,
    onCommit: () -> Unit,
    onExactInput: () -> Unit,
) {
    val range = field.min.toFloat()..ceiling.toFloat()
    val sliderValue = draft.coerceIn(field.min.toFloat(), ceiling.toFloat())
    val valueText = "$displayValue ${field.unit}"
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> CouiSliderPreference(
            title = field.title,
            summary = field.summary,
            value = sliderValue,
            onValueChange = onDraftChange,
            valueText = valueText,
            valueRange = range,
            showTickMarks = false,
            onValueChangeFinished = onCommit,
            onClick = onExactInput,
        )

        AppearanceUiStyle.MIUIX -> SliderPreference(
            title = field.title,
            summary = field.summary,
            value = sliderValue,
            onValueChange = onDraftChange,
            valueText = valueText,
            valueRange = range,
            onValueChangeFinished = onCommit,
            onClick = onExactInput,
        )
    }
}

/**
 * 一项预算的可编辑描述。[ceiling] 是按当前其他预算推导出的实际上限
 * （请求超时不超过任务总时长、空闲超时不超过请求超时），滑块与输入框都用它，避免拖动后被夹回。
 */
private class BudgetField(
    val key: String,
    val title: String,
    val summary: String,
    val unit: String,
    val min: Int,
    val max: Int,
    val read: (SubagentBudgetSettings) -> Int,
    val write: (SubagentBudgetSettings, Int) -> SubagentBudgetSettings,
    val ceiling: (SubagentBudgetSettings) -> Int = { max },
)

private class BudgetGroup(
    val title: String,
    val hint: String,
    val fields: List<BudgetField>,
)

private val RUN_FIELD = BudgetField(
    key = "run",
    title = "单次任务时长上限",
    summary = "开始执行后的总时长硬上限（含暂停），超时停止并保留部分结果。",
    unit = "分钟",
    min = SubagentBudgetSettings.MIN_MAX_RUN_MINUTES,
    max = SubagentBudgetSettings.MAX_MAX_RUN_MINUTES,
    read = { it.maxRunMinutes },
    write = { settings, value -> settings.copy(maxRunMinutes = value) },
)

private val REQUEST_FIELD = BudgetField(
    key = "request",
    title = "单次请求超时",
    summary = "一次模型请求超过该时长判定失败；不会超过单次任务时长上限。",
    unit = "秒",
    min = SubagentBudgetSettings.MIN_MAX_REQUEST_SECONDS,
    max = SubagentBudgetSettings.MAX_MAX_REQUEST_SECONDS,
    read = { it.maxRequestSeconds },
    write = { settings, value -> settings.copy(maxRequestSeconds = value) },
    ceiling = { settings ->
        minOf(SubagentBudgetSettings.MAX_MAX_REQUEST_SECONDS, settings.maxRunMinutes * 60)
    },
)

private val IDLE_FIELD = BudgetField(
    key = "idle",
    title = "空闲超时",
    summary = "模型请求持续没有有效输出时停止；连接心跳不算进度，工具执行另受总时长约束。",
    unit = "秒",
    min = SubagentBudgetSettings.MIN_MAX_IDLE_SECONDS,
    max = SubagentBudgetSettings.MAX_MAX_IDLE_SECONDS,
    read = { it.maxIdleSeconds },
    write = { settings, value -> settings.copy(maxIdleSeconds = value) },
    ceiling = { settings -> minOf(SubagentBudgetSettings.MAX_MAX_IDLE_SECONDS, settings.maxRequestSeconds) },
)

private val MODEL_CALLS_FIELD = BudgetField(
    key = "modelCalls",
    title = "模型调用次数上限",
    summary = "包含重试和上下文压缩；用尽后停止并把已有结果交回主代理。",
    unit = "次",
    min = SubagentBudgetSettings.MIN_MAX_MODEL_CALLS,
    max = SubagentBudgetSettings.MAX_MAX_MODEL_CALLS,
    read = { it.maxModelCalls },
    write = { settings, value -> settings.copy(maxModelCalls = value) },
)

private val TOOL_CALLS_FIELD = BudgetField(
    key = "toolCalls",
    title = "工具调用次数上限",
    summary = "工具调用累加计数，用尽后停止并保留已完成的部分。",
    unit = "次",
    min = SubagentBudgetSettings.MIN_MAX_TOOL_CALLS,
    max = SubagentBudgetSettings.MAX_MAX_TOOL_CALLS,
    read = { it.maxToolCalls },
    write = { settings, value -> settings.copy(maxToolCalls = value) },
)

private val CONCURRENT_FIELD = BudgetField(
    key = "concurrent",
    title = "同时运行的 Subagent 数量",
    summary = "同一主任务内的并发上限；超出后排队，最长等待一个任务时长。",
    unit = "个",
    min = SubagentBudgetSettings.MIN_MAX_CONCURRENT,
    max = SubagentBudgetSettings.MAX_MAX_CONCURRENT,
    read = { it.maxConcurrent },
    write = { settings, value -> settings.copy(maxConcurrent = value) },
)

private val BUDGET_GROUPS = listOf(
    BudgetGroup(
        title = "时间预算",
        hint = "请求超时与空闲超时都会自动收在任务总时长之内，不会出现“单次请求比整轮任务还长”的配置。",
        fields = listOf(RUN_FIELD, REQUEST_FIELD, IDLE_FIELD),
    ),
    BudgetGroup(
        title = "调用预算",
        hint = "调用次数用尽后停止子代理，保留已有输出，由主代理决定如何接手。",
        fields = listOf(MODEL_CALLS_FIELD, TOOL_CALLS_FIELD),
    ),
    BudgetGroup(
        title = "并发预算",
        hint = "并发上限限制同一主任务内的子代理数量，降低网络与设备资源竞争。",
        fields = listOf(CONCURRENT_FIELD),
    ),
)

private val BUDGET_FIELDS = BUDGET_GROUPS.flatMap { it.fields }

private val DEFAULT_BUDGET_HINT =
    "默认：${SubagentBudgetSettings.DEFAULT_MAX_RUN_MINUTES} 分钟、" +
        "${SubagentBudgetSettings.DEFAULT_MAX_REQUEST_SECONDS} 秒请求超时、" +
        "${SubagentBudgetSettings.DEFAULT_MAX_IDLE_SECONDS} 秒空闲超时、" +
        "${SubagentBudgetSettings.DEFAULT_MAX_MODEL_CALLS} 次模型调用、" +
        "${SubagentBudgetSettings.DEFAULT_MAX_TOOL_CALLS} 次工具调用、" +
        "${SubagentBudgetSettings.DEFAULT_MAX_CONCURRENT} 个并发。"
