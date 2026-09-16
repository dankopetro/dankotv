package tv.danko.app

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import tv.danko.app.data.model.PlaylistSpec
import tv.danko.app.ui.AppContainer
import tv.danko.app.ui.VmFactory
import tv.danko.app.ui.channels.ChannelsScreen
import tv.danko.app.ui.channels.ChannelsViewModel
import tv.danko.app.ui.lists.ListsScreen
import tv.danko.app.ui.lists.ListsViewModel
import tv.danko.app.ui.lists.NewListDialog
import tv.danko.app.ui.player.PlayerScreen
import tv.danko.app.ui.theme.DankoTVTheme
import tv.danko.app.ui.theme.DEFAULT_SKIN

/** App Danko TV: listas → canales → player. */
class MainActivity : ComponentActivity() {

    private val container by lazy { AppContainer(this) }

    fun isTelevision(): Boolean {
        val uiMode = (getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).currentModeType
        return uiMode == Configuration.UI_MODE_TYPE_TELEVISION
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // backdoor add_list también con la app cerrada (onCreate)
        intent.getStringExtra("add_list_url")?.let { url ->
            addListRequest.value = AddListRequest(
                name = intent.getStringExtra("add_list_name") ?: "Lista",
                url = url,
            )
        }
        setContent {
            val skin by container.settings.skin.collectAsStateWithLifecycle(initialValue = DEFAULT_SKIN)
            DankoTVTheme(skinName = skin) {
                DankoNav(
                    container = container,
                    startPlayerUrl = intent.getStringExtra("url"),
                    startPlayerTitle = intent.getStringExtra("title") ?: "Prueba",
                    startPlayerLive = intent.getBooleanExtra("live", true),
                    startPlayerUa = intent.getStringExtra("ua"),
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // F3: la navegación reacciona vía PLAY_REQUEST (ver DankoNav).
        playRequest.value = intent.getStringExtra("url")?.let { url ->
            PlayRequest(
                url = url,
                title = intent.getStringExtra("title") ?: "Prueba",
                live = intent.getBooleanExtra("live", true),
                ua = intent.getStringExtra("ua"),
            )
        }
        // Dev backdoor: add_list con extras name/url agrega la lista sin
        // teclear (en la TV el IME por adb no se activa; el usuario final
        // usa el control remoto con su teclado en pantalla real).
        addListRequest.value = intent.getStringExtra("add_list_url")?.let { url ->
            AddListRequest(
                name = intent.getStringExtra("add_list_name") ?: "Lista",
                url = url,
            )
        }
    }

    companion object {
        /** Nuevo pedido de reproducción (smoke tests / intents externos). */
        val playRequest = androidx.compose.runtime.mutableStateOf<PlayRequest?>(null)

        /** Pedido de alta de lista (dev/testing con adb). */
        val addListRequest = androidx.compose.runtime.mutableStateOf<AddListRequest?>(null)
    }
}

data class PlayRequest(val url: String, val title: String, val live: Boolean, val ua: String?)
data class AddListRequest(val name: String, val url: String)

@Composable
fun DankoNav(
    container: AppContainer,
    startPlayerUrl: String?,
    startPlayerTitle: String,
    startPlayerLive: Boolean,
    startPlayerUa: String?,
) {
    val nav = rememberNavController()
    var showAdd by remember { mutableStateOf(false) }
    val playReq by MainActivity.playRequest
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // Cola compartida: la llena ChannelsScreen al reproducir y la usa el
    // player para prev/next/favorito (self.filtered + _playing_idx en Linux).
    val playQueue = remember { tv.danko.app.ui.player.PlayQueue(container.repo) }
    // Arranque SIEMPRE en "Mis listas" (decisión del usuario 13/09, como
    // app.py que abre Home; si no hay listas, el diálogo se auto-abre abajo).
    // OJO: no auto-navegar con last_list — si channels quedara como start,
    // popBackStack() desde channels/player no vuelve a nada (bug verificado).
    val startDest = if (startPlayerUrl != null) {
        "player/${Uri.encode(startPlayerUrl)}/${Uri.encode(startPlayerTitle)}/$startPlayerLive/${Uri.encode(startPlayerUa ?: "")}"
    } else "lists"

    // Intents que llegan con la app abierta van al player.
    androidx.compose.runtime.LaunchedEffect(playReq) {
        playReq?.let { r ->
            nav.navigate("player/${Uri.encode(r.url)}/${Uri.encode(r.title)}/${r.live}/${Uri.encode(r.ua ?: "")}")
            MainActivity.playRequest.value = null
        }
    }

    // (El backdoor add_list se maneja dentro del destino "lists".)

    NavHost(navController = nav, startDestination = startDest) {
        composable("lists") {
            val vm: ListsViewModel = viewModel(factory = VmFactory { ListsViewModel(container.repo) })
            val skin by container.settings.skin.collectAsStateWithLifecycle(initialValue = DEFAULT_SKIN)
            // Dev backdoor: si llegó add_list por intent, dispara el alta acá
            // (dentro del scope composable correcto) y limpia el request.
            val addReq by MainActivity.addListRequest
            androidx.compose.runtime.LaunchedEffect(addReq) {
                addReq?.let { r ->
                    vm.addSpec(PlaylistSpec.M3u(r.name, r.url)) {
                        MainActivity.addListRequest.value = null
                    }
                }
            }
            // Como Linux (app.py:74): sin listas guardadas → auto-abrir el
            // diálogo de nueva lista apenas arranca (solo tras leer la DB).
            val lists by vm.ui.collectAsStateWithLifecycle()
            androidx.compose.runtime.LaunchedEffect(lists.loaded, lists.lists.isEmpty()) {
                if (lists.loaded && lists.lists.isEmpty() && !lists.loading && !showAdd) showAdd = true
            }
            ListsScreen(
                vm = vm, skin = skin,
                onSkin = { name -> scope.launch { container.settings.setSkin(name) } },
                onOpen = { nav.navigate("channels/${Uri.encode(it)}") },
                onAdd = { showAdd = true },
            )
            if (showAdd) {
                NewListDialog(
                    onDismiss = { showAdd = false },
                    onSave = { spec ->
                        vm.addSpec(spec) { ok ->
                            showAdd = false
                            if (ok) nav.navigate("channels/${Uri.encode(spec.name)}")
                        }
                    },
                    onTest = { spec, cb -> vm.testSpec(spec, cb) },
                )
            }
        }
        composable(
            "channels/{name}",
            arguments = listOf(navArgument("name") { type = NavType.StringType }),
        ) { back ->
            val name = Uri.decode(back.arguments?.getString("name") ?: "")
            val vm: ChannelsViewModel = viewModel(
                key = "ch/$name",
                factory = VmFactory { ChannelsViewModel(container.repo, container.settings, name) },
            )
            val skin by container.settings.skin.collectAsStateWithLifecycle(initialValue = DEFAULT_SKIN)
            ChannelsScreen(
                listName = name, vm = vm, skin = skin,
                onSkin = { name2 -> scope.launch { container.settings.setSkin(name2) } },
                onPlay = { ch, list ->
                    playQueue.set(name, list, list.indexOf(ch))
                    nav.navigate("player/${Uri.encode(ch.url)}/${Uri.encode(ch.title)}/true/")
                },
                // Navegación explícita (no popBackStack): si channels quedó como
                // primer destino del stack, popBackStack() es no-op (bug real).
                onBack = {
                    nav.navigate("lists") {
                        popUpTo(0) { inclusive = true } // limpia TODO el backstack
                    }
                },
            )
        }
        composable(
            "player/{url}/{title}/{live}/{ua}",
            arguments = listOf(
                navArgument("url") { type = NavType.StringType },
                navArgument("title") { type = NavType.StringType },
                navArgument("live") { type = NavType.BoolType },
                navArgument("ua") { type = NavType.StringType },
            ),
        ) { back ->
            PlayerScreen(
                url = Uri.decode(back.arguments?.getString("url") ?: ""),
                title = Uri.decode(back.arguments?.getString("title") ?: ""),
                isLive = back.arguments?.getBoolean("live") ?: true,
                userAgent = Uri.decode(back.arguments?.getString("ua") ?: "").ifEmpty { null },
                queue = playQueue,
                onClose = {
                    // Si el player fue el destino inicial (smoke test por intent),
                    // popBackStack no tiene a dónde volver → ir a lists.
                    if (!nav.popBackStack()) {
                        nav.navigate("lists") { popUpTo(0) { inclusive = true } }
                    }
                },
            )
        }
    }
}
