package ch.filecloud.betoo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.NetworkInfo;
import android.os.Message;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;
import static de.robv.android.xposed.XposedHelpers.getObjectField;

/**
 * Created by domi on 5/30/14.
 */
public class XposedMod implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    private static Context mContext;
    private static Class<?> mPhoneFactoryClass;
    private static Class<?> mSystemProperties;

    private static int NETWORK_MODE_WCDMA_PREF = 0;
    private static int NETWORK_MODE_GSM_ONLY = 1;
    private static int NETWORK_MODE_LTE_GSM_WCDMA  = 9; // works on galaxy s5

    public static final String ACTION_CHANGE_NETWORK_TYPE = "betoo.intent.action.CHANGE_NETWORK_TYPE";
    public static final String EXTRA_NETWORK_TYPE = "networkType";

    private static int defaultNetworkType = -1;
    private static int currentNetworkType = -1;

    private static SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

    private static BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(ACTION_CHANGE_NETWORK_TYPE)) {

                int networkType = intent.getIntExtra(EXTRA_NETWORK_TYPE, -1);

                if(networkType == -1) {
                    if(currentNetworkType != defaultNetworkType) {
                        XposedMod.log("Changing back to default network type: " + NetworkType.get(defaultNetworkType));
                        setPreferredNetworkType(defaultNetworkType);
                    }

                } else {
                    if(networkType != currentNetworkType) {
                        XposedMod.log("WLAN connected! Setting preferred network type to: " + NetworkType.get(networkType));
                        setPreferredNetworkType(networkType);
                    }
                }
            }
        }
    };

    @Override
    public void initZygote(StartupParam startupParam) throws Throwable {
        mPhoneFactoryClass = XposedHelpers.findClass("com.android.internal.telephony.PhoneFactory", null);
        mSystemProperties = XposedHelpers.findClass("android.os.SystemProperties", null);

        defaultNetworkType = getDefaultNetworkType();

        XposedHelpers.findAndHookMethod(mPhoneFactoryClass, "makeDefaultPhone",
                Context.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
                        mContext = (Context) param.args[0];
                        XposedMod.log("Initialized phone wrapper (makeDefaultPhone). Context is: " + mContext);
                        onInitialize();
                    }
                }
        );

    }

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {

        if (!lpparam.packageName.contains("android.")) {
            return;
        }
        findAndHookMethod("android.net.wifi.WifiStateMachine", lpparam.classLoader, "setNetworkDetailedState", "android.net.NetworkInfo.DetailedState", new XC_MethodHook() {

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        NetworkInfo.DetailedState state = (NetworkInfo.DetailedState) param.args[0];
                        Context context = (Context) getObjectField(param.thisObject, "mContext");
                        Intent i = new Intent(XposedMod.ACTION_CHANGE_NETWORK_TYPE);

                        if (state.equals(NetworkInfo.DetailedState.CONNECTED)) {
                            i.putExtra(XposedMod.EXTRA_NETWORK_TYPE, NETWORK_MODE_GSM_ONLY);

                        }

                        try {
                            context.sendBroadcast(i);
                        } catch (IllegalStateException e) {
                            XposedMod.log("Cannot broadcast before boot completed. Skipping broadcast...");
                        }
                    }
                }
        );
    }

    private static void onInitialize() {
        if (mContext != null) {
            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(ACTION_CHANGE_NETWORK_TYPE);
            mContext.registerReceiver(mBroadcastReceiver, intentFilter);
        }
    }

    private static void setPreferredNetworkType(int networkType) {
        currentNetworkType = networkType;
        Object defPhone = XposedHelpers.callStaticMethod(mPhoneFactoryClass, "getDefaultPhone");
        if (defPhone == null) {
            XposedMod.log("Default phone was null! Unable to set preferred network type");
            return;
        }

        try {
            Class<?>[] paramArgs = new Class<?>[2];
            paramArgs[0] = int.class;
            paramArgs[1] = Message.class;
            XposedHelpers.callMethod(defPhone, "setPreferredNetworkType", paramArgs, networkType, null);

        } catch (Throwable t) {
            XposedMod.log("Unable to set preferred network type: " + t.getMessage());
        }
    }

    public static int getDefaultNetworkType() {
        try {
            int mode = (Integer) XposedHelpers.callStaticMethod(mSystemProperties, "getInt", "ro.telephony.default_network", -1);

            if(mode == -1) {
                XposedMod.log("Failed to detect default network type! Using: NETWORK_MODE_LTE_GSM_WCDMA");
                //mode = NETWORK_MODE_WCDMA_PREF;
                mode = NETWORK_MODE_LTE_GSM_WCDMA;
            }

            currentNetworkType = mode;

            return mode;
        } catch (Throwable t) {
            XposedMod.log(t.getMessage());
            return NETWORK_MODE_WCDMA_PREF;
        }
    }

    private static void log(String message) {
        sdf.setTimeZone(TimeZone.getDefault());
        XposedBridge.log(sdf.format(new Date()) + " [Betoo] " + message);
    }
}
