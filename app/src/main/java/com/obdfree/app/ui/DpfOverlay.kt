package com.obdfree.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.obdfree.app.model.DpfSnapshot
import com.obdfree.app.model.DpfUiState
import com.obdfree.app.ui.theme.Danger
import com.obdfree.app.ui.theme.DeepInk
import com.obdfree.app.ui.theme.Electric
import com.obdfree.app.ui.theme.GlassStrong
import com.obdfree.app.ui.theme.Mint
import com.obdfree.app.ui.theme.TextPrimary
import com.obdfree.app.ui.theme.TextSecondary
import kotlin.math.roundToInt

@Composable
fun DpfOverlay(
    state: DpfUiState,
    continuousClearEnabled: Boolean,
    onAutoDetectionChanged: (Boolean) -> Unit,
    onRefresh: () -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    val snapshot = state.snapshot
    val regen = snapshot?.regenerationActive == true

    Box(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(end = 18.dp, bottom = 18.dp),
        contentAlignment = Alignment.BottomEnd,
    ) {
        Row(
            modifier = Modifier
                .background(
                    color = if (regen) Danger.copy(alpha = 0.94f) else GlassStrong.copy(alpha = 0.96f),
                    shape = RoundedCornerShape(18.dp),
                )
                .border(
                    width = 1.dp,
                    color = if (regen) Danger else Electric.copy(alpha = 0.34f),
                    shape = RoundedCornerShape(18.dp),
                )
                .clickable { showDialog = true }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (regen) "🔥" else "FAP",
                color = if (regen) DeepInk else Electric,
                fontWeight = FontWeight.Black,
                fontSize = 12.sp,
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = when {
                    regen -> "REGEN ${snapshot.regenerationProgressPercent?.roundToInt() ?: 0}%"
                    snapshot?.sootPercent != null -> "${snapshot.sootPercent.roundToInt()}%"
                    state.autoDetectionEnabled -> "AUTO"
                    else -> ""
                },
                color = if (regen) DeepInk else TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
            )
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            containerColor = Color(0xFF0E1A23),
            title = {
                Column {
                    Text("FAP / Régénération", color = TextPrimary, fontWeight = FontWeight.Black)
                    snapshot?.profile?.let {
                        Text(it, color = TextSecondary, fontSize = 11.sp)
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 530.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Détection automatique",
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "Surveille la régénération toutes les 2 s",
                                color = TextSecondary,
                                fontSize = 11.sp,
                            )
                        }
                        Switch(
                            checked = state.autoDetectionEnabled,
                            onCheckedChange = onAutoDetectionChanged,
                            enabled = !continuousClearEnabled,
                        )
                    }

                    if (continuousClearEnabled) {
                        InfoLine(
                            "Détection FAP suspendue pendant l’effacement continu.",
                            Danger,
                        )
                    }

                    if (state.busy) {
                        InfoLine("Lecture FAP en cours…", Electric)
                    }

                    state.errorMessage?.let { InfoLine(it, Danger) }

                    snapshot?.let { DpfMetrics(it) }

                    if (snapshot == null && state.errorMessage == null) {
                        Text(
                            "Appuie sur Actualiser pour détecter automatiquement les PIDs FAP compatibles avec ton calculateur.",
                            color = TextSecondary,
                            fontSize = 12.sp,
                        )
                    }

                    OutlinedButton(
                        onClick = onRefresh,
                        enabled = !state.busy && !continuousClearEnabled,
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, Electric.copy(alpha = 0.45f)),
                    ) {
                        Text("Actualiser les données FAP", color = Electric, fontWeight = FontWeight.Bold)
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.White.copy(alpha = 0.035f), RoundedCornerShape(15.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "Régénération service",
                            color = TextPrimary,
                            fontWeight = FontWeight.Black,
                        )
                        Text(
                            state.serviceRegenerationReason,
                            color = TextSecondary,
                            fontSize = 11.sp,
                        )
                        Button(
                            onClick = {},
                            enabled = state.serviceRegenerationAvailable,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Danger,
                                contentColor = DeepInk,
                            ),
                        ) {
                            Text("Forcer une régénération", fontWeight = FontWeight.Black)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("Fermer", color = Electric)
                }
            },
        )
    }
}

@Composable
private fun DpfMetrics(snapshot: DpfSnapshot) {
    val regen = snapshot.regenerationActive == true

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        InfoLine(
            text = when {
                regen -> "Régénération active • ${snapshot.regenerationProgressPercent?.roundToInt() ?: 0}%"
                snapshot.regenerationActive == false -> "Régénération inactive"
                else -> "État de régénération inconnu"
            },
            color = if (regen) Danger else Mint,
        )

        Metric("Saturation suie", snapshot.sootPercent?.let { "${it.roundToInt()} %" })
        Metric("Température FAP", snapshot.exhaustTemperatureC?.let { "${it.roundToInt()} °C" })
        Metric("Pression différentielle", snapshot.differentialPressureMbar?.let { "${it.roundToInt()} mbar" })
        Metric("Depuis dernière régénération", snapshot.distanceSinceRegenerationKm?.let { "$it km" })
        Metric("Distance moyenne", snapshot.averageDistanceBetweenRegenerationsKm?.let { "$it km" })
        Metric(
            "Durée moyenne",
            snapshot.averageRegenerationDurationSeconds?.let { seconds ->
                val minutes = seconds / 60
                val rest = seconds % 60
                if (minutes > 0) "${minutes}m ${rest}s" else "${rest}s"
            },
        )
        Metric("Régénérations interrompues", snapshot.interruptedRegenerations?.toString())
        Metric("Liquide refroidissement", snapshot.coolantTemperatureC?.let { "$it °C" })
        Metric("Huile moteur", snapshot.oilTemperatureC?.let { "$it °C" })
        Metric("Régime moteur", snapshot.engineRpm?.let { "$it tr/min" })

        val naturalReady = snapshot.sootPercent?.let { it >= 80f } == true &&
            snapshot.coolantTemperatureC?.let { it >= 70 } == true &&
            snapshot.engineRpm?.let { it > 0 } == true
        InfoLine(
            text = if (naturalReady) {
                "Conditions principales de régénération automatique réunies"
            } else {
                "Conditions principales de régénération automatique non encore réunies"
            },
            color = if (naturalReady) Mint else TextSecondary,
        )
    }
}

@Composable
private fun Metric(label: String, value: String?) {
    if (value == null) return

    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, color = TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(value, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

@Composable
private fun InfoLine(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}
