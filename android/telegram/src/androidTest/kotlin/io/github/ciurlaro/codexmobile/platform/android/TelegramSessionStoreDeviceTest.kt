package io.github.ciurlaro.codexmobile.platform.android

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramSessionStoreDeviceTest {
    @Test
    fun onlyAnActivatedVerifiedSessionGrantsAuthority() {
        withStore { root, store ->
            File(root, "telegram/old-session").apply { parentFile?.mkdirs(); writeText("old") }
            assertNull(store.authority())

            val (authority, staged) = store.createStaging()
            File(staged, "state").writeText("staged")
            assertNull(store.authority())

            store.promote(authority, staged)
            assertNull(store.authority())
            store.activate(authority)

            assertAuthorityEquals(authority, store.authority())
            assertTrue(store.sessionDirectory(authority).isDirectory)
            store.cleanup(authority)
            assertFalse(File(root, "telegram").exists())
            assertTrue(store.remoteRevocationUnconfirmed())
        }
    }

    @Test
    fun corruptAuthorityFailsClosedAndDisconnectClearsAllState() {
        withStore { root, store ->
            File(root, "telegram-client/authority.json").writeText("not-json")
            assertThrows(IllegalStateException::class.java) { store.authority() }
            File(root, "telegram/old-session").apply { parentFile?.mkdirs(); writeText("old") }

            assertTrue(store.clearAll())
            assertNull(store.authority())
            assertFalse(File(root, "telegram").exists())
        }
    }

    @Test
    fun authorityWithoutItsSessionFailsClosed() {
        withStore { _, store ->
            val (authority, staged) = store.createStaging()
            File(staged, "state").writeText("verified")
            store.promote(authority, staged)
            store.activate(authority)
            assertTrue(store.sessionDirectory(authority).deleteRecursively())

            assertAuthorityEquals(authority, store.authority())
            assertThrows(IllegalArgumentException::class.java) { store.sessionDirectory(authority) }
        }
    }

    @Test
    fun activationSurvivesRestartAndCleanupCannotChangeAuthority() {
        withStore { root, store ->
            File(root, "telegram/old-session").apply { parentFile?.mkdirs(); writeText("old") }
            val (authority, staged) = store.createStaging()
            File(staged, "state").writeText("verified")
            store.promote(authority, staged)
            store.activate(authority)

            val restarted = TelegramSessionStore(storeContext(root))
            assertAuthorityEquals(authority, restarted.authority())
            assertTrue(File(root, "telegram").exists())
            restarted.cleanup(authority)
            assertAuthorityEquals(authority, restarted.authority())
            assertFalse(File(root, "telegram").exists())
            assertTrue(restarted.remoteRevocationUnconfirmed())
        }
    }

    @Test
    fun remoteRevocationWarningSurvivesDisconnectCleanupUntilUninstall() {
        withStore { _, store ->
            store.rememberUnconfirmedRemoteAuthorization()
            assertTrue(store.clearAll(preserveRevocationWarning = true))
            assertTrue(store.remoteRevocationUnconfirmed())

            assertTrue(store.clearAll())
            assertFalse(store.remoteRevocationUnconfirmed())
        }
    }

    private fun withStore(block: (File, TelegramSessionStore) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "telegram-store-${System.nanoTime()}")
        try {
            block(root, TelegramSessionStore(storeContext(root)))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun assertAuthorityEquals(expected: TelegramAuthority, actual: TelegramAuthority?) {
        assertEquals(expected.sessionId, actual?.sessionId)
        assertTrue(expected.encryptionKey.contentEquals(checkNotNull(actual).encryptionKey))
    }

    private fun storeContext(root: File) = object : ContextWrapper(
        InstrumentationRegistry.getInstrumentation().targetContext,
    ) {
        override fun getNoBackupFilesDir(): File = root
    }
}
