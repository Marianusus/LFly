package org.sonora.android.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleSessionTest {

    private val signedIn = "VISITOR_INFO1_LIVE=v1; HSID=h; SAPISID=ABCdef123/xyz; __Secure-3PAPISID=other; LOGIN_INFO=l"

    @Test
    fun `the signature is Google's SAPISIDHASH of time, secret and origin`() {
        // sha1("1700000000 ABCdef123/xyz https://music.youtube.com"), computed independently.
        assertEquals(
            "SAPISIDHASH 1700000000_93d8a6e04cb6a97148cf5ba01ab32444c702b3ec",
            GoogleSession.sapisidHash("ABCdef123/xyz", nowSeconds = 1_700_000_000L)
        )
    }

    @Test
    fun `the secret is read from the cookie, plain SAPISID first`() {
        assertEquals("ABCdef123/xyz", GoogleSession.sapisidFrom(signedIn))
        assertEquals("only3p", GoogleSession.sapisidFrom("a=b; __Secure-3PAPISID=only3p"))
        assertTrue(GoogleSession.hasApiSid(signedIn))
        assertFalse(GoogleSession.hasApiSid("VISITOR_INFO1_LIVE=v1; YSC=y"))
        assertFalse(GoogleSession.hasApiSid("SAPISID=; YSC=y"))
    }

    @Test
    fun `a delegated DATASYNC_ID gives its active half`() {
        assertEquals("brand", GoogleSession.normalizeDataSyncId("account||brand"))
        assertEquals("account", GoogleSession.normalizeDataSyncId("account||"))
        assertEquals("plain", GoogleSession.normalizeDataSyncId("plain"))
        assertNull(GoogleSession.normalizeDataSyncId(" "))
        assertNull(GoogleSession.normalizeDataSyncId(null))
    }

    @Test
    fun `an identity keeps its id across logins, a Brand channel has its own`() {
        val first = GoogleSession.accountId("SAPISID=one", dataSyncId = "me", pageId = null)
        val again = GoogleSession.accountId("SAPISID=two", dataSyncId = "me", pageId = null)
        val brand = GoogleSession.accountId("SAPISID=two", dataSyncId = "brand", pageId = "brand")
        assertEquals(first, again)
        assertNotEquals(first, brand)
    }

    private fun account(id: String, name: String = "", cookie: String = "SAPISID=$id") =
        YouTubeAccount(id = id, cookie = cookie, name = name, addedAt = 1L)

    @Test
    fun `adding, choosing and removing accounts`() {
        var state = AccountsState()
        state = state.upsert(account("a", "Marian"))
        state = state.upsert(account("b", "Brand"))
        assertEquals("b", state.active?.id)

        state = state.select("a")
        assertEquals("a", state.active?.id)
        assertEquals(state, state.select("missing"))

        // Signing in again as the same identity renews its cookie and keeps its name.
        state = state.upsert(account("a", cookie = "SAPISID=fresh"))
        assertEquals(2, state.accounts.size)
        assertEquals("Marian", state.active?.name)
        assertEquals("SAPISID=fresh", state.active?.cookie)

        state = state.remove("a")
        assertEquals("b", state.active?.id)
        state = state.remove("b")
        assertNull(state.active)
        assertTrue(state.accounts.isEmpty())
    }

    @Test
    fun `a captured session becomes an account`() {
        val captured = CapturedSession(
            cookie = signedIn, pageId = "UCbrand", dataSyncId = "UCbrand", authUser = "1",
            visitorData = "Cg", clientVersion = "1.2"
        )
        val account = YouTubeAccount.from(captured, now = 5L)
        assertTrue(account.isBrandChannel)
        assertEquals("1", account.authUser)
        assertEquals(GoogleSession.accountId(signedIn, "UCbrand", "UCbrand"), account.id)
    }
}
