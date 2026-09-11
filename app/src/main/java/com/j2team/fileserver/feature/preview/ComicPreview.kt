package com.j2team.fileserver.feature.preview

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.j2team.fileserver.core.cache.AppCacheManager
import com.j2team.fileserver.core.model.RemoteResource
import com.j2team.fileserver.core.model.ServerProfile
import com.j2team.fileserver.core.network.ComicManifest
import com.j2team.fileserver.core.network.ComicPage
import com.j2team.fileserver.core.session.SessionRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ComicReadingMode { Webtoon, Single, Double }

/** In-memory LRU cache to keep decoded bitmaps ready for instant, stutter-free 120fps scrolling */
private object ComicMemoryCache {
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = (maxMemory / 4).coerceAtLeast(16 * 1024)
    val cache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int = bitmap.byteCount / 1024
    }
}

@Composable
internal fun ComicPreview(
    profile: ServerProfile,
    item: RemoteResource,
    sessionRepository: SessionRepository,
    comicSiblings: List<RemoteResource> = emptyList(),
    onNavigateComic: ((RemoteResource) -> Unit)? = null,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var manifest by remember(item.path) { mutableStateOf<ComicManifest?>(null) }
    var loading by remember(item.path) { mutableStateOf(true) }
    var error by remember(item.path) { mutableStateOf<String?>(null) }
    var currentPage by remember(item.path) { mutableStateOf(1) }
    var mode by remember { mutableStateOf(ComicReadingMode.Webtoon) }
    var showHud by remember { mutableStateOf(false) } // Default hidden for immediate immersive reading

    val siblingIndex = remember(item.path, comicSiblings) {
        comicSiblings.indexOfFirst { it.path == item.path }
    }
    val hasPrevChapter = siblingIndex > 0
    val hasNextChapter = siblingIndex >= 0 && siblingIndex < comicSiblings.size - 1

    LaunchedEffect(profile.id, item.path) {
        loading = true
        error = null
        manifest = null
        currentPage = 1
        withContext(Dispatchers.IO) {
            sessionRepository.comicManifest(profile, item.path)
        }.fold(
            onSuccess = {
                manifest = it
                loading = false
            },
            onFailure = {
                error = it.message ?: "Unable to load comic manifest"
                loading = false
            }
        )
    }

    // Preload next 3 pages in background whenever current page changes
    LaunchedEffect(currentPage, manifest) {
        val comic = manifest ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            for (offset in 1..3) {
                val nextIdx = currentPage - 1 + offset
                if (nextIdx < comic.count) {
                    val file = AppCacheManager.comicPageFile(context, profile.id, item.path, nextIdx)
                    if (!file.exists() || file.length() <= 0L) {
                        sessionRepository.downloadComicPage(profile, item.path, nextIdx, file)
                    }
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A))
    ) {
        when {
            loading -> {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(color = Color(0xFF2196F3))
                    Text(androidx.compose.ui.res.stringResource(com.j2team.fileserver.R.string.comic_loading), color = Color.White)
                }
            }
            error != null -> {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(error!!, color = MaterialTheme.colorScheme.error)
                    Button(onClick = {
                        loading = true
                        error = null
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                sessionRepository.comicManifest(profile, item.path)
                            }.fold(
                                onSuccess = { manifest = it; loading = false },
                                onFailure = { error = it.message ?: "Error"; loading = false }
                            )
                        }
                    }) {
                        Text(androidx.compose.ui.res.stringResource(com.j2team.fileserver.R.string.comic_retry))
                    }
                }
            }
            manifest != null -> {
                val comic = manifest!!
                val listState = rememberLazyListState()
                val singlePagerState = rememberPagerState(pageCount = { comic.count })
                val doublePageCount = (comic.count + 1) / 2
                val doublePagerState = rememberPagerState(pageCount = { doublePageCount })

                // KEY REQUIREMENT: When scrolling, IMMEDIATELY hide the HUD for immersive reading
                LaunchedEffect(listState.isScrollInProgress) {
                    if (listState.isScrollInProgress) {
                        showHud = false
                    }
                }
                LaunchedEffect(singlePagerState.isScrollInProgress) {
                    if (singlePagerState.isScrollInProgress) {
                        showHud = false
                    }
                }
                LaunchedEffect(doublePagerState.isScrollInProgress) {
                    if (doublePagerState.isScrollInProgress) {
                        showHud = false
                    }
                }

                // Sync current page on scroll in Webtoon mode
                LaunchedEffect(listState) {
                    snapshotFlow { listState.firstVisibleItemIndex }.collect { index ->
                        if (mode == ComicReadingMode.Webtoon) {
                            currentPage = (index + 1).coerceIn(1, comic.count)
                        }
                    }
                }
                // Sync current page in Single mode
                LaunchedEffect(singlePagerState) {
                    snapshotFlow { singlePagerState.currentPage }.collect { index ->
                        if (mode == ComicReadingMode.Single) {
                            currentPage = (index + 1).coerceIn(1, comic.count)
                        }
                    }
                }
                // Sync current page in Double mode
                LaunchedEffect(doublePagerState) {
                    snapshotFlow { doublePagerState.currentPage }.collect { index ->
                        if (mode == ComicReadingMode.Double) {
                            currentPage = (index * 2 + 1).coerceIn(1, comic.count)
                        }
                    }
                }

                // Main Reading Viewport
                Box(modifier = Modifier.fillMaxSize()) {
                    when (mode) {
                        ComicReadingMode.Webtoon -> {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                items(comic.pages, key = { it.index }) { page ->
                                    ComicPageItem(
                                        profile = profile,
                                        remotePath = item.path,
                                        page = page,
                                        sessionRepository = sessionRepository,
                                        onToggleHud = { showHud = !showHud },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                        ComicReadingMode.Single -> {
                            HorizontalPager(
                                state = singlePagerState,
                                modifier = Modifier.fillMaxSize()
                            ) { pageIndex ->
                                comic.pages.getOrNull(pageIndex)?.let { page ->
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        ComicPageItem(
                                            profile = profile,
                                            remotePath = item.path,
                                            page = page,
                                            sessionRepository = sessionRepository,
                                            fitInside = true,
                                            onToggleHud = { showHud = !showHud },
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                }
                            }
                        }
                        ComicReadingMode.Double -> {
                            HorizontalPager(
                                state = doublePagerState,
                                modifier = Modifier.fillMaxSize(),
                                reverseLayout = true // Manga RTL
                            ) { pairIndex ->
                                val firstPageIndex = pairIndex * 2
                                val secondPageIndex = firstPageIndex + 1
                                Row(
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    comic.pages.getOrNull(firstPageIndex)?.let { page ->
                                        ComicPageItem(
                                            profile = profile,
                                            remotePath = item.path,
                                            page = page,
                                            sessionRepository = sessionRepository,
                                            fitInside = true,
                                            onToggleHud = { showHud = !showHud },
                                            modifier = Modifier.weight(1f).fillMaxSize()
                                        )
                                    }
                                    if (secondPageIndex < comic.count) {
                                        comic.pages.getOrNull(secondPageIndex)?.let { page ->
                                            ComicPageItem(
                                                profile = profile,
                                                remotePath = item.path,
                                                page = page,
                                                sessionRepository = sessionRepository,
                                                fitInside = true,
                                                onToggleHud = { showHud = !showHud },
                                                modifier = Modifier.weight(1f).fillMaxSize()
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // FLOATING TOP BAR (Back button + Chapter Title)
                AnimatedVisibility(
                    visible = showHud,
                    enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
                    modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()
                ) {
                    Surface(
                        color = Color(0xF2121212),
                        modifier = Modifier.fillMaxWidth().statusBarsPadding()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = onBack) {
                                Text("‹", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                            }
                            Text(
                                text = item.name,
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(start = 4.dp)
                            )
                        }
                    }
                }

                // FLOATING HUD CONTROLS AT BOTTOM (Auto-hides on scroll!)
                AnimatedVisibility(
                    visible = showHud,
                    enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                ) {
                    Surface(
                        color = Color(0xF2141414),
                        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Row 1: Scrubber and Navigation
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        if (hasPrevChapter) {
                                            comicSiblings.getOrNull(siblingIndex - 1)?.let {
                                                onNavigateComic?.invoke(it)
                                            }
                                        }
                                    },
                                    enabled = hasPrevChapter,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0x33FFFFFF),
                                        disabledContainerColor = Color(0x11FFFFFF)
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = ButtonDefaults.TextButtonContentPadding
                                ) {
                                    Text(androidx.compose.ui.res.stringResource(com.j2team.fileserver.R.string.comic_prev_chapter), fontSize = 12.sp, color = if (hasPrevChapter) Color.White else Color.Gray)
                                }

                                Button(
                                    onClick = {
                                        val prev = (currentPage - 1).coerceAtLeast(1)
                                        currentPage = prev
                                        scope.launch {
                                            if (mode == ComicReadingMode.Webtoon) listState.scrollToItem(prev - 1)
                                            else if (mode == ComicReadingMode.Single) singlePagerState.scrollToPage(prev - 1)
                                            else doublePagerState.scrollToPage((prev - 1) / 2)
                                        }
                                    },
                                    enabled = currentPage > 1,
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0x33FFFFFF)),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("‹", fontSize = 16.sp, color = Color.White)
                                }

                                Slider(
                                    value = currentPage.toFloat(),
                                    onValueChange = { value ->
                                        val target = value.toInt().coerceIn(1, comic.count)
                                        currentPage = target
                                        scope.launch {
                                            if (mode == ComicReadingMode.Webtoon) listState.scrollToItem(target - 1)
                                            else if (mode == ComicReadingMode.Single) singlePagerState.scrollToPage(target - 1)
                                            else doublePagerState.scrollToPage((target - 1) / 2)
                                        }
                                    },
                                    valueRange = 1f..comic.count.toFloat(),
                                    modifier = Modifier.weight(1f),
                                    colors = SliderDefaults.colors(
                                        thumbColor = Color(0xFF2196F3),
                                        activeTrackColor = Color(0xFF2196F3),
                                        inactiveTrackColor = Color(0x44FFFFFF)
                                    )
                                )

                                Button(
                                    onClick = {
                                        val next = (currentPage + 1).coerceAtMost(comic.count)
                                        currentPage = next
                                        scope.launch {
                                            if (mode == ComicReadingMode.Webtoon) listState.scrollToItem(next - 1)
                                            else if (mode == ComicReadingMode.Single) singlePagerState.scrollToPage(next - 1)
                                            else doublePagerState.scrollToPage((next - 1) / 2)
                                        }
                                    },
                                    enabled = currentPage < comic.count,
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0x33FFFFFF)),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("›", fontSize = 16.sp, color = Color.White)
                                }

                                Button(
                                    onClick = {
                                        if (hasNextChapter) {
                                            comicSiblings.getOrNull(siblingIndex + 1)?.let {
                                                onNavigateComic?.invoke(it)
                                            }
                                        }
                                    },
                                    enabled = hasNextChapter,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0x33FFFFFF),
                                        disabledContainerColor = Color(0x11FFFFFF)
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = ButtonDefaults.TextButtonContentPadding
                                ) {
                                    Text(androidx.compose.ui.res.stringResource(com.j2team.fileserver.R.string.comic_next_chapter), fontSize = 12.sp, color = if (hasNextChapter) Color.White else Color.Gray)
                                }
                            }

                            // Row 2: Status Badge & Mode Switchers
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    color = Color(0x33FFFFFF),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = "$currentPage / ${comic.count}",
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Button(
                                        onClick = { mode = ComicReadingMode.Webtoon },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (mode == ComicReadingMode.Webtoon) Color(0xFF2196F3) else Color(0x22FFFFFF)
                                        ),
                                        shape = RoundedCornerShape(6.dp),
                                        contentPadding = ButtonDefaults.TextButtonContentPadding
                                    ) {
                                        Text(androidx.compose.ui.res.stringResource(com.j2team.fileserver.R.string.comic_mode_webtoon), fontSize = 12.sp, color = Color.White)
                                    }

                                    Button(
                                        onClick = { mode = ComicReadingMode.Single },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (mode == ComicReadingMode.Single) Color(0xFF2196F3) else Color(0x22FFFFFF)
                                        ),
                                        shape = RoundedCornerShape(6.dp),
                                        contentPadding = ButtonDefaults.TextButtonContentPadding
                                    ) {
                                        Text(androidx.compose.ui.res.stringResource(com.j2team.fileserver.R.string.comic_mode_single), fontSize = 12.sp, color = Color.White)
                                    }

                                    Button(
                                        onClick = { mode = ComicReadingMode.Double },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (mode == ComicReadingMode.Double) Color(0xFF2196F3) else Color(0x22FFFFFF)
                                        ),
                                        shape = RoundedCornerShape(6.dp),
                                        contentPadding = ButtonDefaults.TextButtonContentPadding
                                    ) {
                                        Text(androidx.compose.ui.res.stringResource(com.j2team.fileserver.R.string.comic_mode_double), fontSize = 12.sp, color = Color.White)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ComicPageItem(
    profile: ServerProfile,
    remotePath: String,
    page: ComicPage,
    sessionRepository: SessionRepository,
    fitInside: Boolean = false,
    onToggleHud: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val cacheKey = remember(profile.id, remotePath, page.index) {
        "${profile.id}:$remotePath:${page.index}"
    }
    val cacheFile = remember(profile.id, remotePath, page.index) {
        AppCacheManager.comicPageFile(context, profile.id, remotePath, page.index)
    }

    var bitmap by remember(cacheKey) { mutableStateOf(ComicMemoryCache.cache.get(cacheKey)) }
    var loading by remember(cacheKey) { mutableStateOf(bitmap == null) }

    var scale by remember(page.index) { mutableStateOf(1f) }
    var offset by remember(page.index) { mutableStateOf(Offset.Zero) }

    LaunchedEffect(cacheKey) {
        if (bitmap == null) {
            val cached = ComicMemoryCache.cache.get(cacheKey)
            if (cached != null) {
                bitmap = cached
                loading = false
            } else {
                withContext(Dispatchers.IO) {
                    if (!cacheFile.exists() || cacheFile.length() <= 0L) {
                        sessionRepository.downloadComicPage(profile, remotePath, page.index, cacheFile)
                    }
                    if (cacheFile.exists() && cacheFile.length() > 0L) {
                        val targetW = with(density) { 1080.dp.roundToPx() }
                        val targetH = with(density) { 2400.dp.roundToPx() }
                        val decoded = decodeSampledImage(cacheFile, targetW, targetH)
                        if (decoded != null) {
                            ComicMemoryCache.cache.put(cacheKey, decoded)
                        }
                        decoded
                    } else null
                }?.let {
                    bitmap = it
                    loading = false
                } ?: run {
                    loading = false
                }
            }
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .pointerInput(page.index) {
                detectTapGestures(
                    onTap = { onToggleHud() },
                    onDoubleTap = {
                        if (scale > 1.2f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.2f
                            offset = Offset.Zero
                        }
                    }
                )
            }
            .pointerInput(page.index) {
                // Multi-finger pinch-to-zoom that does NOT consume single-finger drag,
                // allowing buttery smooth 120fps scrolling in LazyColumn!
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.size >= 2) {
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            val newScale = (scale * zoomChange).coerceIn(1f, 4f)
                            val maxOffsetX = (newScale - 1f) * (size.width / 2f)
                            val maxOffsetY = (newScale - 1f) * (size.height / 2f)
                            val newOffsetX = if (newScale <= 1f) 0f else (offset.x + panChange.x).coerceIn(-maxOffsetX, maxOffsetX)
                            val newOffsetY = if (newScale <= 1f) 0f else (offset.y + panChange.y).coerceIn(-maxOffsetY, maxOffsetY)
                            scale = newScale
                            offset = Offset(newOffsetX, newOffsetY)
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })

                    if (scale < 1.1f) {
                        scale = 1f
                        offset = Offset.Zero
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (loading && bitmap == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color = Color(0xFF2196F3),
                    strokeWidth = 2.dp,
                    modifier = Modifier.padding(16.dp)
                )
            }
        } else if (bitmap != null) {
            Box {
                Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = page.name,
                    contentScale = if (fitInside) ContentScale.Fit else ContentScale.FillWidth,
                    modifier = Modifier
                        .then(if (fitInside) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        }
                )
                Surface(
                    color = Color(0x99000000),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                ) {
                    Text(
                        text = "${page.index + 1}",
                        color = Color.White,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}
