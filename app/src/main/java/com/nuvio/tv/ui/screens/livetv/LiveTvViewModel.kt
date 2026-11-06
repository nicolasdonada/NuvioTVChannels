package com.nuvio.tv.ui.screens.livetv

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.poster.CustomPosterScreen
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.model.skipStep
import com.nuvio.tv.domain.model.supportsExtra
import com.nuvio.tv.domain.model.mergeCatalogPage
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject

@Immutable
internal data class LiveTvCatalogState(
    val addon: Addon,
    val catalog: CatalogDescriptor,
    val row: CatalogRow? = null,
    val isLoading: Boolean = true,
    val failed: Boolean = false
) {
    val key: String
        get() = listOf(addon.id, addon.liveTvSourceKey(), catalog.apiType, catalog.id)
            .joinToString("\u0000")
}

@Immutable
internal data class LiveTvUiState(
    val catalogs: List<LiveTvCatalogState> = emptyList(),
    val isLoading: Boolean = true,
    val selectedAddonBaseUrl: String? = null,
    val selectedGenre: String? = null
)

@HiltViewModel
class LiveTvViewModel @Inject constructor(
    private val addonRepository: AddonRepository,
    private val catalogRepository: CatalogRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(LiveTvUiState())
    internal val uiState: StateFlow<LiveTvUiState> = _uiState.asStateFlow()

    private var installedAddons: List<Addon> = emptyList()

    init {
        viewModelScope.launch {
            addonRepository.getInstalledAddons()
                .distinctUntilChanged()
                .collectLatest { addons ->
                    installedAddons = addons.enabledAddons()
                    loadChannelCatalogs(installedAddons)
                }
        }
    }

    fun selectAddon(addonBaseUrl: String?) {
        _uiState.update { it.copy(selectedAddonBaseUrl = addonBaseUrl) }
    }

    fun selectGenre(genre: String?) {
        _uiState.update { it.copy(selectedGenre = genre) }
    }

    fun retry() {
        if (installedAddons.isEmpty()) return
        viewModelScope.launch { loadChannelCatalogs(installedAddons) }
    }

    fun loadMore(addonBaseUrl: String?) {
        val eligible = _uiState.value.catalogs.filter {
            (addonBaseUrl == null || it.addon.liveTvSourceKey() == addonBaseUrl) &&
                it.row?.hasMore == true && !it.isLoading && !it.failed
        }
        if (eligible.isEmpty()) return
        val keys = eligible.mapTo(mutableSetOf()) { it.key }
        _uiState.update { state ->
            state.copy(catalogs = state.catalogs.map { if (it.key in keys) it.copy(isLoading = true) else it })
        }
        viewModelScope.launch {
            val semaphore = Semaphore(MAX_PARALLEL_CATALOGS)
            coroutineScope {
                eligible.map { catalog ->
                    async { semaphore.withPermit { fetchCatalogPage(catalog, catalog.row!!.nextSkip) } }
                }.awaitAll()
            }
        }
    }

    private suspend fun loadChannelCatalogs(addons: List<Addon>) {
        val catalogs = addons.flatMap { addon ->
            addon.catalogs
                .filter { it.apiType.equals(CHANNEL_TYPE, ignoreCase = true) }
                .map { LiveTvCatalogState(addon, it) }
        }.distinctBy { it.key }
        val previousAddonBaseUrl = _uiState.value.selectedAddonBaseUrl
        _uiState.value = LiveTvUiState(
            catalogs = catalogs,
            isLoading = catalogs.isNotEmpty(),
            selectedAddonBaseUrl = previousAddonBaseUrl?.takeIf { baseUrl ->
                catalogs.any { it.addon.liveTvSourceKey() == baseUrl }
            }
        )
        if (catalogs.isEmpty()) return

        val semaphore = Semaphore(MAX_PARALLEL_CATALOGS)
        try {
            coroutineScope {
                catalogs.map { catalog ->
                    async { semaphore.withPermit { fetchCatalogPage(catalog, skip = 0) } }
                }.awaitAll()
            }
        } finally {
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    private suspend fun fetchCatalogPage(catalog: LiveTvCatalogState, skip: Int) {
        try {
            when (val result = catalogRepository.getCatalog(
                addonBaseUrl = catalog.addon.baseUrl,
                addonId = catalog.addon.id,
                addonName = catalog.addon.displayName,
                catalogId = catalog.catalog.id,
                catalogName = catalog.catalog.name,
                type = catalog.catalog.apiType,
                skip = skip,
                skipStep = catalog.catalog.skipStep(),
                supportsSkip = catalog.catalog.supportsExtra("skip"),
                posterScreen = CustomPosterScreen.SEARCH
            ).first { it !is NetworkResult.Loading }) {
                is NetworkResult.Success -> _uiState.update { state ->
                    state.copy(catalogs = state.catalogs.map { current ->
                        if (current.key != catalog.key) current else current.copy(
                            row = current.row?.mergeCatalogPage(result.data) ?: result.data,
                            isLoading = false,
                            failed = false
                        )
                    })
                }
                is NetworkResult.Error -> markCatalogFailed(catalog.key)
                NetworkResult.Loading -> Unit
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            markCatalogFailed(catalog.key)
        }
    }

    private fun markCatalogFailed(key: String) {
        _uiState.update { state ->
            state.copy(catalogs = state.catalogs.map { current ->
                if (current.key == key) current.copy(isLoading = false, failed = true) else current
            })
        }
    }

    private companion object {
        const val CHANNEL_TYPE = "channel"
        const val MAX_PARALLEL_CATALOGS = 4
    }
}

internal fun Addon.liveTvSourceKey(): String = baseUrl.trimEnd('/')
