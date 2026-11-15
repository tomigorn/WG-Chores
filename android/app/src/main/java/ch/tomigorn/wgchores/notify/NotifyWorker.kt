package ch.tomigorn.wgchores.notify

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import ch.tomigorn.wgchores.notify.NotifyCheck.Outcome
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/** What the worker needs from the app; tests hand in their own. */
interface NotifyJob {
    suspend fun check(): Outcome
    /** Queues the check after the notify time [ran] was for. */
    suspend fun scheduleAfter(ran: Instant)
}

/** The background check at one notify time; it queues the next one itself. */
class NotifyWorker(context: Context, params: WorkerParameters, private val job: NotifyJob) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val outcome = try {
            job.check()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Outcome.Retry
        }
        when (outcome) {
            Outcome.Off, Outcome.SignedOut -> return Result.success()
            Outcome.Retry -> if (runAttemptCount < MAX_RETRIES) return Result.retry()
            Outcome.Done -> Unit
        }
        job.scheduleAfter(Instant.ofEpochMilli(inputData.getLong(KEY_AT, 0)))
        return Result.success()
    }

    companion object {
        const val KEY_AT = "at"
        const val MAX_RETRIES = 3
    }
}

class NotifyWorkerFactory(private val job: () -> NotifyJob) : WorkerFactory() {
    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
        if (workerClassName == NotifyWorker::class.java.name) NotifyWorker(appContext, workerParameters, job()) else null
}

data class NotifyPlan(val account: String, val times: List<String>, val tz: String)

/** One waiting check at the next notify time (unique work); WorkManager keeps it across reboots. */
class NotifyScheduler(private val work: () -> WorkManager, private val store: NotifyStore, private val now: () -> Instant = Instant::now) {
    /** Sign-in, config or switches changed: the same plan keeps the waiting check, another replaces it, none cancels it. */
    suspend fun apply(plan: NotifyPlan?) {
        val at = plan?.let { NotifySchedule.next(now(), it.times, it.tz) }
        if (plan == null || at == null) {
            work().cancelUniqueWork(NAME)
            store.saveSignature(null)
            return
        }
        val signature = "${plan.account}|${plan.times.joinToString(",")}|${plan.tz}"
        val policy = if (store.signature() == signature) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE
        work().enqueueUniqueWork(NAME, policy, request(at))
        store.saveSignature(signature)
    }

    /** From the check itself: the first notify time after the one it ran for (or after now, when it ran late). It replaces the running check, which ends right after. */
    fun next(plan: NotifyPlan, ran: Instant) {
        val at = NotifySchedule.next(maxOf(now(), ran), plan.times, plan.tz) ?: return
        work().enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request(at))
    }

    private fun request(at: Instant): OneTimeWorkRequest = OneTimeWorkRequestBuilder<NotifyWorker>()
        .setInitialDelay(Duration.between(now(), at).coerceAtLeast(Duration.ZERO))
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
        .setInputData(workDataOf(NotifyWorker.KEY_AT to at.toEpochMilli()))
        .addTag(NAME)
        .build()

    companion object {
        const val NAME = "notify-check"
    }
}
