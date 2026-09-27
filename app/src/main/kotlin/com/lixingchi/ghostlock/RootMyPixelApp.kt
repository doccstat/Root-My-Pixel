package com.lixingchi.ghostlock

import android.app.Application
import androidx.compose.ui.platform.isDebugInspectorInfoEnabled
import com.lixingchi.ghostlock.di.dataModule
import com.lixingchi.ghostlock.di.domainModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class RootMyPixelApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@RootMyPixelApp)
            modules(domainModule, dataModule)
        }
    }
}

