package com.alex193a.rootmypixel.di

import com.alex193a.rootmypixel.data.datasource.PayloadLocalDataSource
import com.alex193a.rootmypixel.data.repository.PayloadRepositoryImpl
import com.alex193a.rootmypixel.domain.repository.PayloadRepository
import com.alex193a.rootmypixel.domain.usecase.DownloadPayloadsUseCase
import com.alex193a.rootmypixel.domain.usecase.ResolveTargetUseCase
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
