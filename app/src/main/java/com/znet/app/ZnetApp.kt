package com.znet.app

import android.app.Application

class ZnetApp : Application() {
    val container: AppContainer by lazy {
        AppContainer(this)
    }
}
