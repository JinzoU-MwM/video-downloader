package com.jni.videodownloader.ui.share

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay

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
                        Text("Memproses tautan…")
                    }

                    is PreviewState.Ready -> {
                        var count by remember { mutableIntStateOf(3) }
                        LaunchedEffect(Unit) {
                            while (count > 0) {
                                delay(1000)
                                count--
                            }
                            vm.confirm()
                        }
                        PreviewCard {
                            s.info.thumbnail?.let { thumb ->
                                AsyncImage(
                                    model = thumb,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxWidth().height(170.dp),
                                )
                                Spacer(Modifier.height(10.dp))
                            }
                            Text(
                                s.info.title ?: "Video",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                s.info.platform.name,
                                style = MaterialTheme.typography.labelSmall,
                            )
                            Spacer(Modifier.height(16.dp))
                            Row {
                                TextButton(onClick = { finish() }) { Text("Batal") }
                                Spacer(Modifier.width(8.dp))
                                Button(onClick = { vm.confirm() }) { Text("Unduh ($count)") }
                            }
                        }
                    }

                    else -> { /* Error / Done handled by LaunchedEffect */ }
                }
            }
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
