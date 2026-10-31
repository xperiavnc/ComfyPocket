package com.comfyui.mobile.ui.screens

import androidx.compose.animation.AnimatedVisibility
import com.comfyui.mobile.ui.designsystem.PocketTokens
import com.comfyui.mobile.ui.designsystem.PocketSettingsGroup
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.comfyui.mobile.ui.LocalFloatingNavClearance
import com.comfyui.mobile.ui.MainViewModel
import com.comfyui.mobile.ui.rememberAppHaptics
import com.comfyui.mobile.ui.theme.ThemePalette
import com.comfyui.mobile.ui.theme.ThemePalettes
import com.comfyui.mobile.ui.theme.themePaletteById
import com.comfyui.mobile.ui.screens.theme.BottomBarStylePicker
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Layers
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Composable
fun ThemeSettingsScreen(navController: NavController, viewModel: MainViewModel) {
    val themeMode by viewModel.themeMode.collectAsState()
    val themePaletteId by viewModel.themePaletteId.collectAsState()
    val bottomBarStyle by viewModel.bottomBarStyle.collectAsState()
    val liveUpdateShowPercent by viewModel.liveUpdateShowPercent.collectAsState()
    val uiScale by viewModel.uiScale.collectAsState()
    val haptics = rememberAppHaptics()

    var scaleDraft by remember { mutableFloatStateOf(uiScale) }

    val scrollBehavior = MiuixScrollBehavior()
    val floatingNavClearance = LocalFloatingNavClearance.current
    val scheme = MiuixTheme.colorScheme

    Scaffold(
        containerColor = scheme.background,
        topBar = {
            TopAppBar(
                title = "外观与主题",
                color = scheme.background,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(MiuixIcons.Back, contentDescription = "返回")
                    }
                }
    )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = PocketTokens.PagePadding)
                .padding(
                    top = innerPadding.calculateTopPadding(),
                    bottom = 28.dp + floatingNavClearance
    ),
            verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
            Spacer(modifier = Modifier.height(4.dp))

            // 1. Appearance mode
            Card(
                modifier = Modifier.fillMaxWidth(),
                insideMargin = PaddingValues(16.dp)
    ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "外观模式",
                        style = MiuixTheme.textStyles.title3,
                        fontWeight = FontWeight.SemiBold
    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
                        AppearanceModeMockup(
                            modifier = Modifier.weight(1f),
                            title = "跟随系统",
                            mode = MainViewModel.THEME_FOLLOW_SYSTEM,
                            currentMode = themeMode,
                            onClick = { if (themeMode != MainViewModel.THEME_FOLLOW_SYSTEM) haptics.tick(); viewModel.setThemeMode(MainViewModel.THEME_FOLLOW_SYSTEM) }
    )
                        AppearanceModeMockup(
                            modifier = Modifier.weight(1f),
                            title = "浅色",
                            mode = MainViewModel.THEME_LIGHT,
                            currentMode = themeMode,
                            onClick = { if (themeMode != MainViewModel.THEME_LIGHT) haptics.tick(); viewModel.setThemeMode(MainViewModel.THEME_LIGHT) }
    )
                        AppearanceModeMockup(
                            modifier = Modifier.weight(1f),
                            title = "深色",
                            mode = MainViewModel.THEME_DARK,
                            currentMode = themeMode,
                            onClick = { if (themeMode != MainViewModel.THEME_DARK) haptics.tick(); viewModel.setThemeMode(MainViewModel.THEME_DARK) }
    )
                    }
                }
            }

            BottomBarStylePicker(bottomBarStyle) { style ->
                haptics.tick()
                viewModel.setBottomBarStyle(style)
                viewModel.showUserMessage("底栏风格已切换：${style.title}")
            }

            // Fixed palettes — collapsed by default; expand to pick from swatch grid
            var palettePickerExpanded by remember { mutableStateOf(false) }
            val currentPalette = remember(themePaletteId) { themePaletteById(themePaletteId) }
            Card(
                modifier = Modifier.fillMaxWidth(),
                insideMargin = PaddingValues(16.dp)
    ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "主题色板",
                        style = MiuixTheme.textStyles.title3,
                        fontWeight = FontWeight.SemiBold
    )
                    Text(
                        text = "固定配色，不使用壁纸动态取色",
                        style = MiuixTheme.textStyles.footnote1,
                        color = scheme.onSurfaceVariantSummary
    )
                    // Collapsed header: current swatch + name; tap to expand
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(PocketTokens.Shape.Medium)
                            .background(scheme.surfaceContainerHigh.copy(alpha = 0.65f))
                            .clickable {
                                haptics.tick()
                                palettePickerExpanded = !palettePickerExpanded
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
                        PaletteDualSwatch(palette = currentPalette, size = 28.dp)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = currentPalette.displayName,
                                style = MiuixTheme.textStyles.body1,
                                fontWeight = FontWeight.SemiBold,
                                color = scheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
    )
                            Text(
                                text = if (palettePickerExpanded) "点选色点切换" else currentPalette.summary,
                                style = MiuixTheme.textStyles.footnote1,
                                color = scheme.onSurfaceVariantSummary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
    )
                        }
                        Icon(
                            imageVector = MiuixIcons.ExpandMore,
                            contentDescription = if (palettePickerExpanded) "收起色板" else "展开色板",
                            tint = scheme.onSurfaceVariantSummary,
                            modifier = Modifier
                                .size(20.dp)
                                .rotate(if (palettePickerExpanded) 180f else 0f)
    )
                    }
                    AnimatedVisibility(
                        visible = palettePickerExpanded,
                        enter = fadeIn(tween(160)) + expandVertically(tween(200)),
                        exit = fadeOut(tween(120)) + shrinkVertically(tween(180))
    ) {
                        PaletteSwatchGrid(
                            selectedId = themePaletteId,
                            onSelect = { id ->
                                if (id != themePaletteId) haptics.confirm()
                                viewModel.setThemePalette(id)
                            }
    )
                    }
                }
            }

            // 3. Visual effects / notifications
            PocketSettingsGroup("通知") {
                SwitchPreference(
                    title = "通知显示百分比",
                    summary = "通知栏显示进度百分比",
                    startAction = {
                        Icon(
                            MiuixIcons.Info,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp),
                            tint = scheme.onBackground
    )
                    },
                    checked = liveUpdateShowPercent,
                    onCheckedChange = { haptics.toggle(); viewModel.setLiveUpdateShowPercent(it) }
    )
            }

            // 4. UI scale
            PocketSettingsGroup("显示") {
                SliderPreference(
                    value = scaleDraft,
                    onValueChange = { scaleDraft = (it * 100f).roundToInt() / 100f },
                    onValueChangeFinished = { viewModel.setUiScale(scaleDraft) },
                    title = "界面缩放",
                    summary = "调整整体字号与控件大小",
                    valueText = "${(scaleDraft * 100f).roundToInt()}%",
                    valueRange = 0.85f..1.30f,
                    steps = 44,
                    showKeyPoints = true,
                    keyPoints = listOf(0.85f, 1.0f, 1.15f, 1.30f),
                    hapticEffect = SliderDefaults.SliderHapticEffect.Step,
                    startAction = {
                        Icon(
                            MiuixIcons.Tune,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp),
                            tint = scheme.onBackground
    )
                    }
    )
            }
        }
    }
}


@Composable
private fun PaletteDualSwatch(
    palette: ThemePalette,
    size: Dp,
    modifier: Modifier = Modifier
    ) {
    val half = size * 0.72f
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(half)
                .shadow(1.dp, CircleShape)
                .clip(CircleShape)
                .background(palette.previewLight)
                .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape)
    )
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .size(half)
                .shadow(1.dp, CircleShape)
                .clip(CircleShape)
                .background(palette.previewDark)
                .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape)
    )
    }
}

/** Compact 4-column swatch grid for fixed palettes. */
@Composable
private fun PaletteSwatchGrid(
    selectedId: String,
    onSelect: (String) -> Unit
    ) {
    val rows = ThemePalettes.chunked(4)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
                row.forEach { palette ->
                    PaletteSwatchCell(
                        palette = palette,
                        selected = palette.id == selectedId,
                        onClick = { onSelect(palette.id) },
                        modifier = Modifier.weight(1f)
    )
                }
                repeat(4 - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun PaletteSwatchCell(
    palette: ThemePalette,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
    ) {
    val scheme = MiuixTheme.colorScheme
    val ring by animateColorAsState(
        targetValue = if (selected) scheme.primary else scheme.outline.copy(alpha = 0.35f),
        animationSpec = tween(160),
        label = "swatchRing"
    )
    Column(
        modifier = modifier
            .clip(PocketTokens.Shape.Medium)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .border(
                        width = if (selected) 2.dp else 1.dp,
                        color = ring,
                        shape = CircleShape
                    )
                    .padding(4.dp),
                contentAlignment = Alignment.Center
    ) {
                PaletteDualSwatch(palette = palette, size = 34.dp)
            }
            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(scheme.primary),
                    contentAlignment = Alignment.Center
    ) {
                    Icon(
                        imageVector = MiuixIcons.Ok,
                        contentDescription = null,
                        tint = scheme.onPrimary,
                        modifier = Modifier.size(10.dp)
    )
                }
            }
        }
        Text(
            text = palette.displayName,
            style = MiuixTheme.textStyles.footnote1,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) scheme.primary else scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
    )
    }
}


@Composable
private fun AppearanceModeMockup(
    modifier: Modifier = Modifier,
    title: String,
    mode: Int,
    currentMode: Int,
    onClick: () -> Unit
    ) {
    val isSelected = mode == currentMode
    val scheme = MiuixTheme.colorScheme
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) scheme.primary else Color.Transparent,
        animationSpec = tween(220),
        label = "mockupBorder"
    )

    Column(
        modifier = modifier
            .clip(PocketTokens.Shape.Large)
            .clickable(onClick = onClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 手机拟态视窗
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(108.dp)
                .clip(PocketTokens.Shape.Medium)
                .border(
                    width = if (isSelected) 2.5.dp else 1.dp,
                    color = if (isSelected) borderColor else scheme.outline.copy(alpha = 0.4f),
                    shape = PocketTokens.Shape.Medium
    )
                .background(
                    when (mode) {
                        MainViewModel.THEME_LIGHT -> Color(0xFFF6F7F9)
                        MainViewModel.THEME_DARK -> Color(0xFF141822)
                        else -> Color(0xFFE9ECF0)
                    }
                )
    ) {
            when (mode) {
                MainViewModel.THEME_LIGHT -> {
                    MockupContent(
                        bgColor = Color(0xFFF6F7F9),
                        cardColor = Color.White,
                        barColor = Color(0xFFE2E6EE),
                        accentColor = scheme.primary
    )
                }
                MainViewModel.THEME_DARK -> {
                    MockupContent(
                        bgColor = Color(0xFF141822),
                        cardColor = Color(0xFF22283A),
                        barColor = Color(0xFF2F374E),
                        accentColor = scheme.primary
    )
                }
                else -> {
                    Row(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxSize()
                                .background(Color(0xFFF6F7F9))
                                .padding(4.dp)
    ) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Box(modifier = Modifier.fillMaxWidth(0.8f).height(4.dp).background(Color(0xFFCCD2DF), RoundedCornerShape(2.dp)))
                                Box(modifier = Modifier.fillMaxWidth().height(28.dp).background(Color.White, RoundedCornerShape(4.dp)))
                                Box(modifier = Modifier.fillMaxWidth(0.5f).height(4.dp).background(scheme.primary, RoundedCornerShape(2.dp)))
                            }
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxSize()
                                .background(Color(0xFF141822))
                                .padding(4.dp)
    ) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Box(modifier = Modifier.fillMaxWidth(0.8f).height(4.dp).background(Color(0xFF3B435C), RoundedCornerShape(2.dp)))
                                Box(modifier = Modifier.fillMaxWidth().height(28.dp).background(Color(0xFF22283A), RoundedCornerShape(4.dp)))
                                Box(modifier = Modifier.fillMaxWidth(0.5f).height(4.dp).background(scheme.primary, RoundedCornerShape(2.dp)))
                            }
                        }
                    }
                }
            }

            // 选中角标
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(scheme.primary),
                    contentAlignment = Alignment.Center
    ) {
                    Icon(
                        imageVector = MiuixIcons.Ok,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(12.dp)
    )
                }
            }
        }

        // 标签
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isSelected) scheme.primary else scheme.onBackground,
            textAlign = TextAlign.Center
    )
    }
}

@Composable
private fun MockupContent(
    bgColor: Color,
    cardColor: Color,
    barColor: Color,
    accentColor: Color
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .height(5.dp)
                .background(barColor, RoundedCornerShape(3.dp))
    )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .shadow(1.dp, PocketTokens.Shape.Small)
                .background(cardColor, PocketTokens.Shape.Small)
                .padding(6.dp)
    ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(modifier = Modifier.fillMaxWidth(0.7f).height(4.dp).background(barColor, RoundedCornerShape(2.dp)))
                Box(modifier = Modifier.fillMaxWidth(0.4f).height(3.dp).background(barColor.copy(alpha = 0.6f), RoundedCornerShape(2.dp)))
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .height(6.dp)
                .background(accentColor, RoundedCornerShape(3.dp))
    )
    }
}
