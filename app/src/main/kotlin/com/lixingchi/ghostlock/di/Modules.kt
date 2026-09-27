package com.lixingchi.ghostlock.di

import com.lixingchi.ghostlock.data.datasource.PayloadLocalDataSource
import com.lixingchi.ghostlock.data.repository.PayloadRepositoryImpl
import com.lixingchi.ghostlock.domain.repository.PayloadRepository
import com.lixingchi.ghostlock.domain.usecase.DownloadPayloadsUseCase
import com.lixingchi.ghostlock.domain.usecase.ResolveTargetUseCase
import org.koin.dsl.module

val domainModule = module {
    single { ResolveTargetUseCase(get()) }
    single { DownloadPayloadsUseCase(get()) }
}

val dataModule = module {
    single { PayloadLocalDataSource(get()) }
    single<PayloadRepository> {
        PayloadRepositoryImpl(
            localDataSource = get(),
            filesDir = get<android.content.Context>().filesDir,
        )
    }
}
