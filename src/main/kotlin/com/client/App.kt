package com.client

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import java.util.Locale

fun main() = application {
    val controller = remember { ClientController() }
    DisposableEffect(Unit) {
        controller.start()
        onDispose { controller.stop() }
    }

    Window(onCloseRequest = ::exitApplication, title = "Transformer Client") {
        val state by controller.state.collectAsState()
        MaterialTheme {
            AppUi(
                state,
                onLogin = controller::login,
                onLogout = controller::logout,
                onRegisterSettingsSave = controller::saveRegisterThresholds,
                onMotorStart = controller::startMotor,
                onMotorStop = controller::stopMotor,
                onMotorDirectionChange = controller::setMotorDirection,
                onMotorSpeedChange = controller::setMotorSpeed
            )
        }
    }
}

@Composable
private fun AppUi(
    state: AppState,
    onLogin: (String, String, Boolean) -> Unit,
    onLogout: () -> Unit,
    onRegisterSettingsSave: (Long, Long, String, String) -> Unit,
    onMotorStart: () -> Unit,
    onMotorStop: () -> Unit,
    onMotorDirectionChange: (MotorDirection) -> Unit,
    onMotorSpeedChange: (String) -> Unit
) {
    val appBackground = Color(0xFF0C1220)
    Column(
        modifier = Modifier.fillMaxSize().background(appBackground).padding(18.dp)
    ) {
        Header(state)
        Spacer(Modifier.height(12.dp))
        if (!state.isLoggedIn) {
            LoginPanel(state, onLogin)
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = onLogout) {
                    Text("Wyloguj")
                }
            }
            Spacer(Modifier.height(12.dp))
            state.motorControl?.let { motorState ->
                MotorControlCard(
                    motorState = motorState,
                    onStart = onMotorStart,
                    onStop = onMotorStop,
                    onDirectionChange = onMotorDirectionChange,
                    onSpeedSubmit = onMotorSpeedChange
                )
                Spacer(Modifier.height(12.dp))
            }
            if (state.meters.isEmpty()) {
                Text("Brak konfiguracji metrow lub brak polaczenia.", color = Color(0xFFE0E0E0))
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.meters, key = { it.meter.id }) { meter ->
                        MeterCard(meter, onRegisterSettingsSave)
                    }
                }
            }
        }
    }
}

@Composable
private fun LoginPanel(state: AppState, onLogin: (String, String, Boolean) -> Unit) {
    var email by remember(state.loginPrefillEmail) { mutableStateOf(state.loginPrefillEmail) }
    var password by remember(state.loginPrefillPassword) { mutableStateOf(state.loginPrefillPassword) }
    var rememberCreds by remember(state.rememberPrefill) { mutableStateOf(state.rememberPrefill) }
    val loginTextStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium)
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color(0xFFECECEC),
        unfocusedTextColor = Color(0xFFECECEC),
        disabledTextColor = Color(0xFF777777),
        focusedBorderColor = Color(0xFF5DA9FF),
        unfocusedBorderColor = Color(0xFF3A4455),
        focusedLabelColor = Color(0xFFBFD7FF),
        unfocusedLabelColor = Color(0xFF9AA3AE),
        cursorColor = Color(0xFFBFD7FF)
    )

    Column(
        modifier = Modifier.fillMaxWidth().fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = "Zaloguj sie, aby kontynuowac",
            color = Color(0xFFECECEC),
            style = MaterialTheme.typography.titleMedium
        )
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            singleLine = true,
            textStyle = loginTextStyle,
            colors = fieldColors,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Haslo") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            textStyle = loginTextStyle,
            colors = fieldColors,
            modifier = Modifier.fillMaxWidth()
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = rememberCreds,
                onCheckedChange = { rememberCreds = it }
            )
            Text(
                text = "Zapamietaj dane logowania",
                color = Color(0xFFE0E0E0),
                style = MaterialTheme.typography.bodySmall
            )
        }
        Button(
            onClick = { onLogin(email.trim(), password, rememberCreds) },
            enabled = email.isNotBlank() && password.isNotBlank()
        ) {
            Text("Zaloguj")
        }
        state.loginError?.let {
            Text(
                text = "Blad logowania: $it",
                color = Color(0xFFFF8C8C),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun Header(state: AppState) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Transformer Client",
            color = Color(0xFFF4F7FB),
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            text = state.status,
            color = Color(0xFF8FA3BF),
            style = MaterialTheme.typography.bodySmall
        )
        val name = state.transformer?.name ?: "brak"
        Text(
            text = "Transformer: $name",
            color = Color(0xFF74C0FC),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun MeterCard(
    meterState: MeterState,
    onRegisterSettingsSave: (Long, Long, String, String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF111A2B))
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = meterState.meter.name,
                    color = Color(0xFFF4F7FB),
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = meterState.connectionStatus,
                    color = if (meterState.connectionStatus == "CONNECTED") Color(0xFF2ECC71) else Color(0xFFFFB454),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(
                text = "Port: ${meterState.meter.serialPort} @ ${meterState.meter.baudRate}bps",
                color = Color(0xFF8FA3BF),
                style = MaterialTheme.typography.bodySmall
            )
            if (meterState.lastError != null) {
                Text(
                    text = "Blad: ${meterState.lastError}",
                    color = Color(0xFFFF6B6B),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            HorizontalDivider(color = Color(0xFF22324A), modifier = Modifier.padding(vertical = 10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                meterState.registers.forEach { register ->
                    RegisterRow(register, onRegisterSettingsSave)
                }
            }
        }
    }
}

@Composable
private fun RegisterRow(
    registerState: RegisterState,
    onRegisterSettingsSave: (Long, Long, String, String) -> Unit
) {
    var targetValueText by remember(registerState.meterId, registerState.register.id, registerState.register.targetValue) {
        mutableStateOf(registerState.register.targetValue?.formatNumber() ?: "")
    }
    var thresholdValueText by remember(registerState.meterId, registerState.register.id, registerState.register.thresholdValue) {
        mutableStateOf(registerState.register.thresholdValue?.formatNumber() ?: "")
    }
    val panelBorder = if (registerState.alarmActive) Color(0xFFFF6B6B) else Color(0xFF24364F)
    val panelBackground = if (registerState.alarmActive) Color(0xFF2A1820) else Color(0xFF162235)
    val valueColor = if (registerState.alarmActive) Color(0xFFFF8787) else Color(0xFF7DD3FC)
    val badgeColor = if (registerState.alarmActive) Color(0xFFFF6B6B) else Color(0xFF3A4D68)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, panelBorder, shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            .background(panelBackground, shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = registerState.register.name,
                    color = Color(0xFFF4F7FB),
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = "Adres ${registerState.register.address} • ${registerState.register.registerType}",
                    color = Color(0xFF8FA3BF),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            AlarmBadge(
                text = if (registerState.alarmActive) "ALARM" else "OK",
                background = badgeColor
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ValueTile(
                label = "Aktualna",
                value = registerState.value?.formatNumber() ?: "--",
                accent = valueColor,
                modifier = Modifier.weight(0.42f)
            )
            ValueTile(
                label = "Jednostka",
                value = registerState.register.unit ?: "--",
                accent = Color(0xFFB6C2D1),
                modifier = Modifier.weight(0.24f)
            )
            ValueTile(
                label = "Ostatni alarm",
                value = if (registerState.alarmActive) "Przekroczony prog" else "Brak",
                accent = if (registerState.alarmActive) Color(0xFFFF8787) else Color(0xFF9FB3C8),
                modifier = Modifier.weight(0.34f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StyledNumberField(
                value = targetValueText,
                onValueChange = { targetValueText = it },
                label = "Wartosc docelowa",
                accent = Color(0xFF4ECDC4),
                modifier = Modifier.weight(1f)
            )
            StyledNumberField(
                value = thresholdValueText,
                onValueChange = { thresholdValueText = it },
                label = "Wartosc progowa",
                accent = Color(0xFFFFB454),
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = {
                    onRegisterSettingsSave(
                        registerState.meterId,
                        registerState.register.id,
                        targetValueText,
                        thresholdValueText
                    )
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF1D8CF8),
                    contentColor = Color.White
                ),
                modifier = Modifier.weight(0.55f)
            ) {
                Text("Zapisz")
            }
        }

        registerState.lastUpdate?.let {
            Text(
                text = "Ostatni odczyt: $it",
                color = Color(0xFF7E93AD),
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic
            )
        }
    }
    registerState.alarmMessage?.let {
        Text(
            text = it,
            color = Color(0xFFFF8787),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun AlarmBadge(text: String, background: Color) {
    Row(
        modifier = Modifier
            .background(background, shape = androidx.compose.foundation.shape.RoundedCornerShape(999.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Spacer(
            modifier = Modifier
                .size(8.dp)
                .background(Color.White, shape = androidx.compose.foundation.shape.CircleShape)
        )
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun ValueTile(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Color(0xFF0E1727), shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = label,
            color = Color(0xFF7E93AD),
            style = MaterialTheme.typography.labelSmall
        )
        Text(
            text = value,
            color = accent,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
private fun StyledNumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color(0xFFF4F7FB),
            unfocusedTextColor = Color(0xFFE3EBF5),
            focusedContainerColor = Color(0xFF0E1727),
            unfocusedContainerColor = Color(0xFF0E1727),
            focusedBorderColor = accent,
            unfocusedBorderColor = Color(0xFF314763),
            focusedLabelColor = accent,
            unfocusedLabelColor = Color(0xFF8FA3BF),
            cursorColor = accent
        ),
        modifier = modifier
    )
}

@Composable
private fun MotorControlCard(
    motorState: MotorControlState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDirectionChange: (MotorDirection) -> Unit,
    onSpeedSubmit: (String) -> Unit
) {
    var speedValue by remember(motorState.speedSetpoint) {
        mutableStateOf(motorState.speedSetpoint.formatNumber())
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().background(Color(0xFF111A2B)).padding(14.dp)) {
            Text(
                text = motorState.config.name,
                color = Color(0xFFF4F7FB),
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "Sprzezenie: ${buildFeedbackSummary(motorState)}",
                color = Color(0xFF8FA3BF),
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "Piny: ${buildPinSummary(motorState.config)}",
                color = Color(0xFF8FA3BF),
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onStart, enabled = motorState.available) {
                    Text("Start")
                }
                Button(onClick = onStop, enabled = motorState.available) {
                    Text("Stop")
                }
                Button(onClick = { onDirectionChange(MotorDirection.FORWARD) }, enabled = motorState.available) {
                    Text("Przod")
                }
                Button(onClick = { onDirectionChange(MotorDirection.REVERSE) }, enabled = motorState.available) {
                    Text("Tyl")
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = speedValue,
                    onValueChange = { speedValue = it },
                    label = { Text("Predkosc zadana") },
                    singleLine = true,
                    modifier = Modifier.width(180.dp)
                )
                Button(onClick = { onSpeedSubmit(speedValue) }, enabled = motorState.available) {
                    Text("Ustaw")
                }
                Text(
                    text = if (motorState.isRunning) "Stan: PRACA" else "Stan: STOP",
                    color = if (motorState.isRunning) Color(0xFF2ECC71) else Color(0xFFFFB454),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            motorState.lastCommandStatus?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = it,
                    color = Color(0xFF7DD3FC),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            motorState.lastCommandAt?.let {
                Text(
                    text = "Ostatnia komenda: $it",
                    color = Color(0xFF7E93AD),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

private fun Double.formatNumber(): String = String.format(Locale.US, "%.3f", this)

private fun buildPinSummary(config: MotorControlConfig): String {
    config.gpio?.let { gpio ->
        val enablePart = gpio.enablePin?.let { " | EN=$it" }.orEmpty()
        return "STEP=${gpio.stepPin} | DIR=${gpio.directionPin}$enablePart"
    }
    val items = listOfNotNull(
        config.runPin?.let { "RUN=${it.pinName}@${it.address}" },
        config.directionPin?.let { "DIR=${it.pinName}@${it.address}" },
        config.speedPin?.let { "SPEED=${it.pinName}@${it.address}" }
    )
    return items.joinToString(" | ").ifBlank { "brak mapowania" }
}

private fun buildFeedbackSummary(motorState: MotorControlState): String {
    val feedback = motorState.config.feedback ?: return motorState.meter?.name ?: "brak przypisanego metra"
    val meterName = motorState.meter?.name ?: "meter=${feedback.meterId ?: "auto"}"
    return "$meterName, rejestr=${feedback.registerId}"
}
