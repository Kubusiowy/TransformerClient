package com.client

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    val controller = remember { ClientController() }
    DisposableEffect(Unit) {
        controller.start()
        onDispose { controller.stop() }
    }

    Window(onCloseRequest = ::exitApplication, title = "Transformer Client") {
        val state by controller.state.collectAsState()
        MaterialTheme {
            AppUi(state, onLogin = controller::login, onLogout = controller::logout)
        }
    }
}

@Composable
private fun AppUi(state: AppState, onLogin: (String, String, Boolean) -> Unit, onLogout: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF0F1115)).padding(16.dp)
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
            if (state.meters.isEmpty()) {
                Text("Brak konfiguracji metrow lub brak polaczenia.", color = Color(0xFFE0E0E0))
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.meters, key = { it.meter.id }) { meter ->
                        MeterCard(meter)
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
            color = Color(0xFFECECEC),
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            text = state.status,
            color = Color(0xFF9AA3AE),
            style = MaterialTheme.typography.bodySmall
        )
        val name = state.transformer?.name ?: "brak"
        Text(
            text = "Transformer: $name",
            color = Color(0xFFBFD7FF),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun MeterCard(meterState: MeterState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().background(Color(0xFF171A21)).padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = meterState.meter.name,
                    color = Color(0xFFF5F5F5),
                    fontWeight = FontWeight.Medium,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = meterState.connectionStatus,
                    color = if (meterState.connectionStatus == "CONNECTED") Color(0xFF7CFC98) else Color(0xFFFFB36B),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(
                text = "Port: ${meterState.meter.serialPort} @ ${meterState.meter.baudRate}bps",
                color = Color(0xFF9AA3AE),
                style = MaterialTheme.typography.bodySmall
            )
            if (meterState.lastError != null) {
                Text(
                    text = "Blad: ${meterState.lastError}",
                    color = Color(0xFFFF8C8C),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            HorizontalDivider(color = Color(0xFF2A2F3A), modifier = Modifier.padding(vertical = 8.dp))
            RegisterHeaderRow()
            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                meterState.registers.forEach { register ->
                    RegisterRow(register)
                }
            }
        }
    }
}

@Composable
private fun RegisterHeaderRow() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Nazwa",
            color = Color(0xFF9AA3AE),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(0.55f)
        )
        Text(
            text = "Wartosc",
            color = Color(0xFF9AA3AE),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(0.25f)
        )
        Text(
            text = "Jednostka",
            color = Color(0xFF9AA3AE),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(0.2f)
        )
    }
}

@Composable
private fun RegisterRow(registerState: RegisterState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = registerState.register.name,
            color = Color(0xFFE0E0E0),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(0.55f)
        )
        val valueText = registerState.value?.let { "%.3f".format(it) } ?: "--"
        Text(
            text = valueText,
            color = Color(0xFFBFD7FF),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(0.25f)
        )
        val unit = registerState.register.unit ?: ""
        Text(
            text = unit,
            color = Color(0xFFBFD7FF),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(0.2f)
        )
    }
    registerState.lastUpdate?.let {
        Text(
            text = "Ostatni odczyt: $it",
            color = Color(0xFF7A8392),
            style = MaterialTheme.typography.bodySmall
        )
    }
}
