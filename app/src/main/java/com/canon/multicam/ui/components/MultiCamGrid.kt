package com.canon.multicam.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.canon.multicam.domain.model.CameraSlotUiState
import com.canon.multicam.domain.model.ConnectionState
import com.canon.multicam.domain.model.OverlayGuidesConfig
import com.canon.multicam.domain.model.ThermalStatus
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap

private const val HERO_WEIGHT = 0.68f
private const val RAIL_WEIGHT = 0.32f
private const val GUIDE_THIRD_ONE = 0.3333f
private const val GUIDE_THIRD_TWO = 0.6667f
private const val GUIDE_HALF = 0.5f
private const val SAFE_MARGIN_FRACTION = 0.05f
private const val SAFE_INNER_FRACTION = 0.90f
private const val CROSSHAIR_RADIUS_DP = 14
private const val AF_RETICLE_SIZE_DP = 44
private const val ASPECT_16_9 = 16f / 9f

@Composable
fun MultiCamStudioGrid(
    slots: ImmutableList<CameraSlotUiState>,
    liveViewFrames: ImmutableMap<String, Bitmap>,
    primarySlotId: String?,
    onSelectPrimary: (String) -> Unit,
    onTouchAf: (String, Float, Float) -> Unit,
    onToggleRecord: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val primarySlot = slots.firstOrNull { it.slotId == primarySlotId } ?: slots.firstOrNull()
    val secondarySlots = slots.filterNot { it.slotId == primarySlot?.slotId }

    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (primarySlot != null) {
            HeroMonitorPane(
                slot = primarySlot,
                frame = liveViewFrames[primarySlot.slotId],
                onTouchAf = { nx, ny -> onTouchAf(primarySlot.slotId, nx, ny) },
                onToggleRecord = { onToggleRecord(primarySlot.slotId) },
                modifier = Modifier
                    .weight(HERO_WEIGHT)
                    .fillMaxHeight()
            )
        }

        if (secondarySlots.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier
                    .weight(RAIL_WEIGHT)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(secondarySlots, key = { it.slotId }) { slot ->
                    SecondaryCameraTile(
                        slot = slot,
                        frame = liveViewFrames[slot.slotId],
                        onSelect = { onSelectPrimary(slot.slotId) }
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroMonitorPane(
    slot: CameraSlotUiState,
    frame: Bitmap?,
    onTouchAf: (Float, Float) -> Unit,
    onToggleRecord: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = if (slot.isRecording) Color(0xFFE53935) else Color(0xFF263042)
    val imageBitmap = remember(frame) { frame?.asImageBitmap() }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFF11151C),
        border = BorderStroke(2.dp, borderColor)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(slot.slotId) {
                    detectTapGestures { offset ->
                        if (size.width > 0 && size.height > 0) {
                            val normX = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                            val normY = (offset.y / size.height.toFloat()).coerceIn(0f, 1f)
                            onTouchAf(normX, normY)
                        }
                    }
                }
        ) {
            if (imageBitmap != null) {
                Image(
                    bitmap = imageBitmap,
                    contentDescription = slot.profile.displayName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = slot.statusMessage.ifEmpty { "Waiting for LiveView..." },
                        color = Color(0xFF8C98A9),
                        fontSize = 13.sp
                    )
                }
            }

            StudioOverlayCanvas(
                guides = slot.overlayGuides,
                touchAf = slot.touchAfNormalized,
                modifier = Modifier.fillMaxSize()
            )

            HeroTopHud(
                slot = slot,
                onToggleRecord = onToggleRecord,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(10.dp)
            )
        }
    }
}

@Composable
private fun HeroTopHud(
    slot: CameraSlotUiState,
    onToggleRecord: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = Color(0xCC0A0C10),
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    text = "${slot.profile.displayName} • ${slot.activeProtocol.displayName}",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            if (slot.thermalStatus != ThermalStatus.NORMAL) {
                Surface(
                    color = Color(0xCCEF6C00),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = "TEMP ${slot.thermalStatus.name}",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                }
            }
        }

        Surface(
            color = if (slot.isRecording) Color(0xFFE53935) else Color(0xCC1E2533),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.clickable { onToggleRecord() }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(Color.White, CircleShape)
                )
                Text(
                    text = if (slot.isRecording) "REC" else "STBY",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun SecondaryCameraTile(
    slot: CameraSlotUiState,
    frame: Bitmap?,
    onSelect: () -> Unit
) {
    val imageBitmap = remember(frame) { frame?.asImageBitmap() }
    val statusColor = when (slot.connectionState) {
        ConnectionState.CONNECTED -> Color(0xFF43A047)
        ConnectionState.CONNECTING -> Color(0xFFFB8C00)
        ConnectionState.ERROR -> Color(0xFFE53935)
        else -> Color(0xFF757575)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(ASPECT_16_9)
            .clickable { onSelect() },
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFF141923),
        border = BorderStroke(
            width = 1.dp,
            color = if (slot.isRecording) Color(0xFFE53935) else Color(0xFF283244)
        )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (imageBitmap != null) {
                Image(
                    bitmap = imageBitmap,
                    contentDescription = slot.profile.displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Color(0xAA0A0C10))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .background(statusColor, CircleShape)
                    )
                    Text(
                        text = slot.profile.displayName,
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Text(
                    text = "${slot.iso} • ${slot.shutterSpeed}",
                    color = Color(0xFFB0BEC5),
                    fontSize = 10.sp
                )
            }
        }
    }
}

@Composable
private fun StudioOverlayCanvas(
    guides: OverlayGuidesConfig,
    touchAf: Pair<Float, Float>?,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val gridColor = Color.White.copy(alpha = 0.25f)

        if (guides.showRuleOfThirds) {
            drawLine(gridColor, Offset(w * GUIDE_THIRD_ONE, 0f), Offset(w * GUIDE_THIRD_ONE, h), 1f)
            drawLine(gridColor, Offset(w * GUIDE_THIRD_TWO, 0f), Offset(w * GUIDE_THIRD_TWO, h), 1f)
            drawLine(gridColor, Offset(0f, h * GUIDE_THIRD_ONE), Offset(w, h * GUIDE_THIRD_ONE), 1f)
            drawLine(gridColor, Offset(0f, h * GUIDE_THIRD_TWO), Offset(w, h * GUIDE_THIRD_TWO), 1f)
        }

        if (guides.showCenterCrosshair) {
            val cx = w * GUIDE_HALF
            val cy = h * GUIDE_HALF
            val r = CROSSHAIR_RADIUS_DP.dp.toPx()
            drawLine(gridColor, Offset(cx - r, cy), Offset(cx + r, cy), 1.5f)
            drawLine(gridColor, Offset(cx, cy - r), Offset(cx, cy + r), 1.5f)
        }

        if (guides.showSafeArea16x9) {
            drawRect(
                color = Color(0x66FFD54F),
                topLeft = Offset(w * SAFE_MARGIN_FRACTION, h * SAFE_MARGIN_FRACTION),
                size = Size(w * SAFE_INNER_FRACTION, h * SAFE_INNER_FRACTION),
                style = Stroke(width = 1.5f)
            )
        }

        if (touchAf != null) {
            val boxSide = AF_RETICLE_SIZE_DP.dp.toPx()
            val halfSide = boxSide * GUIDE_HALF
            val fx = (touchAf.first * w - halfSide).coerceIn(0f, (w - boxSide).coerceAtLeast(0f))
            val fy = (touchAf.second * h - halfSide).coerceIn(0f, (h - boxSide).coerceAtLeast(0f))
            drawRect(
                color = Color(0xFF00E676),
                topLeft = Offset(fx, fy),
                size = Size(boxSide, boxSide),
                style = Stroke(width = 2.dp.toPx())
            )
        }
    }
}
