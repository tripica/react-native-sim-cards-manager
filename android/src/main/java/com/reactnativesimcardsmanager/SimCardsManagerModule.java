package com.reactnativesimcardsmanager;

import static android.content.Context.EUICC_SERVICE;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.telephony.euicc.EuiccManager;
import com.facebook.react.bridge.*;
import android.app.PendingIntent;
import android.content.Intent;
import android.telephony.euicc.DownloadableSubscription;
import android.content.IntentFilter;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.telecom.TelecomManager;
import android.telephony.TelephonyManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telecom.PhoneAccountHandle;
import android.app.role.RoleManager;
import android.util.Log;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;

import java.util.Collections;
import java.util.List;
import java.util.Set;

public class SimCardsManagerModule extends ReactContextBaseJavaModule {
  public static final String NAME = "SimCardsManager";
  private static final String TAG = "SimCardsManager";

  private static final String EXTRA_RESOLUTION_INTENT =
      "android.telephony.euicc.extra.EMBEDDED_SUBSCRIPTION_RESOLUTION_INTENT";

  private final String ACTION_DOWNLOAD_SUBSCRIPTION;
  private final ReactContext mReactContext;

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

  private boolean hasPermission(String permission) {
    return ActivityCompat.checkSelfPermission(mReactContext, permission)
        == PackageManager.PERMISSION_GRANTED;
  }

  @RequiresApi(api = Build.VERSION_CODES.P)
  private void initMgr() {
    if (mgr == null) {
      mgr = (EuiccManager) mReactContext.getSystemService(EUICC_SERVICE);
    }
  }

  // -------------------------
  // getSimCardsNative
  // -------------------------
  @ReactMethod
  public void getSimCardsNative(Promise promise) {
    WritableArray simCardsList = new WritableNativeArray();
    try {
      SubscriptionManager manager = (SubscriptionManager) mReactContext
          .getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
        List<SubscriptionInfo> subscriptionInfos = manager.getActiveSubscriptionInfoList();
        if (subscriptionInfos == null) subscriptionInfos = Collections.emptyList();

        for (SubscriptionInfo subInfo : subscriptionInfos) {
          WritableMap simCard = Arguments.createMap();
          simCard.putString("carrierName", subInfo.getCarrierName() != null ? subInfo.getCarrierName().toString() : "");
          simCard.putString("displayName", subInfo.getDisplayName() != null ? subInfo.getDisplayName().toString() : "");
          simCard.putString("isoCountryCode", subInfo.getCountryIso());
          simCard.putInt("mobileCountryCode", subInfo.getMcc());
          simCard.putInt("mobileNetworkCode", subInfo.getMnc());
          simCard.putInt("isDataRoaming", subInfo.getDataRoaming());
          simCard.putInt("simSlotIndex", subInfo.getSimSlotIndex());
          simCard.putString("simSerialNumber", subInfo.getIccId());
          simCard.putInt("subscriptionId", subInfo.getSubscriptionId());
          simCardsList.pushMap(simCard);
        }
      } else {
        promise.reject("0", "Not supported before Android 5.1");
        return;
      }
    } catch (Exception e) {
      promise.reject("1", "Error fetching simcards: " + e.getLocalizedMessage());
      return;
    }
    promise.resolve(simCardsList);
  }

  // -------------------------
  // isEsimSupported
  // -------------------------
  @ReactMethod
  public void isEsimSupported(Promise promise) {
    initMgr();
    boolean supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && mgr != null && mgr.isEnabled();
    promise.resolve(supported);
  }

  // -------------------------
  // setupEsim
  // -------------------------
  @ReactMethod
  public void setupEsim(ReadableMap config, Promise promise) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
      promise.reject("0", "Not supported before Android 9");
      return;
    }

    initMgr();
    if (mgr == null || !mgr.isEnabled()) {
      promise.reject("1", "EuiccManager not available");
      return;
    }

    String activationCode = config.hasKey("activationCode") ? config.getString("activationCode") : null;
    if (activationCode == null || activationCode.trim().isEmpty()) {
      promise.reject("ARG", "Missing activationCode (LPA)");
      return;
    }

    final Handler handler = new Handler(Looper.getMainLooper());

    BroadcastReceiver receiver = new BroadcastReceiver() {
      @Override
      public void onReceive(Context context, Intent intent) {
        int rc = getResultCode();
        Log.d(TAG, "setupEsim.onReceive: resultCode=" + rc);

        if (rc == EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_RESOLVABLE_ERROR) {
          PendingIntent resolutionPi = null;
          try {
            resolutionPi = intent.getParcelableExtra(EXTRA_RESOLUTION_INTENT, PendingIntent.class);
          } catch (Throwable ignored) {}

          if (resolutionPi == null) {
            Log.d(TAG, "setupEsim: RESOLVABLE_ERROR SANS EXTRA_RESOLUTION_INTENT -> fallback UI");

            // ouvrir settings eSIM
            try {
              Intent settings = new Intent(Settings.ACTION_WIRELESS_SETTINGS);
              settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
              mReactContext.startActivity(settings);
            } catch (Exception e) {
              Log.d(TAG, "setupEsim: ACTION_WIRELESS_SETTINGS failed -> " + e.getMessage());
            }

            try { mReactContext.unregisterReceiver(this); } catch (Exception ignored) {}
            handler.removeCallbacksAndMessages(null);

            // ✅ on résout avec false au lieu de reject
            promise.resolve(false);
            return;
          }
        }

        try { mReactContext.unregisterReceiver(this); } catch (Exception ignored) {}
        handler.removeCallbacksAndMessages(null);

        if (rc == EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_OK) {
          promise.resolve(true);
        } else {
          promise.reject("2", "Install failed, code=" + rc);
        }
      }
    };

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      mReactContext.registerReceiver(receiver, new IntentFilter(ACTION_DOWNLOAD_SUBSCRIPTION), Context.RECEIVER_EXPORTED);
    } else {
      mReactContext.registerReceiver(receiver, new IntentFilter(ACTION_DOWNLOAD_SUBSCRIPTION));
    }

    DownloadableSubscription sub = DownloadableSubscription.forActivationCode(activationCode);

    Intent piIntent = new Intent(ACTION_DOWNLOAD_SUBSCRIPTION).setPackage(mReactContext.getPackageName());
    PendingIntent callbackIntent = PendingIntent.getBroadcast(
        mReactContext,
        0,
        piIntent,
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
    );

    mgr.downloadSubscription(sub, true, callbackIntent);
  }
}
