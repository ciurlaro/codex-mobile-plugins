package io.github.ciurlaro.codexmobile.platform.android

import androidx.test.platform.app.InstrumentationRegistry
import io.github.ciurlaro.codexmobile.agent.codex.ProviderSecrets
import io.github.ciurlaro.codexmobile.providers.telegram.TELEGRAM_API_HASH_SECRET
import io.github.ciurlaro.codexmobile.providers.telegram.TELEGRAM_API_ID_SECRET
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class TelegramCredentialsDeviceTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun providerDeclaresScopedCredentialsAndFailsClosedWhenTheyAreMissing() {
        val provider = TelegramProvider(context)
        assertEquals(
            listOf(TELEGRAM_API_ID_SECRET, TELEGRAM_API_HASH_SECRET),
            provider.descriptor.secrets.map { it.name },
        )

        val integration = TelegramIntegration(context, TelegramCredentials.from(ProviderSecrets.EMPTY))
        assertFalse(integration.status().available)
        assertThrows(IllegalStateException::class.java) {
            integration.startAuthentication("+41790000000")
        }
    }
}
