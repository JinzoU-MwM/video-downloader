package com.jni.videodownloader.ui.main

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jni.videodownloader.data.net.AppLatest
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            MaterialTheme {
                val update by vm.update.collectAsStateWithLifecycle()
                GalleryScreen(vm)
                when (val u = update) {
                    is UpdateUi.Available -> UpdateAvailableDialog(
                        info = u.info,
                        onUpdate = { vm.startUpdate() },
                        onLater = { vm.dismissUpdate() },
                    )
                    is UpdateUi.Downloading -> DownloadingDialog(u.percent)
                    UpdateUi.Idle -> Unit
                }
            }
        }
    }
}

@Composable
private fun UpdateAvailableDialog(info: AppLatest, onUpdate: () -> Unit, onLater: () -> Unit) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text("Update tersedia" + (info.versionName?.let { " (v$it)" } ?: "")) },
        text = { Text(info.notes ?: "Versi baru aplikasi sudah tersedia.") },
        confirmButton = { Button(onClick = onUpdate) { Text("Update sekarang") } },
        dismissButton = { TextButton(onClick = onLater) { Text("Nanti") } },
    )
}

@Composable
private fun DownloadingDialog(percent: Int) {
    AlertDialog(
        onDismissRequest = { },
        title = { Text("Mengunduh pembaruan") },
        text = { Text("$percent%") },
        confirmButton = { },
    )
}
