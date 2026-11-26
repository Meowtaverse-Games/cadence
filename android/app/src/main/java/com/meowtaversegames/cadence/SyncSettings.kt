package com.meowtaversegames.cadence

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun SyncSettings(sync: FirebaseSync) {
    // Credentials stay only in this composition; never save them to instance state.
    var address by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Column {
        Text("同期", style = MaterialTheme.typography.titleLarge)
        Text(if (sync.signedIn) "サインイン中" else "サインインしていません")
        Text(sync.status.text)
        Text("最終同期: " + (sync.status.lastSync?.let {
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(it)
        } ?: "未同期"))
        sync.signInProblem?.let { Text(it) }
        if (sync.signedIn) {
            sync.email?.let { Text(it) }
            Button(onClick = sync::signOut) { Text("サインアウト") }
        } else if (sync.configured) {
            OutlinedTextField(address, { address = it }, label = { Text("メールアドレス") },
                singleLine = true, enabled = !sync.signingIn,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
            OutlinedTextField(password, { password = it }, label = { Text("パスワード") },
                singleLine = true, enabled = !sync.signingIn, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            Button(enabled = !sync.signingIn, onClick = {
                sync.signIn(address, password)
                password = ""
            }) { Text(if (sync.signingIn) "サインインしています" else "サインイン") }
        }
    }
    LaunchedEffect(sync.signedIn) {
        if (sync.signedIn) { address = ""; password = "" }
    }
}
