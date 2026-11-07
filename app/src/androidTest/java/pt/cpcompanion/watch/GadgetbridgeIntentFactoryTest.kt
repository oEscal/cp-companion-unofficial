package pt.cpcompanion.watch

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GadgetbridgeIntentFactoryTest {
    @Test
    fun createsTargetedWatchMessageIntentWithDocumentedExtras() {
        val intent = GadgetbridgeIntentFactory.create(
            SmartBandSettings(enabled = true, deviceAddress = "aa:bb:cc:dd:ee:ff"),
            "{\"version\":1}",
        )

        requireNotNull(intent)
        assertEquals(GadgetbridgeIntentFactory.ACTION_SEND_WATCH_APP_MESSAGE, intent.action)
        assertEquals(GadgetbridgeIntentFactory.GADGETBRIDGE_PACKAGE, intent.`package`)
        assertEquals("AA:BB:CC:DD:EE:FF", intent.getStringExtra("device"))
        assertEquals(GadgetbridgeIntentFactory.WATCH_APP_ID, intent.getStringExtra("appId"))
        assertEquals("{\"version\":1}", intent.getStringExtra("payload"))
    }

    @Test
    fun incompleteConfigurationDoesNotCreateAnIntent() {
        assertNull(
            GadgetbridgeIntentFactory.create(
                SmartBandSettings(enabled = false, deviceAddress = "AA:BB:CC:DD:EE:FF"),
                "{}",
            ),
        )
        assertNull(
            GadgetbridgeIntentFactory.create(
                SmartBandSettings(enabled = true, deviceAddress = "not-a-mac"),
                "{}",
            ),
        )
    }
}
