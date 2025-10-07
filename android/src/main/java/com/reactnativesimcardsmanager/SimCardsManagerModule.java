package com.reactnativesimcardsmanager;

import static android.content.Context.EUICC_SERVICE;

import android.Manifest;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telecom.PhoneAccountHandle;
import android.app.role.RoleManager;
import android.telecom.TelecomManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.euicc.DownloadableSubscription;
import android.telephony.euicc.EuiccManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;

import com.facebook.react.bridge.*;

import java.util.Collections;
import java.util.List;

public class SimCardsManagerModule extends ReactContextBaseJavaModule {
  public static final String NAME = "SimCardsManager";
  private static final String TAG = "SimCardsManager";
  private static final String EXTRA_RESOLUTION_INTENT =
    "android.telephony.euicc.extra.EMBEDDED_SUBSCRIPTION_RESOLUTION_INTENT";
  private final ReactApplicationContext mReactContext;
  private String ACTION_DOWNLOAD_SUBSCRIPTION;

  @RequiresApi(api = Build.VERSION_CODES.P)
  private EuiccManager mgr;

  public SimCardsManagerModule(ReactApplicationContext reactContext) {
    super(reactContext);
    mReactContext = reactContext;
    ACTION_DOWNLOAD_SUBSCRIPTION = mReactContext.getPackageName() + ".euicc.DOWNLOAD_SUBSCRIPTION";
    Log.d(TAG, "Constructor: ACTION_DOWNLOAD_SUBSCRIPTION=" + ACTION_DOWNLOAD_SUBSCRIPTION);
  }

  @Override
  @NonNull
  public String getName() {
    return NAME;
  }

  private boolean hasPermission(String p) {
    return ActivityCompat.checkSelfPermission(mReactContext, p) == PackageManager.PERMISSION_GRANTED;
  }

  @RequiresApi(api = Build.VERSION_CODES.P)
  private void initMgr() {
    if (mgr == null) {
      mgr = (EuiccManager) mReactContext.getSystemService(EUICC_SERVICE);
      Log.d(TAG, "initMgr: EuiccManager initialized = " + (mgr != null));
    } else {
      Log.d(TAG, "initMgr: EuiccManager already initialized");
    }
  }

  @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP_MR1)
  @ReactMethod
  public void getSimCardsNative(Promise promise) {
    Log.d(TAG, "getSimCardsNative: ENTER");
    WritableArray simCardsList = new WritableNativeArray();

    TelephonyManager telManager = (TelephonyManager) mReactContext.getSystemService(Context.TELEPHONY_SERVICE);
    try {
      SubscriptionManager manager = (SubscriptionManager) mReactContext.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);

      boolean hasReadPhoneState = hasPermission(Manifest.permission.READ_PHONE_STATE);
      boolean hasCarrierPriv = false;
      try { hasCarrierPriv = telManager != null && telManager.hasCarrierPrivileges(); } catch (Throwable ignored) {}
      Log.d(TAG, "getSimCardsNative: READ_PHONE_STATE=" + hasReadPhoneState + " carrierPriv=" + hasCarrierPriv);

      if (!hasReadPhoneState && !hasCarrierPriv) {
        promise.reject("PERMISSION", "READ_PHONE_STATE permission required (or carrier privileges).");
        return;
      }

      List<SubscriptionInfo> subscriptionInfos = manager.getActiveSubscriptionInfoList();
      if (subscriptionInfos == null) subscriptionInfos = Collections.emptyList();

      for (SubscriptionInfo subInfo : subscriptionInfos) {
        WritableMap simCard = Arguments.createMap();
        String number = "";
        try {
          if (Build.VERSION.SDK_INT >= 33) {
            if (hasPermission(Manifest.permission.READ_PHONE_NUMBERS) || hasReadPhoneState) {
              number = manager.getPhoneNumber(subInfo.getSubscriptionId());
            }
          } else {
            number = subInfo.getNumber();
          }
        } catch (SecurityException ignored) {}

        simCard.putString("carrierName", subInfo.getCarrierName() != null ? subInfo.getCarrierName().toString() : "");
        simCard.putString("displayName", subInfo.getDisplayName() != null ? subInfo.getDisplayName().toString() : "");
        simCard.putString("isoCountryCode", subInfo.getCountryIso());
        simCard.putInt("mobileCountryCode", subInfo.getMcc());
        simCard.putInt("mobileNetworkCode", subInfo.getMnc());
        simCard.putInt("isNetworkRoaming", (telManager != null && telManager.isNetworkRoaming()) ? 1 : 0);
        simCard.putInt("isDataRoaming", subInfo.getDataRoaming());
        simCard.putInt("simSlotIndex", subInfo.getSimSlotIndex());
        simCard.putString("phoneNumber", number);
        simCard.putString("simSerialNumber", subInfo.getIccId());
        simCard.putInt("subscriptionId", subInfo.getSubscriptionId());
        simCard.putInt("isEmbedded", subInfo.isEmbedded() ? 1 : 0);
        simCardsList.pushMap(simCard);
      }

      promise.resolve(simCardsList);
    } catch (Exception e) {
      promise.reject("1", "Failed to fetch simcards: " + e.getLocalizedMessage());
    }
  }

  @RequiresApi(api = Build.VERSION_CODES.P)
  @ReactMethod
  public void sendPhoneCall(String phoneNumberString, int simSlotIndex) {
    Uri uri = Uri.parse("tel:" + phoneNumberString.trim());
    TelecomManager telecomManager = (TelecomManager) mReactContext.getSystemService(Context.TELECOM_SERVICE);

    boolean isDefaultDialer = false;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      RoleManager roleManager = (RoleManager) mReactContext.getSystemService(RoleManager.class);
      if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)) {
        isDefaultDialer = roleManager.isRoleHeld(RoleManager.ROLE_DIALER);
      }
    }

    if (!isDefaultDialer || telecomManager == null) {
      Intent dial = new Intent(Intent.ACTION_DIAL, uri);
      dial.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      mReactContext.startActivity(dial);
      return;
    }

    if (!hasPermission(Manifest.permission.CALL_PHONE)) {
      Intent dial = new Intent(Intent.ACTION_DIAL, uri);
      dial.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      mReactContext.startActivity(dial);
      return;
    }

    PhoneAccountHandle accountHandle = null;
    try {
      if (hasPermission(Manifest.permission.READ_PHONE_STATE)) {
        List<PhoneAccountHandle> accounts = telecomManager.getCallCapablePhoneAccounts();
        if (accounts != null && !accounts.isEmpty()) {
          int idx = Math.min(Math.max(0, simSlotIndex), accounts.size() - 1);
          accountHandle = accounts.get(idx);
        }
      }
    } catch (SecurityException ignored) {}

    try {
      if (accountHandle != null) {
        Bundle extras = new Bundle();
        extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, accountHandle);
        telecomManager.placeCall(uri, extras);
      } else {
        telecomManager.placeCall(uri, null);
      }
    } catch (SecurityException se) {
      Intent dial = new Intent(Intent.ACTION_DIAL, uri);
      dial.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      mReactContext.startActivity(dial);
    }
  }

  @RequiresApi(api = Build.VERSION_CODES.P)
  @ReactMethod
  public void isEsimSupported(Promise promise) {
    initMgr();
    boolean supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && mgr != null && mgr.isEnabled();
    promise.resolve(supported);
  }

  @RequiresApi(api = Build.VERSION_CODES.P)
  @ReactMethod
  public void setupEsim(ReadableMap config, Promise promise) {
    Log.d(TAG, "setupEsim: ENTER");

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
      promise.reject("0", "EuiccManager unavailable (< API 28)");
      return;
    }

    initMgr();
    if (mgr == null || !mgr.isEnabled()) {
      promise.reject("1", "Device does not support eSIM (EuiccManager disabled)");
      return;
    }

    final String activationCode = config.hasKey("activationCode") ? config.getString("activationCode") : null;
    if (activationCode == null || activationCode.trim().isEmpty()) {
      promise.reject("0", "Activation code required");
      return;
    }

    Log.d(TAG, "setupEsim: activationCode reçu (contenu non loggé)");

    final Handler handler = new Handler(Looper.getMainLooper());

    BroadcastReceiver receiver = new BroadcastReceiver() {
      @Override
      public void onReceive(Context context, Intent intent) {
        int rc = getResultCode();
        Log.d(TAG, "setupEsim.onReceive: action=" + intent.getAction() + " resultCode=" + rc);

        if (!ACTION_DOWNLOAD_SUBSCRIPTION.equals(intent.getAction())) {
          try { mReactContext.unregisterReceiver(this); } catch (Exception ignored) {}
          handler.removeCallbacksAndMessages(null);
          promise.reject("3", "Wrong intent");
          return;
        }

        if (rc == EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_RESOLVABLE_ERROR) {
          PendingIntent resolutionPi = null;
          try {
            resolutionPi = intent.getParcelableExtra(EXTRA_RESOLUTION_INTENT, PendingIntent.class);
          } catch (Throwable ignored) {}

          if (resolutionPi != null) {
            try {
              Log.d(TAG, "setupEsim.onReceive: startResolutionActivity (AOSP)");

              Intent cb = new Intent(ACTION_DOWNLOAD_SUBSCRIPTION).setPackage(mReactContext.getPackageName());
              PendingIntent callbackPi = PendingIntent.getBroadcast(
                mReactContext, 0, cb,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
              );
              mgr.startResolutionActivity(getCurrentActivity(), 3, intent, callbackPi);

              return;
            } catch (Exception e) {
              Log.d(TAG, "setupEsim.onReceive: startResolutionActivity ERROR -> " + e.getMessage(), e);

            }
          }

          Log.d(TAG, "setupEsim.onReceive: RESOLVABLE_ERROR sans resolutionPi -> handoff UI");

          boolean launched = false;
          try {
            Intent startAct = new Intent(EuiccManager.ACTION_START_EUICC_ACTIVATION);
            startAct.putExtra(EuiccManager.EXTRA_USE_QR_SCANNER, true);
            startAct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mReactContext.startActivity(startAct);
            launched = true;
            Log.d(TAG, "setupEsim.onReceive: opened START_EUICC_ACTIVATION (QR)");
          } catch (Throwable t) {
            Log.d(TAG, "setupEsim.onReceive: START_EUICC_ACTIVATION failed -> " + t.getMessage());
          }

          if (!launched) {
            try {
              Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(activationCode));
              view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
              mReactContext.startActivity(view);
              launched = true;
              Log.d(TAG, "setupEsim.onReceive: opened ACTION_VIEW lpa: URI");
            } catch (Throwable t2) {
              Log.d(TAG, "setupEsim.onReceive: ACTION_VIEW lpa failed -> " + t2.getMessage());
            }
          }

          try { mReactContext.unregisterReceiver(this); } catch (Exception ignored) {}
          handler.removeCallbacksAndMessages(null);
          promise.resolve(false);
          return;
        }

        try { mReactContext.unregisterReceiver(this); } catch (Exception ignored) {}
        handler.removeCallbacksAndMessages(null);

        if (rc == EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_OK) {
          Log.d(TAG, "setupEsim.onReceive: RESULT_OK");
          promise.resolve(true);
        } else if (rc == EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_ERROR) {
          int detailed = intent.getIntExtra(EuiccManager.EXTRA_EMBEDDED_SUBSCRIPTION_DETAILED_CODE, 0);
          int opCode  = intent.getIntExtra(EuiccManager.EXTRA_EMBEDDED_SUBSCRIPTION_OPERATION_CODE, 0);
          int errCode = intent.getIntExtra(EuiccManager.EXTRA_EMBEDDED_SUBSCRIPTION_ERROR_CODE, 0);
          Log.d(TAG, "setupEsim.onReceive: RESULT_ERROR detailed=" + detailed + " opCode=" + opCode + " errCode=" + errCode);
          promise.reject("2", "Can't add eSIM (RESULT_ERROR) err=" + errCode + " op=" + opCode + " detailed=" + detailed);
        } else {
          Log.d(TAG, "setupEsim.onReceive: UNKNOWN rc=" + rc);
          promise.reject("3", "Unknown error, rc=" + rc);
        }
      }
    };

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      Log.d(TAG, "setupEsim: registerReceiver(EXPORTED) for API>=33");
      mReactContext.registerReceiver(
        receiver,
        new IntentFilter(ACTION_DOWNLOAD_SUBSCRIPTION),
        Context.RECEIVER_EXPORTED
      );
    } else {
      Log.d(TAG, "setupEsim: registerReceiver (legacy)");
      mReactContext.registerReceiver(receiver, new IntentFilter(ACTION_DOWNLOAD_SUBSCRIPTION));
    }

    DownloadableSubscription sub;
    try {
      sub = DownloadableSubscription.forActivationCode(activationCode);
    } catch (Throwable t) {
      promise.reject("0", "Invalid activation code");
      try { mReactContext.unregisterReceiver(receiver); } catch (Exception ignored) {}
      return;
    }

    Intent piIntent = new Intent(ACTION_DOWNLOAD_SUBSCRIPTION).setPackage(mReactContext.getPackageName());
    PendingIntent callbackIntent = PendingIntent.getBroadcast(
      mReactContext, 0, piIntent,
      PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
    );

    TelephonyManager tm = (TelephonyManager) mReactContext.getSystemService(Context.TELEPHONY_SERVICE);
    boolean hasCarrier = false;
    try { hasCarrier = tm != null && tm.hasCarrierPrivileges(); } catch (Throwable ignored) {}
    boolean hasWriteEmbedded =
      mReactContext.checkSelfPermission("android.permission.WRITE_EMBEDDED_SUBSCRIPTIONS") == PackageManager.PERMISSION_GRANTED;

    Log.d(TAG, "setupEsim: hasCarrierPrivileges=" + hasCarrier
      + " READ_PHONE_STATE=" + hasPermission(Manifest.permission.READ_PHONE_STATE)
      + " WRITE_EMBEDDED_SUBSCRIPTIONS=" + hasWriteEmbedded);

    Log.d(TAG, "setupEsim: calling mgr.downloadSubscription()");
    try {
      mgr.downloadSubscription(sub, true, callbackIntent);
      Log.d(TAG, "setupEsim: mgr.downloadSubscription() invoked");
    } catch (Throwable t) {
      try { mReactContext.unregisterReceiver(receiver); } catch (Exception ignored) {}
      promise.reject("1", "downloadSubscription failed: " + t.getMessage());
    }
  }
}
