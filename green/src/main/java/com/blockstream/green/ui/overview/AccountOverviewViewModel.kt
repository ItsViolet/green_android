package com.blockstream.green.ui.overview


import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.blockstream.common.data.CredentialType
import com.blockstream.common.data.EnrichedAsset
import com.blockstream.common.data.GreenWallet
import com.blockstream.common.extensions.ifConnected
import com.blockstream.common.extensions.lightningMnemonic
import com.blockstream.common.gdk.data.Account
import com.blockstream.common.gdk.data.AccountAsset
import com.blockstream.common.gdk.data.Transaction
import com.blockstream.common.lightning.fromSwapInfo
import com.blockstream.common.sideeffects.SideEffects
import com.blockstream.green.ui.items.AlertType
import com.blockstream.green.ui.wallet.AbstractAccountWalletViewModel
import com.rickclephas.kmm.viewmodel.coroutineScope
import com.rickclephas.kmm.viewmodel.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import org.koin.android.annotation.KoinViewModel
import org.koin.core.annotation.InjectedParam

@KoinViewModel
class AccountOverviewViewModel constructor(
    @InjectedParam initWallet: GreenWallet,
    @InjectedParam account: Account,
) : AbstractAccountWalletViewModel(initWallet, AccountAsset.fromAccount(account)) {
    val isWatchOnly: LiveData<Boolean> = MutableLiveData(wallet.isWatchOnly)

    private val _twoFactorStateLiveData: MutableLiveData<List<AlertType>> = MutableLiveData()
    val twoFactorStateLiveData: LiveData<List<AlertType>> get() = _twoFactorStateLiveData

    private val _transactionsLiveData: MutableLiveData<List<Transaction>> =
        MutableLiveData(listOf(Transaction.LoadingTransaction))
    val transactionsLiveData: LiveData<List<Transaction>> get() = _transactionsLiveData

    private val _transactionsPagerLiveData: MutableLiveData<Boolean?> = MutableLiveData(null)
    val transactionsPagerLiveData: LiveData<Boolean?> get() = _transactionsPagerLiveData
    val transactionsPager: Boolean? get() = _transactionsPagerLiveData.value

    private val _assetsLiveData: MutableLiveData<Map<EnrichedAsset, Long>> = MutableLiveData(mapOf())
    val assetsLiveData: LiveData<Map<EnrichedAsset, Long>> get() = _assetsLiveData
    val assets: Map<EnrichedAsset, Long> get() = _assetsLiveData.value!!

    val policyAsset: Long get() = session.ifConnected { session.accountAssets(account).value.policyAsset } ?: 0L

    private val _swapInfoStateFlow
        get() = session.takeIf { account.isLightning }?.ifConnected {
            session.lightningSdkOrNull?.swapInfoStateFlow
        } ?: flowOf(listOf())

    val lightningShortcut = if(wallet.isEphemeral) {
        emptyFlow<Boolean?>()
    } else {
        database.getLoginCredentialsFlow(wallet.id).map {
            it.lightningMnemonic != null
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        bootstrap()

        if(session.isConnected) {
            session
                .accountAssets(account)
                .map {
                    it.assets.takeIf { account.isLiquid && it.size > 1 } ?: mapOf()
                }
                .onEach { assets ->
                    _assetsLiveData.value = assets.mapKeys {
                        EnrichedAsset.create(session = session, assetId = it.key)
                    }
                }.launchIn(viewModelScope.coroutineScope)

            combine(
                session.accountTransactions(account),
                _swapInfoStateFlow
            ) { transactions, swaps ->
                swaps.map {
                    Transaction.fromSwapInfo(account, it.first, it.second)
                } + transactions
            }.onEach {
                _transactionsLiveData.value = it
            }.launchIn(viewModelScope.coroutineScope)

            session.accountTransactionsPager(account).onEach {
                _transactionsPagerLiveData.value = it
            }.launchIn(viewModelScope.coroutineScope)

            session.twoFactorReset(network).onEach {
                _twoFactorStateLiveData.postValue(
                    listOfNotNull(
                        if (it != null && it.isActive == true) {
                            if (it.isDisputed == true) {
                                AlertType.Dispute2FA(network, it)
                            } else {
                                AlertType.Reset2FA(network, it)
                            }
                        } else {
                            null
                        }
                    )
                )
            }.launchIn(viewModelScope.coroutineScope)

            session.getTransactions(account = account, isReset = true, isLoadMore = false)
        }
    }

    fun refresh() {
        session.refresh(account)
    }

    fun loadMoreTransactions() {
        logger.info { "loadMoreTransactions" }
        session.getTransactions(account = account, isReset = false, isLoadMore = true)
    }

    fun archiveAccount() {
        super.updateAccountVisibility(account, true){
            postSideEffect(SideEffects.Navigate(WalletOverviewFragment.ACCOUNT_ARCHIVED))
        }
    }

    fun removeAccount() {
        super.removeAccount(account){
            postSideEffect(SideEffects.NavigateToRoot)
        }
    }

    fun removeLightningShortcut() {
        doUserAction({
            database.deleteLoginCredentials(wallet.id, CredentialType.LIGHTNING_MNEMONIC)
        }, onSuccess = {

        })
    }

    fun enableLightningShortcut() {
        doAsync({
            _enableLightningShortcut()
        }, onSuccess = {

        })
    }
}