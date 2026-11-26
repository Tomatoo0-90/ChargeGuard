package com.chargeguard.core.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.chargeguard.alarm.AlarmCoordinator
import com.chargeguard.core.common.AndroidMonitoringServiceController
import com.chargeguard.core.common.WallClock
import com.chargeguard.data.charging.AndroidChargingRepository
import com.chargeguard.data.history.HistoryDao
import com.chargeguard.data.history.HistoryDatabase
import com.chargeguard.data.history.RoomHistoryRepository
import com.chargeguard.data.security.KeystorePinMac
import com.chargeguard.data.security.PinVerifier
import com.chargeguard.data.security.SharedPrefsKeyValueStore
import com.chargeguard.data.settings.DataStoreSettingsRepository
import com.chargeguard.domain.protection.ProtectionManager
import com.chargeguard.domain.repository.AlarmOutput
import com.chargeguard.domain.repository.ChargingRepository
import com.chargeguard.domain.repository.Clock
import com.chargeguard.domain.repository.HistoryRepository
import com.chargeguard.domain.repository.MonitoringServiceController
import com.chargeguard.domain.repository.SettingsRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.security.SecureRandom
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds abstract fun clock(impl: WallClock): Clock
    @Binds abstract fun charging(impl: AndroidChargingRepository): ChargingRepository
    @Binds abstract fun history(impl: RoomHistoryRepository): HistoryRepository
    @Binds abstract fun alarmOutput(impl: AlarmCoordinator): AlarmOutput
    @Binds abstract fun serviceController(impl: AndroidMonitoringServiceController): MonitoringServiceController
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Provides @Singleton
    fun dataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("chargeguard_settings") }

    @Provides @Singleton
    fun settingsRepository(dataStore: DataStore<Preferences>): SettingsRepository = DataStoreSettingsRepository(dataStore)

    @Provides @Singleton
    fun database(@ApplicationContext context: Context): HistoryDatabase =
        Room.databaseBuilder(context, HistoryDatabase::class.java, "chargeguard_history.db").build()

    @Provides
    fun historyDao(db: HistoryDatabase): HistoryDao = db.historyDao()

    @Provides @Singleton
    fun pinVerifier(@ApplicationContext context: Context, clock: Clock): PinVerifier {
        val random = SecureRandom()
        return PinVerifier(SharedPrefsKeyValueStore(context), KeystorePinMac(), clock) { n ->
            ByteArray(n).also(random::nextBytes)
        }
    }

    @Provides @Singleton
    fun protectionManager(
        charging: ChargingRepository,
        settings: SettingsRepository,
        history: HistoryRepository,
        alarm: AlarmOutput,
        serviceController: MonitoringServiceController,
        clock: Clock,
        @ApplicationScope scope: CoroutineScope,
    ): ProtectionManager = ProtectionManager(charging, settings, history, alarm, serviceController, clock, scope)
}
