package org.sonora.android.auth

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The active YouTube Music identity, for code without a [Context] (the API clients). Null browses as
 * a guest, as LFly always did.
 */
object YouTubeAuth {
    @Volatile
    var active: YouTubeAccount? = null
        internal set

    private val _expired = MutableStateFlow(false)

    /** True once Google refused the saved session: the listener has to sign in again. */
    val expired: StateFlow<Boolean> = _expired.asStateFlow()

    /** Called by a client that got a 401 for a signed request. */
    fun markExpired() {
        if (active != null) _expired.value = true
    }

    internal fun clearExpired() {
        _expired.value = false
    }
}

/**
 * The YouTube Music accounts the listener signed in with, encrypted at rest ([SecurePrefs]) and
 * never backed up. Several Google accounts and their Brand channels can be saved; one is active.
 */
class GoogleAccountStore private constructor(context: Context) {
    companion object {
        private const val TAG = "GoogleAccounts"
        private const val FILE = "lfly_credentials"
        private const val KEY_STATE = "youtube_accounts"

        @Volatile
        private var instance: GoogleAccountStore? = null

        fun getInstance(context: Context): GoogleAccountStore =
            instance ?: synchronized(this) {
                instance ?: GoogleAccountStore(context.applicationContext).also { instance = it }
            }
    }

    private val prefs = SecurePrefs(context, FILE)
    private val gson = Gson()

    private val _state = MutableStateFlow(load())
    val state: StateFlow<AccountsState> = _state.asStateFlow()

    init {
        YouTubeAuth.active = _state.value.active
    }

    /** Saves a signed-in identity and acts as it. */
    fun add(account: YouTubeAccount) = update { it.upsert(account, activate = true) }

    /** Updates what is known about a saved identity (its name and avatar, once fetched). */
    fun refresh(account: YouTubeAccount) = update { state ->
        if (state.accounts.none { it.id == account.id }) state else state.upsert(account, activate = state.activeId == account.id)
    }

    fun select(id: String) = update { it.select(id) }

    /** Signs out of one identity; the browser forgets its Google login too. */
    fun remove(id: String) {
        update { it.remove(id) }
        BrowserSession.clearGoogleCookies()
    }

    private fun update(change: (AccountsState) -> AccountsState) {
        val next = synchronized(this) {
            val updated = change(_state.value)
            _state.value = updated
            updated
        }
        YouTubeAuth.active = next.active
        YouTubeAuth.clearExpired()
        runCatching { prefs.putString(KEY_STATE, gson.toJson(next)) }
            .onFailure { Log.w(TAG, "Could not save the accounts", it) }
    }

    private fun load(): AccountsState = runCatching {
        val raw = prefs.getString(KEY_STATE) ?: return AccountsState()
        val saved = gson.fromJson(raw, AccountsState::class.java) ?: return AccountsState()
        // Gson leaves missing fields null whatever Kotlin says; keep only complete entries.
        @Suppress("SENSELESS_COMPARISON")
        val accounts = saved.accounts.orEmpty().filter { it != null && it.id != null && it.cookie != null && GoogleSession.hasApiSid(it.cookie) }
        AccountsState(accounts, saved.activeId)
    }.onFailure { Log.w(TAG, "Dropping unreadable accounts", it) }.getOrDefault(AccountsState())
}
