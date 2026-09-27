package io.github.MoWei.Frozen.hook;

import android.os.Build;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam;
import de.robv.android.xposed.XC_MethodReplacement;
import io.github.MoWei.Frozen.BuildConfig;
import io.github.MoWei.Frozen.hook.android.AlarmHook;
import io.github.MoWei.Frozen.hook.android.BroadCastHook;
import io.github.MoWei.Frozen.hook.android.FreezeitService;
import io.github.MoWei.Frozen.hook.android.WakeLockHook;
import io.github.MoWei.Frozen.hook.app.PowerKeeper;
import io.github.MoWei.Frozen.hook.android.Audio.AudioStateHook;
import io.github.MoWei.Frozen.hook.android.Audio.SendMediaButtonHook;
import io.github.MoWei.Frozen.hook.android.Audio.PlayerBanHook;
import io.github.MoWei.Frozen.hook.android.Audio.AudioFocusHook;
import io.github.MoWei.Frozen.hook.android.PendingIntent.PendingIntentHook;
import io.github.MoWei.Frozen.hook.android.Anr.ANRErrorStateHook;
import io.github.MoWei.Frozen.hook.android.Anr.ANRHelperHooks;
import io.github.MoWei.Frozen.hook.android.Anr.ANRHook;
import io.github.MoWei.Frozen.hook.android.BroadCast.BroadcastIntentHook;
import io.github.MoWei.Frozen.hook.android.BroadCast.BroadcastDeliveryHook;
import io.github.MoWei.Frozen.hook.android.BroadCast.BroadcastSkipHook;
import io.github.MoWei.Frozen.hook.android.CachedAppOptimizer.CachedAppOptimizerHook;
import io.github.MoWei.Frozen.hook.android.CachedAppOptimizer.DisableUseFreezerHook;
import io.github.MoWei.Frozen.hook.android.CachedAppOptimizer.MilletDisable;
import io.github.MoWei.Frozen.hook.android.Job.JobSchedulerHook;
public class Hook extends XposedModule {

    private static final java.util.concurrent.atomic.AtomicBoolean androidHooked =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** [A17-FIX] 无参构造器：被 LSPosed 实例化的瞬间就留下痕迹，
     *  用于区分「模块未加载」与「模块已加载但回调未触发」两种情况。 */
    public Hook() {
        XpUtils.moduleRef = this;
        XpUtils.log("Frozen[Xposed]", "entry constructed");
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        XpUtils.moduleRef = this;
        XpUtils.log("Frozen[Xposed]", "onPackageReady: " + param.getPackageName());
        try {
            dispatch(param.getPackageName(), param.getClassLoader());
        } catch (Throwable t) {
            XpUtils.log("Frozen[Xposed]", "onPackageReady failed: " + t);
        }
    }

    @Override
    public void onSystemServerStarting(SystemServerStartingParam param) {
        XpUtils.moduleRef = this;
        XpUtils.log("Frozen[Xposed]", "onSystemServerStarting");
        try {
            if (androidHooked.compareAndSet(false, true))
                hookAndroid(param.getClassLoader());
        } catch (Throwable t) {
            XpUtils.log("Frozen[Xposed]", "onSystemServerStarting failed: " + t);
        }
    }

    private void dispatch(String packageName, ClassLoader classLoader) {
        switch (packageName) {
            case Enum.Package.self:
                XpUtils.hookMethod("Frozen[manager]:", classLoader,
                        XC_MethodReplacement.returnConstant(true),
                        Enum.Class.self, Enum.Method.isXposedActive);
                return;
            case Enum.Package.android:
                if (androidHooked.compareAndSet(false, true))
                    hookAndroid(classLoader);
                return;
            case Enum.Package.powerkeeper:
                PowerKeeper.Hook(classLoader);
                return;
            default:
        }
    }

    public void hookAndroid(ClassLoader classLoader) {
        XpUtils.log("Frozen[Xposed]", BuildConfig.VERSION_NAME);

        Config config = new Config();

        new FreezeitService(config, classLoader);
        new AlarmHook(config, classLoader);
       // new AnrHook(config, classLoader);
        new AudioStateHook(config, classLoader);
        new PlayerBanHook(config, classLoader);
        new SendMediaButtonHook(config, classLoader);
        new CachedAppOptimizerHook(config, classLoader);
        new DisableUseFreezerHook(config, classLoader);
        new MilletDisable(config, classLoader);
        new AudioFocusHook(config, classLoader);
        new PendingIntentHook(config, classLoader);
        new ANRErrorStateHook(config, classLoader);
        new ANRHelperHooks(config, classLoader);
        new ANRHook(config, classLoader);
        new JobSchedulerHook(config, classLoader);
        new BroadcastIntentHook(config, classLoader);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM)
            new BroadcastSkipHook(config, classLoader);
        else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            new BroadcastDeliveryHook(config, classLoader);
        else
            new BroadCastHook(config, classLoader);
        new WakeLockHook(config, classLoader); //FreezeitService 的 handleWakeLock 暂时不用
    }
}

