package com.aigate.router.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.aigate.router.data.model.AiModel
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
        ModelPricing::class
    ],
    version = 3,
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

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "aigate.db"
                )
                    // Pre-release only: v0.1.0 has no shipped data, so schema changes
                    // (v2 credentials table; v3 resource_pools/quota_snapshots/model_pricing
                    // for the AI Resource Manager) are handled by a destructive rebuild.
                    // Replace with real migrations before the first public release.
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
