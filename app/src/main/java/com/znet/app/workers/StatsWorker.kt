package com.znet.app.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class StatsWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        // Telemetry transport will move through Appbridge in a later step.
        // Until that contract exists, the mobile app should not talk to the
        // orchestrator directly.
        return Result.success()
    }
}
