package com.znet.app

import android.content.Context
import com.znet.app.data.UserPreferencesRepository
import com.znet.app.data.remote.OrchestratorClient
import com.znet.app.data.repo.AdaptiveNodeSelector
import com.znet.app.data.repo.VpnRepository
import com.znet.app.workers.StatsScheduler

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val preferencesRepository: UserPreferencesRepository = UserPreferencesRepository(appContext)
    val orchestratorClient: OrchestratorClient = OrchestratorClient()
    val adaptiveNodeSelector: AdaptiveNodeSelector = AdaptiveNodeSelector()
    val vpnRepository: VpnRepository = VpnRepository(
        context = appContext,
        preferencesRepository = preferencesRepository,
        orchestratorClient = orchestratorClient,
        adaptiveNodeSelector = adaptiveNodeSelector
    )

    fun scheduleWorkers() {
        StatsScheduler.schedule(appContext)
    }
}
