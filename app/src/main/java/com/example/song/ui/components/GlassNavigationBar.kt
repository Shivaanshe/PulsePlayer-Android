package com.example.song.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect

import androidx.compose.ui.graphics.lerp
import kotlin.math.abs
import com.example.song.ui.spotlight.SpotlightController
import com.example.song.ui.spotlight.TourStep
import com.example.song.ui.spotlight.spotlightTarget

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Discover : Screen("discover", "Discover", Icons.Default.MusicNote)
    object Favorites : Screen("favorites", "Favorites", Icons.Default.Favorite)
    object Library : Screen("library", "Library", Icons.Default.CloudDownload)
}

@Composable
fun GlassNavigationBar(
    pagerOffset: Float,
    onPageSelected: (Int) -> Unit,
    spotlightController: SpotlightController? = null
) {
    val items = listOf(Screen.Discover, Screen.Favorites, Screen.Library)
    val itemWidth = 72.dp
    val containerHeight = 64.dp
    val bubbleHeight = 48.dp

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .wrapContentSize()
                .height(containerHeight)
                .clip(RoundedCornerShape(32.dp))
                .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(32.dp)),
            color = Color(0xFF1E1E26),
            tonalElevation = 0.dp
        ) {
            Box(modifier = Modifier.padding(horizontal = 8.dp).wrapContentSize()) {
                val bubbleX = itemWidth * pagerOffset
                val bubbleY = (containerHeight - bubbleHeight) / 2

                // 1. Sliding Bubble Background
                Box(
                    modifier = Modifier
                        .offset(x = bubbleX, y = bubbleY)
                        .size(width = itemWidth, height = bubbleHeight)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color.White.copy(alpha = 0.2f))
                )

                // 2. Icon Layer
                Row(
                    modifier = Modifier.height(containerHeight),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items.forEachIndexed { index, screen ->
                        val lerpFactor = (1f - abs(pagerOffset - index)).coerceIn(0f, 1f)
                        val iconColor = lerp(Color.White.copy(alpha = 0.5f), Color.Red, lerpFactor)

                        Box(
                            modifier = Modifier
                                .width(itemWidth)
                                .fillMaxHeight()
                                .then(
                                    if (screen == Screen.Library) {
                                        Modifier.spotlightTarget(TourStep.STEP_4_NAVIGATION, spotlightController)
                                    } else Modifier
                                )
                                .clickable(
                                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                    indication = null
                                ) { onPageSelected(index) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = screen.icon,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = iconColor
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun GlassNavigationRail(
    pagerOffset: Float,
    onPageSelected: (Int) -> Unit,
    spotlightController: SpotlightController? = null
) {
    val items = listOf(Screen.Discover, Screen.Favorites, Screen.Library)
    val itemHeight = 64.dp
    val railWidth = 56.dp
    val bubbleWidth = 44.dp
    val bubbleHeight = 48.dp

    Box(
        modifier = Modifier
            .fillMaxHeight()
            .padding(start = 12.dp, end = 4.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Surface(
            modifier = Modifier
                .width(railWidth)
                .wrapContentHeight()
                .clip(RoundedCornerShape(32.dp))
                .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(32.dp)),
            color = Color(0xFF1E1E26),
            tonalElevation = 0.dp
        ) {
            Box(modifier = Modifier.padding(vertical = 12.dp).wrapContentSize()) {
                val bubbleX = (railWidth - bubbleWidth) / 2
                val bubbleY = (itemHeight * pagerOffset) + (itemHeight - bubbleHeight) / 2

                // 1. Sliding Bubble Background
                Box(
                    modifier = Modifier
                        .offset(x = bubbleX, y = bubbleY)
                        .size(width = bubbleWidth, height = bubbleHeight)
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color.White.copy(alpha = 0.2f))
                )

                // 2. Icon Layer
                Column(
                    modifier = Modifier.width(railWidth),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    items.forEachIndexed { index, screen ->
                        val lerpFactor = (1f - abs(pagerOffset - index)).coerceIn(0f, 1f)
                        val iconColor = lerp(Color.White.copy(alpha = 0.5f), Color.Red, lerpFactor)

                        Box(
                            modifier = Modifier
                                .size(width = railWidth, height = itemHeight)
                                .then(
                                    if (screen == Screen.Library) {
                                        Modifier.spotlightTarget(TourStep.STEP_4_NAVIGATION, spotlightController)
                                    } else Modifier
                                )
                                .clickable(
                                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                    indication = null
                                ) { onPageSelected(index) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = screen.icon,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = iconColor
                            )
                        }
                    }
                }
            }
        }
    }
}
