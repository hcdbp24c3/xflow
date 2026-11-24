package com.alchemistaloha.xflow.data.client

import com.alchemistaloha.xflow.core.model.Account
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The logged-in account, for screens to observe (Flutter: `accountProvider`).
 * It starts from [TwitterAccount.currentAccount], so [TwitterAccount.init]
 * should have run before the first screen reads it; `init` also pushes the
 * account it loaded here, in case this object was touched earlier.
 */
object AccountStore {
    private val mutableState = MutableStateFlow(TwitterAccount.currentAccount)

    val state: StateFlow<Account?> = mutableState.asStateFlow()

    suspend fun logout() {
        TwitterAccount.logout()
        mutableState.value = null
    }

    fun login(account: Account) {
        TwitterAccount.setCurrentAccount(account)
        mutableState.value = account
    }

    /** Called by [TwitterAccount.init] after it loaded the stored account. */
    internal fun onAccountLoaded(account: Account) {
        mutableState.value = account
    }
}
