package com.jni.videodownloader.ui.share

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jni.videodownloader.domain.DownloadMode
import com.jni.videodownloader.domain.DownloadOptions
import com.jni.videodownloader.domain.VideoQuality
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ShareReceiverActivity : ComponentActivity() {

    private val vm: PreviewViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent
            ?.takeIf { it.action == Intent.ACTION_SEND }
            ?.getStringExtra(Intent.EXTRA_TEXT)
        if (text.isNullOrBlank()) {
            Toast.makeText(this, "Tidak ada tautan", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        vm.start(text)

        setContent {
            MaterialTheme {
                val state by vm.state.collectAsStateWithLifecycle()

                LaunchedEffect(state) {
                    if (state is PreviewState.Done) {
                        Toast.makeText(this@ShareReceiverActivity, "Unduhan dimulai", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    if (state is PreviewState.Error) {
                        Toast.makeText(
                            this@ShareReceiverActivity,
                            (state as PreviewState.Error).message,
                            Toast.LENGTH_LONG,
                        ).show()
                        finish()
                    }
                }

                when (val s = state) {
                    is PreviewState.Loading -> PreviewCard {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text("Menyiapkan…")
                    }

                    is PreviewState.Ready -> OptionsCard(
                        platformName = s.platform.name,
                        initial = s.initial,
                        onCancel = { finish() },
                        onDownload = { vm.confirm(it) },
                    )

                    else -> { /* Error / Done handled by LaunchedEffect */ }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OptionsCard(
    platformName: String,
    initial: DownloadOptions,
    onCancel: () -> Unit,
    onDownload: (DownloadOptions) -> Unit,
) {
    var quality by remember { mutableStateOf(initial.quality) }
    var audio by remember { mutableStateOf(initial.mode == DownloadMode.AUDIO) }
    var whatsapp by remember { mutableStateOf(initial.whatsapp) }

    PreviewCard {
        Text(platformName, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))

        Text("Kualitas", style = MaterialTheme.typography.labelMedium)
        FlowRow {
            VideoQuality.entries.forEach { q ->
                FilterChip(
                    selected = quality == q && !audio,
                    enabled = !audio,
                    onClick = { quality = q },
                    label = { Text(q.label) },
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Audio saja (MP3)")
            Spacer(Modifier.width(8.dp))
            Switch(checked = audio, onCheckedChange = { audio = it })
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Optimalkan untuk WhatsApp (HD)")
            Spacer(Modifier.width(8.dp))
            Switch(checked = whatsapp && !audio, enabled = !audio, onCheckedChange = { whatsapp = it })
        }
        Text(
            "WhatsApp tetap mengompres video; opsi ini meminimalkan 'pecah'.",
            style = MaterialTheme.typography.labelSmall,
        )

        Spacer(Modifier.height(16.dp))
        Row {
            TextButton(onClick = onCancel) { Text("Batal") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                onDownload(
                    DownloadOptions(
                        quality = quality,
                        mode = if (audio) DownloadMode.AUDIO else DownloadMode.VIDEO,
                        whatsapp = whatsapp && !audio,
                    )
                )
            }) { Text("Unduh") }
        }
    }
}

@Composable
private fun PreviewCard(content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = {}) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                content = content,
            )
        }
    }
}
