package tv.danko.app.ui.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import tv.danko.app.data.VodRepository
import tv.danko.app.data.model.SeriesDetail
import tv.danko.app.data.model.SeriesItem
import tv.danko.app.ui.tvFocusBorder

/**
 * Series (F6): grilla → detalle (temporadas/episodios) → reproduce.
 * TV y teléfono. Sin caché en v1.
 */
@Composable
fun SeriesScreen(
    listName: String,
    vod: VodRepository,
    onPlay: (String, String) -> Unit,
    onBack: () -> Unit,
) {
    var cats by remember { mutableStateOf<List<tv.danko.app.data.model.VodCategory>>(emptyList()) }
    var selCat by remember { mutableStateOf<String?>(null) }
    var catQuery by remember { mutableStateOf("") }
    var series by remember { mutableStateOf<List<SeriesItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<SeriesItem?>(null) }
    var detail by remember { mutableStateOf<SeriesDetail?>(null) }
    var detailLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(listName) {
        try {
            val c = vod.seriesCategories(listName)
            cats = c
            selCat = c.firstOrNull()?.id
        } catch (_: Exception) {
        }
    }
    LaunchedEffect(listName, selCat, selected) {
        if (selected != null) {
            detailLoading = true
            try {
                detail = vod.seriesDetail(listName, selected!!.id)
            } catch (_: Exception) {
                detail = null
            } finally {
                detailLoading = false
            }
        } else {
            val cat = selCat ?: return@LaunchedEffect
            loading = true
            try {
                series = vod.series(listName, cat)
            } catch (_: Exception) {
                series = emptyList()
            } finally {
                loading = false
            }
        }
    }
    val shown = remember(series, query) {
        if (query.isBlank()) series
        else series.filter { it.title.contains(query, ignoreCase = true) }.take(500)
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = {
                    if (selected != null) {
                        selected = null
                        detail = null
                    } else {
                        onBack()
                    }
                },
            ) { Text(if (selected != null) "← Series" else "← Listas", color = Color.White) }
            Text(
                selected?.title ?: "Series", fontWeight = FontWeight.Bold, fontSize = 18.sp,
                color = Color.White, modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (loading || detailLoading) CircularProgressIndicator(Modifier.padding(end = 8.dp))
        }
        if (selected == null) {
            val shownCats = remember(cats, catQuery) {
                val q = catQuery.trim()
                if (q.isEmpty()) cats
                else cats.filter { it.name.contains(q, ignoreCase = true) }
            }
            OutlinedTextField(
                value = catQuery,
                onValueChange = { catQuery = it },
                label = { Text("Filtrar categorías…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            ) {
                items(shownCats, key = { it.id }) { c ->
                    FilterChip(
                        selected = selCat == c.id,
                        onClick = { selCat = c.id },
                        label = { Text(c.name, maxLines = 1) },
                        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    )
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Filtrar…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(shown, key = { it.id }) { s ->
                    Row(
                        Modifier.fillMaxWidth().tvFocusBorder()
                            .clickable { selected = s }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (s.cover.isNotEmpty()) {
                            AsyncImage(
                                model = s.cover,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(48.dp, 72.dp)
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant,
                                        MaterialTheme.shapes.small,
                                    ),
                            )
                        }
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(
                                s.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                                color = Color.White, maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (s.plot.isNotEmpty()) {
                                Text(
                                    s.plot, fontSize = 12.sp, color = Color.White,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        } else {
            val d = detail
            if (d == null && !detailLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Sin episodios (el panel no los sirve).", color = Color.White)
                }
            } else {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val bySeason = (d?.episodes ?: emptyList()).groupBy { it.season }.toSortedMap()
                    for ((season, eps) in bySeason) {
                        item(key = "s$season") {
                            Text(
                                "Temporada $season", fontWeight = FontWeight.Bold,
                                fontSize = 14.sp, color = Color.White,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        items(eps, key = { it.id }) { ep ->
                            Row(
                                Modifier.fillMaxWidth().tvFocusBorder()
                                    .clickable {
                                        scope.launch {
                                            try {
                                                vod.episodeUrl(listName, ep)?.let {
                                                    onPlay(it, ep.title.ifEmpty { "${selected!!.title} S${ep.season}E${ep.epNum}" })
                                                }
                                            } catch (_: Exception) {
                                            }
                                        }
                                    }.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "E${ep.epNum} · ${ep.title.ifEmpty { "Episodio ${ep.epNum}" }}",
                                    color = Color.White, fontSize = 14.sp,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
