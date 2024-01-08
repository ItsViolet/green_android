package com.blockstream.common.models

import co.touchlab.kermit.Logger
import com.blockstream.common.CountlyBase
import com.blockstream.common.ViewModelView
import com.blockstream.common.crypto.GreenKeystore
import com.blockstream.common.data.AppInfo
import com.blockstream.common.data.Banner
import com.blockstream.common.data.CredentialType
import com.blockstream.common.data.DenominatedValue
import com.blockstream.common.data.ErrorReport
import com.blockstream.common.data.GreenWallet
import com.blockstream.common.data.LogoutReason
import com.blockstream.common.data.Redact
import com.blockstream.common.database.Database
import com.blockstream.common.di.ApplicationScope
import com.blockstream.common.events.Event
import com.blockstream.common.events.EventWithSideEffect
import com.blockstream.common.events.Events
import com.blockstream.common.extensions.cleanup
import com.blockstream.common.extensions.createLoginCredentials
import com.blockstream.common.extensions.isNotBlank
import com.blockstream.common.extensions.logException
import com.blockstream.common.gdk.GdkSession
import com.blockstream.common.gdk.data.Account
import com.blockstream.common.gdk.data.AccountAsset
import com.blockstream.common.gdk.device.DeviceBrand
import com.blockstream.common.gdk.device.GdkHardwareWallet
import com.blockstream.common.gdk.device.HardwareWalletInteraction
import com.blockstream.common.managers.SessionManager
import com.blockstream.common.managers.SettingsManager
import com.blockstream.common.sideeffects.SideEffect
import com.blockstream.common.sideeffects.SideEffects
import com.blockstream.common.utils.Loggable
import com.rickclephas.kmm.viewmodel.KMMViewModel
import com.rickclephas.kmm.viewmodel.MutableStateFlow
import com.rickclephas.kmm.viewmodel.coroutineScope
import com.rickclephas.kmp.nativecoroutines.NativeCoroutines
import com.rickclephas.kmp.nativecoroutines.NativeCoroutinesState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.native.ObjCName


abstract class GreenViewModel constructor(
    val greenWalletOrNull: GreenWallet? = null,
    accountAssetOrNull: AccountAsset? = null,
) : KMMViewModel(), KoinComponent, ViewModelView, HardwareWalletInteraction {
    protected val appInfo: AppInfo by inject()
    protected val database: Database by inject()
    protected val countly: CountlyBase by inject()
    protected val sessionManager: SessionManager by inject()
    protected val settingsManager: SettingsManager by inject()
    protected val applicationScope: ApplicationScope by inject()
    protected val greenKeystore: GreenKeystore by inject()

    private val isPreview by lazy { this::class.simpleName?.contains("Preview") == true }

    private val _event: MutableSharedFlow<Event> = MutableSharedFlow()
    private val _sideEffect: Channel<SideEffect> = Channel()

    @NativeCoroutines
    val sideEffect = _sideEffect.receiveAsFlow()

    @NativeCoroutinesState
    val onProgress = MutableStateFlow(viewModelScope, false)

    @NativeCoroutinesState
    val onProgressDescription = MutableStateFlow<String?>(viewModelScope, null)

    // Main action validation
    internal val _isValid = MutableStateFlow(viewModelScope, isPreview)
    //@NativeCoroutinesState
    //val isValid = _isValid.asStateFlow()

    // Main button enabled flag
    private val _buttonEnabled = MutableStateFlow(viewModelScope, isPreview)
    @NativeCoroutinesState
    val buttonEnabled = _buttonEnabled.asStateFlow()

    override fun screenName(): String? = null
    override fun segmentation(): HashMap<String, Any>? = null

    private var _greenWallet: GreenWallet? = null
    val greenWallet: GreenWallet
        get() = _greenWallet ?: greenWalletOrNull!!

    @NativeCoroutines
    val greenWalletFlow: Flow<GreenWallet> by lazy {
        if (greenWallet.isEphemeral) {
            flowOf(greenWallet)
        } else {
            database.getWalletFlow(greenWallet.id).onEach {
                _greenWallet = it
            }
        }
    }

    @NativeCoroutinesState
    val accountAsset: MutableStateFlow<AccountAsset?> = MutableStateFlow(accountAssetOrNull)

    val sessionOrNull: GdkSession? by lazy {
        greenWalletOrNull?.let { sessionManager.getWalletSessionOrNull(it) }
    }

    val session: GdkSession by lazy {
        if (greenWalletOrNull == null) {
            // If GreenWallet is null, we can create an onboarding session
            sessionManager.getWalletSessionOrOnboarding(greenWalletOrNull)
        } else {
            sessionManager.getWalletSessionOrCreate(greenWalletOrNull)
        }
    }

    @NativeCoroutines
    val banner: MutableStateFlow<Banner?> = MutableStateFlow(viewModelScope, null)
    val closedBanners = mutableListOf<Banner>()

    private var _deviceRequest: CompletableDeferred<String>? = null
    private var _bootstrapped: Boolean = false

    open val isLoginRequired: Boolean = greenWalletOrNull != null

    init {
        // It's better to initiate the ViewModel with a bootstrap() call
        // https://kotlinlang.org/docs/inheritance.html#derived-class-initialization-order
    }

    protected fun bootstrap(){
        _bootstrapped = true
        if (greenWalletOrNull != null) {
            if (isLoginRequired) {
                session.isConnectedState.onEach { isConnected ->
                    if (!isConnected) {
                        (session.logoutReason ?: LogoutReason.USER_ACTION).also {
                            logoutSideEffect(it)
                        }
                    }
                }.launchIn(viewModelScope.coroutineScope)
            }
        }

        _event.onEach {
            handleEvent(it)
        }.launchIn(viewModelScope.coroutineScope)

        countly.viewModel(this)

        // If session is connected, listen for network events
        if(sessionOrNull?.isConnected == true){
            listenForNetworksEvents()
        }

        combine(_isValid, onProgress) { isValid, onProgress ->
            isValid && !onProgress
        }.onEach {
            _buttonEnabled.value = it
        }.launchIn(viewModelScope.coroutineScope)

        initBanner()
    }

    @ObjCName(name = "post", swiftName = "postEvent")
    fun postEvent(@ObjCName(swiftName = "_") event: Event) {
        if(!_bootstrapped){

            if(this::class.simpleName?.contains("Preview") == true){
                logger.i { "postEvent() Preview ViewModel detected"}
                return
            }
            throw RuntimeException("ViewModel wasn't bootstrapped")
        }

        if(event is Redact){
            if(appInfo.isDevelopmentOrDebug){
                Logger.d { "postEvent: Redacted(${event::class.simpleName}) Debug: $event" }
            }else{
                Logger.d { "postEvent: Redacted(${event::class.simpleName})" }
            }
        }else{
            Logger.d { "postEvent: $event" }
        }

        viewModelScope.coroutineScope.launch { _event.emit(event) }
    }

    protected fun postSideEffect(sideEffect: SideEffect) {
        if (sideEffect is Redact) {
            if(appInfo.isDevelopmentOrDebug){
                Logger.d { "postSideEffect: Redacted(${sideEffect::class.simpleName}) Debug: $sideEffect" }
            }else{
                Logger.d { "postSideEffect: Redacted(${sideEffect::class.simpleName})" }
            }
        } else {
            Logger.d { "postSideEffect: $sideEffect" }
        }
        viewModelScope.coroutineScope.launch { _sideEffect.send(sideEffect) }
    }

    private fun listenForNetworksEvents(){
        session.networkErrors.onEach {
            postSideEffect(SideEffects.ErrorDialog(Exception("id_your_personal_electrum_server_for_s|${it.first.canonicalName}")))
        }.launchIn(viewModelScope.coroutineScope)
    }

    private fun initBanner() {
        countly.remoteConfigUpdateEvent.onEach {
            val oldBanner = banner.value
            countly.getRemoteConfigValueForBanners()
                // Filter
                ?.filter {
                    // Filter closed banners
                    !closedBanners.contains(it) &&

                            // Filter networks
                            (!it.hasNetworks || ((it.networks ?: listOf()).intersect((sessionOrNull?.activeSessions?.map { it.network } ?: setOf()).toSet()).isNotEmpty()))  &&

                            // Filter based on screen name
                            (it.screens?.contains(screenName()) == true || it.screens?.contains("*") == true)
                }
                ?.shuffled()
                ?.let {
                    // Search for the already displayed banner, else give priority to those with screen name, else "*"
                    it.find { it == oldBanner } ?: it.find { it.screens?.contains(screenName()) == true } ?: it.firstOrNull()
                }.also {
                    // Set banner to ViewModel
                    banner.value = it
                }
        }.launchIn(viewModelScope.coroutineScope)
    }

    open fun handleEvent(event: Event) {
        when(event){
            is EventWithSideEffect -> {
                postSideEffect(event.sideEffect)
            }
            is Events.BannerDismiss -> {
                banner.value?.also {
                    closedBanners += it
                }
                banner.value = null
            }
            is Events.BannerAction -> {
                banner.value?.also { banner ->
                    banner.link?.takeIf { it.isNotBlank() }?.also { url ->
                        postSideEffect(SideEffects.OpenBrowser(url))
                    }
                }
            }
            is Events.DeleteWallet -> {
                doAsync(action = {
                    sessionManager.destroyWalletSession(event.wallet)
                    database.deleteWallet(event.wallet.id)
                }, onSuccess = {
                    countly.deleteWallet()
                    postSideEffect(SideEffects.WalletDelete)
                })
            }
            is Events.RenameWallet -> {
                doAsync(action = {
                    event.name.cleanup().takeIf { it.isNotBlank() }?.also { name ->
                        event.wallet.name = name
                        database.updateWallet(event.wallet)
                    } ?: throw Exception("Name should not be blank")
                }, onSuccess = {
                    countly.renameWallet()
                })
            }
            is Events.DeviceRequestResponse -> {
                if(event.data == null){
                    _deviceRequest?.completeExceptionally(Exception("id_action_canceled"))
                }else{
                    _deviceRequest?.complete(event.data)
                }
            }
            is Events.SelectDenomination -> {
                viewModelScope.coroutineScope.launch {
                    denominatedValue()?.also {
                        postSideEffect(SideEffects.OpenDenominationDialog(it))
                    }
                }
            }
            is Events.SetDenomination -> {
                setDenomination(event.denominatedValue)
            }
            is Events.Logout -> {
                sessionOrNull?.disconnectAsync(event.reason)
                (sessionOrNull?.logoutReason ?: event.reason).also {
                    logoutSideEffect(it)
                }
            }
        }
    }

    private fun logoutSideEffect(reason: LogoutReason){
        postSideEffect(SideEffects.Logout(reason))
        when(reason){
            LogoutReason.CONNECTION_DISCONNECTED -> {
                postSideEffect(SideEffects.Snackbar("id_unstable_internet_connection"))
            }
            LogoutReason.AUTO_LOGOUT_TIMEOUT -> {
                postSideEffect(SideEffects.Snackbar("id_auto_logout_timeout_expired"))
            }
            LogoutReason.DEVICE_DISCONNECTED -> {
                postSideEffect(SideEffects.Snackbar("id_your_device_was_disconnected"))
            }
            else -> {}
        }
    }

    protected fun <T: Any?> doAsync(
        action: suspend () -> T,
        timeout: Long = 0,
        preAction: (() -> Unit)? = {
            onProgress.value = true
        },
        postAction: ((Exception?) -> Unit)? = {
            onProgress.value = false
        },
        onSuccess: (T) -> Unit,
        onError: ((Throwable) -> Unit) = {
            if (appInfo.isDebug) {
                it.printStackTrace()
            }
            postSideEffect(SideEffects.ErrorDialog(it, errorReport = errorReport(it)))
        }
    ): Job {
        return viewModelScope.coroutineScope.launch {
            try {
                preAction?.invoke()

                withContext(context = Dispatchers.IO) {
                    if(timeout <= 0L) {
                        action.invoke()
                    }else{
                        withTimeout(timeout) {
                            action.invoke()
                        }
                    }
                }.also {
                    if (this.isActive) {
                        postAction?.invoke(null)
                        onSuccess.invoke(it)
                    }
                }
            } catch (e: Exception) {
                if (this.isActive) {
                    countly.recordException(e)
                    postAction?.invoke(e)
                    onError.invoke(e)
                }
            }
        }
    }

    protected fun updateAccount(account: Account, isHidden: Boolean){
        doAsync({
            session.updateAccount(account = account, isHidden = isHidden)
        }, onSuccess = {
            if(isHidden){
                // Update active account from Session if it was archived
                session.activeAccount.value?.also {
                    setActiveAccount(it)
                }

                postSideEffect(SideEffects.AccountArchived(account = account))
            }else{
                // Make it active
                setActiveAccount(account)
            }
        })
    }

    private fun setActiveAccount(account: Account) {
        session.setActiveAccount(account)

        greenWallet.also {
            it.activeNetwork = account.networkId
            it.activeAccount = account.pointer

            if(!it.isEphemeral) {
                viewModelScope.coroutineScope.launch(context = logException(countly)){
                    database.updateWallet(it)
                }
            }
        }
    }

    protected suspend fun _enableLightningShortcut(lightningMnemonic: String? = null) {
        sessionOrNull?.also { session ->
            val encryptedData = (lightningMnemonic ?: session.deriveLightningMnemonic()).let {
                greenKeystore.encryptData(it.encodeToByteArray())
            }

            greenWalletOrNull?.also { wallet ->
                database.replaceLoginCredential(
                    createLoginCredentials(
                        walletId = wallet.id,
                        network = session.lightning!!.id,
                        credentialType = CredentialType.LIGHTNING_MNEMONIC,
                        encryptedData = encryptedData
                    )
                )
            }
        }
    }

    final override fun interactionRequest(
        hw: GdkHardwareWallet,
        completable: CompletableDeferred<Boolean>?,
        text: String?
    ) {
        postSideEffect(SideEffects.DeviceInteraction(hw.device, text, completable))
    }

    final override fun requestPassphrase(deviceBrand: DeviceBrand?): String {
        return CompletableDeferred<String>().let {
            _deviceRequest = it
            postSideEffect(SideEffects.DeviceRequestPassphrase)
            runBlocking { it.await() }
        }
    }

    final override fun requestPinMatrix(deviceBrand: DeviceBrand?): String? {
        return CompletableDeferred<String>().let {
            _deviceRequest = it
            postSideEffect(SideEffects.DeviceRequestPin)
            runBlocking { it.await() }
        }
    }

    protected open suspend fun denominatedValue(): DenominatedValue? = null
    protected open fun setDenomination(denominatedValue: DenominatedValue) { }
    protected open fun errorReport(exception: Throwable): ErrorReport? { return null}

    companion object: Loggable()
}