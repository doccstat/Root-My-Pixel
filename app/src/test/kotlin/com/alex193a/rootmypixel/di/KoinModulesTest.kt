package com.alex193a.rootmypixel.di

import android.content.Context
import android.content.ContextWrapper
import com.alex193a.rootmypixel.domain.repository.PayloadRepository
import com.alex193a.rootmypixel.domain.usecase.DownloadPayloadsUseCase
import com.alex193a.rootmypixel.domain.usecase.ResolveTargetUseCase
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.java.KoinJavaComponent.get
import java.io.File

class KoinModulesTest {

    private class TestContext : ContextWrapper(null) {
        override fun getFilesDir(): File {
            return File(System.getProperty("java.io.tmpdir") ?: "/tmp")
        }

        override fun getApplicationContext(): Context = this
    }

    @Before
    fun setUp() {
        startKoin {
            androidContext(TestContext())
            modules(domainModule, dataModule)
        }
    }

    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun verifyKoinModuleWiring() {
        val repo: PayloadRepository = get(PayloadRepository::class.java)
        assertNotNull(repo)

        val resolveUseCase: ResolveTargetUseCase = get(ResolveTargetUseCase::class.java)
        assertNotNull(resolveUseCase)

        val downloadUseCase: DownloadPayloadsUseCase = get(DownloadPayloadsUseCase::class.java)
        assertNotNull(downloadUseCase)
    }
}
