package com.obdfree.app.ui

import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.obdfree.app.model.BluetoothAdapterItem
import com.obdfree.app.model.ConnectionState
import com.obdfree.app.model.DiagnosticUiState
import com.obdfree.app.model.TroubleCode
import com.obdfree.app.ui.theme.*

@Composable
fun ObdFreeApp(
    state: DiagnosticUiState,
    onRequestBluetoothPermission: () -> Unit,
    onRefreshAdapters: () -> Unit,
    onSelectAdapter: (BluetoothAdapterItem) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onScan: () -> Unit,
    onRefreshLive: () -> Unit,
    onClearCodes: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }
    var showAdapters by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF07131D), DeepInk, Color(0xFF061018)),
                ),
            ),
    ) {
        AmbientGlow(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 34.dp),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Header(state)

            AnimatedVisibility(
                visible = state.errorMessage != null,
                modifier = Modifier.padding(horizontal = 18.dp),
            ) {
                ErrorBanner(state.errorMessage.orEmpty())
            }

            AnimatedContent(
                targetState = tab,
                modifier = Modifier.weight(1f),
                label = "obdfree-page",
            ) { page ->
                when (page) {
                    0 -> DiagnosticPage(
                        state = state,
                        onPermission = onRequestBluetoothPermission,
                        onChooseAdapter = { showAdapters = true },
                        onConnect = onConnect,
                        onDisconnect = onDisconnect,
                        onScan = onScan,
                        onClear = { showClearDialog = true },
                    )

                    1 -> LivePage(state, onRefreshLive)

                    else -> ConnectionPage(
                        state = state,
                        onPermission = onRequestBluetoothPermission,
                        onRefreshAdapters = onRefreshAdapters,
                        onChooseAdapter = { showAdapters = true },
                        onConnect = onConnect,
                        onDisconnect = onDisconnect,
                    )
                }
            }

            PremiumNavBar(tab) { tab = it }
        }
    }

    if (showAdapters) {
        AdapterDialog(
            adapters = state.pairedAdapters,
            selected = state.selectedAdapter,
            onDismiss = { showAdapters = false },
            onSelect = {
                onSelectAdapter(it)
                showAdapters = false
            },
            onRefresh = onRefreshAdapters,
        )
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            containerColor = Color(0xFF101B23),
            title = {
                Text(
                    "Effacer les défauts ?",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Text(
                    "La commande OBD-II Mode 04 peut effacer les codes, les freeze frames et remettre les moniteurs d’émissions à non prêts. Elle ne répare pas la panne.",
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
                    Text("Effacer", fontWeight = FontWeight.Bold)
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
private fun Header(state: DiagnosticUiState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            Electric.copy(alpha = 0.28f),
                            Mint.copy(alpha = 0.09f),
                        ),
                    ),
                )
                .border(1.dp, Electric.copy(alpha = 0.30f), RoundedCornerShape(15.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.DirectionsCar, null, tint = Electric)
        }

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                "OBDfree",
                color = TextPrimary,
                fontWeight = FontWeight.Black,
                fontSize = 22.sp,
                letterSpacing = (-0.6).sp,
            )
            Text(
                "Diagnostic automobile • local & privé",
                color = TextSecondary,
                fontSize = 12.sp,
            )
        }

        ConnectionPill(state.connectionState)
    }
}

@Composable
private fun DiagnosticPage(
    state: DiagnosticUiState,
    onPermission: () -> Unit,
    onChooseAdapter: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onScan: () -> Unit,
    onClear: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            VehicleHero(
                state = state,
                onPermission = onPermission,
                onChooseAdapter = onChooseAdapter,
                onConnect = onConnect,
                onDisconnect = onDisconnect,
                onScan = onScan,
            )
        }

        item {
            SectionTitle(
                "Diagnostic",
                if (state.troubleCodes.isEmpty()) {
                    "Codes défaut OBD-II génériques"
                } else {
                    "${state.troubleCodes.size} code(s) détecté(s)"
                },
            )
        }

        if (state.troubleCodes.isEmpty()) {
            item { EmptyDiagnosticCard(state.connectionState == ConnectionState.CONNECTED) }
        } else {
            items(state.troubleCodes, key = { it.code }) { DtcCard(it) }
            item {
                OutlinedButton(
                    onClick = onClear,
                    enabled = !state.isBusy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, Danger.copy(alpha = 0.45f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Danger),
                ) {
                    Icon(Icons.Rounded.DeleteSweep, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Effacer les informations diagnostic")
                }
            }
        }

        item { Spacer(Modifier.height(6.dp)) }
    }
}

@Composable
private fun VehicleHero(
    state: DiagnosticUiState,
    onPermission: () -> Unit,
    onChooseAdapter: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onScan: () -> Unit,
) {
    GlassPanel {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusOrb(state.connectionState)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when (state.connectionState) {
                            ConnectionState.CONNECTED -> "Véhicule connecté"
                            ConnectionState.CONNECTING -> "Connexion Bluetooth"
                            ConnectionState.INITIALIZING -> "Initialisation OBD-II"
                            ConnectionState.ERROR -> "Connexion interrompue"
                            ConnectionState.DISCONNECTED -> "Prêt à diagnostiquer"
                        },
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 19.sp,
                    )
                    Text(
                        state.protocol?.takeIf(String::isNotBlank) ?: state.statusMessage,
                        color = TextSecondary,
                        fontSize = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

            AdapterRow(state, onPermission, onChooseAdapter)

            when {
                !state.permissionGranted -> PrimaryButton(
                    "Autoriser le Bluetooth",
                    Icons.Rounded.Bluetooth,
                    onPermission,
                )

                state.connectionState == ConnectionState.CONNECTED -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrimaryButton(
                            text = "Scanner",
                            icon = Icons.Rounded.Refresh,
                            onClick = onScan,
                            enabled = !state.isBusy,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(
                            onClick = onDisconnect,
                            modifier = Modifier.height(54.dp),
                            shape = RoundedCornerShape(18.dp),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
                        ) {
                            Text("Déconnecter", color = TextSecondary)
                        }
                    }
                }

                else -> PrimaryButton(
                    text = if (state.isBusy) "Connexion…" else "Connecter l’adaptateur",
                    icon = Icons.Rounded.BluetoothConnected,
                    onClick = onConnect,
                    enabled = !state.isBusy && state.selectedAdapter != null,
                )
            }

            AnimatedVisibility(state.isBusy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = Electric,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(state.statusMessage, color = TextSecondary, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun AdapterRow(
    state: DiagnosticUiState,
    onPermission: () -> Unit,
    onChooseAdapter: () -> Unit,
) {
    val adapter = state.selectedAdapter

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = state.permissionGranted, onClick = onChooseAdapter)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Memory, null, tint = Electric, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                adapter?.name ?: "Aucun adaptateur sélectionné",
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
            )
            Text(
                when {
                    !state.permissionGranted -> "Autorisation Bluetooth nécessaire"
                    adapter != null -> adapter.address
                    else -> "Appuie pour choisir un appareil appairé"
                },
                color = TextSecondary,
                fontSize = 11.sp,
            )
        }

        if (!state.permissionGranted) {
            TextButton(onClick = onPermission) { Text("Autoriser", color = Electric) }
        } else {
            Text("Changer", color = Electric, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun EmptyDiagnosticCard(connected: Boolean) {
    GlassPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (connected) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                null,
                tint = if (connected) Mint else TextSecondary,
                modifier = Modifier.size(30.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    if (connected) "Aucun défaut affiché" else "Pas encore de diagnostic",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    if (connected) "Lance un scan pour relire les codes présents."
                    else "Connecte un adaptateur ELM327 pour lire le véhicule.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun DtcCard(code: TroubleCode) {
    GlassPanel {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(13.dp))
                    .background(Danger.copy(alpha = 0.12f))
                    .border(1.dp, Danger.copy(alpha = 0.24f), RoundedCornerShape(13.dp))
                    .padding(horizontal = 11.dp, vertical = 8.dp),
            ) {
                Text(code.code, color = Danger, fontWeight = FontWeight.Black, fontSize = 15.sp)
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    code.description ?: "Code OBD-II détecté",
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.height(3.dp))
                Text(code.category, color = TextSecondary, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun LivePage(state: DiagnosticUiState, onRefresh: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { SectionTitle("Données live", "Valeurs OBD-II standard • Mode 01") }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricCard(
                    Modifier.weight(1f),
                    Icons.Rounded.Speed,
                    "Régime",
                    state.liveData.rpm?.toString() ?: "—",
                    "tr/min",
                    Electric,
                )
                MetricCard(
                    Modifier.weight(1f),
                    Icons.Rounded.DirectionsCar,
                    "Vitesse",
                    state.liveData.speedKph?.toString() ?: "—",
                    "km/h",
                    Mint,
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricCard(
                    Modifier.weight(1f),
                    Icons.Rounded.Thermostat,
                    "Liquide",
                    state.liveData.coolantCelsius?.toString() ?: "—",
                    "°C",
                    Amber,
                )
                MetricCard(
                    Modifier.weight(1f),
                    Icons.Rounded.SettingsEthernet,
                    "Papillon",
                    state.liveData.throttlePercent?.let { "%.0f".format(it) } ?: "—",
                    "%",
                    ElectricStrong,
                )
            }
        }

        item {
            PrimaryButton(
                "Actualiser les valeurs",
                Icons.Rounded.Refresh,
                onRefresh,
                enabled = state.connectionState == ConnectionState.CONNECTED && !state.isBusy,
            )
        }

        item {
            GlassPanel {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("À venir", color = TextPrimary, fontWeight = FontWeight.Bold)
                    Text(
                        "Graphiques haute fréquence, enregistrement CSV, sélection automatique des PID supportés, freeze-frame et readiness.",
                        color = TextSecondary,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectionPage(
    state: DiagnosticUiState,
    onPermission: () -> Unit,
    onRefreshAdapters: () -> Unit,
    onChooseAdapter: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { SectionTitle("Connexion", "Bluetooth Classic • ELM327 SPP") }
        item {
            GlassPanel {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    KeyValue("État", state.connectionState.name)
                    KeyValue("Adaptateur", state.adapterIdentity ?: state.selectedAdapter?.name ?: "—")
                    KeyValue("Adresse", state.selectedAdapter?.address ?: "—")
                    KeyValue("Protocole véhicule", state.protocol ?: "Auto / non détecté")
                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                    if (!state.permissionGranted) {
                        PrimaryButton("Autoriser le Bluetooth", Icons.Rounded.Bluetooth, onPermission)
                    } else {
                        OutlinedButton(
                            onClick = onChooseAdapter,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape = RoundedCornerShape(18.dp),
                        ) { Text("Choisir l’adaptateur") }

                        OutlinedButton(
                            onClick = onRefreshAdapters,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape = RoundedCornerShape(18.dp),
                        ) {
                            Icon(Icons.Rounded.Refresh, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Relire les appareils appairés")
                        }

                        if (state.connectionState == ConnectionState.CONNECTED) {
                            OutlinedButton(
                                onClick = onDisconnect,
                                modifier = Modifier.fillMaxWidth().height(52.dp),
                                shape = RoundedCornerShape(18.dp),
                            ) { Text("Déconnecter") }
                        } else {
                            PrimaryButton(
                                "Connecter",
                                Icons.Rounded.BluetoothConnected,
                                onConnect,
                                enabled = state.selectedAdapter != null && !state.isBusy,
                            )
                        }
                    }
                }
            }
        }

        item {
            GlassPanel {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Compatibilité V0.1", color = TextPrimary, fontWeight = FontWeight.Bold)
                    Text(
                        "Adaptateurs Bluetooth Classic SPP compatibles ELM327. Les dongles BLE uniquement seront pris en charge par un transport séparé.",
                        color = TextSecondary,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun PremiumNavBar(selected: Int, onSelected: (Int) -> Unit) {
    val tabs = listOf(
        Triple(Icons.Rounded.ErrorOutline, "Diagnostic", 0),
        Triple(Icons.Rounded.Speed, "Live", 1),
        Triple(Icons.Rounded.Bluetooth, "Connexion", 2),
    )

    Surface(
        color = Color(0xD90A1219),
        modifier = Modifier
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(24.dp))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(24.dp)),
    ) {
        Row(Modifier.fillMaxWidth().padding(6.dp)) {
            tabs.forEach { (icon, title, index) ->
                val active = selected == index
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (active) Electric.copy(alpha = 0.12f) else Color.Transparent)
                        .clickable { onSelected(index) }
                        .padding(vertical = 9.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Icon(
                        icon,
                        title,
                        tint = if (active) Electric else TextSecondary,
                        modifier = Modifier.size(21.dp),
                    )
                    Text(
                        title,
                        color = if (active) TextPrimary else TextSecondary,
                        fontSize = 10.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

@Composable
private fun GlassPanel(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .shadow(20.dp, RoundedCornerShape(24.dp))
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.linearGradient(
                    listOf(GlassStrong, Glass, Color.White.copy(alpha = 0.035f)),
                ),
            )
            .border(
                1.dp,
                Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = 0.22f),
                        GlassBorder.copy(alpha = 0.55f),
                        Electric.copy(alpha = 0.10f),
                    ),
                ),
                RoundedCornerShape(24.dp),
            )
            .padding(18.dp),
    ) { content() }
}

@Composable
private fun MetricCard(
    modifier: Modifier,
    icon: ImageVector,
    label: String,
    value: String,
    unit: String,
    accent: Color,
) {
    GlassPanel(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(22.dp))
            Text(label, color = TextSecondary, fontSize = 11.sp)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    value,
                    color = TextPrimary,
                    fontSize = 27.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-0.7).sp,
                )
                Spacer(Modifier.width(5.dp))
                Text(unit, color = TextSecondary, fontSize = 10.sp, modifier = Modifier.padding(bottom = 4.dp))
            }
        }
    }
}

@Composable
private fun PrimaryButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(54.dp),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Electric,
            contentColor = DeepInk,
            disabledContainerColor = Electric.copy(alpha = 0.16f),
            disabledContentColor = TextSecondary,
        ),
    ) {
        Icon(icon, null)
        Spacer(Modifier.width(9.dp))
        Text(text, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column(Modifier.padding(horizontal = 3.dp, vertical = 2.dp)) {
        Text(title, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Black)
        Text(subtitle, color = TextSecondary, fontSize = 11.sp)
    }
}

@Composable
private fun StatusOrb(state: ConnectionState) {
    val connected = state == ConnectionState.CONNECTED
    val busy = state == ConnectionState.CONNECTING || state == ConnectionState.INITIALIZING
    val transition = rememberInfiniteTransition(label = "status-orb")
    val pulse by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(950), repeatMode = RepeatMode.Reverse),
        label = "status-pulse",
    )

    val color = when {
        connected -> Mint
        state == ConnectionState.ERROR -> Danger
        else -> Electric
    }

    Box(
        modifier = Modifier
            .size(54.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = if (busy) 0.12f * pulse else 0.12f))
            .border(1.dp, color.copy(alpha = if (busy) 0.35f * pulse else 0.35f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (connected) Icons.Rounded.BluetoothConnected else Icons.Rounded.Bluetooth,
            null,
            tint = color,
        )
    }
}

@Composable
private fun ConnectionPill(state: ConnectionState) {
    val color = when (state) {
        ConnectionState.CONNECTED -> Mint
        ConnectionState.CONNECTING, ConnectionState.INITIALIZING -> Electric
        ConnectionState.ERROR -> Danger
        ConnectionState.DISCONNECTED -> TextSecondary
    }

    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.08f))
            .border(1.dp, color.copy(alpha = 0.18f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(
            when (state) {
                ConnectionState.CONNECTED -> "Connecté"
                ConnectionState.CONNECTING -> "Connexion"
                ConnectionState.INITIALIZING -> "Init"
                ConnectionState.ERROR -> "Erreur"
                ConnectionState.DISCONNECTED -> "Hors ligne"
            },
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Danger.copy(alpha = 0.09f))
            .border(1.dp, Danger.copy(alpha = 0.24f), RoundedCornerShape(16.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.ErrorOutline, null, tint = Danger, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(message, color = Color(0xFFFFB3BE), fontSize = 12.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun KeyValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(
            value,
            color = TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1.35f),
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
        containerColor = Color(0xFF101B23),
        title = {
            Text("Adaptateur OBD", color = TextPrimary, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (adapters.isEmpty()) {
                    Text(
                        "Aucun appareil appairé. Appaire d’abord ton dongle OBD dans les réglages Bluetooth Android.",
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
                                Icons.Rounded.Bluetooth,
                                null,
                                tint = if (active) Electric else TextSecondary,
                            )
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    adapter.name,
                                    color = TextPrimary,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp,
                                )
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
private fun AmbientGlow(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "ambient-glow")
    val glowAlpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.65f,
        animationSpec = infiniteRepeatable(tween(4_000), repeatMode = RepeatMode.Reverse),
        label = "ambient-alpha",
    )

    Box(
        modifier = modifier
            .size(260.dp)
            .alpha(glowAlpha)
            .background(
                Brush.radialGradient(
                    listOf(
                        Electric.copy(alpha = 0.11f),
                        Electric.copy(alpha = 0.02f),
                        Color.Transparent,
                    ),
                ),
                CircleShape,
            ),
    )
}
