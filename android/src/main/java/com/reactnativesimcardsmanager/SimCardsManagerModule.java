package com.reactnativesimcardsmanager;

import static android.content.Context.EUICC_SERVICE;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.euicc.DownloadableSubscription;
import android.telephony.euicc.EuiccManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.WritableNativeArray;
import com.facebook.react.module.annotations.ReactModule;

import java.util.List;

@ReactModule(name = SimCardsManagerModule.NAME)
public class SimCardsManagerModule extends ReactContextBaseJavaModule {

  public static final String NAME = "SimCardsManager";
  private static final String TAG  = "SimCardsManager";

  private static final String ACTION_DOWNLOAD_SUBSCRIPTION = "download_subscription";
  private static final String EXTRA_EMBEDDED_SUBSCRIPTION_RESULT =
    "android.telephony.euicc.extra.EMBEDDED_SUBSCRIPTION_RESULT";
  private static final String EXTRA_EMBEDDED_SUBSCRIPTION_DETAILED_CODE =
    "android.telephony.euicc.extra.EMBEDDED_SUBSCRIPTION_DETAILED_CODE";

  private final ReactApplicationContext mReactContext;

  @RequiresApi(api = Build.VERSION_CODES.P)
  private EuiccManager mgr;

  public SimCardsManagerModule(ReactApplicationContext reactContext) {
    super(reactContext);
    mReactContext = reactContext;
    Log.d(TAG, "Module constructed. SDK=" + Build.VERSION.SDK_INT);
  }

  @RequiresApi(api = Build.VERSION_CODES.P)
  private void initMgr() {
    if (mgr == null) {
      mgr = (EuiccManager) mReactContext.getSystemService(EUICC_SERVICE);
      Log.d(TAG, "initMgr(): EuiccManager=" + (mgr != null ? "OK" : "null"));
    }
  }

  @Override
  @NonNull
  public String getName() {
    return NAME;
  }

  @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP_MR1)
  @ReactMethod
  public void getSimCardsNative(Promise promise) {
    Log.d(TAG, "getSimCardsNative(): entry");
    WritableArray simCardsList = new WritableNativeArray();

    TelephonyManager telManager = (TelephonyManager) mReactContext.getSystemService(Context.TELEPHONY_SERVICE);
    try {
      SubscriptionManager manager =
        (SubscriptionManager) mReactContext.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);

      List<SubscriptionInfo> subscriptionInfos = manager.getActiveSubscriptionInfoList();
      Log.d(TAG, "getSimCardsNative(): list=" + (subscriptionInfos != null ? subscriptionInfos.size() : "null"));

      if (subscriptionInfos != null) {
        for (SubscriptionInfo subInfo : subscriptionInfos) {
          WritableMap simCard = Arguments.createMap();

          String number;
          if (Build.VERSION.SDK_INT >= 33) {
            number = manager.getPhoneNumber(subInfo.getSubscriptionId());
          } else {
            number = subInfo.getNumber();
          }

          CharSequence carrierName = subInfo.getCarrierName();
          String countryIso = subInfo.getCountryIso();
          int dataRoaming = subInfo.getDataRoaming();
          CharSequence displayName = subInfo.getDisplayName();
          String iccId = subInfo.getIccId();
          int mcc = subInfo.getMcc();
          int mnc = subInfo.getMnc();
          int simSlotIndex = subInfo.getSimSlotIndex();
          int subscriptionId = subInfo.getSubscriptionId();
          int networkRoaming = telManager.isNetworkRoaming() ? 1 : 0;

          simCard.putString("carrierName", carrierName != null ? carrierName.toString() : "");
          simCard.putString("displayName", displayName != null ? displayName.toString() : "");
          simCard.putString("isoCountryCode", countryIso);
          simCard.putInt("mobileCountryCode", mcc);
          simCard.putInt("mobileNetworkCode", mnc);
          simCard.putInt("isNetworkRoaming", networkRoaming);
          simCard.putInt("isDataRoaming", dataRoaming);
          simCard.putInt("simSlotIndex", simSlotIndex);
          simCard.putString("phoneNumber", number);
          simCard.putString("simSerialNumber", iccId);
          simCard.putInt("subscriptionId", subscriptionId);

          simCardsList.pushMap(simCard);
        }
      }
      Log.d(TAG, "getSimCardsNative(): success -> " + simCardsList.size() + " sims");
      promise.resolve(simCardsList);
    } catch (Exception e) {
      Log.e(TAG, "getSimCardsNative(): error", e);
      promise.reject("1", "Something goes wrong to fetch simcards: " + e.getLocalizedMessage());
    }
  }

  @RequiresApi(api = Build.VERSION_CODES.P)
  @ReactMethod
  public void sendPhoneCall(String phoneNumberString, int simSlotIndex) {
    Log.d(TAG, "sendPhoneCall(): number=" + phoneNumberString + " slot=" + simSlotIndex);
    Uri uri = Uri.parse("tel:" + phoneNumberString.trim());
    TelecomManager telecomManager = (TelecomManager) mReactContext.getSystemService(Context.TELECOM_SERVICE);
    List<PhoneAccountHandle> list = telecomManager.getCallCapablePhoneAccounts();

    PhoneAccountHandle accountHandle = null;
    if (list != null && !list.isEmpty()) {
      int index = Math.max(0, Math.min(simSlotIndex, list.size() - 1));
      accountHandle = list.get(index);
    }

    if (accountHandle != null) {
      Bundle extras = new Bundle();
      extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, accountHandle);
      Log.d(TAG, "sendPhoneCall(): placing call with account handle");
      telecomManager.placeCall(uri, extras);
    } else {
      Log.w(TAG, "sendPhoneCall(): no PhoneAccountHandle found");
    }
  }

  @RequiresApi(api = Build.VERSION_CODES.P)
  @ReactMethod
  public void isEsimSupported(Promise promise) {
    Log.d(TAG, "isEsimSupported(): entry");
    initMgr();
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && mgr != null) {
      boolean enabled = mgr.isEnabled();
      Log.d(TAG, "isEsimSupported(): mgr.isEnabled=" + enabled);
      promise.resolve(enabled);
    } else {
      Log.d(TAG, "isEsimSupported(): unsupported (SDK or mgr null)");
      promise.resolve(false);
    }
  }

  private boolean checkCarrierPrivileges() {
    TelephonyManager telManager = (TelephonyManager) mReactContext.getSystemService(Context.TELEPHONY_SERVICE);
    boolean has = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1 && telManager.hasCarrierPrivileges();
    Log.d(TAG, "checkCarrierPrivileges(): " + has);
    return has;
  }

  @RequiresApi(api = Build.VERSION_CODES.P)
  private void handleResolvableError(Promise promise, Intent intent) {
    Log.w(TAG, "handleResolvableError(): attempting resolution activity");
    try {
      Activity current = getCurrentActivity();
      if (current == null) {
        Log.e(TAG, "handleResolvableError(): current Activity is null");
        promise.reject("3", "Resolvable error but no current Activity to start resolution.");
        return;
      }

      int resolutionRequestCode = 3;
      PendingIntent callbackIntent = PendingIntent.getBroadcast(
        mReactContext,
        resolutionRequestCode,
        new Intent(ACTION_DOWNLOAD_SUBSCRIPTION).setPackage(mReactContext.getPackageName()),
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE
      );

      Log.d(TAG, "handleResolvableError(): calling startResolutionActivity rc=" + resolutionRequestCode);
      mgr.startResolutionActivity(current, resolutionRequestCode, intent, callbackIntent);
    } catch (Exception e) {
      Log.e(TAG, "handleResolvableError(): exception", e);
      promise.reject("3", "EMBEDDED_SUBSCRIPTION_RESULT_RESOLVABLE_ERROR - Can't setup eSim: " + e.getLocalizedMessage());
    }
  }

  @RequiresApi(api = Build.VERSION_CODES.P)
  @ReactMethod
  public void setupEsim(ReadableMap config, Promise promise) {
    Log.d(TAG, "setupEsim(): entry, SDK=" + Build.VERSION.SDK_INT);
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
      Log.e(TAG, "setupEsim(): SDK < 28");
      promise.reject("0", "EuiccManager is not available before Android 9 (API 28)");
      return;
    }

    initMgr();

    if (mgr == null || !mgr.isEnabled()) {
      Log.e(TAG, "setupEsim(): mgr null or not enabled");
      promise.reject("1", "The device doesn't support a cellular plan (EuiccManager is not available)");
      return;
    }

    final String activationCode =
      (config != null && config.hasKey("confirmationCode")) ? config.getString("confirmationCode") : null;

    Log.d(TAG, "setupEsim(): activationCode present=" + (activationCode != null && !activationCode.isEmpty()));

    if (activationCode == null || activationCode.isEmpty()) {
      Log.e(TAG, "setupEsim(): missing confirmationCode");
      promise.reject("0", "Missing confirmationCode for eSIM activation");
      return;
    }

    try {
      String ac = activationCode;
      int dollarCount = ac.length() - ac.replace("$", "").length();
      Log.d(TAG, "setupEsim(): acPrefix=" + ac.substring(0, Math.min(ac.length(), 10)));
      Log.d(TAG, "setupEsim(): acLooksLikeLPA=" + ac.startsWith("LPA:"));
      Log.d(TAG, "setupEsim(): dollarCount=" + dollarCount);
      if (ac.startsWith("LPA:") && dollarCount >= 2) {
        int first = ac.indexOf('$');
        int second = ac.indexOf('$', first + 1);
        if (first > 0 && second > first) {
          String smdp = ac.substring(first + 1, second);
          Log.d(TAG, "setupEsim(): smdpHostPrefix=" + smdp.substring(0, Math.min(8, smdp.length())));
        }
      } else {
        Log.e(TAG, "setupEsim(): activationCode format invalid (expected LPA:...$<host>$<token>)");
      }
    } catch (Throwable t) {
      Log.w(TAG, "setupEsim(): activationCode logging failed (ignored)", t);
    }

    final boolean[] retriedWithoutSwitch = { false };

    BroadcastReceiver receiver = new BroadcastReceiver() {
      @Override
      public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "onReceive(): action=" + (intent != null ? intent.getAction() : "null"));
        if (intent == null || !ACTION_DOWNLOAD_SUBSCRIPTION.equals(intent.getAction())) {
          Log.e(TAG, "onReceive(): wrong action=" + (intent != null ? intent.getAction() : "null"));
          promise.reject("3", "Wrong Intent action: " + (intent != null ? intent.getAction() : "null"));
          try { mReactContext.unregisterReceiver(this); } catch (Exception ignore) {}
          return;
        }

        int result = intent.getIntExtra(EXTRA_EMBEDDED_SUBSCRIPTION_RESULT,
          EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_ERROR);
        int detailed = intent.getIntExtra(EXTRA_EMBEDDED_SUBSCRIPTION_DETAILED_CODE, 0);
        Log.d(TAG, "onReceive(): result=" + result + " detailed=" + detailed);

        if (result == EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_OK) {
          Log.i(TAG, "onReceive(): eSIM download OK");
          promise.resolve(true);
          try { mReactContext.unregisterReceiver(this); Log.d(TAG, "onReceive(): receiver unregistered (OK)"); } catch (Exception ignore) {}
          return;
        }

        if (result == EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_RESOLVABLE_ERROR) {
          Log.w(TAG, "onReceive(): RESOLVABLE_ERROR -> startResolutionActivity");
          handleResolvableError(promise, intent);
          return;
        }

        Log.e(TAG, "onReceive(): ERROR -> code=" + result + " detail=" + detailed);

        if (!retriedWithoutSwitch[0]) {
          retriedWithoutSwitch[0] = true;
          try {
            Log.w(TAG, "onReceive(): retry once with switchAfter=false");
            DownloadableSubscription subRetry = DownloadableSubscription.forActivationCode(activationCode);
            Intent cbIntentRetry = new Intent(ACTION_DOWNLOAD_SUBSCRIPTION)
              .setPackage(mReactContext.getPackageName());
            PendingIntent piRetry = PendingIntent.getBroadcast(
              mReactContext,
              0,
              cbIntentRetry,
              PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE
            );
            mgr.downloadSubscription(subRetry, /*switchAfterDownload=*/false, piRetry);
            return;
          } catch (Exception e) {
            Log.e(TAG, "onReceive(): retry exception", e);
          }
        }

        promise.reject("2", "eSIM download failed (code=" + result + ", detail=" + detailed + ")");
        try { mReactContext.unregisterReceiver(this); Log.d(TAG, "onReceive(): receiver unregistered (ERROR)"); } catch (Exception ignore) {}
      }
    };

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      Log.d(TAG, "setupEsim(): registerReceiver API33+ with RECEIVER_EXPORTED");
      mReactContext.registerReceiver(
        receiver,
        new IntentFilter(ACTION_DOWNLOAD_SUBSCRIPTION),
        Context.RECEIVER_EXPORTED
      );
    } else {
      Log.d(TAG, "setupEsim(): registerReceiver legacy (no flags)");
      mReactContext.registerReceiver(receiver, new IntentFilter(ACTION_DOWNLOAD_SUBSCRIPTION));
    }

    DownloadableSubscription sub = DownloadableSubscription.forActivationCode(activationCode);
    Log.d(TAG, "setupEsim(): DownloadableSubscription created");

    Intent cbIntent = new Intent(ACTION_DOWNLOAD_SUBSCRIPTION).setPackage(mReactContext.getPackageName());
    int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE;
    PendingIntent callbackIntent = PendingIntent.getBroadcast(mReactContext, 0, cbIntent, piFlags);
    Log.d(TAG, "setupEsim(): PendingIntent flags=UPDATE_CURRENT|MUTABLE");

    try {
      Log.i(TAG, "setupEsim(): calling mgr.downloadSubscription(..., switchAfter=true)");
      mgr.downloadSubscription(sub, /*switchAfterDownload=*/true, callbackIntent);
    } catch (Exception e) {
      Log.e(TAG, "setupEsim(): downloadSubscription exception", e);
      promise.reject("3", "Failed to start eSIM download: " + e.getLocalizedMessage());
      try { mReactContext.unregisterReceiver(receiver); Log.d(TAG, "setupEsim(): receiver unregistered (exception)"); } catch (Exception ignore) {}
    }
  }
}