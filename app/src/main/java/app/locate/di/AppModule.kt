package app.locate.di

import android.content.Context
import androidx.room.Room
import app.locate.BuildConfig
import app.locate.core.ble.AndroidBleSource
import app.locate.core.ble.BleEventBus
import app.locate.core.ble.BleSource
import app.locate.core.ble.FakeBleSource
import app.locate.core.location.FusedLocationProvider
import app.locate.core.location.LocationProvider
import app.locate.core.permissions.PermissionChecker
import app.locate.core.time.Clock
import app.locate.data.db.LocateDatabase
import app.locate.data.repo.LocalSightingRepository
import app.locate.data.repo.SightingRepository
import app.locate.domain.alert.AlertConfig
import app.locate.notify.AlertNotifier
import app.locate.notify.Notifications
import app.locate.service.MonitorConfig
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    fun clock(): Clock = Clock.System

    @Provides
    fun alertConfig() = AlertConfig()

    @Provides
    fun monitorConfig() = MonitorConfig()

    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): LocateDatabase =
        Room.databaseBuilder(context, LocateDatabase::class.java, "locate.db")
            // Pre-1.0: schema changes wipe local data. Replace with real migrations before release.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides fun deviceDao(db: LocateDatabase) = db.deviceDao()
    @Provides fun sightingDao(db: LocateDatabase) = db.sightingDao()
    @Provides fun safeZoneDao(db: LocateDatabase) = db.safeZoneDao()
    @Provides fun alertEpisodeDao(db: LocateDatabase) = db.alertEpisodeDao()

    @Provides
    @Singleton
    fun bleSource(@ApplicationContext context: Context, bus: BleEventBus, clock: Clock): BleSource =
        if (BuildConfig.USE_FAKE_BLE) FakeBleSource(clock, rssiJitterDb = 4) else AndroidBleSource(context, bus)

    @Provides
    @Singleton
    fun locationProvider(@ApplicationContext context: Context, permissions: PermissionChecker): LocationProvider =
        FusedLocationProvider(context, permissions)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds
    @Singleton
    abstract fun sightingRepository(impl: LocalSightingRepository): SightingRepository

    @Binds
    abstract fun alertNotifier(impl: Notifications): AlertNotifier
}
