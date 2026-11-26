package io.github.mangi.eta.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.config.RequestCompactionTarget
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ProviderSetting
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * The target is identified by provider.id and Model.modelId.  The local model row id is
 * intentionally not used here because the runtime sends the API model name to the provider.
 */
internal data class RequestCompactionTargetDetails(
    val provider: ProviderSetting,
    val model: Model,
)

internal fun List<ProviderSetting>.findEnabledRequestCompactionTarget(
    target: RequestCompactionTarget?,
): RequestCompactionTargetDetails? {
    target ?: return null
    val provider = firstOrNull { it.isEnabled && it.id == target.providerId } ?: return null
    val model = provider.models.firstOrNull { it.isEnabled && it.modelId == target.modelId } ?: return null
    return RequestCompactionTargetDetails(provider = provider, model = model)
}

/** Match the enabled picker choices; IDs are only added when their displayed names collide. */
internal fun ProviderSetting.requestCompactionLabel(providers: List<ProviderSetting>): String {
    val label = name.ifBlank { id }
    val duplicateName = providers.count { it.isEnabled && it.name.ifBlank { it.id } == label } > 1
    return if (duplicateName) "$label ($id)" else label
}

internal fun Model.requestCompactionLabel(models: List<Model>): String {
    val label = displayName.ifBlank { modelId }
    val duplicateName = models.count { it.isEnabled && it.displayName.ifBlank { it.modelId } == label } > 1
    return if (duplicateName) "$label ($modelId)" else label
}

@Composable
internal fun RequestCompactionTargetPicker(
    providers: List<ProviderSetting>,
    selectedProviderId: String?,
    selectedModelId: String?,
    enabled: Boolean,
    showTargetError: Boolean,
    onProviderSelected: (String?) -> Unit,
    onModelSelected: (String?) -> Unit,
    onClear: () -> Unit,
) {
    val enabledProviders = providers
        .filter { it.isEnabled }
        .sortedWith(compareBy<ProviderSetting> { it.sortOrder }.thenBy { it.id })
    val selectedProvider = enabledProviders.firstOrNull { it.id == selectedProviderId }
    val selectedProviderIsUnavailable = selectedProviderId != null && selectedProvider == null
    val selectedModel = selectedProvider?.models
        ?.filter { it.isEnabled }
        ?.sortedWith(compareBy<Model> { it.sortOrder }.thenBy { it.modelId })
        ?.firstOrNull { it.modelId == selectedModelId }
    val selectedModelIsUnavailable = selectedModelId != null && selectedModel == null
    val noneLabel = stringResource(R.string.settings_request_compaction_target_none)
    val unavailableProviderLabel = stringResource(
        R.string.settings_request_compaction_target_unavailable,
        selectedProviderId.orEmpty(),
    )
    val unavailableModelLabel = stringResource(
        R.string.settings_request_compaction_target_unavailable,
        selectedModelId.orEmpty(),
    )

    val providerItems = buildList {
        add(noneLabel)
        if (selectedProviderIsUnavailable) {
            add(unavailableProviderLabel)
        }
        addAll(enabledProviders.map { it.requestCompactionLabel(enabledProviders) })
    }
    val providerSelectedIndex = when {
        selectedProviderId == null -> 0
        selectedProviderIsUnavailable -> 1
        else -> enabledProviders.indexOfFirst { it.id == selectedProviderId } + 1
    }.coerceIn(0, providerItems.lastIndex.coerceAtLeast(0))

    val enabledModels = selectedProvider?.models
        ?.filter { it.isEnabled }
        ?.sortedWith(compareBy<Model> { it.sortOrder }.thenBy { it.modelId })
        .orEmpty()
    val modelItems = buildList {
        add(noneLabel)
        if (selectedModelIsUnavailable) {
            add(unavailableModelLabel)
        }
        addAll(enabledModels.map { it.requestCompactionLabel(enabledModels) })
    }
    val modelSelectedIndex = when {
        selectedModelId == null -> 0
        selectedModelIsUnavailable -> 1
        else -> enabledModels.indexOfFirst { it.modelId == selectedModelId } + 1
    }.coerceIn(0, modelItems.lastIndex.coerceAtLeast(0))

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_request_compaction_target),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(top = 4.dp),
        )
        GlassDropdownPreference(
            title = stringResource(R.string.settings_request_compaction_provider),
            items = providerItems,
            selectedIndex = providerSelectedIndex,
            backdrop = io.github.mangi.eta.ui.components.currentGlassOverlayBackdrop(),
            enabled = enabled,
            onSelectedIndexChange = { index ->
                when {
                    index == 0 -> onClear()
                    selectedProviderIsUnavailable && index == 1 -> Unit
                    else -> {
                        val providerIndex = index - 1 - if (selectedProviderIsUnavailable) 1 else 0
                        enabledProviders.getOrNull(providerIndex)?.let { provider ->
                            onProviderSelected(provider.id)
                        }
                    }
                }
            },
        )
        GlassDropdownPreference(
            title = stringResource(R.string.settings_request_compaction_model),
            items = modelItems,
            selectedIndex = modelSelectedIndex,
            backdrop = io.github.mangi.eta.ui.components.currentGlassOverlayBackdrop(),
            enabled = enabled && selectedProvider != null,
            onSelectedIndexChange = { index ->
                when {
                    index == 0 -> onModelSelected(null)
                    selectedModelIsUnavailable && index == 1 -> Unit
                    else -> {
                        val modelIndex = index - 1 - if (selectedModelIsUnavailable) 1 else 0
                        enabledModels.getOrNull(modelIndex)?.let { model ->
                            onModelSelected(model.modelId)
                        }
                    }
                }
            },
        )
        selectedModelId?.takeIf { it.isNotBlank() }?.let { modelId ->
            Text(
                text = stringResource(R.string.settings_request_compaction_api_model_id, modelId),
                style = MiuixTheme.textStyles.footnote2,
                color = if (selectedModelIsUnavailable) {
                    MiuixTheme.colorScheme.error
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        if (selectedProvider != null && selectedModel != null) {
            Text(
                text = stringResource(
                    R.string.settings_request_compaction_target_selected,
                    selectedProvider.requestCompactionLabel(enabledProviders),
                    selectedModel.requestCompactionLabel(enabledModels),
                ),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        if (showTargetError) {
            Text(
                text = stringResource(R.string.settings_request_compaction_target_required),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        BasicComponent(
            title = stringResource(R.string.settings_request_compaction_target_clear),
            enabled = enabled && (selectedProviderId != null || selectedModelId != null),
            onClick = onClear,
        )
    }
}
