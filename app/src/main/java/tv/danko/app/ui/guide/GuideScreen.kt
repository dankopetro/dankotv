package tv.danko.app.ui.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import tv.danko.app.data.EpgRepository
import tv.danko.app.data.ListsRepository
import tv.danko.app.data.db.ChannelEntity
import tv.danko.app.ui.channels.ChannelsViewModel
import tv.danko.app.ui.tvFocusBorder

private const val MAX_CHANNELS = 300

private val hourFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

/**
 * Grilla de programación (F5): solo canales CON datos (auto-detección).
 * TV y teléfono con el mismo layout (filas + tira horizontal por canal).
 */
@Composable
fun GuideScreen(
    listName: String,
    groupName: String,
    repo: ListsRepository,
    epg: EpgRepository,
    onPlay: (ChannelEntity, List<ChannelEntity>) -> Unit,
    onBack: () -> Unit,
) {
    var loading by remember { mutableStateOf(true) }
    var rows by remember { mutableStateOf<List<Pair<ChannelEntity, List<EpgRowItem>>>>(emptyList()) }
    // generation>0 = Recargar = bypass de caché (force=true en guideRows).
    var generation by remember { mutableStateOf(0) }

    LaunchedEffect(listName, groupName, generation) {
        loading = true
        try {
            // Mismo ruteo de grupos que ChannelsViewModel (Todas/★/grupo).
            val scope: List<ChannelEntity> = when (groupName) {
                "Todas" -> repo.channelsPage(listName)
                ChannelsViewModel.FAV_GROUP -> repo.favoritesPage(listName)
                else -> repo.groupPage(listName, groupName)
            }
            val channels = scope.take(MAX_CHANNELS)
            val byUrl = channels.associateBy { it.url }
            val guide = epg.guideRows(listName, channels.map { it.url }, generation > 0)
            val now = System.currentTimeMillis()
            rows = guide.mapNotNull { r ->
                val ch = byUrl[r.channelUrl] ?: return@mapNotNull null
                (ch to r.programmes.map { EpgRowItem(it.title, it.startMs, it.endMs) })
            }.filter { (_, progs) -> progs.any { p -> now in p.startMs until p.endMs } }
        } catch (_: Exception) {
            rows = emptyList()
        } finally {
            loading = false
        }
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Atrás") }
            Text(
                "Guía · $groupName", fontWeight = FontWeight.Bold, fontSize = 18.sp,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = { generation++ }, modifier = Modifier.tvFocusBorder()) {
                Text("Recargar")
            }
        }
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Sin guía para este grupo (solo canales con datos aparecen acá).",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Text(
                "${rows.size} canales con programación",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            LazyColumn(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(rows, key = { it.first.id }) { (ch, progs) ->
                    GuideRow(ch, progs, onPlay = { onPlay(ch, rows.map { r -> r.first }) })
                }
            }
        }
    }
}

private data class EpgRowItem(val title: String, val startMs: Long, val endMs: Long)

@Composable
private fun GuideRow(
    ch: ChannelEntity,
    progs: List<EpgRowItem>,
    onPlay: () -> Unit,
) {
    val now = System.currentTimeMillis()
    Column(
        Modifier.fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                MaterialTheme.shapes.small,
            )
            .clickable(onClick = onPlay)
            .padding(8.dp),
    ) {
        Text(
            ch.title, fontWeight = FontWeight.Bold, fontSize = 14.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        ) {
            items(progs) { p ->
                val isNow = now in p.startMs until p.endMs
                val mins = ((p.endMs - p.startMs) / 60000L).coerceAtLeast(15)
                Column(
                    Modifier.width((mins * 2).coerceIn(100L, 320L).toInt().dp)
                        .background(
                            if (isNow) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                            },
                            MaterialTheme.shapes.small,
                        )
                        .padding(6.dp),
                ) {
                    Text(
                        p.title.ifEmpty { "(sin título)" }, fontSize = 12.sp,
                        fontWeight = if (isNow) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${hourFmt.format(Date(p.startMs))}–${hourFmt.format(Date(p.endMs))}",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
