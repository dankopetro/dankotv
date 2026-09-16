package tv.danko.app.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import tv.danko.app.data.ListsRepository
import tv.danko.app.data.SettingsStore
import tv.danko.app.data.XtreamClient
import tv.danko.app.data.db.AppDatabase
import tv.danko.app.data.db.MIGRATION_1_2

/** DI manual (sin Hilt): un contenedor por proceso, como el wiring de app.py. */
class AppContainer(context: Context) {
    private val appCtx = context.applicationContext
    val db: AppDatabase by lazy {
        Room.databaseBuilder(appCtx, AppDatabase::class.java, "dankotv.db")
            .addMigrations(MIGRATION_1_2).build()
    }
    val settings: SettingsStore by lazy { SettingsStore.create(appCtx) }
    val xtream: XtreamClient by lazy { XtreamClient() }
    val repo: ListsRepository by lazy { ListsRepository(db.playlists(), settings, xtream = xtream) }
}

/** Factory genérica para ViewModels con constructor. */
class VmFactory<T : ViewModel>(private val make: () -> T) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = make() as T
}
