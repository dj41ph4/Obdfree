package com.obdfree.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothConnected
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.obdfree.app.model.BluetoothAdapterItem
import com.obdfree.app.model.ConnectionState
import com.obdfree.app.model.DiagnosticUiState
import com.obdfree.app.model.TroubleCode
import com.obdfree.app.ui.theme.Danger
import com.obdfree.app.ui.theme.DeepInk
import com.obdfree.app.ui.theme.Electric
import com.obdfree.app.ui.theme.Glass
import com.obdfree.app.ui.theme.GlassBorder
import com.obdfree.app.ui.theme.GlassStrong
import com.obdfree.app.ui.theme.Mint
import com.obdfree.app.ui.theme.TextPrimary
import com.obdfree.app.ui.theme.TextSecondary

@Composable
fun ObdFreeApp(
    state: DiagnosticUiState,
    onRequestBluetoothPermission: () -> Unit,
    onRefreshAdapters: () -> Unit,
    onSelectAdapter: (BluetoothAdapterItem) -> Unit,
    onConnect: () -> Unit,
    onScan: () -> Unit,
    onClearCodes: () -> Unit,
) {
    var showAdapters by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF081722),
                        DeepInk,
                        Color(0xFF050C12),
                    ),
                ),
            ),
    ) {
        AmbientGlow(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 18.dp),
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { Header() }

            item {
                ConnectionCard(
                    state = state,
                    onPermission = onRequestBluetoothPermission,
                    onChooseAdapter = { showAdapters = true },
                    onConnect = onConnect,
                )
            }

            if (state.errorMessage != null) {
                item { ErrorBanner(state.errorMessage) }
            }

            item {
                Button(
                    onClick = onScan,
                    enabled = state.connectionState == ConnectionState.CONNECTED && !state.isBusy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Electric,
                        contentColor = DeepInk,
                        disabledContainerColor = Electric.copy(alpha = 0.14f),
                        disabledContentColor = TextSecondary,
                    ),
                ) {
                    if (state.isBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = DeepInk,
                        )
                    } else {
                        Icon(Icons.Rounded.Refresh, null)
                    }
                    Spacer(Modifier.width(9.dp))
                    Text(
                        if (state.isBusy) state.statusMessage else "Lire les codes défaut",
                        fontWeight = FontWeight.Black,
                    )
                }
            }

            item {
                SectionHeader(
                    count = state.troubleCodes.size,
                    connected = state.connectionState == ConnectionState.CONNECTED,
                    status = state.statusMessage,
                )
            }

            if (state.troubleCodes.isEmpty()) {
                item {
                    EmptyState(
                        connected = state.connectionState == ConnectionState.CONNECTED,
                        status = state.statusMessage,
                    )
                }
            } else {
                items(state.troubleCodes, key = { it.code }) { DtcCard(it) }
            }

            if (state.connectionState == ConnectionState.CONNECTED) {
                item {
                    OutlinedButton(
                        onClick = { showClearDialog = true },
                        enabled = !state.isBusy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, Danger.copy(alpha = 0.42f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Danger),
                    ) {
                        Icon(Icons.Rounded.DeleteSweep, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Effacer les défauts", fontWeight = FontWeight.Bold)
                    }
                }
            }

            item {
                Text(
                    "OBDfree 0.0.2  •  descriptions FR avec base OBDex en cache",
                    color = TextSecondary.copy(alpha = 0.62f),
                    fontSize = 10.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                )
            }
        }
    }

    if (showAdapters) {
        AdapterDialog(
            adapters = state.pairedAdapters,
            selected = state.selectedAdapter,
            onDismiss = { showAdapters = false },
            onSelect = {
                showAdapters = false
                onSelectAdapter(it)
            },
            onRefresh = onRefreshAdapters,
        )
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            containerColor = Color(0xFF0E1A23),
            title = {
                Text("Effacer les défauts ?", color = TextPrimary, fontWeight = FontWeight.Black)
            },
            text = {
                Text(
                    "À faire après avoir noté les codes. L’effacement remet aussi certains moniteurs OBD à zéro et ne répare pas la panne.",
                    color = TextSecondary,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearDialog = false
                        onClearCodes()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Danger,
                        contentColor = DeepInk,
                    ),
                ) {
                    Text("Effacer", fontWeight = FontWeight.Black)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Annuler", color = TextSecondary)
                }
            },
        )
    }
}

@Composable
private fun Header() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(17.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            Electric.copy(alpha = 0.25f),
                            Mint.copy(alpha = 0.08f),
                        ),
                    ),
                )
                .border(1.dp, Electric.copy(alpha = 0.30f), RoundedCornerShape(17.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.DirectionsCar, null, tint = Electric, modifier = Modifier.size(27.dp))
        }

        Spacer(Modifier.width(13.dp))

        Column(Modifier.weight(1f)) {
            Text(
                "OBDfree",
                color = TextPrimary,
                fontWeight = FontWeight.Black,
                fontSize = 25.sp,
                letterSpacing = (-0.8).sp,
            )
            Text(
                "Le diagnostic utile. Rien de plus.",
                color = TextSecondary,
                fontSize = 12.sp,
            )
        }

        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.055f))
                .border(1.dp, Color.White.copy(alpha = 0.09f), CircleShape)
                .padding(horizontal = 9.dp, vertical = 6.dp),
        ) {
            Text("0.0.2", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ConnectionCard(
    state: DiagnosticUiState,
    onPermission: () -> Unit,
    onChooseAdapter: () -> Unit,
    onConnect: () -> Unit,
) {
    GlassPanel {
        Column(verticalArrangement = Arrangement.spacedBy(15.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusOrb(state.connectionState)
                Spacer(Modifier.width(14.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        when (state.connectionState) {
                            ConnectionState.CONNECTED -> "Connecté"
                            ConnectionState.CONNECTING -> "Connexion…"
                            ConnectionState.INITIALIZING -> "Initialisation OBD-II…"
                            ConnectionState.ERROR -> "Connexion interrompue"
                            ConnectionState.DISCONNECTED -> "Bluetooth OBD"
                        },
                        color = TextPrimary,
                        fontWeight = FontWeight.Black,
                        fontSize = 18.sp,
                    )
                    Text(
                        state.selectedAdapter?.name
                            ?: if (state.permissionGranted) "Choisis ton adaptateur" else "Autorisation Bluetooth requise",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (state.connectionState == ConnectionState.CONNECTED) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Mint.copy(alpha = 0.11f))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text("AUTO", color = Mint, fontWeight = FontWeight.Black, fontSize = 9.sp)
                    }
                }
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.07f))

            when {
                !state.permissionGranted -> {
                    PremiumButton("Autoriser le Bluetooth", Icons.Rounded.Bluetooth, onPermission)
                }

                state.selectedAdapter == null -> {
                    PremiumButton("Choisir un adaptateur", Icons.Rounded.Bluetooth, onChooseAdapter)
                }

                state.connectionState == ConnectionState.CONNECTED -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable(onClick = onChooseAdapter)
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Memory, null, tint = Mint, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Adaptateur mémorisé", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text(
                                "Reconnexion automatique au prochain démarrage",
                                color = TextSecondary,
                                fontSize = 11.sp,
                            )
                        }
                        Text("Changer", color = Electric, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                else -> {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        PremiumButton(
                            text = if (state.isBusy) "Connexion…" else "Connecter ${state.selectedAdapter.name}",
                            icon = Icons.Rounded.BluetoothConnected,
                            onClick = onConnect,
                            enabled = !state.isBusy,
                        )
                        TextButton(
                            onClick = onChooseAdapter,
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        ) {
                            Text("Changer d’adaptateur", color = TextSecondary, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(count: Int, connected: Boolean, status: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 3.dp, top = 4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Défauts", color = TextPrimary, fontWeight = FontWeight.Black, fontSize = 18.sp)
            Text(
                when {
                    count > 0 -> "$count code(s) lu(s) sur le véhicule"
                    connected -> status
                    else -> "Connecte le véhicule pour lancer une lecture"
                },
                color = TextSecondary,
                fontSize = 11.sp,
            )
        }
        if (count > 0) {
            Text(
                count.toString(),
                color = Danger,
                fontWeight = FontWeight.Black,
                fontSize = 19.sp,
            )
        }
    }
}

@Composable
private fun DtcCard(code: TroubleCode) {
    GlassPanel {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Danger.copy(alpha = 0.11f))
                        .border(1.dp, Danger.copy(alpha = 0.23f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 11.dp, vertical = 7.dp),
                ) {
                    Text(code.code, color = Danger, fontWeight = FontWeight.Black, fontSize = 15.sp)
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    code.category,
                    color = TextSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f),
                )
            }

            Text(
                code.description ?: "Défaut OBD-II détecté.",
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
        }
    }
}

@Composable
private fun EmptyState(connected: Boolean, status: String) {
    GlassPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (connected && status.startsWith("Aucun défaut")) Icons.Rounded.CheckCircle
                else Icons.Rounded.ErrorOutline,
                null,
                tint = if (connected && status.startsWith("Aucun défaut")) Mint else TextSecondary,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(13.dp))
            Column {
                Text(
                    if (connected && status.startsWith("Aucun défaut")) "Aucun défaut détecté" else "Aucune lecture pour le moment",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    if (connected) "Appuie sur “Lire les codes défaut”." else "La reconnexion au dernier dongle se fait automatiquement.",
                    color = TextSecondary,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun AdapterDialog(
    adapters: List<BluetoothAdapterItem>,
    selected: BluetoothAdapterItem?,
    onDismiss: () -> Unit,
    onSelect: (BluetoothAdapterItem) -> Unit,
    onRefresh: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0E1A23),
        title = {
            Text("Adaptateur Bluetooth", color = TextPrimary, fontWeight = FontWeight.Black)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (adapters.isEmpty()) {
                    Text(
                        "Aucun appareil appairé. Appaire d’abord ton dongle OBD dans Android, puis actualise.",
                        color = TextSecondary,
                        fontSize = 12.sp,
                    )
                } else {
                    adapters.forEach { adapter ->
                        val active = adapter.address == selected?.address
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(15.dp))
                                .background(if (active) Electric.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.025f))
                                .clickable { onSelect(adapter) }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (active) Icons.Rounded.BluetoothConnected else Icons.Rounded.Bluetooth,
                                null,
                                tint = if (active) Electric else TextSecondary,
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(adapter.name, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text(adapter.address, color = TextSecondary, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onRefresh) { Text("Actualiser", color = Electric) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Fermer", color = TextSecondary) }
        },
    )
}

@Composable
private fun PremiumButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Electric,
            contentColor = DeepInk,
            disabledContainerColor = Electric.copy(alpha = 0.15f),
            disabledContentColor = TextSecondary,
        ),
    ) {
        Icon(icon, null)
        Spacer(Modifier.width(9.dp))
        Text(text, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun GlassPanel(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(18.dp, RoundedCornerShape(24.dp))
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        GlassStrong,
                        Glass,
                        Color.White.copy(alpha = 0.028f),
                    ),
                ),
            )
            .border(
                1.dp,
                Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = 0.20f),
                        GlassBorder.copy(alpha = 0.52f),
                        Electric.copy(alpha = 0.08f),
                    ),
                ),
                RoundedCornerShape(24.dp),
            )
            .padding(18.dp),
    ) { content() }
}

@Composable
private fun StatusOrb(state: ConnectionState) {
    val transition = rememberInfiniteTransition(label = "obd-status")
    val pulse by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), repeatMode = RepeatMode.Reverse),
        label = "obd-status-pulse",
    )

    val color = when (state) {
        ConnectionState.CONNECTED -> Mint
        ConnectionState.ERROR -> Danger
        else -> Electric
    }
    val animated = state == ConnectionState.CONNECTING || state == ConnectionState.INITIALIZING

    Box(
        modifier = Modifier
            .size(54.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = if (animated) 0.12f * pulse else 0.11f))
            .border(1.dp, color.copy(alpha = if (animated) 0.40f * pulse else 0.30f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (state == ConnectionState.CONNECTED) Icons.Rounded.BluetoothConnected else Icons.Rounded.Bluetooth,
            null,
            tint = color,
            modifier = Modifier.size(25.dp),
        )
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(17.dp))
            .background(Danger.copy(alpha = 0.08f))
            .border(1.dp, Danger.copy(alpha = 0.22f), RoundedCornerShape(17.dp))
            .padding(13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.ErrorOutline, null, tint = Danger, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(message, color = Color(0xFFFFBBC4), fontSize = 12.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun AmbientGlow(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "ambient")
    val glowAlpha by transition.animateFloat(
        initialValue = 0.36f,
        targetValue = 0.62f,
        animationSpec = infiniteRepeatable(tween(3_800), repeatMode = RepeatMode.Reverse),
        label = "ambient-alpha",
    )

    Box(
        modifier = modifier
            .size(270.dp)
            .alpha(glowAlpha)
            .background(
                Brush.radialGradient(
                    listOf(
                        Electric.copy(alpha = 0.10f),
                        Electric.copy(alpha = 0.018f),
                        Color.Transparent,
                    ),
                ),
                CircleShape,
            ),
    )
}
