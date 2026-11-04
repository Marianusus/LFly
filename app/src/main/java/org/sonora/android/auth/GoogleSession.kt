/*
 * Adapted from BitChord (https://github.com/kushagrasinghx/BitChord, GPL-3.0):
 * auth/WebSession.kt, auth/AccountSessions.kt, auth/DataSyncId.kt and the
 * SAPISIDHASH signing in data/innertube/Innertube.kt.
 */
package org.sonora.android.auth

import java.security.MessageDigest
import java.util.Locale

/** What the in-app browser is opened for. */
enum class WebSessionMode {
    /** No usable session yet: sign in to Google. */
    SIGN_IN,

    /** Signed in, on another channel: YouTube Music's own account switcher is used, then the page is read. */
    SWITCH_CHANNEL
}

/**
 * A session lifted out of the in-app browser: its cookies, and who the page in front of the listener
 * says it is, from the page's own `ytcfg`. The password never passes through the app.
 */
data class CapturedSession(
    val cookie: String,
    /** `DELEGATED_SESSION_ID`: set only while a Brand channel is selected. */
    val pageId: String?,
    /** The active identity, sent as `context.user.onBehalfOfUser`. */
    val dataSyncId: String?,
    /** `SESSION_INDEX`: which Google account of the cookie jar. */
    val authUser: String?,
    val visitorData: String?,
    val clientVersion: String?
)

/** One signed-in YouTube Music identity: a Google account, or one of its Brand channels. */
data class YouTubeAccount(
    val id: String,
    val cookie: String,
    val dataSyncId: String? = null,
    val pageId: String? = null,
    val authUser: String? = null,
    val visitorData: String? = null,
    val clientVersion: String? = null,
    val name: String = "",
    val handle: String = "",
    val email: String = "",
    val avatarUrl: String? = null,
    val addedAt: Long = 0L
) {
    val isBrandChannel: Boolean get() = pageId != null

    companion object {
        fun from(session: CapturedSession, now: Long = System.currentTimeMillis()): YouTubeAccount = YouTubeAccount(
            id = GoogleSession.accountId(session.cookie, session.dataSyncId, session.pageId),
            cookie = session.cookie,
            dataSyncId = session.dataSyncId,
            pageId = session.pageId,
            authUser = session.authUser,
            visitorData = session.visitorData,
            clientVersion = session.clientVersion,
            addedAt = now
        )
    }
}

/** The saved accounts and the one LFly acts as. */
data class AccountsState(
    val accounts: List<YouTubeAccount> = emptyList(),
    val activeId: String? = null
) {
    val active: YouTubeAccount? get() = accounts.firstOrNull { it.id == activeId } ?: accounts.firstOrNull()

    /** Adds [account] or replaces the saved one with its id (a fresh login, a renewed cookie). */
    fun upsert(account: YouTubeAccount, activate: Boolean = true): AccountsState {
        val index = accounts.indexOfFirst { it.id == account.id }
        val next = if (index >= 0) {
            // A new capture of the same identity keeps what was learned about it before.
            val old = accounts[index]
            accounts.toMutableList().also {
                it[index] = account.copy(
                    name = account.name.ifBlank { old.name },
                    handle = account.handle.ifBlank { old.handle },
                    email = account.email.ifBlank { old.email },
                    avatarUrl = account.avatarUrl ?: old.avatarUrl,
                    addedAt = old.addedAt.takeIf { t -> t > 0 } ?: account.addedAt
                )
            }
        } else {
            accounts + account
        }
        return copy(accounts = next, activeId = if (activate) account.id else activeId ?: account.id)
    }

    fun select(id: String): AccountsState = if (accounts.any { it.id == id }) copy(activeId = id) else this

    /** Forgets [id]; the next account, if any, becomes the active one. */
    fun remove(id: String): AccountsState {
        val remaining = accounts.filterNot { it.id == id }
        val active = if (activeId == id) remaining.firstOrNull()?.id else activeId
        return AccountsState(remaining, active)
    }
}

/** Pure helpers for Google's web session. */
object GoogleSession {
    const val MUSIC_ORIGIN = "https://music.youtube.com"

    /** The cookies that sign a request, most specific first. */
    private val SAPISID_NAMES = listOf("SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID")

    /** The cookies of a `Cookie` header, by name. */
    fun cookies(header: String): Map<String, String> = header.split(';')
        .mapNotNull { entry ->
            val name = entry.substringBefore('=').trim()
            val value = entry.substringAfter('=', "").trim()
            if (name.isEmpty() || value.isEmpty()) null else name to value
        }
        .toMap()

    /** The secret requests are signed with; null when the jar holds no login yet. */
    fun sapisidFrom(cookieHeader: String): String? {
        val jar = cookies(cookieHeader)
        return SAPISID_NAMES.firstNotNullOfOrNull { jar[it] }
    }

    /** True when the cookies can sign a request: the login finished. */
    fun hasApiSid(cookieHeader: String): Boolean = sapisidFrom(cookieHeader) != null

    /**
     * Google's web signature, `SAPISIDHASH <ts>_sha1("<ts> <SAPISID> <origin>")`. It is made per
     * request from the cookie, so no long-lived token is ever minted or stored.
     */
    fun sapisidHash(sapisid: String, origin: String = MUSIC_ORIGIN, nowSeconds: Long = System.currentTimeMillis() / 1_000): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest("$nowSeconds $sapisid $origin".toByteArray())
            .joinToString("") { "%02x".format(Locale.ROOT, it) }
        return "SAPISIDHASH ${nowSeconds}_$digest"
    }

    /**
     * `DATASYNC_ID` as `context.user.onBehalfOfUser` takes it. YouTube often gives it as
     * `account||delegated`: the second half is the active identity, and a plain account leaves it empty.
     */
    fun normalizeDataSyncId(raw: String?): String? {
        val value = raw?.takeIf { it.isNotBlank() } ?: return null
        if (!value.contains("||")) return value
        return value.substringAfter("||").takeIf { it.isNotBlank() }
            ?: value.substringBefore("||").takeIf { it.isNotBlank() }
    }

    /**
     * A stable id for an identity: its channel when it has one, its account otherwise, and as a last
     * resort a hash of the login (which changes with every sign-in).
     */
    fun accountId(cookie: String, dataSyncId: String?, pageId: String?): String {
        val key = pageId ?: dataSyncId ?: sapisidFrom(cookie) ?: cookie
        return MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
            .take(12).joinToString("") { "%02x".format(Locale.ROOT, it) }
    }
}
