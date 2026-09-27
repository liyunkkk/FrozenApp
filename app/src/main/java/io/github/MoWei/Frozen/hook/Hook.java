package io.github.MoWei.Frozen.hook;

import android.os.Build;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;
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

/**
 * [A17-FIX] 回到 legacy 入口。
 *
 * 背景(实测, Android 17 / LSPosed v2.2.0):
 *   - 存在 META-INF/xposed/java_init.list 时, LSPosed 把模块判为 modern(legacy=false),
 *     只走 LSPosedContext 的 modern 通道; 此时框架侧的 legacy bridge 未就绪,
 *     业务代码里 de.robv.android.xposed.XC_MethodHook 等 legacy 类在 system_server 中
 *     解析失败, 抛出:
 *       NoClassDefFoundError: Failed resolution of: LQgpc/T/LGCnILscJx/GR/y/XC_MethodHook;
 *     导致 hookAndroid() 第一步即中断(此前表现为 @FrozenXposedServer 永不创建,
 *     native 侧报 "handlePendingIntent() 工作异常")。
 *   - 反例: 纯 legacy 模块(只有 assets/xposed_init, 无 java_init.list)如 BackgroundOpt
 *     在本机工作正常, 证明 legacy 通道在 Android 17 上依然可用。
 *
 * 因此删除 java_init.list 并让入口类实现 IXposedHookLoadPackage, 复用全部既有 hook 代码。
 */
public class Hook implements IXposedHookLoadPackage {

    private static final java.util.concurrent.atomic.AtomicBoolean androidHooked =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** [A17-FIX] 无参构造器: 被框架实例化的瞬间就留下痕迹 */
    public Hook() {
        XpUtils.log("Frozen[Xposed]", "entry constructed (legacy)");
    }

    @Override
    public void handleLoadPackage(LoadPackageParam lpParam) {
        try {
            XpUtils.log("Frozen[Xposed]", "handleLoadPackage: " + lpParam.packageName);
            dispatch(lpParam.packageName, lpParam.classLoader);
        } catch (Throwable t) {
            XpUtils.log("Frozen[Xposed]", "handleLoadPackage failed: " + t);
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

