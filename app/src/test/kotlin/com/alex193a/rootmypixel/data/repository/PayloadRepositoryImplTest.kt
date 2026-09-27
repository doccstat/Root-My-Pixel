package com.alex193a.rootmypixel.data.repository

import android.content.ContextWrapper
import com.alex193a.rootmypixel.core.Result
import com.alex193a.rootmypixel.data.datasource.PayloadLocalDataSource
import com.alex193a.rootmypixel.data.model.BundledProfileDto
import com.alex193a.rootmypixel.domain.model.DeviceSnapshot
import com.alex193a.rootmypixel.domain.repository.PayloadError
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class PayloadRepositoryImplTest {

    private lateinit var repository: PayloadRepositoryImpl

    private val testProfiles = listOf(
        BundledProfileDto(
            profileId = "mustang-CP2A.260705.006",
            codename = "mustang",
            kernelRelease = "6.6.118-android15-9",
            buildDisplay = "CP2A.260705.006",
            exploitAsset = "exploits/mustang-CP2A.260705.006.so",
            kmi = "android15-6.6",
        ),
        BundledProfileDto(
            profileId = "frankel-CP2A.260605.012",
            codename = "frankel",
            kernelRelease = "6.6.89",
            buildDisplay = "CP2A.260605.012",
            exploitAsset = "exploits/frankel-CP2A.260605.012.so",
            kmi = "android15-6.6",
        )
    )

    private class FakeLocalDataSource(private val profiles: List<BundledProfileDto>) :
        PayloadLocalDataSource(ContextWrapper(null)) {

        override fun loadProfiles(): kotlin.Result<List<BundledProfileDto>> {
            return kotlin.Result.success(profiles)
        }
    }

    @Before
    fun setUp() {
        val fakeDataSource = FakeLocalDataSource(testProfiles)
        val tempDir = File(System.getProperty("java.io.tmpdir"), "rootmypixel_test")
        repository = PayloadRepositoryImpl(fakeDataSource, tempDir)
    }

    @Test
    fun resolveTarget_exactMatch_returnsProfile() = runBlocking {
        val snapshot = DeviceSnapshot(
            kernelRelease = "6.6.118-android15-9",
            kernelVersion = "Linux version 6.6.118-android15-9-g690101101069",
            buildDisplay = "CP2A.260705.006",
            sdkVersion = 36,
            abi = "arm64-v8a",
            pageSize = 4096,
            model = "Pixel 10 Pro XL",
            device = "mustang",
        )

        val result = repository.resolveTarget(snapshot)
        assertTrue(result is Result.Success)
        assertEquals("mustang-CP2A.260705.006", (result as Result.Success).data.profileId)
    }

    @Test
    fun resolveTarget_kernelVersionPrefixMatch_returnsProfile() = runBlocking {
        val snapshot = DeviceSnapshot(
            kernelRelease = "6.6.89",
            kernelVersion = "Linux version 6.6.89-android15-7-g96da6eff8481-ab12814056-extra",
            buildDisplay = "CP2A.260605.012",
            sdkVersion = 36,
            abi = "arm64-v8a",
            pageSize = 4096,
            model = "Pixel 10",
            device = "frankel",
        )

        val result = repository.resolveTarget(snapshot)
        assertTrue(result is Result.Success)
        assertEquals("frankel-CP2A.260605.012", (result as Result.Success).data.profileId)
    }

    @Test
    fun resolveTarget_byProfileId_returnsProfile() = runBlocking {
        val result = repository.resolveTarget("mustang-CP2A.260705.006")
        assertTrue(result is Result.Success)
        assertEquals("mustang", (result as Result.Success).data.codename)
    }

    @Test
    fun resolveTarget_differentBuildDisplay_stillMatchesProfile() = runBlocking {
        val snapshot = DeviceSnapshot(
            kernelRelease = "6.6.118-android15-9",
            kernelVersion = "Linux version 6.6.118-android15-9-g690101101069",
            buildDisplay = "DIFFERENT.BUILD.123",
            sdkVersion = 36,
            abi = "arm64-v8a",
            pageSize = 4096,
            model = "Pixel 10 Pro XL",
            device = "mustang",
        )

        val result = repository.resolveTarget(snapshot)
        assertTrue(result is Result.Success)
        assertEquals("mustang-CP2A.260705.006", (result as Result.Success).data.profileId)
    }

    @Test
    fun resolveTarget_unsupportedDevice_returnsError() = runBlocking {
        val snapshot = DeviceSnapshot(
            kernelRelease = "5.10.0",
            kernelVersion = "Linux version 5.10.0",
            buildDisplay = "UNKNOWN",
            sdkVersion = 30,
            abi = "arm64-v8a",
            pageSize = 4096,
            model = "Unknown Phone",
            device = "unknown",
        )

        val result = repository.resolveTarget(snapshot)
        assertTrue(result is Result.Error)
        assertTrue((result as Result.Error).error is PayloadError.UnsupportedError)
    }
}
