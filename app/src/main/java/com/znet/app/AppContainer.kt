package com.znet.app

import android.content.Context
import com.znet.app.data.UserPreferencesRepository
import com.znet.app.data.remote.OrchestratorClient
import com.znet.app.data.repo.VpnRepository

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val preferencesRepository: UserPreferencesRepository = UserPreferencesRepository(appContext)
    val orchestratorClient: OrchestratorClient = OrchestratorClient()
    val vpnRepository: VpnRepository = VpnRepository(
        context = appContext,
        preferencesRepository = preferencesRepository,
        orchestratorClient = orchestratorClient
    )
}
