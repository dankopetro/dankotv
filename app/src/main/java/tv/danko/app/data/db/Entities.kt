package tv.danko.app.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction

/** Lista guardada. Espejo del dict de config.upsert_list() en Linux. */
@Entity(tableName = "lists")
data class ListEntity(
    @PrimaryKey val name: String,
    val type: String, // "m3u" | "xtream"
    val url: String? = null,
    val host: String? = null,
    val user: String? = null,
    val pass: String? = null,
    val channelCount: Int = 0,
    val groupCount: Int = 0,
    val epgUrl: String = "",
    val updatedAt: Long = 0L,
)

@Entity(
    tableName = "channels",
    indices = [
        Index("listName"),
        Index(value = ["listName", "group"]),
        // Búsqueda incremental: LIKE '%q%' + ORDER BY title sobre 78k filas.
        // Índice covering (listName, title): el WHERE + COUNT + ORDER se
        // resuelven solo con el índice, sin tocar la tabla.
        Index(value = ["listName", "title"]),
    ],
)
data class ChannelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val listName: String,
    val title: String,
    val group: String,
    val url: String,
    val logo: String = "",
    val epgId: String = "",
)

@Entity(
    tableName = "favorites",
    primaryKeys = ["listName", "url"],
)
data class FavoriteEntity(val listName: String, val url: String)

/** Programas EPG cacheados por canal (F5). startMs/endMs = epoch UTC. */
@Entity(
    tableName = "epg_programmes",
    indices = [Index(value = ["listName", "channelUrl", "startMs"])],
)
data class EpgProgramme(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val listName: String,
    val channelUrl: String,
    val startMs: Long,
    val endMs: Long,
    val title: String = "",
    val desc: String = "",
    val fetchedAt: Long = 0L,
)

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM lists ORDER BY updatedAt DESC")
    suspend fun lists(): List<ListEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertList(e: ListEntity)

    @Query("DELETE FROM lists WHERE name = :name")
    suspend fun deleteList(name: String)

    @Query("DELETE FROM channels WHERE listName = :list")
    suspend fun clearChannels(list: String)

    @Insert
    suspend fun insertChannels(rows: List<ChannelEntity>)

    /** Insert en lotes: Room/SQLite con 344k filas de una falla por binder/txn. */
    @Transaction
    suspend fun insertChannelsBatched(list: String, rows: List<ChannelEntity>) {
        var i = 0
        while (i < rows.size) {
            val end = minOf(i + 5000, rows.size)
            insertChannels(rows.subList(i, end).map { it.copy(listName = list) })
            i = end
        }
    }

    @Transaction
    suspend fun replaceChannels(list: String, rows: List<ChannelEntity>) {
        clearChannels(list)
        insertChannels(rows)
    }

    @Query("SELECT * FROM channels WHERE listName = :list ORDER BY title")
    suspend fun channels(list: String): List<ChannelEntity>

    // --- queries LIMITADAS para listas gigantes (344k): el SELECT completo
    //     sin límite satura RAM y el ORDER BY sin índice nunca vuelve a tiempo
    //     (verificado en device: "0 canales" eterno). Display = 2000, igual
    //     que DISPLAY_LIMIT en mainview.py. ---

    @Query("SELECT * FROM channels WHERE listName = :list ORDER BY title LIMIT :limit")
    suspend fun channelsLimited(list: String, limit: Int = 2000): List<ChannelEntity>

    @Query("SELECT COUNT(*) FROM channels WHERE listName = :list")
    suspend fun countChannels(list: String): Int

    @Query("SELECT * FROM channels WHERE listName = :list AND `group` = :group ORDER BY title LIMIT :limit")
    suspend fun channelsInGroup(list: String, group: String, limit: Int = 2000): List<ChannelEntity>

    @Query("SELECT COUNT(*) FROM channels WHERE listName = :list AND `group` = :group")
    suspend fun countInGroup(list: String, group: String): Int

    @Query(
        "SELECT * FROM channels WHERE listName = :list AND `group` = :group AND title LIKE '%' || :q || '%' ESCAPE '\\' " +
            "ORDER BY title LIMIT :limit"
    )
    suspend fun searchInGroup(list: String, group: String, q: String, limit: Int = 2000): List<ChannelEntity>

    @Query(
        "SELECT COUNT(*) FROM channels WHERE listName = :list AND `group` = :group AND title LIKE '%' || :q || '%' ESCAPE '\\'"
    )
    suspend fun countSearchInGroup(list: String, group: String, q: String): Int

    @Query(
        "SELECT COUNT(*) FROM channels WHERE listName = :list AND title LIKE '%' || :q || '%' ESCAPE '\\'"
    )
    suspend fun countSearch(list: String, q: String): Int

    /** Canales favoritos de la lista (JOIN, en SQL — los favs pueden ser cualquiera de 344k). */
    @Query(
        "SELECT c.* FROM channels c JOIN favorites f ON c.listName = f.listName AND c.url = f.url " +
            "WHERE c.listName = :list ORDER BY c.title LIMIT :limit"
    )
    suspend fun favoriteChannels(list: String, limit: Int = 2000): List<ChannelEntity>

    @Query(
        "SELECT COUNT(*) FROM channels c JOIN favorites f ON c.listName = f.listName AND c.url = f.url " +
            "WHERE c.listName = :list"
    )
    suspend fun countFavorites(list: String): Int

    @Query(
        "SELECT c.* FROM channels c JOIN favorites f ON c.listName = f.listName AND c.url = f.url " +
            "WHERE c.listName = :list AND c.title LIKE '%' || :q || '%' ESCAPE '\\' ORDER BY c.title LIMIT :limit"
    )
    suspend fun searchFavorites(list: String, q: String, limit: Int = 2000): List<ChannelEntity>

    @Query(
        "SELECT COUNT(*) FROM channels c JOIN favorites f ON c.listName = f.listName AND c.url = f.url " +
            "WHERE c.listName = :list AND c.title LIKE '%' || :q || '%' ESCAPE '\\'"
    )
    suspend fun countSearchFavorites(list: String, q: String): Int

    @Query("SELECT DISTINCT `group` FROM channels WHERE listName = :list ORDER BY `group`")
    suspend fun groups(list: String): List<String>

    /** Búsqueda incremental. Escapa % _ y \ para que LIKE sea literal. */
    @Query(
        "SELECT * FROM channels WHERE listName = :list AND title LIKE '%' || :q || '%' ESCAPE '\\' " +
            "ORDER BY title LIMIT 2000"
    )
    suspend fun search(list: String, q: String): List<ChannelEntity>

    @Query("SELECT url FROM favorites WHERE listName = :list")
    suspend fun favoriteUrls(list: String): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addFavorite(f: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE listName = :list AND url = :url")
    suspend fun removeFavorite(list: String, url: String)

    @Query("DELETE FROM favorites WHERE listName = :list")
    suspend fun clearFavorites(list: String)

    @Query(
        "SELECT * FROM epg_programmes WHERE listName = :list AND channelUrl = :url " +
            "AND endMs > :now ORDER BY startMs LIMIT 12"
    )
    suspend fun epgFor(list: String, url: String, now: Long): List<EpgProgramme>

    @Transaction
    suspend fun replaceEpg(list: String, url: String, rows: List<EpgProgramme>) {
        deleteEpg(list, url)
        insertEpg(rows)
    }

    @Query("DELETE FROM epg_programmes WHERE listName = :list AND channelUrl = :url")
    suspend fun deleteEpg(list: String, url: String)

    @Insert
    suspend fun insertEpg(rows: List<EpgProgramme>)

    @Query("DELETE FROM epg_programmes WHERE endMs < :now")
    suspend fun pruneEpg(now: Long)
}

fun String.escapeLike(): String {
    val sb = StringBuilder(length)
    for (c in this) {
        if (c == '%' || c == '_' || c == '\\') sb.append('\\')
        sb.append(c)
    }
    return sb.toString()
}
