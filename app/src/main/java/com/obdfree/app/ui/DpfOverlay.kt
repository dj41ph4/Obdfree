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
import com.obdfree.app.model.AstraJEcuIdentity
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
    onProbeServiceControl: () -> Unit,
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
                    regen && snapshot?.regenerationProgressPercent != null ->
                        "REGEN ${snapshot.regenerationProgressPercent.roundToInt()}%"
                    regen -> "REGEN"
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
                        .heightIn(max = 560.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.fillMaxWidth(0.78f)) {
                            Text("Détection automatique", color = TextPrimary, fontWeight = FontWeight.Bold)
                            Text("Surveille la régénération toutes les 2 s", color = TextSecondary, fontSize = 11.sp)
                        }
                        Switch(
                            checked = state.autoDetectionEnabled,
                            onCheckedChange = onAutoDetectionChanged,
                            enabled = !continuousClearEnabled,
                        )
                    }

                    if (continuousClearEnabled) {
                        InfoLine("Détection FAP suspendue pendant l’effacement continu.", Danger)
                    }
                    if (state.busy) InfoLine("Identification ECU / lecture FAP en cours…", Electric)
                    state.errorMessage?.let { InfoLine(it, Danger) }

                    snapshot?.ecuIdentity?.let { EcuIdentityCard(it) }
                    snapshot?.let { DpfMetrics(it) }

                    if (snapshot == null && state.errorMessage == null) {
                        Text(
                            "Appuie sur Actualiser : OBDfree interroge directement l’ECU (GMLAN 1A), identifie Denso DECe01 / GM E87 puis charge le profil FAP correspondant.",
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
                        Text("Identifier ECU + actualiser FAP", color = Electric, fontWeight = FontWeight.Bold)
                    }

                    ServiceRegenerationCard(
                        state = state,
                        continuousClearEnabled = continuousClearEnabled,
                        onProbeServiceControl = onProbeServiceControl,
                    )

                    IdleAdjustmentCard(state)
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
private fun EcuIdentityCard(identity: AstraJEcuIdentity) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Electric.copy(alpha = 0.07f), RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Calculateur moteur", color = Electric, fontWeight = FontWeight.Black, fontSize = 12.sp)
        Metric("Famille", identity.displayName)
        identity.engineCode?.let { Metric("Moteur / système", it) }
        identity.systemIdentification?.let { Metric("Identification", it) }
        identity.supplierIdentification?.let { Metric("Fournisseur", it) }
        identity.programmingDate?.let { Metric("Programmation", it) }
        identity.vin?.takeIf { it.length >= 6 }?.let { Metric("VIN", "…${it.takeLast(6)}") }
        if (identity.confidence > 0) Metric("Confiance", "${identity.confidence}%")
    }
}

@Composable
private fun ServiceRegenerationCard(
    state: DpfUiState,
    continuousClearEnabled: Boolean,
    onProbeServiceControl: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.035f), RoundedCornerShape(15.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Régénération service", color = TextPrimary, fontWeight = FontWeight.Black)
        Text(state.serviceRegenerationReason, color = TextSecondary, fontSize = 11.sp)

        OutlinedButton(
            onClick = onProbeServiceControl,
            enabled = !state.serviceProbeBusy && !continuousClearEnabled && state.snapshot != null,
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, Electric.copy(alpha = 0.45f)),
        ) {
            Text(
                if (state.serviceProbeBusy) "Test GMLAN AE…" else "Tester le contrôle service GMLAN",
                color = Electric,
                fontWeight = FontWeight.Bold,
            )
        }

        state.serviceProbeResult?.let { InfoLine(it, Electric) }

        Button(
            onClick = {},
            enabled = state.serviceRegenerationAvailable,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Danger, contentColor = DeepInk),
        ) {
            Text("Forcer une régénération", fontWeight = FontWeight.Black)
        }

        if (!state.serviceRegenerationAvailable) {
            Text(
                "Le service AE est testable sans actionner le moteur. Le CPID exact de régénération reste verrouillé tant qu’il n’est pas identifié : un CPID inventé pourrait commander un autre actionneur.",
                color = TextSecondary,
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun IdleAdjustmentCard(state: DpfUiState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.035f), RoundedCornerShape(15.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text("Réglage ralenti", color = TextPrimary, fontWeight = FontWeight.Black)
        Text(state.idleAdjustmentReason, color = TextSecondary, fontSize = 11.sp)
        InfoLine(
            if (state.idleAdjustmentRecognized) {
                "Fonction reconnue sur ce profil ECU • écriture encore verrouillée"
            } else {
                "Fonction non activée tant que l’ECU n’est pas identifié"
            },
            if (state.idleAdjustmentRecognized) Mint else TextSecondary,
        )
    }
}

@Composable
private fun DpfMetrics(snapshot: DpfSnapshot) {
    val regen = snapshot.regenerationActive == true

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        InfoLine(
            text = when {
                regen && snapshot.regenerationProgressPercent != null ->
                    "Régénération active • ${snapshot.regenerationProgressPercent.roundToInt()}%"
                regen -> "Régénération active"
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

        snapshot.regenerationInterlockRaw?.let { Metric("Verrous régénération 326D", it) }
        snapshot.deviceControlStatusRaw?.let { Metric("État contrôle 32EB", it) }
        snapshot.deviceControlRejectRaw?.let { Metric("Refus contrôle 32EC", it) }

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

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = TextSecondary, fontSize = 12.sp)
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
