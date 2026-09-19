package tv.danko.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** v1: listas + canales + favoritos; v3: + programas EPG (F5). */
@Database(
    entities = [ListEntity::class, ChannelEntity::class, FavoriteEntity::class, EpgProgramme::class],
    version = 4,
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

/** v2→v3: tabla de programas EPG cacheados (F5). */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `epg_programmes` (`id` INTEGER PRIMARY KEY " +
                "AUTOINCREMENT NOT NULL, `listName` TEXT NOT NULL, `channelUrl` TEXT NOT NULL, " +
                "`startMs` INTEGER NOT NULL, `endMs` INTEGER NOT NULL, `title` TEXT NOT NULL, " +
                "`desc` TEXT NOT NULL, `fetchedAt` INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_epg_programmes_listName_channelUrl_startMs` " +
                "ON `epg_programmes` (`listName`, `channelUrl`, `startMs`)",
        )
    }
}

/**
 * v3→v4: purga del caché EPG (las filas viejas traen horas SIN corregir;
 * desde v4 getShortEpg aplica +3h — ver EPG_TZ_SHIFT_MS).
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DELETE FROM `epg_programmes`")
    }
}
