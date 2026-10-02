package com.nexastream.app.database

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.nexastream.app.database.dao.EpisodeDao
import com.nexastream.app.database.dao.MovieDao
import com.nexastream.app.database.dao.SeasonDao
import com.nexastream.app.database.dao.TvShowDao
import com.nexastream.app.database.dao.DownloadDao
import com.nexastream.app.database.dao.LiveTvDao
import com.nexastream.app.database.dao.SearchHistoryDao
import com.nexastream.app.database.dao.MetadataCacheDao
import com.nexastream.app.models.TmdbMetadataCache
import com.nexastream.app.models.Episode
import com.nexastream.app.models.Movie
import com.nexastream.app.models.Season
import com.nexastream.app.models.TvShow
import com.nexastream.app.models.Download
import com.nexastream.app.models.EpgProgram
import com.nexastream.app.models.EpgChannelMapping
import com.nexastream.app.models.LiveChannelPreference
import com.nexastream.app.models.LivePlaybackDiagnostic
import com.nexastream.app.models.LiveRecording
import com.nexastream.app.models.LiveStreamHealth
import com.nexastream.app.models.ProgramReminder
import com.nexastream.app.models.SearchHistory
import com.nexastream.app.models.XmlTvChannel
import com.nexastream.app.utils.UserPreferences

@Database(
    entities = [
        Episode::class,
        Movie::class,
        Season::class,
        TvShow::class,
        Download::class,
        EpgProgram::class,
        LiveStreamHealth::class,
        ProgramReminder::class,
        LiveRecording::class,
        LiveChannelPreference::class,
        LivePlaybackDiagnostic::class,
        XmlTvChannel::class,
        EpgChannelMapping::class,
        SearchHistory::class,
        TmdbMetadataCache::class,
    ],
    version = 21,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun movieDao(): MovieDao

    abstract fun tvShowDao(): TvShowDao

    abstract fun seasonDao(): SeasonDao

    abstract fun episodeDao(): EpisodeDao

    abstract fun downloadDao(): DownloadDao

    abstract fun liveTvDao(): LiveTvDao

    abstract fun searchHistoryDao(): SearchHistoryDao

    abstract fun metadataCacheDao(): MetadataCacheDao

    companion object {

        @Volatile
        private var INSTANCE: AppDatabase? = null
        @Volatile
        private var currentProviderName: String? = null

        private fun sanitizeProviderName(name: String): String {
            // Rimuove caratteri non validi per i nomi dei file DB, 
            // come spazi, parentesi, e li converte in lowercase.
            return name.lowercase()
                .replace("[^a-z0-9]".toRegex(), "_")
                .replace("__+".toRegex(), "_") // Sostituisce doppie underscore con una singola
                .trim('_') // Rimuove underscore iniziale/finale
        }

        fun setup(context: Context) {
            if (UserPreferences.currentProvider == null) return

            getInstance(context)
        }

        fun getInstance(context: Context): AppDatabase {
            val providerName = UserPreferences.currentProvider?.name
                ?: currentProviderName
                ?: throw IllegalStateException("Current provider is not set")

            return INSTANCE?.takeIf { currentProviderName == providerName } ?: synchronized(this) {
                INSTANCE?.takeIf { currentProviderName == providerName } ?: run {
                    Log.d("AppDatabase", "Switching database from '$currentProviderName' to '$providerName'")
                    buildDatabase(providerName, context).also { instance ->
                        INSTANCE = instance
                        currentProviderName = providerName
                    }
                }
            }
        }

        // Metodo per forzare il cambio di database quando cambia il provider
        fun resetInstance() {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
                currentProviderName = null
            }
        }

        fun getInstanceForProvider(providerName: String, context: Context): AppDatabase {
            return buildDatabase(providerName, context)
        }

        private fun buildDatabase(providerName: String, context: Context): AppDatabase {
            val sanitizedName = sanitizeProviderName(providerName)
            val profileId = UserPreferences.currentProfileId
            val dbName = if (profileId == 0) "$sanitizedName.db" else "${sanitizedName}_$profileId.db"
            return Room.databaseBuilder(
                context = context.applicationContext,
                klass = AppDatabase::class.java,
                name = dbName
            )
                .fallbackToDestructiveMigration()
                .addMigrations(*ALL_MIGRATIONS)
                .build()
        }

        private fun addColumnIfMissing(
            db: SupportSQLiteDatabase,
            table: String,
            column: String,
            definition: String,
        ) {
            val cursor = db.query("PRAGMA table_info(`$table`)")
            cursor.use {
                val nameIndex = it.getColumnIndex("name")
                while (it.moveToNext()) {
                    if (it.getString(nameIndex) == column) return
                }
            }
            db.execSQL("ALTER TABLE `$table` ADD COLUMN $definition")
        }

        private val MIGRATION_8_9: Migration = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `downloads` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `poster` TEXT, `filePath` TEXT NOT NULL, `url` TEXT NOT NULL, `status` TEXT NOT NULL, `progress` INTEGER NOT NULL, `totalSize` INTEGER NOT NULL, `quality` TEXT, `headers` TEXT, `mimeType` TEXT, `errorMessage` TEXT, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            }
        }

        private val MIGRATION_9_10: Migration = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "downloads", "url", "`url` TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_10_11: Migration = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "downloads", "headers", "`headers` TEXT")
                addColumnIfMissing(db, "downloads", "errorMessage", "`errorMessage` TEXT")
            }
        }

        private val MIGRATION_11_12: Migration = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "downloads", "mimeType", "`mimeType` TEXT")
            }
        }

        private val MIGRATION_12_13: Migration = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Version bump to fix integrity hash issues
            }
        }

        val MIGRATION_13_14: Migration = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "downloads", "downloadedSize", "`downloadedSize` INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing(db, "downloads", "downloadSpeed", "`downloadSpeed` INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing(db, "downloads", "etaSeconds", "`etaSeconds` INTEGER")
            }
        }

        val MIGRATION_15_16: Migration = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `epg_programs` (" +
                        "`programId` TEXT NOT NULL, `channelId` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, `description` TEXT, `category` TEXT, `icon` TEXT, " +
                        "`startMillis` INTEGER NOT NULL, `endMillis` INTEGER NOT NULL, " +
                        "`sourceUrl` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`programId`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_epg_programs_channelId_startMillis` ON `epg_programs` (`channelId`, `startMillis`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_epg_programs_endMillis` ON `epg_programs` (`endMillis`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_epg_programs_sourceUrl` ON `epg_programs` (`sourceUrl`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `live_stream_health` (" +
                        "`streamKey` TEXT NOT NULL, `channelId` TEXT NOT NULL, `streamUrl` TEXT NOT NULL, " +
                        "`lastSuccessMillis` INTEGER, `lastFailureMillis` INTEGER, " +
                        "`consecutiveFailures` INTEGER NOT NULL, `latencyMs` INTEGER, `lastError` TEXT, " +
                        "PRIMARY KEY(`streamKey`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_live_stream_health_channelId` ON `live_stream_health` (`channelId`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `program_reminders` (" +
                        "`programId` TEXT NOT NULL, `channelId` TEXT NOT NULL, `channelName` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, `startMillis` INTEGER NOT NULL, `endMillis` INTEGER NOT NULL, " +
                        "`channelPayload` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `firedAt` INTEGER, " +
                        "PRIMARY KEY(`programId`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_program_reminders_startMillis` ON `program_reminders` (`startMillis`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `live_recordings` (" +
                        "`recordingId` TEXT NOT NULL, `channelId` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                        "`filePath` TEXT NOT NULL, `sourceUrl` TEXT NOT NULL, `status` TEXT NOT NULL, " +
                        "`startedAt` INTEGER NOT NULL, `endedAt` INTEGER, `bytesWritten` INTEGER NOT NULL, " +
                        "`error` TEXT, `mimeType` TEXT NOT NULL, PRIMARY KEY(`recordingId`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_live_recordings_channelId_status` ON `live_recordings` (`channelId`, `status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_live_recordings_startedAt` ON `live_recordings` (`startedAt`)")
            }
        }

        val MIGRATION_16_17: Migration = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `live_channel_preferences` (" +
                        "`channelId` TEXT NOT NULL, `channelPayload` TEXT NOT NULL, " +
                        "`channelName` TEXT NOT NULL, `isFavorite` INTEGER NOT NULL, " +
                        "`customGroup` TEXT, `lastWatchedAt` INTEGER, `updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`channelId`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_live_channel_preferences_isFavorite` ON `live_channel_preferences` (`isFavorite`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_live_channel_preferences_customGroup` ON `live_channel_preferences` (`customGroup`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_live_channel_preferences_lastWatchedAt` ON `live_channel_preferences` (`lastWatchedAt`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `live_playback_diagnostics` (" +
                        "`eventId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `channelId` TEXT NOT NULL, " +
                        "`channelName` TEXT NOT NULL, `streamKey` TEXT NOT NULL, `host` TEXT NOT NULL, " +
                        "`quality` TEXT, `event` TEXT NOT NULL, `message` TEXT, `latencyMs` INTEGER, " +
                        "`timestamp` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_live_playback_diagnostics_channelId_timestamp` ON `live_playback_diagnostics` (`channelId`, `timestamp`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_live_playback_diagnostics_timestamp` ON `live_playback_diagnostics` (`timestamp`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `xmltv_channels` (" +
                        "`sourceUrl` TEXT NOT NULL, `xmlTvChannelId` TEXT NOT NULL, " +
                        "`displayName` TEXT NOT NULL, `icon` TEXT, `updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`sourceUrl`, `xmlTvChannelId`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_xmltv_channels_displayName` ON `xmltv_channels` (`displayName`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_xmltv_channels_xmlTvChannelId` ON `xmltv_channels` (`xmlTvChannelId`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `epg_channel_mappings` (" +
                        "`channelId` TEXT NOT NULL, `sourceUrl` TEXT NOT NULL, " +
                        "`xmlTvChannelId` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`channelId`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_epg_channel_mappings_sourceUrl_xmlTvChannelId` ON `epg_channel_mappings` (`sourceUrl`, `xmlTvChannelId`)")
            }
        }

        private val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE episodes ADD COLUMN watchedDate TEXT")
                db.execSQL("ALTER TABLE episodes ADD COLUMN lastEngagementTimeUtcMillis INTEGER")
                db.execSQL("ALTER TABLE episodes ADD COLUMN lastPlaybackPositionMillis INTEGER")
                db.execSQL("ALTER TABLE episodes ADD COLUMN durationMillis INTEGER")

                db.execSQL("ALTER TABLE movies ADD COLUMN watchedDate TEXT")
                db.execSQL("ALTER TABLE movies ADD COLUMN lastEngagementTimeUtcMillis INTEGER")
                db.execSQL("ALTER TABLE movies ADD COLUMN lastPlaybackPositionMillis INTEGER")
                db.execSQL("ALTER TABLE movies ADD COLUMN durationMillis INTEGER")
            }
        }

        private val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE `episodes_temp` (`id` TEXT NOT NULL, `number` INTEGER NOT NULL, `title` TEXT, `poster` TEXT, `tvShow` TEXT, `season` TEXT, `released` TEXT, `isWatched` INTEGER NOT NULL, `watchedDate` TEXT, `lastEngagementTimeUtcMillis` INTEGER, `lastPlaybackPositionMillis` INTEGER, `durationMillis` INTEGER, PRIMARY KEY(`id`))")
                db.execSQL("INSERT INTO episodes_temp SELECT * FROM episodes")
                db.execSQL("DROP TABLE episodes")
                db.execSQL("ALTER TABLE episodes_temp RENAME TO episodes")

                db.execSQL("CREATE TABLE `movies_temp` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `overview` TEXT, `runtime` INTEGER, `trailer` TEXT, `quality` TEXT, `rating` REAL, `poster` TEXT, `banner` TEXT, `released` TEXT, `isFavorite` INTEGER NOT NULL, `isWatched` INTEGER NOT NULL, `watchedDate` TEXT, `lastEngagementTimeUtcMillis` INTEGER, `lastPlaybackPositionMillis` INTEGER, `durationMillis` INTEGER, PRIMARY KEY(`id`))")
                db.execSQL("INSERT INTO movies_temp SELECT * FROM movies")
                db.execSQL("DROP TABLE movies")
                db.execSQL("ALTER TABLE movies_temp RENAME TO movies")

                db.execSQL("CREATE TABLE `seasons_temp` (`id` TEXT NOT NULL, `number` INTEGER NOT NULL, `title` TEXT, `poster` TEXT, `tvShow` TEXT, PRIMARY KEY(`id`))")
                db.execSQL("INSERT INTO seasons_temp SELECT * FROM seasons")
                db.execSQL("DROP TABLE seasons")
                db.execSQL("ALTER TABLE seasons_temp RENAME TO seasons")

                db.execSQL("CREATE TABLE `tv_shows_temp` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `overview` TEXT, `runtime` INTEGER, `trailer` TEXT, `quality` TEXT, `rating` REAL, `poster` TEXT, `banner` TEXT, `released` TEXT, `isFavorite` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("INSERT INTO tv_shows_temp SELECT * FROM tv_shows")
                db.execSQL("DROP TABLE tv_shows")
                db.execSQL("ALTER TABLE tv_shows_temp RENAME TO tv_shows")
            }
        }

        private val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tv_shows ADD COLUMN isWatching INTEGER DEFAULT 1 NOT NULL")
            }
        }

        private val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE episodes ADD COLUMN overview TEXT")
            }
        }

        private val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Create indexes for query optimization
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_tvShow_isWatched` ON `episodes` (`tvShow`, `isWatched`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_tvShow_lastEngagementTimeUtcMillis` ON `episodes` (`tvShow`, `lastEngagementTimeUtcMillis`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_season_number` ON `episodes` (`season`, `number`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_seasons_tvShow_number` ON `seasons` (`tvShow`, `number`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tv_shows_isWatching` ON `tv_shows` (`isWatching`)")
            }
        }

        private val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE movies ADD COLUMN favoritedAtMillis INTEGER")
                db.execSQL("ALTER TABLE tv_shows ADD COLUMN favoritedAtMillis INTEGER")
            }
        }

        private val MIGRATION_7_8: Migration = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // No SQL changes needed as indices were already created in previous migrations 
                // but are now formally declared in Entity classes, requiring a version bump.
            }
        }

        val MIGRATION_14_15: Migration = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Version bump for EPG and Live TV features
            }
        }

        val MIGRATION_17_18: Migration = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `search_history` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `query` TEXT NOT NULL, " +
                        "`timestamp` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_search_history_query` ON `search_history` (`query`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_search_history_timestamp` ON `search_history` (`timestamp`)")
            }
        }

        val MIGRATION_18_19: Migration = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tmdb_metadata_cache` (" +
                        "`cacheKey` TEXT NOT NULL, `payload` TEXT NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`cacheKey`))",
                )
            }
        }

        val MIGRATION_19_20: Migration = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Version bump for metadata cache optimization
            }
        }

        val MIGRATION_20_21: Migration = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Version bump for profile isolation and DB synchronization
            }
        }

        val ALL_MIGRATIONS = arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
            MIGRATION_10_11,
            MIGRATION_11_12,
            MIGRATION_12_13,
            MIGRATION_13_14,
            MIGRATION_14_15,
            MIGRATION_15_16,
            MIGRATION_16_17,
            MIGRATION_17_18,
            MIGRATION_18_19,
            MIGRATION_19_20,
            MIGRATION_20_21,
        )
    }
}
