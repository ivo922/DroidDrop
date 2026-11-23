package dev.ivo.droiddrop

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RetryPolicyTest {
    @Test
    fun keepsOnlyWhenVisibleAndPaired() {
        assertTrue(RetryPolicy.keep(notificationsEnabled = true, channelEnabled = true, paired = true))
        assertFalse(RetryPolicy.keep(notificationsEnabled = false, channelEnabled = true, paired = true))
        assertFalse(RetryPolicy.keep(notificationsEnabled = true, channelEnabled = false, paired = true))
        assertFalse(RetryPolicy.keep(notificationsEnabled = true, channelEnabled = true, paired = false))
    }
}
