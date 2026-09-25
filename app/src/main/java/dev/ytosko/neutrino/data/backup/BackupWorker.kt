package dev.ytosko.neutrino.data.backup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.ytosko.neutrino.appContainer
import kotlinx.coroutines.flow.first

/**
 * Runs daily, and shortly after meals change: refreshes the local backup file and uploads to
 * Drive when a copy is due. Drive failures are recorded for the Backup screen; a network error
 * is retried with WorkManager's backoff.
 */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = applicationContext.appContainer
        runCatching { container.syncHealthConnect() }
        val backups = container.backups
        val run = runCatching { backups.backUp() }.getOrElse { return Result.retry() }
        val problem = backups.state.first().driveProblem
        return if (run.drive == false && problem == DriveProblem.Failed && runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
    }

    companion object {
        const val PERIODIC = "backup-daily"
        const val SOON = "backup-soon"
        private const val MAX_RETRIES = 3
    }
}
