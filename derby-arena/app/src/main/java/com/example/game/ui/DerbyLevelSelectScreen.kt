package com.example.game.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.game.GameViewModel
import com.example.game.systems.CampaignSystem

@Composable
fun DerbyLevelSelectScreen(
    viewModel: GameViewModel,
    modifier: Modifier = Modifier
) {
    val maxUnlocked by viewModel.maxUnlockedLevel.collectAsStateWithLifecycle()
    val totalStars by viewModel.totalStars.collectAsStateWithLifecycle()
    val starsMap by viewModel.starsMap.collectAsStateWithLifecycle()
    val currentSelectedLevel by viewModel.currentLevel.collectAsStateWithLifecycle()
    val playerCar by viewModel.player.collectAsStateWithLifecycle()

    val chapters = remember { viewModel.campaignSystem.getChapters() }
    var selectedChapterIndex by remember {
        mutableStateOf(((currentSelectedLevel - 1) / CampaignSystem.LEVELS_PER_CHAPTER).coerceIn(0, chapters.size - 1))
    }
    val currentChapter = chapters[selectedChapterIndex]

    var previewLevel by remember { mutableStateOf(currentSelectedLevel) }
    val previewConfig = remember(previewLevel) { viewModel.campaignSystem.getLevelConfig(previewLevel) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF07090E),
                        Color(0xFF10141D),
                        Color(0xFF0A0C12)
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = { viewModel.goToMenu() },
                    modifier = Modifier
                        .size(42.dp)
                        .background(Color(0xFF1E2638), CircleShape)
                        .testTag("back_to_menu_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color(0xFF00FFCC)
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "450 BÖLÜM DERBY",
                        color = Color(0xFFFF1744),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 2.sp
                    )
                    Text(
                        text = "ŞAMPİYONLUK SEÇİMİ",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .background(Color(0xFF1E2638), RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = "Stars",
                        tint = Color(0xFFFFEA00),
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "$totalStars",
                        color = Color(0xFFFFEA00),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // Chapter Tabs
            ScrollableTabRow(
                selectedTabIndex = selectedChapterIndex,
                containerColor = Color(0xFF10141D),
                contentColor = Color(0xFF00FFCC),
                edgePadding = 8.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.dp, Color(0xFF263238), RoundedCornerShape(10.dp))
            ) {
                chapters.forEachIndexed { index, chapter ->
                    Tab(
                        selected = selectedChapterIndex == index,
                        onClick = {
                            selectedChapterIndex = index
                            val chapterStart = chapter.startLevel
                            if (previewLevel < chapterStart || previewLevel > chapter.endLevel) {
                                previewLevel = chapterStart.coerceAtMost(maxUnlocked)
                            }
                        },
                        text = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "CH. ${chapter.chapterNumber}",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = "${chapter.startLevel}-${chapter.endLevel}",
                                    fontSize = 8.sp,
                                    color = if (selectedChapterIndex == index) Color(0xFF00FFCC) else Color(0xFF90A4AE)
                                )
                            }
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Chapter Title Banner
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF161C28), RoundedCornerShape(8.dp))
                    .border(1.dp, Color(0xFF37474F), RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(
                    text = currentChapter.title,
                    color = Color(0xFFFFEA00),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = currentChapter.subtitle,
                    color = Color(0xFFB0BEC5),
                    fontSize = 10.sp
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 90 Level Grid for Selected Chapter
            val chapterLevels = remember(selectedChapterIndex) {
                (currentChapter.startLevel..currentChapter.endLevel).toList()
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color(0xFF0D1017), RoundedCornerShape(12.dp))
                    .border(1.dp, Color(0xFF263238), RoundedCornerShape(12.dp))
                    .padding(8.dp)
            ) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 58.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(chapterLevels) { levelNum ->
                        val isUnlocked = levelNum <= maxUnlocked
                        val isSelected = levelNum == previewLevel
                        val stars = starsMap[levelNum] ?: 0
                        val isBoss = (levelNum % 10 == 0)

                        Box(
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    when {
                                        isSelected -> Color(0xFF00E5FF).copy(alpha = 0.35f)
                                        isBoss && isUnlocked -> Color(0xFFFF1744).copy(alpha = 0.25f)
                                        isUnlocked -> Color(0xFF1A2234)
                                        else -> Color(0xFF11141C)
                                    }
                                )
                                .border(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = when {
                                        isSelected -> Color(0xFF00E5FF)
                                        isBoss && isUnlocked -> Color(0xFFFF1744)
                                        isUnlocked -> Color(0xFF37474F)
                                        else -> Color(0xFF1C222E)
                                    },
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable(enabled = isUnlocked) {
                                    previewLevel = levelNum
                                }
                                .testTag("level_btn_$levelNum"),
                            contentAlignment = Alignment.Center
                        ) {
                            if (!isUnlocked) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = "Locked",
                                    tint = Color(0xFF546E7A),
                                    modifier = Modifier.size(16.dp)
                                )
                            } else {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = "$levelNum",
                                        color = if (isBoss) Color(0xFFFF5252) else Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Black,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    // Stars Indicator
                                    Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                                        for (s in 1..3) {
                                            Text(
                                                text = if (s <= stars) "★" else "☆",
                                                color = if (s <= stars) Color(0xFFFFEA00) else Color(0xFF455A64),
                                                fontSize = 8.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Level Preview / Launch Action Card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF10141D), RoundedCornerShape(12.dp))
                    .border(1.5.dp, Color(0xFF00FFCC), RoundedCornerShape(12.dp))
                    .padding(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "BÖLÜM $previewLevel: ${previewConfig.levelTitle}",
                            color = Color(0xFFFFEA00),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "Düşmanlar: ${previewConfig.enemyCount} Araç · Hedef Süre: ${previewConfig.targetTimeSeconds}s · Ödül: 🔩 ${previewConfig.scrapReward}",
                            color = Color(0xFFB0BEC5),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Button(
                        onClick = { viewModel.startLevel(previewLevel) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF00FFCC),
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .height(44.dp)
                            .testTag("start_selected_level_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Start",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "BAŞLAT",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}
