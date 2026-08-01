package pt.cpcompanion.sms

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsNotificationTrustTest {
    @Test
    fun acceptsOnlyTheSelectedDefaultSmsPackage() {
        assertTrue(
            isTrustedSmsNotificationPackage(
                packageName = "com.example.sms",
                defaultSmsPackage = "com.example.sms",
            ),
        )
        assertFalse(
            isTrustedSmsNotificationPackage(
                packageName = "com.example.attacker",
                defaultSmsPackage = "com.example.sms",
            ),
        )
    }

    @Test
    fun rejectsWhenNoDefaultSmsPackageIsAvailable() {
        assertFalse(isTrustedSmsNotificationPackage("com.example.sms", null))
        assertFalse(isTrustedSmsNotificationPackage("com.example.sms", ""))
    }
}
