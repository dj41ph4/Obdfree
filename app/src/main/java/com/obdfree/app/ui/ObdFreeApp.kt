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
import com.obdfree.app.BuildConfig
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
                        Color(0xFF071722),
                        DeepInk,
                        Color(0xFF040A0F),
                    ),
                ),
            ),
    ) {
        AmbientGlow(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 28.dp),
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
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
                        .height(60.dp),
                    shape = RoundedCornerShape(21.dp),
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
                        Icon(Icons.Rounded.Refresh, contentDescription = null)
                    }
                    Spacer(Modifier.width(9.dp))
                    Text(
                        text = if (state.isBusy) state.statusMessage else "Lire les codes défaut",
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
                items(state.troubleCodes, key = { it.code }) { code ->
                    DtcCard(code)
                }
            }

            if (state.connectionState == ConnectionState.CONNECTED) {
                item {
                    OutlinedButton(
                        onClick = { showClearDialog = true },
                        enabled = !state.isBusy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(58.dp),
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, Danger.copy(alpha = 0.42f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Danger),
                    ) {
                        Icon(Icons.Rounded.DeleteSweep, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Effacer les défauts", fontWeight = FontWeight.Bold)
                    }
                }
            }

            item {
                Text(
                    text = "OBDfree ${BuildConfig.VERSION_NAME}  •  descriptions françaises",
                    color = TextSecondary.copy(alpha = 0.58f),
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
                Text(
                    text = "Effacer les défauts ?",
                    color = TextPrimary,
                    fontWeight = FontWeight.Black,
                )
            },
            text = {
                Text(
                    text = "À faire après avoir noté les codes. L’effacement remet aussi certains moniteurs OBD à zéro et ne répare pas la panne.",
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
            .padding(top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(50.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            Electric.copy(alpha = 0.26f),
                            Mint.copy(alpha = 0.08f),
                        ),
                    ),
                )
                .border(1.dp, Electric.copy(alpha = 0.32f), RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.DirectionsCar,
                contentDescription = null,
                tint = Electric,
                modifier = Modifier.size(28.dp),
            )
        }

        Spacer(Modifier.width(14.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = "OBDfree",
                color = TextPrimary,
                fontWeight = FontWeight.Black,
                fontSize = 26.sp,
                letterSpacing = (-0.8).sp,
            )
            Text(
                text = "Le diagnostic utile. Rien de plus.",
                color = TextSecondary,
                fontSize = 12.sp,
            )
        }

        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.055f))
                .border(1.dp, Color.White.copy(alpha = 0.09f), CircleShape)
                .padding(horizontal = 10.dp, vertical = 7.dp),
        ) {
            Text(
                text = BuildConfig.VERSION_NAME,
                color = TextSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
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
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusOrb(state.connectionState)
                Spacer(Modifier.width(14.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        text = when (state.connectionState) {
                            ConnectionState.CONNECTED -> "Connecté"
                            ConnectionState.CONNECTING -> "Connexion…"
                            ConnectionState.INITIALIZING -> "Initialisation OBD-II…"
                            ConnectionState.ERROR -> "Connexion interrompue"
                            ConnectionState.DISCONNECTED -> "Bluetooth OBD"
                        },
                        color = TextPrimary,
                        fontWeight = FontWeight.Black,
                        fontSize = 19.sp,
                    )
                    Text(
                        text = state.selectedAdapter?.name
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
                    PremiumButton(
                        text = "Autoriser le Bluetooth",
                        iconConnected = false,
                        onClick = onPermission,
                    )
                }

                state.selectedAdapter == null -> {
                    PremiumButton(
                        text = "Choisir un adaptateur",
                        iconConnected = false,
                        onClick = onChooseAdapter,
                    )
                }

                state.connectionState == ConnectionState.CONNECTED -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable(onClick = onChooseAdapter)
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Memory,
                            contentDescription = null,
                            tint = Mint,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "Adaptateur mémorisé",
                                color = TextPrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                            )
                            Text(
                                text = "Reconnexion automatique au prochain démarrage",
                                color = TextSecondary,
                                fontSize = 11.sp,
                            )
                        }
                        Text(
                            text = "Changer",
                            color = Electric,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                else -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PremiumButton(
                            text = if (state.isBusy) "Connexion…" else "Connecter ${state.selectedAdapter.name}",
                            iconConnected = true,
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
private fun PremiumButton(
    text: String,
    iconConnected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
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
        Icon(
            imageVector = if (iconConnected) Icons.Rounded.BluetoothConnected else Icons.Rounded.Bluetooth,
            contentDescription = null,
        )
        Spacer(Modifier.width(9.dp))
        Text(text, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun SectionHeader(count: Int, connected: Boolean, status: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 3.dp, top = 5.dp, end = 3.dp, bottom = 0.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "Défauts",
                color = TextPrimary,
                fontWeight = FontWeight.Black,
                fontSize = 19.sp,
            )
            Text(
                text = when {
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
                text = count.toString(),
                color = Danger,
                fontWeight = FontWeight.Black,
                fontSize = 20.sp,
            )
        }
    }
}

@Composable
private fun DtcCard(code: TroubleCode) {
    GlassPanel {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Danger.copy(alpha = 0.11f))
                        .border(1.dp, Danger.copy(alpha = 0.23f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 11.dp, vertical = 7.dp),
                ) {
                    Text(
                        text = code.code,
                        color = Danger,
                        fontWeight = FontWeight.Black,
                        fontSize = 15.sp,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = code.category,
                    color = TextSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f),
                )
            }

            Text(
                text = code.description ?: "Défaut OBD-II détecté.",
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
    val clean = connected && status.startsWith("Aucun défaut")

    GlassPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (clean) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = if (clean) Mint else TextSecondary,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(13.dp))
            Column {
                Text(
                    text = if (clean) "Aucun défaut détecté" else "Aucune lecture pour le moment",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = if (connected) "Appuie sur Lire les codes défaut." else "Connecte ton adaptateur OBD-II.",
                    color = TextSecondary,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Danger.copy(alpha = 0.08f))
            .border(1.dp, Danger.copy(alpha = 0.25f), RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.ErrorOutline,
            contentDescription = null,
            tint = Danger,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(11.dp))
        Text(
            text = message,
            color = Color(0xFFFFB8C1),
            fontSize = 12.sp,
            lineHeight = 17.sp,
            modifier = Modifier.weight(1f),
        )
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
            Text("Choisir l’adaptateur", color = TextPrimary, fontWeight = FontWeight.Black)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (adapters.isEmpty()) {
                    Text(
                        text = "Aucun appareil appairé. Appaire d’abord ton dongle OBD dans les réglages Bluetooth Android.",
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
                                .background(
                                    if (active) Electric.copy(alpha = 0.10f)
                                    else Color.White.copy(alpha = 0.025f),
                                )
                                .clickable { onSelect(adapter) }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Bluetooth,
                                contentDescription = null,
                                tint = if (active) Electric else TextSecondary,
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = adapter.name,
                                    color = TextPrimary,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp,
                                )
                                Text(
                                    text = adapter.address,
                                    color = TextSecondary,
                                    fontSize = 10.sp,
                                )
                            }
                            if (active) {
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = null,
                                    tint = Mint,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onRefresh) {
                Text("Actualiser", color = Electric)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Fermer", color = TextSecondary)
            }
        },
    )
}

@Composable
private fun GlassPanel(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 22.dp,
                shape = RoundedCornerShape(26.dp),
                ambientColor = Color.Black.copy(alpha = 0.28f),
                spotColor = Color.Black.copy(alpha = 0.28f),
            )
            .clip(RoundedCornerShape(26.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        GlassStrong,
                        Glass,
                        Color.White.copy(alpha = 0.03f),
                    ),
                ),
            )
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = 0.24f),
                        GlassBorder.copy(alpha = 0.55f),
                        Electric.copy(alpha = 0.09f),
                    ),
                ),
                shape = RoundedCornerShape(26.dp),
            )
            .padding(18.dp),
    ) {
        content()
    }
}

@Composable
private fun StatusOrb(state: ConnectionState) {
    val transition = rememberInfiniteTransition(label = "obd-status")
    val pulse by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(950),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "obd-pulse",
    )

    val color = when (state) {
        ConnectionState.CONNECTED -> Mint
        ConnectionState.CONNECTING,
        ConnectionState.INITIALIZING -> Electric
        ConnectionState.ERROR -> Danger
        ConnectionState.DISCONNECTED -> Electric
    }

    val alpha = when (state) {
        ConnectionState.CONNECTING,
        ConnectionState.INITIALIZING -> 0.10f + (0.08f * pulse)
        else -> 0.12f
    }

    Box(
        modifier = Modifier
            .size(54.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = alpha))
            .border(1.dp, color.copy(alpha = 0.32f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (state == ConnectionState.CONNECTED) {
                Icons.Rounded.BluetoothConnected
            } else {
                Icons.Rounded.Bluetooth
            },
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(27.dp),
        )
    }
}

@Composable
private fun AmbientGlow(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "ambient")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.65f,
        animationSpec = infiniteRepeatable(
            animation = tween(4200),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ambient-alpha",
    )

    Box(
        modifier = modifier
            .size(270.dp)
            .alpha(alpha)
            .background(
                brush = Brush.radialGradient(
                    listOf(
                        Electric.copy(alpha = 0.11f),
                        Electric.copy(alpha = 0.02f),
                        Color.Transparent,
                    ),
                ),
                shape = CircleShape,
            ),
    )
}
