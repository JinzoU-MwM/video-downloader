package com.jni.videodownloader.ui.main

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.jni.videodownloader.domain.Download
import com.jni.videodownloader.domain.DownloadStatus

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(vm: MainViewModel) {
    val items by vm.items.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    Scaffold(topBar = { TopAppBar(title = { Text("R Downloader") }) }) { pad ->
        if (items.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(pad).padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Bagikan video dari TikTok, Instagram, atau Facebook ke aplikasi ini untuk mengunduhnya.")
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize().padding(pad),
                contentPadding = PaddingValues(8.dp),
            ) {
                items(items, key = { it.id }) { d ->
                    DownloadCard(
                        d = d,
                        onClick = { open(ctx, d) },
                        onLongClick = { vm.delete(d.id) },
                    )
                }
            }
        }
    }
}

private fun open(ctx: Context, d: Download) {
    if (d.status == DownloadStatus.COMPLETED && d.filePath != null) {
        runCatching {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(d.filePath.toUri(), "video/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun DownloadCard(d: Download, onClick: () -> Unit, onLongClick: () -> Unit) {
    Card(
        modifier = Modifier
            .padding(6.dp)
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Column {
            d.thumbnail?.let {
                AsyncImage(
                    model = it,
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                )
            }
            Column(Modifier.padding(8.dp)) {
                Text(d.platform.name, style = MaterialTheme.typography.labelSmall)
                Text(
                    d.title ?: "Video",
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
                when (d.status) {
                    DownloadStatus.DOWNLOADING -> LinearProgressIndicator(
                        progress = { d.progress / 100f },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                    DownloadStatus.QUEUED -> Text("Menunggu…", style = MaterialTheme.typography.labelSmall)
                    DownloadStatus.FAILED -> Text(
                        "Gagal",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    DownloadStatus.COMPLETED -> Text("Selesai", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
