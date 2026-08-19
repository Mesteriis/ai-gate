package com.aigate.router.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aigate.router.data.model.AiModel
import com.aigate.router.data.model.LocalModel
import com.aigate.router.data.model.Provider
import com.aigate.router.data.model.TokenUsage
import com.aigate.router.data.model.SpeedHistory
import com.aigate.router.data.model.RoutingRule
import com.aigate.router.data.model.Credential
import com.aigate.router.data.model.ResourcePool
import com.aigate.router.data.model.QuotaSnapshot
import com.aigate.router.data.model.ModelPricing

@Database(
    entities = [
        Provider::class,
        AiModel::class,
        TokenUsage::class,
        SpeedHistory::class,
        RoutingRule::class,
        Credential::class,
        ResourcePool::class,
        QuotaSnapshot::class,
        ModelPricing::class,
        LocalModel::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun providerDao(): ProviderDao
    abstract fun aiModelDao(): AiModelDao
    abstract fun tokenUsageDao(): TokenUsageDao
    abstract fun speedHistoryDao(): SpeedHistoryDao
    abstract fun routingRuleDao(): RoutingRuleDao
    abstract fun credentialDao(): CredentialDao
    abstract fun resourcePoolDao(): ResourcePoolDao
    abstract fun quotaSnapshotDao(): QuotaSnapshotDao
    abstract fun modelPricingDao(): ModelPricingDao
    abstract fun localModelDao(): LocalModelDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * v3 → v4: таблица скачанных моделей.
         *
         * Первая настоящая миграция в проекте. Раньше схему пересобирали
         * начисто, и это было допустимо, пока в базе не было ничего ценного.
         * Сейчас там подключённые провайдеры, зашифрованные ключи и история
         * расхода — терять их ради новой таблицы нельзя, тем более что
         * изменение чисто добавочное.
         *
         * SQL повторяет то, что сгенерировал бы Room для LocalModel: имена и
         * типы столбцов должны совпасть точно, иначе Room отвергнет схему при
         * первом открытии.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `local_models` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `source` TEXT NOT NULL,
                        `repo` TEXT NOT NULL,
                        `ref` TEXT NOT NULL,
                        `engine` TEXT NOT NULL,
                        `display_name` TEXT NOT NULL,
                        `size_bytes` INTEGER NOT NULL,
                        `downloaded_bytes` INTEGER NOT NULL,
                        `sha256` TEXT NOT NULL,
                        `file_path` TEXT NOT NULL,
                        `state` TEXT NOT NULL,
                        `error_message` TEXT NOT NULL,
                        `context_window` INTEGER NOT NULL,
                        `quant` TEXT NOT NULL,
                        `params_b` REAL,
                        `created_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_local_models_source_repo_ref` " +
                        "ON `local_models` (`source`, `repo`, `ref`)"
                )
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "aigate.db"
                )
                    .addMigrations(MIGRATION_3_4)
                    // Запасной путь только для версий ниже 3: для них пути
                    // миграции нет, а данные тех сборок давно неактуальны.
                    // Начиная с v3 схема развивается миграциями — база
                    // пользователя больше не пересобирается начисто.
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
