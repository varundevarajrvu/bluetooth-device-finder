package app.locate.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.locate.core.time.Clock
import app.locate.data.repo.AlertEpisodeRepository
import app.locate.data.repo.SightingRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/** Every 15 minutes: restart the monitor if an OEM killed it, and drop history older than 30 days. */
@HiltWorker
class WatchdogWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val serviceStarter: ServiceStarter,
    private val sightings: SightingRepository,
    private val episodes: AlertEpisodeRepository,
    private val clock: Clock,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        serviceStarter.ensureRunning()
        val cutoff = clock.now() - RETENTION_MS
        sightings.prune(cutoff)
        episodes.prune(cutoff)
        return Result.success()
    }

    companion object {
        private const val NAME = "watchdog"
        private val RETENTION_MS = TimeUnit.DAYS.toMillis(30)

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
