package com.nuvio.tv.ui.screens.livetv

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.GridContentCard
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.PosterCardStyle
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.localizedGenreLabel

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class, ExperimentalTvMaterial3Api::class)
@Composable
fun LiveTvScreen(
    showBuiltInHeader: Boolean = true,
    viewModel: LiveTvViewModel = hiltViewModel(),
    onChannelSelected: (MetaPreview) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val channels = remember(uiState.catalogs) {
        val collected = linkedMapOf<String, LiveTvChannel>()
        uiState.catalogs.forEach { catalog ->
            val sourceAddonKey = catalog.addon.liveTvSourceKey()
            catalog.row?.items.orEmpty().forEach { item ->
                val key = "${item.apiType}:${item.id}"
                val current = collected[key]
                collected[key] = if (current == null) {
                    LiveTvChannel(item, setOf(sourceAddonKey))
                } else {
                    current.copy(sourceAddonKeys = current.sourceAddonKeys + sourceAddonKey)
                }
            }
        }
        collected.values.toList()
    }
    val addons = remember(uiState.catalogs) {
        uiState.catalogs.map { it.addon }.distinctBy { it.liveTvSourceKey() }
    }
    // shortcut: categories come from loaded metadata, query manifest extras when an add-on only exposes remote options.
    val genres = remember(channels) {
        channels.flatMap { it.item.genres }
            .distinctBy { it.lowercase() }
            .sortedBy { it.lowercase() }
    }
    val filteredChannels = remember(channels, uiState.selectedAddonBaseUrl, uiState.selectedGenre) {
        channels.filter { channel ->
            (uiState.selectedAddonBaseUrl == null || uiState.selectedAddonBaseUrl in channel.sourceAddonKeys) &&
                (uiState.selectedGenre == null || channel.item.genres.any {
                    it.equals(uiState.selectedGenre, ignoreCase = true)
                })
        }
    }
    val gridState = rememberLazyGridState()
    val itemFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    var focusedChannelKey by rememberSaveable { mutableStateOf<String?>(null) }
    var focusFirstChannel by rememberSaveable { mutableStateOf(true) }
    val focusTarget = filteredChannels.firstOrNull { it.key == focusedChannelKey }
        ?: filteredChannels.firstOrNull()
    val focusTargetRequester = remember(focusTarget?.key) {
        focusTarget?.let { itemFocusRequesters.getOrPut(it.key) { FocusRequester() } }
            ?: FocusRequester()
    }
    val channelCardStyle = remember {
        PosterCardStyle(width = 220.dp, height = 124.dp, cornerRadius = 12.dp)
    }

    LaunchedEffect(uiState.selectedAddonBaseUrl, uiState.selectedGenre) {
        gridState.scrollToItem(0)
    }

    LaunchedEffect(focusFirstChannel, focusTarget?.key) {
        if (!focusFirstChannel || focusTarget == null) return@LaunchedEffect
        if (focusTarget.key != focusedChannelKey) {
            focusedChannelKey = focusTarget.key
        }
        val focused = focusTargetRequester.requestFocusAfterFrames(frames = 3)
        if (focused) focusFirstChannel = false
    }

    LaunchedEffect(gridState.firstVisibleItemIndex, filteredChannels.size, uiState.catalogs) {
        val lastVisibleIndex = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@LaunchedEffect
        val hasMore = uiState.catalogs.any { it.row?.hasMore == true }
        val loadingMore = uiState.catalogs.any { it.row != null && it.isLoading }
        if (filteredChannels.isNotEmpty() && hasMore && !loadingMore &&
            lastVisibleIndex >= filteredChannels.size - 6
        ) {
            viewModel.loadMore(uiState.selectedAddonBaseUrl)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.Background)
            .padding(top = NuvioTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        Text(
            text = stringResource(R.string.live_tv_title),
            modifier = Modifier.padding(horizontal = NuvioTheme.spacing.xxxl),
            style = MaterialTheme.typography.headlineMedium,
            color = if (showBuiltInHeader) NuvioTheme.colors.TextPrimary else Color.Transparent
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NuvioTheme.spacing.xxxl),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
            val selectedAddonName = addons.firstOrNull {
                it.liveTvSourceKey() == uiState.selectedAddonBaseUrl
            }?.displayName
                ?: stringResource(R.string.live_tv_filter_all_addons)
            LiveTvFilterPicker(
                modifier = Modifier.weight(1f),
                title = stringResource(R.string.live_tv_filter_addon),
                selectedLabel = selectedAddonName,
                options = buildList {
                    add(FilterOption(stringResource(R.string.live_tv_filter_all_addons), null))
                    addAll(addons.map { FilterOption(it.displayName, it.liveTvSourceKey()) })
                },
                onSelect = { addonId ->
                    focusedChannelKey = null
                    viewModel.selectAddon(addonId)
                }
            )
            val selectedGenreName = genres.firstOrNull {
                it.equals(uiState.selectedGenre, ignoreCase = true)
            }?.let { localizedGenreLabel(it) } ?: stringResource(R.string.live_tv_filter_all_categories)
            LiveTvFilterPicker(
                modifier = Modifier.weight(1f),
                title = stringResource(R.string.live_tv_filter_category),
                selectedLabel = selectedGenreName,
                options = buildList {
                    add(FilterOption(stringResource(R.string.live_tv_filter_all_categories), null))
                    addAll(genres.map { FilterOption(localizedGenreLabel(it), it) })
                },
                onSelect = { genre ->
                    focusedChannelKey = null
                    viewModel.selectGenre(genre)
                }
            )
            if (uiState.catalogs.any { it.failed } && uiState.catalogs.none { it.isLoading }) {
                Button(onClick = viewModel::retry) {
                    Text(stringResource(R.string.live_tv_retry))
                }
            }
        }

        when {
            uiState.isLoading && channels.isEmpty() -> Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center
            ) {
                LoadingIndicator()
            }

            uiState.catalogs.isEmpty() -> EmptyScreenState(
                title = stringResource(R.string.live_tv_no_addons_title),
                subtitle = stringResource(R.string.live_tv_no_addons_subtitle),
                icon = Icons.Default.LiveTv,
                modifier = Modifier.weight(1f)
            )

            channels.isEmpty() && uiState.catalogs.any { it.failed } -> Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                EmptyScreenState(
                    title = stringResource(R.string.live_tv_load_failed),
                    icon = Icons.Default.LiveTv,
                    height = 220.dp
                )
                Button(onClick = viewModel::retry) {
                    Text(stringResource(R.string.live_tv_retry))
                }
            }

            channels.isEmpty() -> EmptyScreenState(
                title = stringResource(R.string.live_tv_no_channels_title),
                subtitle = stringResource(R.string.live_tv_no_channels_subtitle),
                icon = Icons.Default.LiveTv,
                modifier = Modifier.weight(1f)
            )

            filteredChannels.isEmpty() -> EmptyScreenState(
                title = stringResource(R.string.live_tv_no_filtered_channels),
                icon = Icons.Default.LiveTv,
                modifier = Modifier.weight(1f)
            )

            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = channelCardStyle.width),
                    state = gridState,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = NuvioTheme.spacing.xxxl)
                        .focusGroup()
                        .focusRestorer(focusTargetRequester),
                    contentPadding = PaddingValues(bottom = NuvioTheme.spacing.xxl),
                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
                ) {
                    itemsIndexed(
                        items = filteredChannels,
                        key = { _, channel -> channel.key },
                        contentType = { _, _ -> "live_tv_channel" }
                    ) { _, channel ->
                        val requester = itemFocusRequesters.getOrPut(channel.key) { FocusRequester() }
                        GridContentCard(
                            item = channel.item.copy(posterShape = PosterShape.LANDSCAPE),
                            onClick = { onChannelSelected(channel.item) },
                            posterCardStyle = channelCardStyle,
                            showLogo = true,
                            modifier = Modifier.padding(top = 3.dp),
                            focusRequester = requester,
                            onFocused = { focusedChannelKey = channel.key }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LiveTvFilterPicker(
    modifier: Modifier,
    title: String,
    selectedLabel: String,
    options: List<FilterOption>,
    onSelect: (String?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = NuvioTheme.colors.TextTertiary
        )
        Spacer(Modifier.height(NuvioTheme.spacing.xxs))
        Box {
            Button(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(selectedLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
                }
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.widthIn(min = 220.dp)
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = {
                            expanded = false
                            onSelect(option.value)
                        }
                    )
                }
            }
        }
    }
}

private data class FilterOption(val label: String, val value: String?)

private data class LiveTvChannel(
    val item: MetaPreview,
    val sourceAddonKeys: Set<String>
) {
    val key: String
        get() = "${item.apiType}:${item.id}"
}
