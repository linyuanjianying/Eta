package io.github.mangi.eta.ui.screens.settings

import io.github.mangi.eta.config.RequestCompactionTarget
import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RequestCompactionTargetPickerTest {
    @Test
    fun resolvesExactProviderIdAndApiModelId() {
        val provider = provider(
            id = "provider-id",
            name = "Friendly provider name",
            model = Model(
                id = "database-row-id",
                modelId = "vendor/api-model-name",
                displayName = "Friendly model name",
            ),
        )

        val resolved = listOf(provider).findEnabledRequestCompactionTarget(
            RequestCompactionTarget(providerId = "provider-id", modelId = "vendor/api-model-name"),
        )

        assertEquals(provider, resolved?.provider)
        assertEquals("vendor/api-model-name", resolved?.model?.modelId)
    }

    @Test
    fun rejectsProviderNamesModelDisplayNamesAndRowIdsAsIdentities() {
        val provider = provider(
            id = "provider-id",
            name = "Friendly provider name",
            model = Model(
                id = "database-row-id",
                modelId = "vendor/api-model-name",
                displayName = "Friendly model name",
            ),
        )

        assertNull(
            listOf(provider).findEnabledRequestCompactionTarget(
                RequestCompactionTarget(providerId = "Friendly provider name", modelId = "vendor/api-model-name"),
            ),
        )
        assertNull(
            listOf(provider).findEnabledRequestCompactionTarget(
                RequestCompactionTarget(providerId = "provider-id", modelId = "Friendly model name"),
            ),
        )
        assertNull(
            listOf(provider).findEnabledRequestCompactionTarget(
                RequestCompactionTarget(providerId = "provider-id", modelId = "database-row-id"),
            ),
        )
    }

    @Test
    fun rejectsMissingOrDisabledProvidersAndModelsWithoutFallback() {
        val enabledProvider = provider(
            id = "enabled-provider",
            name = "Enabled",
            model = Model(id = "enabled-row", modelId = "enabled-api-model", displayName = "Enabled model"),
        )
        val disabledModelProvider = provider(
            id = "provider-with-disabled-model",
            name = "Disabled model provider",
            model = Model(
                id = "disabled-row",
                modelId = "disabled-api-model",
                displayName = "Disabled model",
                isEnabled = false,
            ),
        )
        val disabledProvider = provider(
            id = "disabled-provider",
            name = "Disabled provider",
            model = Model(id = "disabled-provider-row", modelId = "disabled-provider-model", displayName = "Model"),
            isEnabled = false,
        )
        val providers: List<ProviderSetting> = listOf(enabledProvider, disabledModelProvider, disabledProvider)

        assertNull(providers.findEnabledRequestCompactionTarget(null))
        assertNull(
            providers.findEnabledRequestCompactionTarget(
                RequestCompactionTarget("deleted-provider", "enabled-api-model"),
            ),
        )
        assertNull(
            providers.findEnabledRequestCompactionTarget(
                RequestCompactionTarget("provider-with-disabled-model", "disabled-api-model"),
            ),
        )
        assertNull(
            providers.findEnabledRequestCompactionTarget(
                RequestCompactionTarget("disabled-provider", "disabled-provider-model"),
            ),
        )
    }

    @Test
    fun duplicateProviderAndModelLabelsAreUniqueAndKeepExactTargetMappings() {
        val models = listOf(
            Model(id = "row-b", modelId = "vendor/api-model-b", displayName = "Shared model"),
            Model(id = "row-a", modelId = "vendor/api-model-a", displayName = "Shared model"),
        )
        val providers = listOf(
            provider(id = "provider-b", name = "Shared provider", models = models),
            provider(id = "provider-a", name = "Shared provider", models = models.reversed()),
        )
        val providerLabels = providers.map { it.requestCompactionLabel(providers) }

        assertEquals(listOf("Shared provider (provider-b)", "Shared provider (provider-a)"), providerLabels)
        assertEquals(providers.size, providerLabels.distinct().size)
        providers.forEach { provider ->
            val providerLabel = "Shared provider (${provider.id})"
            val selectedProvider = providers[providerLabels.indexOf(providerLabel)]
            assertEquals(provider.id, selectedProvider.id)
            assertEquals(providerLabel, provider.requestCompactionLabel(providers.reversed()))
            val modelLabels = selectedProvider.models.map { it.requestCompactionLabel(selectedProvider.models) }
            assertEquals(selectedProvider.models.size, modelLabels.distinct().size)
            selectedProvider.models.forEach { model ->
                val modelLabel = "Shared model (${model.modelId})"
                val selectedModel = selectedProvider.models[modelLabels.indexOf(modelLabel)]
                assertEquals(model.modelId, selectedModel.modelId)
                assertEquals(modelLabel, model.requestCompactionLabel(selectedProvider.models.reversed()))
                val resolved = providers.findEnabledRequestCompactionTarget(
                    RequestCompactionTarget(selectedProvider.id, selectedModel.modelId),
                )
                assertEquals(provider.id, resolved?.provider?.id)
                assertEquals(model.modelId, resolved?.model?.modelId)
                // The settings summary/dialog use the same labels as their dropdown choices.
                assertEquals(providerLabel, resolved?.provider?.requestCompactionLabel(providers))
                assertEquals(modelLabel, resolved?.model?.requestCompactionLabel(provider.models))
            }
        }
    }

    @Test
    fun uniqueEnabledNamesStayUnchangedWhenDisabledEntriesHaveTheSameNames() {
        val model = Model(id = "row-a", modelId = "vendor/api-model-a", displayName = "Shared model")
        val models = listOf(model, model.copy(id = "row-b", modelId = "vendor/api-model-b", isEnabled = false))
        val enabledProvider = provider(id = "enabled-provider", name = "Shared provider", models = models)
        val providers = listOf(
            enabledProvider,
            provider(id = "disabled-provider", name = "Shared provider", model = model, isEnabled = false),
        )

        assertEquals("Shared provider", enabledProvider.requestCompactionLabel(providers))
        assertEquals("Shared model", model.requestCompactionLabel(models))
    }

    @Test
    fun duplicateModelNamesAreScopedToTheirOwnProvider() {
        val firstModel = Model(id = "row-a", modelId = "vendor/api-model-a", displayName = "Shared model")
        val secondModel = Model(id = "row-b", modelId = "vendor/api-model-b", displayName = "Shared model")
        val providers = listOf(
            provider(id = "provider-a", name = "Provider A", model = firstModel),
            provider(id = "provider-b", name = "Provider B", model = secondModel),
        )

        assertEquals(listOf("Provider A", "Provider B"), providers.map { it.requestCompactionLabel(providers) })
        assertEquals(
            listOf("Shared model", "Shared model"),
            providers.map { it.models.single().requestCompactionLabel(it.models) },
        )
    }

    @Test
    fun blankNamesKeepIdentityFallbacksAndDetectCollisionsWithDisplayedFallbacks() {
        val unnamedModel = Model(id = "row-a", modelId = "vendor/api-model-a", displayName = "")
        val unnamedProvider = provider(id = "provider-a", name = "", model = unnamedModel)
        assertEquals("provider-a", unnamedProvider.requestCompactionLabel(listOf(unnamedProvider)))
        assertEquals("vendor/api-model-a", unnamedModel.requestCompactionLabel(unnamedProvider.models))

        val providers = listOf(
            unnamedProvider,
            provider(id = "provider-b", name = "provider-a", model = unnamedModel),
        )
        val models = listOf(
            unnamedModel,
            Model(id = "row-b", modelId = "vendor/api-model-b", displayName = "vendor/api-model-a"),
        )
        assertEquals(
            listOf("provider-a (provider-a)", "provider-a (provider-b)"),
            providers.map { it.requestCompactionLabel(providers) },
        )
        assertEquals(
            listOf("vendor/api-model-a (vendor/api-model-a)", "vendor/api-model-a (vendor/api-model-b)"),
            models.map { it.requestCompactionLabel(models) },
        )
    }

    private fun provider(
        id: String,
        name: String,
        model: Model,
        isEnabled: Boolean = true,
    ): ProviderSetting = provider(id = id, name = name, models = listOf(model), isEnabled = isEnabled)

    private fun provider(
        id: String,
        name: String,
        models: List<Model>,
        isEnabled: Boolean = true,
    ): ProviderSetting = CustomProviderSetting(
        id = id,
        name = name,
        baseUrl = "https://example.invalid/v1",
        isEnabled = isEnabled,
        models = models,
    )
}
