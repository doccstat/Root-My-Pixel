package com.alex193a.rootmypixel.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException

class NetfixScriptTest {

    private val expectedRules = listOf(
        "allow domain unlabeled packet send",
        "allow domain unlabeled packet recv",
        "allow domain netif netif egress",
        "allow domain netif netif ingress",
        "allow unlabeled netif netif ingress",
        "allow unlabeled netif netif egress",
        "allow domain node node sendto",
        "allow domain node node recvfrom",
        "allow unlabeled node node sendto",
        "allow unlabeled node node recvfrom",
    )

    private fun script(): String {
        val candidates = listOf(
            File("src/main/assets/netfix.sh"),
            File("app/src/main/assets/netfix.sh"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: throw FileNotFoundException("netfix.sh not found from ${File("").absolutePath}")
        return file.readText()
    }

    @Test
    fun `patches every broken selinux class`() {
        // The LKM desynchronises SECMARK for three classes (packet, netif, node).
        // A partial set still breaks DNS or aborts netd, so guard the full list.
        val text = script()
        for (rule in expectedRules) {
            assertTrue("missing rule: $rule", text.contains(rule))
        }
    }

    @Test
    fun `does not use brace groups that ksud parses as one token`() {
        val text = script()
        assertFalse(text.contains("{"))
        assertFalse(text.contains("}"))
    }

    @Test
    fun `signals success and gives up on the first failure`() {
        val text = script()
        assertTrue(text.contains("RMP_NETFIX_OK"))
        assertTrue(text.contains("RMP_NETFIX_FAIL:"))
    }

    @Test
    fun `is not a persistent on-disk patch`() {
        val text = script()
        assertFalse(text.contains("sepolicy load"))
        assertFalse(text.contains("> /sys/fs/selinux/load"))
    }
}
