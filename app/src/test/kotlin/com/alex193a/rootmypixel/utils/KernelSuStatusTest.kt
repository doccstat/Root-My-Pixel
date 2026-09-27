package com.alex193a.rootmypixel.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KernelSuStatusTest {

    @Test
    fun `active status requires a responsive KernelSU driver and version`() {
        val status = NativeProbe.KernelSuStatus.parse(
            "probe=1;present=1;responsive=1;granted=1;version=12345;flags=5;features=9;uapi=2"
        )

        assertTrue(status.isActive)
        assertTrue(status.isLateLoad)
        assertTrue(status.appRootGranted)
    }

    @Test
    fun `driver file descriptor alone is not treated as active KernelSU`() {
        val status = NativeProbe.KernelSuStatus.parse(
            "probe=1;present=1;responsive=0;granted=0;version=0;flags=0;features=0;uapi=0"
        )

        assertFalse(status.isActive)
    }
}
