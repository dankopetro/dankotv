package tv.danko.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** v1: listas + canales + favoritos. Tablas EPG llegan en F6 (auto-migración). */
@Database(
    entities = [ListEntity::class, ChannelEntity::class, FavoriteEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun playlists(): PlaylistDao
}

/** v1→v2: índice covering para la búsqueda incremental (listName, title). */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_channels_listName_title` " +
                "ON `channels` (`listName`, `title`)",
        )
    }
}
