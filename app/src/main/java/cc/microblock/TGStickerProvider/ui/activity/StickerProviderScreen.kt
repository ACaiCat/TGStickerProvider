package cc.microblock.TGStickerProvider.ui.activity

import android.os.Build
import android.widget.ImageView
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExpandedFullScreenContainedSearchBar
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberContainedSearchBarState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bumptech.glide.Glide
import cc.microblock.TGStickerProvider.R
import kotlinx.coroutines.launch

private val accent = Color(0xFF168B81)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun StickerProviderScreen(
    moduleActive: Boolean,
    version: String,
    progress: OperationProgress?,
    refreshing: Boolean,
    deleteConfirmation: DeleteConfirmation?,
    qualitySelections: Map<String, SyncQuality>,
    stickers: List<StickerInfo>,
    search: String,
    onSearchChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onSyncAll: () -> Unit,
    onReset: () -> Unit,
    onSync: (StickerInfo) -> Unit,
    onQualityChange: (StickerInfo, SyncQuality) -> Unit,
    onDeleteSynced: (StickerInfo) -> Unit,
    onDeleteExported: (StickerInfo) -> Unit,
    onConfirmDelete: () -> Unit,
    onDismissDelete: () -> Unit
) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Color(0xFF74D8CB))
        else -> lightColorScheme(primary = accent, background = Color(0xFFF5F8F7), surface = Color.White)
    }
    val safeArea = WindowInsets.safeDrawing.asPaddingValues()
    val fabLift = with(LocalDensity.current) { 64.toDp() }
    val refreshState = rememberPullToRefreshState()
    val isRefreshing = refreshing
    val layoutDirection = LocalLayoutDirection.current
    val safeStart = if (layoutDirection == LayoutDirection.Ltr) {
        safeArea.calculateLeftPadding(layoutDirection)
    } else {
        safeArea.calculateRightPadding(layoutDirection)
    }
    val safeEnd = if (layoutDirection == LayoutDirection.Ltr) {
        safeArea.calculateRightPadding(layoutDirection)
    } else {
        safeArea.calculateLeftPadding(layoutDirection)
    }
    MaterialTheme(colorScheme = colors) {
        val searchBarState = rememberContainedSearchBarState()
        val textFieldState = rememberTextFieldState(search)
        val searchScope = rememberCoroutineScope()
        val searchColors = SearchBarDefaults.containedColors(state = searchBarState)
        val searchFocused = searchBarState.targetValue == SearchBarValue.Expanded
        LaunchedEffect(textFieldState) {
            snapshotFlow { textFieldState.text.toString() }.collect { onSearchChange(it) }
        }
        val searchInputField: @Composable () -> Unit = {
            SearchBarDefaults.InputField(
                textFieldState = textFieldState,
                searchBarState = searchBarState,
                onSearch = { searchScope.launch { searchBarState.animateToCollapsed() } },
                placeholder = { Text("名称、ID 或格式") },
                colors = searchColors.inputFieldColors,
                leadingIcon = {
                    IconButton(onClick = {
                        searchScope.launch {
                            if (searchBarState.targetValue == SearchBarValue.Expanded) searchBarState.animateToCollapsed()
                            else searchBarState.animateToExpanded()
                        }
                    }) {
                        Icon(
                            painterResource(if (searchFocused) R.drawable.ic_arrow_back else R.drawable.ic_search),
                            contentDescription = if (searchFocused) "返回列表" else "搜索"
                        )
                    }
                },
                trailingIcon = {
                    if (textFieldState.text.isNotEmpty()) {
                        IconButton(onClick = { textFieldState.setTextAndPlaceCursorAtEnd("") }) {
                            Icon(painterResource(R.drawable.ic_close), contentDescription = "清除搜索")
                        }
                    }
                }
            )
        }
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(modifier = Modifier.fillMaxSize()) {
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = onRefresh,
                    modifier = Modifier.fillMaxSize(),
                    state = refreshState,
                    indicator = {
                        PullToRefreshDefaults.LoadingIndicator(
                            state = refreshState,
                            isRefreshing = isRefreshing,
                            modifier = Modifier.align(Alignment.TopCenter)
                                .padding(top = safeArea.calculateTopPadding())
                        )
                    }
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().imePadding(),
                        contentPadding = PaddingValues(
                            start = safeStart + 16.dp,
                            top = safeArea.calculateTopPadding() + 8.dp,
                            end = safeEnd + 16.dp,
                            bottom = safeArea.calculateBottomPadding() +
                                if (searchFocused) 16.dp else 80.dp + fabLift
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item { Text("表情包管理", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
                        item { StatusCard(moduleActive, version) }
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("表情包列表", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                    Spacer(Modifier.width(8.dp))
                                    Text("${stickers.size}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.weight(1f))
                                    TextButton(onClick = onReset) { Text("清除缓存") }
                                }
                                SearchBar(
                                    state = searchBarState,
                                    inputField = searchInputField,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = searchColors
                                )
                            }
                        }
                        if (stickers.isEmpty()) {
                            item {
                                Card(shape = RoundedCornerShape(20.dp)) {
                                    Text(
                                        if (search.isNotBlank()) "没有符合条件的表情包" else "暂无表情包，打开 Telegram 加载表情后刷新列表。",
                                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else {
                            items(stickers, key = { it.hash }) { info ->
                                StickerCard(
                                    info = info,
                                    quality = effectiveSyncQuality(info, qualitySelections[info.id]),
                                    onQualityChange = onQualityChange,
                                    onSync = onSync,
                                    onDeleteSynced = onDeleteSynced,
                                    onDeleteExported = onDeleteExported
                                )
                            }
                        }
                    }
                }
                if (!searchFocused) {
                    Column(
                        modifier = Modifier.align(Alignment.BottomEnd)
                            .imePadding()
                            .padding(end = safeEnd + 16.dp, bottom = safeArea.calculateBottomPadding() + 16.dp + fabLift),
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        ExtendedFloatingActionButton(
                            onClick = onSyncAll,
                            icon = { Icon(painterResource(R.drawable.ic_sync), contentDescription = null) },
                            text = { Text("同步全部") }
                        )
                    }
                }
            }
        }
        ExpandedFullScreenContainedSearchBar(
            state = searchBarState,
            inputField = searchInputField,
            colors = searchColors
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().imePadding(),
                contentPadding = PaddingValues(
                    start = safeStart + 16.dp,
                    top = 12.dp,
                    end = safeEnd + 16.dp,
                    bottom = safeArea.calculateBottomPadding() + 16.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (stickers.isEmpty()) {
                    item {
                        Text(
                            if (search.isNotBlank()) "没有符合条件的表情包" else "暂无表情包",
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    items(stickers, key = { it.hash }) { info ->
                        StickerCard(
                            info = info,
                            quality = effectiveSyncQuality(info, qualitySelections[info.id]),
                            onQualityChange = onQualityChange,
                            onSync = onSync,
                            onDeleteSynced = onDeleteSynced,
                            onDeleteExported = onDeleteExported
                        )
                    }
                }
            }
        }
        progress?.let { OperationProgressDialog(it) }
        deleteConfirmation?.let {
            DeleteConfirmationDialog(it, onConfirmDelete, onDismissDelete)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun OperationProgressDialog(progress: OperationProgress) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
    ) {
        Surface(shape = RoundedCornerShape(28.dp), tonalElevation = 6.dp) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (progress.expressive) {
                        LoadingIndicator(modifier = Modifier.size(48.dp))
                    } else {
                        CircularProgressIndicator(modifier = Modifier.size(40.dp))
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(progress.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (progress.detail.isNotBlank()) {
                            Text(progress.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                val fraction = progress.fraction
                if (fraction == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(
                        progress = { fraction.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun DeleteConfirmationDialog(
    confirmation: DeleteConfirmation,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Box(
                modifier = Modifier.size(56.dp)
                    .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painterResource(confirmation.iconRes),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(28.dp)
                )
            }
        },
        title = {
            Text(
                confirmation.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = { Text(confirmation.message, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) { Text(confirmation.confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    )
}

@Composable
private fun StatusCard(active: Boolean, version: String) {
    val background = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
    val foreground = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = background)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                painterResource(if (active) R.drawable.ic_check else R.drawable.ic_close),
                contentDescription = null,
                tint = foreground,
                modifier = Modifier.size(24.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (active) "模块已激活" else "模块可能未激活",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = foreground
                )
                Text("$version · Telegram 表情包管理", style = MaterialTheme.typography.bodySmall, color = foreground)
            }
        }
    }
}

@Composable
private fun StickerCard(
    info: StickerInfo,
    quality: SyncQuality,
    onQualityChange: (StickerInfo, SyncQuality) -> Unit,
    onSync: (StickerInfo) -> Unit,
    onDeleteSynced: (StickerInfo) -> Unit,
    onDeleteExported: (StickerInfo) -> Unit
) {
    val supported = info.type == "unknown" || info.type == "image/webp" || info.type == "video/webm"
    val actionShape = CircleShape
    val actionSize = Modifier.size(width = 52.dp, height = 40.dp)
    Card(shape = RoundedCornerShape(20.dp), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                StickerPreview(info)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(info.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(info.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!supported) Text("不支持的格式", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CountCell("已同步", info.syncedState, Modifier.weight(1f))
                CountCell("已导出", info.remoteState, Modifier.weight(1f))
                Column(Modifier.weight(0.7f)) {
                    Text("总数", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${info.all}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (supported) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Switch(
                            checked = quality == SyncQuality.HIGH,
                            onCheckedChange = { high ->
                                onQualityChange(info, if (high) SyncQuality.HIGH else SyncQuality.LOW)
                            },
                            enabled = info.remoteState.highQuality > 0 && info.remoteState.lowQuality > 0,
                            modifier = Modifier.semantics {
                                contentDescription = "同步画质：${if (quality == SyncQuality.HIGH) "高清" else "低清"}"
                            }
                        )
                        Text(
                            if (quality == SyncQuality.HIGH) "高清" else "低清",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                FilledTonalIconButton(
                    onClick = { onDeleteExported(info) },
                    modifier = actionSize,
                    shape = actionShape,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                ) {
                    Icon(painterResource(R.drawable.ic_archive), contentDescription = "清缓存", modifier = Modifier.size(20.dp))
                }
                if (supported) {
                    FilledTonalIconButton(
                        onClick = { onDeleteSynced(info) },
                        modifier = actionSize,
                        shape = actionShape,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    ) {
                        Icon(painterResource(R.drawable.ic_delete), contentDescription = "删除同步", modifier = Modifier.size(20.dp))
                    }
                    FilledIconButton(
                        onClick = { onSync(info) },
                        modifier = actionSize,
                        shape = actionShape
                    ) {
                        Icon(painterResource(R.drawable.ic_sync), contentDescription = "同步", modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun CountCell(title: String, state: StickerState, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("${state.all}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("低清 ${state.lowQuality} · 高清 ${state.highQuality}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun StickerPreview(info: StickerInfo) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = Modifier.size(72.dp).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        when {
            info.preview.type == "webp" -> AndroidView(
                factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.CENTER_CROP } },
                modifier = Modifier.fillMaxSize(),
                update = { Glide.with(it).load(info.preview.url).into(it) }
            )
            info.preview.type == "webm" -> {
                val context = LocalContext.current
                val video = remember(info.preview.url) {
                    VideoView(context).apply {
                        setVideoPath(info.preview.url)
                        setOnPreparedListener { player ->
                            player.isLooping = true
                            player.setVolume(0f, 0f)
                            start()
                        }
                    }
                }
                AndroidView(factory = { video }, modifier = Modifier.fillMaxSize())
                DisposableEffect(video) { onDispose { video.stopPlayback() } }
            }
            else -> Text("贴纸", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
