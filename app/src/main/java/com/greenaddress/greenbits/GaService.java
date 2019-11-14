package com.greenaddress.greenbits;

import android.app.Activity;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Binder;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.greenaddress.gdk.GDKSession;
import com.greenaddress.greenapi.ConnectionManager;
import com.greenaddress.greenapi.data.AssetInfoData;
import com.greenaddress.greenapi.data.BalanceData;
import com.greenaddress.greenapi.data.NetworkData;
import com.greenaddress.greenapi.data.PinData;
import com.greenaddress.greenapi.data.SettingsData;
import com.greenaddress.greenapi.data.SubaccountData;
import com.greenaddress.greenapi.model.AssetsDataObservable;
import com.greenaddress.greenapi.model.Model;
import com.greenaddress.greenapi.model.SettingsObservable;
import com.greenaddress.greenapi.model.TorProgressObservable;
import com.greenaddress.greenbits.spv.SPV;
import com.greenaddress.greenbits.ui.BuildConfig;
import com.greenaddress.greenbits.ui.R;
import com.greenaddress.greenbits.ui.UI;
import com.greenaddress.greenbits.ui.assets.RegistryErrorActivity;
import com.greenaddress.greenbits.ui.authentication.FirstScreenActivity;
import com.greenaddress.greenbits.ui.preferences.PrefKeys;

import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.PeerGroup;
import org.bitcoinj.core.Sha256Hash;
import org.h2.command.ddl.GrantRevoke;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Observable;
import java.util.Observer;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static com.greenaddress.gdk.GDKSession.getSession;

public class GaService extends Service  {
    private static final String TAG = GaService.class.getSimpleName();

    //private NetworkData mNetwork;
    //private Model mModel;
    //private ConnectionManager mConnectionManager;
    private final ListeningExecutorService mExecutor = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(8));

    private String mSignUpMnemonic;
    private Bitmap mSignUpQRCode;
    private boolean pinJustSaved = false;

    private final SPV mSPV = new SPV(this);

    private int mRefCount; // Number of non-paused activities using us
    private ScheduledThreadPoolExecutor mTimerExecutor = new ScheduledThreadPoolExecutor(1);
    private Long mDisconnectTimer;

    // This could be a local variable in theory but since there is a warning in the documentation
    // about possibly being garbage collected has been made a member of the class
    // https://developer.android.com/reference/android/content/SharedPreferences
    private SharedPreferences.OnSharedPreferenceChangeListener mSyncListener;

    class GaBinder extends Binder {
        GaService getService() { return GaService.this; }
    }
    private final IBinder mBinder = new GaBinder();

    @Override
    public IBinder onBind(final Intent intent) { return mBinder; }

    public void onBound(final GreenAddressApplication app) {
        // Update our state when network connectivity changes.
        final BroadcastReceiver netConnectivityReceiver = new BroadcastReceiver() {
            public void onReceive(final Context context, final Intent intent) {
                onNetConnectivityChanged();
            }
        };
        app.registerReceiver(netConnectivityReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
        // Fire a fake connectivity change to kick start the state machine
        netConnectivityReceiver.onReceive(null, null);
    }

    public ListeningExecutorService getExecutor() {
        return mExecutor;
    }

    public ScheduledThreadPoolExecutor getTimerExecutor() {
        return mTimerExecutor;
    }

    public String getReceivingId() {
        return getSubaccountData(0).getReceivingId();
    }

    public File getSPVChainFile(final String networkName) {
        final String dirName;
        if (getNetwork().IsNetworkMainnet()) {
            dirName = "blockstore_" + getReceivingId();
        } else {
            dirName = "blockstore_" + networkName;
        }

        Log.i(TAG, "dirName:" + dirName);
        return new File(getDir(dirName, Context.MODE_PRIVATE), "blockchain.spvchain");
    }

    public NetworkData getNetwork() {
        return ((GreenAddressApplication) getApplication()).getCurrentNetworkData();
    }
    public File getSPVChainFile() {
        return getSPVChainFile(getNetwork().getName());
    }

    public String getBitcoinOrLiquidUnit() {
        final int index = Math.max(UI.UNIT_KEYS_LIST.indexOf(getUnitKey()), 0);
        if (getNetwork().getLiquid()) {
            return UI.LIQUID_UNITS[index];
        } else {
            return UI.UNITS[index];
        }
    }

    public String getUnitKey() {
        final String unit = getGAApp().getModel().getSettings().getUnit();
        return toUnitKey(unit);
    }

    public static String toUnitKey(final String unit) {
        if (!Arrays.asList(UI.UNITS).contains(unit))
            return UI.UNITS[0].toLowerCase(Locale.US);
        return unit.equals("\u00B5BTC") ? "ubtc" : unit.toLowerCase(Locale.US);
    }

    public String getValueString(final long amount, final boolean asFiat, boolean withUnit) {
        try {
            return getValueString(getSession().convertSatoshi(amount), asFiat, withUnit);
        } catch (final RuntimeException | IOException e) {
            Log.e(TAG, "Conversion error: " + e.getLocalizedMessage());
            return "";
        }
    }
    public String getValueString(final ObjectNode amount, final boolean asFiat, boolean withUnit) {
        if (asFiat)
            return amount.get("fiat").asText() + (withUnit ? (" " + getFiatCurrency()) : "");
        return amount.get(getUnitKey()).asText() + (withUnit ? (" " + getBitcoinOrLiquidUnit()) : "");
    }

    public String getValueString(final long amount, final String asset, final AssetInfoData assetInfo, boolean withUnit) {
        try {
            final AssetInfoData assetInfoData = assetInfo != null ? assetInfo : new AssetInfoData(asset);
            final ObjectNode details = new ObjectMapper().createObjectNode();
            details.put("satoshi", amount);
            details.set("asset_info", assetInfoData.toObjectNode());
            final ObjectNode converted = getSession().convert(details);
            return converted.get(asset).asText() + (withUnit ? " " + assetInfoData.getTicker() : "");
        } catch (final RuntimeException | IOException e) {
            Log.e(TAG, "Conversion error: " + e.getLocalizedMessage());
            return "";
        }
    }

    public SharedPreferences cfg() {
        final String network = PreferenceManager.getDefaultSharedPreferences(this).getString(PrefKeys.NETWORK_ID_ACTIVE, "mainnet");
        return getSharedPreferences(network, MODE_PRIVATE);
    }

    public SharedPreferences.Editor cfgEdit() { return cfg().edit(); }

    public String getProxyHost() { return cfg().getString(PrefKeys.PROXY_HOST, ""); }
    public String getProxyPort() { return cfg().getString(PrefKeys.PROXY_PORT, ""); }
    public boolean getProxyEnabled() { return cfg().getBoolean(PrefKeys.PROXY_ENABLED, false); }

    public boolean getTorEnabled() { return cfg().getBoolean(PrefKeys.TOR_ENABLED, false); }
    public boolean isProxyEnabled() { return !TextUtils.isEmpty(getProxyHost()) && !TextUtils.isEmpty(getProxyPort()); }

    // SPV_SYNCRONIZATION
    public String getSPVTrustedPeers() { return mSPV.getTrustedPeers(); }
    public void setSPVTrustedPeersAsync(final String peers) { mSPV.setTrustedPeersAsync(peers); }

    public boolean isSPVEnabled() { return mSPV.isEnabled(); }
    public void setSPVEnabledAsync(final boolean enabled) { mSPV.setEnabledAsync(enabled); }

    public boolean isSPVSyncOnMobileEnabled() { return mSPV.isSyncOnMobileEnabled(); }
    public void setSPVSyncOnMobileEnabledAsync(final boolean enabled) { mSPV.setSyncOnMobileEnabledAsync(enabled); }

    public void resetSPVAsync() { mSPV.resetAsync(); }

    public PeerGroup getSPVPeerGroup() { return mSPV.getPeerGroup(); }
    public int getSPVHeight() { return mSPV.getSPVHeight(); }

    public boolean isSPVVerified(final Sha256Hash txHash) { return mSPV.isVerified(txHash); }

    public void enableSPVPingMonitoring() { mSPV.enablePingMonitoring(); }
    public void disableSPVPingMonitoring() { mSPV.disablePingMonitoring(); }

    @Override
    public void onCreate() {
        super.onCreate();

        if(GreenAddressApplication.isRunningTest())
            return;

        // Uncomment to test slow service creation
        // android.os.SystemClock.sleep(10000);
        final String network = PreferenceManager.getDefaultSharedPreferences(this).getString(PrefKeys.NETWORK_ID_ACTIVE, "mainnet");
        getGAApp().setConnectionManager(new ConnectionManager(network, getProxyHost(), getProxyPort(), getProxyEnabled(), getTorEnabled()));

        String deviceId = cfg().getString(PrefKeys.DEVICE_ID, null);
        if (deviceId == null) {
            // Generate a unique device id
            deviceId = UUID.randomUUID().toString();
            cfgEdit().putString(PrefKeys.DEVICE_ID, deviceId).apply();
        }

        mTimerExecutor.scheduleWithFixedDelay(this::checkDisconnect, 5,5, TimeUnit.SECONDS);
    }

    public GreenAddressApplication getGAApp() {
        return (GreenAddressApplication) getApplication();
    }
    public void onPostLogin() {
        // Uncomment to test slow login post processing
        // android.os.SystemClock.sleep(10000);
        Log.d(TAG, "Success LOGIN callback onPostLogin" );

        final Model model = new Model(mExecutor);
        getGAApp().setModel(model);
        initSettings();
        getSession().setNotificationModel(getGAApp().getModel(), getGAApp().getConnectionManager());
        final int activeAccount = getGAApp().getConnectionManager().isLoginWithPin() ? cfg().getInt(PrefKeys.ACTIVE_SUBACCOUNT, 0) : 0;
        if (model.getSubaccountDataObservable().getSubaccountDataWithPointer(activeAccount) != null)
            model.getActiveAccountObservable().setActiveAccount(activeAccount);
        else
            model.getActiveAccountObservable().setActiveAccount(0);

        // FIXME the following prevents an issue when notification are not transmitted even if login was successful
        if (model.getBlockchainHeightObservable().getHeight() == null) {
            return;
        }
        final NetworkData networkData = ((GreenAddressApplication) getApplication()).getCurrentNetworkData();
        if (networkData.getLiquid()) {
            model.getAssetsObservable().addObserver((observable, o) -> {
                AssetsDataObservable assetsDataObservable = (AssetsDataObservable) observable;

                if (assetsDataObservable.isAssetsLoaded() || assetsDataObservable.isShownErrorPopup()) {
                    return;
                }

                final Intent intent = new Intent();
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.setClass(this, RegistryErrorActivity.class);
                startActivity(intent);

                assetsDataObservable.setShownErrorPopup();
            });
            model.getAssetsObservable().refresh();
        }
        getGAApp().setModel(model);
        getGAApp().getConnectionManager().goPostLogin();

        if (!getGAApp().isWatchOnly()) {
            mSPV.startAsync();
        }
    }

    private void initSettings() {
        final Observer observer = new Observer() {
            @Override
            public void update(final Observable observable, final Object o) {
                if (observable instanceof SettingsObservable) {
                    Log.d(TAG,"initSettings");
                    final SettingsData settings = ((SettingsObservable) observable).getSettings();
                    final SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(GaService.this);
                    final SharedPreferences.Editor edit = pref.edit();
                    if (settings.getPricing() != null)
                        edit.putString(PrefKeys.PRICING, settings.getPricing().toString());
                    if (settings.getNotifications() != null)
                        edit.putBoolean(PrefKeys.TWO_FAC_N_LOCKTIME_EMAILS, settings.getNotifications().isEmailIncoming());
                    if (settings.getAltimeout() != null)
                        edit.putString(PrefKeys.ALTIMEOUT, String.valueOf(settings.getAltimeout()));
                    if (settings.getUnit() != null)
                        edit.putString(PrefKeys.UNIT, settings.getUnit());
                    if (settings.getRequiredNumBlocks() != null)
                        edit.putString(PrefKeys.REQUIRED_NUM_BLOCKS, String.valueOf(settings.getRequiredNumBlocks()));
                    if (settings.getPgp() != null)
                        edit.putString(PrefKeys.PGP_KEY, settings.getPgp());
                    edit.apply();
                    getGAApp().getModel().getSettingsObservable().deleteObserver(this);
                }
            }
        };
        getGAApp().getModel().getSettingsObservable().addObserver(observer);
    }

    public String getMnemonic() {
        return getSession().getMnemonicPassphrase();
    }

    public List<Long> getFeeEstimates() {
        return getGAApp().getModel().getFeeObservable().getFees();
    }

    public ListenableFuture<Void> setPin(final String mnemonic, final String pin, final SharedPreferences preferences) {
        return mExecutor.submit(() -> {
            final PinData pinData = getSession().setPin(mnemonic, pin, "default");
            AuthenticationHandler.setPin(pinData, pin.length() == 6, preferences);
            setPinJustSaved(true);
            return null;
        });
    }

    public boolean isPinJustSaved() {
        return pinJustSaved;
    }

    public void setPinJustSaved(boolean pinJustSaved) {
        this.pinJustSaved = pinJustSaved;
    }

    public void resetSignUp() {
        mSignUpMnemonic = null;
        if (mSignUpQRCode != null)
            mSignUpQRCode.recycle();
        mSignUpQRCode = null;
    }

    public String getSignUpMnemonic() {
        if (mSignUpMnemonic == null)
            mSignUpMnemonic = GDKSession.generateMnemonic("en");
        return mSignUpMnemonic;
    }

    public Bitmap getSignUpQRCode() {
        if (mSignUpQRCode == null)
            mSignUpQRCode = new QrBitmap(getSignUpMnemonic(), getResources().getColor(R.color.green)).getQRCode();
       return mSignUpQRCode;
    }

    public SubaccountData getSubaccountData(final int subAccount) {
        return getGAApp().getModel().getSubaccountDataObservable().getSubaccountDataWithPointer(subAccount);
    }

    public String getAddress(final int subAccount) {
        return getGAApp().getModel().getReceiveAddressObservable(subAccount).getReceiveAddress();
    }

    public BalanceData getBalanceData(final int subAccount) {
        try {
            final long satoshi = getGAApp().getModel().getBalanceDataObservable(subAccount).getBtcBalanceData();
            return getSession().convertBalance(satoshi);
        } catch (final Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public String getFiatCurrency() {
        return getGAApp().getModel().getSettings().getPricing().getCurrency();
    }

    private void onNetConnectivityChanged() {
        final NetworkInfo info = getNetworkInfo();
        Log.d(TAG, "onNetConnectivityChanged " + info);
        // TODO: auto-reconnect using gdk
        if (info != null)
            mSPV.onNetConnectivityChangedAsync(info);
    }

    public NetworkInfo getNetworkInfo() {
        final Context ctx = getApplicationContext();
        final ConnectivityManager cm;
        cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        try {
            final NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.isConnectedOrConnecting() ? ni : null;
        } catch (final Exception e) {
            return null;
        }
    }

    public void incRef() {
        ++mRefCount;
        rescheduleDisconnect();
    }

    public void decRef() {
        if (BuildConfig.DEBUG && mRefCount <= 0)
            throw new RuntimeException("Incorrect reference count");
        if (--mRefCount == 0)
            checkDisconnect();
    }

    public void rescheduleDisconnect() {
        mDisconnectTimer = System.currentTimeMillis() + getAutoLogoutTimeout() * 60 * 1000;
    }

    public int getAutoLogoutTimeout() {
        if (getGAApp().getModel() == null || getGAApp().getModel().getSettings() == null) {
            try {
                // we need to fetch this also locally,
                // cause we can scheduleDisconnect before being logged in
                final String altimeString = cfg().getString(PrefKeys.ALTIMEOUT, "5");
                return Integer.parseInt(altimeString);
            } catch (Exception e) {
                Log.e(TAG,"getAutoLogoutTimeout: " + e.getMessage());
                return 5;
            }
        }
        return getGAApp().getModel().getSettings().getAltimeout();
    }

    private void checkDisconnect() {
        if (getGAApp().getConnectionManager().isDisconnected())
            return;
        if (mDisconnectTimer != null && System.currentTimeMillis() > mDisconnectTimer) {
            mExecutor.submit(() -> getGAApp().getConnectionManager().disconnect());
        }
    }

    public ListenableFuture<Boolean> changeMemo(final String txHashHex, final String memo) {
        return mExecutor.submit(() -> getSession().changeMemo(txHashHex, memo));
    }

}
