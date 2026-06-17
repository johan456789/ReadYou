package me.ash.reader.domain.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import me.ash.reader.domain.data.DiffMapHolder
import me.ash.reader.domain.model.account.Account
import me.ash.reader.infrastructure.android.ForegroundSyncController
import timber.log.Timber

@HiltWorker
class SyncWorker
@AssistedInject
constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val rssService: RssService,
    private val diffMapHolder: DiffMapHolder,
    private val workManager: WorkManager,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        if (
            tags.contains(PERIODIC_WORK_TAG) &&
                ForegroundSyncController.tryRecordMissedPeriodicSync()
        ) {
            Timber.tag(TAG).d("Deferring periodic sync tick while reader is active")
            return Result.success()
        }

        val data = inputData
        val accountId = data.getInt("accountId", -1)
        require(accountId != -1)
        val feedId = data.getString("feedId")
        val groupId = data.getString("groupId")
        val excludedReadStateIds = diffMapHolder.prepareReadStateForSync(accountId)

        return rssService
            .get()
            .sync(
                accountId = accountId,
                feedId = feedId,
                groupId = groupId,
                excludedReadStateIds = excludedReadStateIds,
            )
            .also {
                workManager
                    .beginUniqueWork(
                        uniqueWorkName = POST_SYNC_WORK_NAME,
                        existingWorkPolicy = ExistingWorkPolicy.KEEP,
                        OneTimeWorkRequestBuilder<ReaderWorker>()
                            .addTag(READER_TAG)
                            .addTag(ONETIME_WORK_TAG)
                            .setBackoffCriteria(
                                backoffPolicy = BackoffPolicy.EXPONENTIAL,
                                backoffDelay = 30,
                                timeUnit = TimeUnit.SECONDS,
                            )
                            .build(),
                    )
                    .then(OneTimeWorkRequestBuilder<WidgetUpdateWorker>().build())
                    .enqueue()
            }
    }

    companion object {
        private const val TAG = "SyncWorker"
        private const val SYNC_WORK_NAME_PERIODIC = "ReadYou"
        private const val LEGACY_READER_WORK_NAME_PERIODIC = "FETCH_FULL_CONTENT_PERIODIC"
        private const val POST_SYNC_WORK_NAME = "POST_SYNC_WORK"

        private const val SYNC_ONETIME_NAME = "SYNC_ONETIME"
        private const val SYNC_DEFERRED_PERIODIC_NAME = "SYNC_DEFERRED_PERIODIC"

        const val SYNC_TAG = "SYNC_TAG"
        const val READER_TAG = "READER_TAG"
        const val ONETIME_WORK_TAG = "ONETIME_WORK_TAG"
        const val PERIODIC_WORK_TAG = "PERIODIC_WORK_TAG"

        fun cancelOneTimeWork(workManager: WorkManager) {
            workManager.cancelUniqueWork(SYNC_ONETIME_NAME)
        }

        fun cancelPeriodicWork(workManager: WorkManager) {
            workManager.cancelUniqueWork(SYNC_WORK_NAME_PERIODIC)
            workManager.cancelUniqueWork(SYNC_DEFERRED_PERIODIC_NAME)
            workManager.cancelUniqueWork(LEGACY_READER_WORK_NAME_PERIODIC)
        }

        fun enqueueOneTimeWork(workManager: WorkManager, inputData: Data = workDataOf()) {
            workManager
                .beginUniqueWork(
                    SYNC_ONETIME_NAME,
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<SyncWorker>()
                        .addTag(SYNC_TAG)
                        .addTag(ONETIME_WORK_TAG)
                        .setInputData(inputData)
                        .build(),
                )
                .enqueue()
        }

        fun enqueueOneTimeWorkForFeed(workManager: WorkManager, inputData: Data, feedId: String) {
            workManager
                .beginUniqueWork(
                    "SYNC_FEED_$feedId",
                    ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<SyncWorker>()
                        .addTag(SYNC_TAG)
                        .addTag(ONETIME_WORK_TAG)
                        .setInputData(inputData)
                        .build(),
                )
                .enqueue()
        }

        fun enqueueDeferredPeriodicCatchUpWork(account: Account, workManager: WorkManager) {
            workManager
                .beginUniqueWork(
                    SYNC_DEFERRED_PERIODIC_NAME,
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<SyncWorker>()
                        .setConstraints(buildPeriodicConstraints(account))
                        .setBackoffCriteria(
                            backoffPolicy = BackoffPolicy.EXPONENTIAL,
                            backoffDelay = 30,
                            timeUnit = TimeUnit.SECONDS,
                        )
                        .setInputData(workDataOf("accountId" to account.id))
                        .addTag(SYNC_TAG)
                        .addTag(PERIODIC_WORK_TAG)
                        .build(),
                )
                .enqueue()
        }

        fun enqueuePeriodicWork(account: Account, workManager: WorkManager) {
            val syncInterval = account.syncInterval
            val workState =
                workManager
                    .getWorkInfosForUniqueWork(SYNC_WORK_NAME_PERIODIC)
                    .get()
                    .firstOrNull()
                    ?.state

            val policy =
                if (workState == WorkInfo.State.ENQUEUED || workState == WorkInfo.State.RUNNING)
                    ExistingPeriodicWorkPolicy.UPDATE
                else ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE

            workManager.enqueueUniquePeriodicWork(
                SYNC_WORK_NAME_PERIODIC,
                policy,
                PeriodicWorkRequestBuilder<SyncWorker>(syncInterval.value, TimeUnit.MINUTES)
                    .setConstraints(buildPeriodicConstraints(account))
                    .setBackoffCriteria(
                        backoffPolicy = BackoffPolicy.EXPONENTIAL,
                        backoffDelay = 30,
                        timeUnit = TimeUnit.SECONDS,
                    )
                    .setInputData(workDataOf("accountId" to account.id))
                    .addTag(SYNC_TAG)
                    .addTag(PERIODIC_WORK_TAG)
                    .setInitialDelay(syncInterval.value, TimeUnit.MINUTES)
                    .build(),
            )

            workManager.cancelUniqueWork(LEGACY_READER_WORK_NAME_PERIODIC)
        }

        private fun buildPeriodicConstraints(account: Account): Constraints =
            Constraints.Builder()
                .setRequiresCharging(account.syncOnlyWhenCharging.value)
                .setRequiredNetworkType(
                    if (account.syncOnlyOnWiFi.value) NetworkType.UNMETERED else NetworkType.CONNECTED
                )
                .build()
    }
}
