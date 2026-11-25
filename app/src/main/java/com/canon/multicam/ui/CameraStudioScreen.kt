package com.canon.multicam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.canon.multicam.domain.model.CameraDeviceProfile
import com.canon.multicam.domain.model.CameraSlotUiState
import com.canon.multicam.domain.model.CanonModelCatalog
import com.canon.multicam.ui.components.MultiCamStudioGrid

@Composable
fun CameraStudioScreen(viewModel: MultiCamViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val liveViewFrames by viewModel.liveViewFrames.collectAsStateWithLifecycle()
    var showConnectDialog by remember { mutableStateOf(false) }

    val authRequiredSlot = uiState.slots.firstOrNull { it.authRequired }
    val primarySlot = uiState.slots.firstOrNull { it.slotId == uiState.primarySlotId }
        ?: uiState.slots.firstOrNull()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0C10))
    ) {
        StudioTopToolbar(
            isScanning = uiState.isScanning,
            bannerText = uiState.statusBanner,
            onScanClick = { viewModel.scanForCameras() },
            onAddCameraClick = { showConnectDialog = true }
        )

        Box(modifier = Modifier.weight(1f)) {
            if (uiState.slots.isEmpty()) {
                EmptyStudioPlaceholder(onConnectClick = { showConnectDialog = true })
            } else {
                MultiCamStudioGrid(
                    slots = uiState.slots,
                    liveViewFrames = liveViewFrames,
                    primarySlotId = uiState.primarySlotId,
                    onSelectPrimary = { viewModel.setPrimarySlot(it) },
                    onTouchAf = { id, nx, ny -> viewModel.triggerTouchAf(id, nx, ny) },
                    onToggleRecord = { viewModel.toggleRecording(it) }
                )
            }
        }

        if (primarySlot != null) {
            ExposureAndTransportBar(
                slot = primarySlot,
                onIsoSelected = { viewModel.updateExposure(primarySlot.slotId, iso = it) },
                onShutterSelected = {
                    viewModel.updateExposure(primarySlot.slotId, shutterSpeed = it)
                },
                onApertureSelected = {
                    viewModel.updateExposure(primarySlot.slotId, aperture = it)
                },
                onShutterAll = { viewModel.triggerAllShutters() },
                onRecAll = { viewModel.toggleAllRecording() }
            )
        }
    }

    if (showConnectDialog || authRequiredSlot != null) {
        ManualIpConnectDialog(
            initialIp = authRequiredSlot?.hostIp ?: "192.168.1.2",
            initialProfile = authRequiredSlot?.profile ?: CanonModelCatalog.allProfiles.first(),
            authWarning = if (authRequiredSlot != null) authRequiredSlot.statusMessage else null,
            onDismiss = { showConnectDialog = false },
            onConnect = { ip, profile, user, pass ->
                showConnectDialog = false
                viewModel.connectWifiCamera(ip, profile, user, pass)
            }
        )
    }
}

@Composable
private fun StudioTopToolbar(
    isScanning: Boolean,
    bannerText: String?,
    onScanClick: () -> Unit,
    onAddCameraClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF11151C))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "CANON MULTICAM PRO",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            if (bannerText != null) {
                Text(text = bannerText, color = Color(0xFF90A4AE), fontSize = 12.sp)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onScanClick, enabled = !isScanning) {
                Text(if (isScanning) "Scanning..." else "Auto-Scan")
            }
            Button(
                onClick = onAddCameraClick,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
            ) {
                Text("+ Connect IP")
            }
        }
    }
}

@Composable
private fun EmptyStudioPlaceholder(onConnectClick: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "No Canon EOS R Cameras Connected",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Supports EOS R8, R7, R, RP, R6, R6 Mark II/III & R5 C (XC / CCAPI / PTP)",
                color = Color(0xFF90A4AE),
                fontSize = 12.sp
            )
            Button(onClick = onConnectClick) {
                Text("Add Camera by IP")
            }
        }
    }
}

@Composable
private fun ExposureAndTransportBar(
    slot: CameraSlotUiState,
    onIsoSelected: (String) -> Unit,
    onShutterSelected: (String) -> Unit,
    onApertureSelected: (String) -> Unit,
    onShutterAll: () -> Unit,
    onRecAll: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF11151C))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ExposureSelectorChip(
                label = "ISO",
                currentValue = slot.iso,
                options = CanonModelCatalog.supportedIsoValues,
                onSelect = onIsoSelected
            )
            ExposureSelectorChip(
                label = "TV",
                currentValue = slot.shutterSpeed,
                options = CanonModelCatalog.supportedShutterValues,
                onSelect = onShutterSelected
            )
            ExposureSelectorChip(
                label = "AV",
                currentValue = slot.aperture,
                options = CanonModelCatalog.supportedApertureValues,
                onSelect = onApertureSelected
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onShutterAll) {
                Text("SHUTTER ALL")
            }
            Button(
                onClick = onRecAll,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
            ) {
                Text("REC ALL")
            }
        }
    }
}

@Composable
private fun ExposureSelectorChip(
    label: String,
    currentValue: String,
    options: List<String>,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text("$label: $currentValue", fontSize = 12.sp)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (opt in options) {
                DropdownMenuItem(
                    text = { Text(opt) },
                    onClick = {
                        expanded = false
                        onSelect(opt)
                    }
                )
            }
        }
    }
}

@Composable
private fun ManualIpConnectDialog(
    initialIp: String,
    initialProfile: CameraDeviceProfile,
    authWarning: String?,
    onDismiss: () -> Unit,
    onConnect: (String, CameraDeviceProfile, String, String) -> Unit
) {
    var ipText by remember(initialIp) { mutableStateOf(initialIp) }
    var selectedProfile by remember(initialProfile) { mutableStateOf(initialProfile) }
    var modelDropdownOpen by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF171C26)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Connect Canon Camera",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                if (authWarning != null) {
                    Text(text = authWarning, color = Color(0xFFFFB74D), fontSize = 12.sp)
                }

                Box {
                    OutlinedButton(
                        onClick = { modelDropdownOpen = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(selectedProfile.displayName)
                    }
                    DropdownMenu(
                        expanded = modelDropdownOpen,
                        onDismissRequest = { modelDropdownOpen = false }
                    ) {
                        for (prof in CanonModelCatalog.allProfiles) {
                            DropdownMenuItem(
                                text = { Text(prof.displayName) },
                                onClick = {
                                    selectedProfile = prof
                                    modelDropdownOpen = false
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = ipText,
                    onValueChange = { ipText = it },
                    label = { Text("Camera IP Address") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Digest Username (Optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Digest Password (Optional)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(
                        onClick = { onConnect(ipText, selectedProfile, username, password) }
                    ) {
                        Text("Connect")
                    }
                }
            }
        }
    }
}
