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
import tv.danko.app.data.model.VodItem
import tv.danko.app.ui.tvFocusBorder

/**
 * Películas (F6): categorías + posters + filtro. TV y teléfono.
 * Sin caché en v1 (se trae por apertura/categoría).
 */
@Composable
fun VodScreen(
    listName: String,
    vod: VodRepository,
    onPlay: (String, String) -> Unit,
    onBack: () -> Unit,
) {
    var cats by remember { mutableStateOf<List<tv.danko.app.data.model.VodCategory>>(emptyList()) }
    var selCat by remember { mutableStateOf<String?>(null) }
    var items by remember { mutableStateOf<List<VodItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(listName) {
        try {
            val c = vod.vodCategories(listName)
            cats = c
            selCat = c.firstOrNull()?.id
        } catch (_: Exception) {
        }
    }
    LaunchedEffect(listName, selCat) {
        val cat = selCat ?: return@LaunchedEffect
        loading = true
        try {
            items = vod.vodStreams(listName, cat)
        } catch (_: Exception) {
            items = emptyList()
        } finally {
            loading = false
        }
    }
    val shown = remember(items, query) {
        if (query.isBlank()) items
        else items.filter { it.title.contains(query, ignoreCase = true) }.take(500)
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Listas", color = Color.White) }
            Text(
                "Películas", fontWeight = FontWeight.Bold, fontSize = 18.sp,
                color = Color.White, modifier = Modifier.weight(1f),
            )
            if (loading) CircularProgressIndicator(Modifier.padding(end = 8.dp))
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        ) {
            items(cats, key = { it.id }) { c ->
                FilterChip(
                    selected = selCat == c.id,
                    onClick = { selCat = c.id },
                    label = { Text(c.name, maxLines = 1) },
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
        Text(
            "${shown.size} títulos", color = Color.White, fontSize = 12.sp,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        if (!loading && shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Sin películas (el panel no las sirve para esta lista).",
                    color = Color.White,
                )
            }
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(shown, key = { it.id }) { m ->
                    Row(
                        Modifier.fillMaxWidth().tvFocusBorder()
                            .clickable {
                                scope.launch {
                                    try {
                                        vod.movieUrl(listName, m)?.let { onPlay(it, m.title) }
                                    } catch (_: Exception) {
                                    }
                                }
                            }.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (m.icon.isNotEmpty()) {
                            AsyncImage(
                                model = m.icon,
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
                                m.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                                color = Color.White, maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (m.rating.isNotEmpty()) {
                                Text(
                                    "Rating: ${m.rating}", fontSize = 12.sp,
                                    color = Color.White,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
