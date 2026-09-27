package io.github.MoWei.Frozen.hook;

import android.annotation.SuppressLint;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.util.HashMap;

import io.github.MoWei.Frozen.hook.XpUtils.BucketSet;
import io.github.MoWei.Frozen.hook.XpUtils.VectorSet;

import java.util.HashSet;
import java.util.Set;
public class Config {
    public final static HashMap<Integer, Set<Integer>> playingUid = new HashMap<>(512); // 正在播放音频的应用
    public static Set<Integer> AudioFocusUid = new HashSet<>(); // 正在焦点音频的应用
    public static Set<Integer> IntentUid = new HashSet<>(); // 正在广播推送 后台意图 音频意图的应用
    public int[] settings = new int[256];
    public BucketSet managedApp = new BucketSet();// 受Frozen管控的应用 只含冻结配置和杀死后台 不含自由后台
    public BucketSet permissive = new BucketSet();  // 宽松前台
    public VectorSet foregroundUid = new VectorSet(64); // 当前在前台(含宽松前台) 底层进程问询时才刷新
    public VectorSet pendingUid = new VectorSet(64);    // 切到后台暂未冻结的应用
    public HashMap<String, Integer> uidIndex = new HashMap<>(512); // UID索引

    public HashMap<Integer, String> pkgIndex = new HashMap<>(512); // 包名索引

    Field processRecordUidField,
            mCurProcStateField,
            broadcastFilterOwningUidField,
            broadcastRecordCallingUidField,
            broadcastRecordDeliveryField,
            serviceRecordDefiningUidField,
            alarmUidField,
            processRecordStateField,
            mScreenStateField;

    public boolean initField = false;

    public final boolean isCurProcStateInitialized() {
        return mCurProcStateField != null;
    }

    /**
     * 按类名查找字段, 类不存在或字段不存在都返回 null(不抛异常)。
     * 注意 getDeclaredField 不跨父类, 因此 A17 上必须显式指定字段实际所在的基类。
     */
    private static Field tryGetDeclaredField(ClassLoader classLoader, String className, String fieldName) {
        try {
            final Class<?> c = Class.forName(className, true, classLoader);
            // [A17-FIX] 沿继承链查找: Android 17 把 ProcessRecord 的 uid 等字段上移到
            // psc.ProcessRecordInternal 等基类, getDeclaredField 不跨父类会直接失败,
            // 进而抛 NoSuchFieldException 让整个 Init() 失败(实测日志:
            //  "No field uid in class Lcom/android/server/am/ProcessRecord")。
            for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
                try {
                    return k.getDeclaredField(fieldName);
                } catch (NoSuchFieldException ignored) {
                }
            }
        } catch (Throwable t) {
        }
        return null;
    }

    /** [A17-FIX] 空安全地打开字段访问权限: 字段缺失时不再抛 NPE 打断整个 Init() */
    private static void safeAccessible(Field f) {
        if (f == null) return;
        try {
            f.setAccessible(true);
        } catch (Throwable ignored) {
        }
    }

    @SuppressLint("PrivateApi")
    public String Init(ClassLoader classLoader) {
        try {
            // 需进入桌面后才能初始化
            processRecordUidField = tryGetDeclaredField(classLoader, Enum.Class.ProcessRecord, Enum.Field.uid);
            broadcastFilterOwningUidField = tryGetDeclaredField(classLoader, Enum.Class.BroadcastFilter, Enum.Field.owningUid);
            broadcastRecordCallingUidField = tryGetDeclaredField(classLoader, Enum.Class.BroadcastRecord, Enum.Field.callingUid);
            broadcastRecordDeliveryField = tryGetDeclaredField(classLoader, Enum.Class.BroadcastRecord, Enum.Field.delivery);
            serviceRecordDefiningUidField = tryGetDeclaredField(classLoader, Enum.Class.ServiceRecord, Enum.Field.definingUid);
            alarmUidField = tryGetDeclaredField(classLoader, Enum.Class.AlarmS, Enum.Field.uid);
            mScreenStateField = tryGetDeclaredField(classLoader, Enum.Class.DisplayPowerState, Enum.Field.mScreenState);

            safeAccessible(processRecordUidField);
            safeAccessible(broadcastFilterOwningUidField);
            safeAccessible(broadcastRecordCallingUidField);
            safeAccessible(broadcastRecordDeliveryField);
            safeAccessible(serviceRecordDefiningUidField);
            safeAccessible(alarmUidField);
            safeAccessible(mScreenStateField);

            // mCurProcState 的存放位置随 SDK 漂移, 两种布局依次尝试:
            //   SDK x ~ 36 : ProcessRecord.mState -> ProcessStateRecord.mCurProcState (getDeclaredField 不跨父类, 故需两跳)
            //   SDK 37+ (Android 17): ProcessStateRecord 被移除, mCurProcState 上移至 ProcessRecord 的新基类
            //                          psc.ProcessRecordInternal, 此时无需两跳
            mCurProcStateField = tryGetDeclaredField(classLoader, Enum.Class.ProcessStateRecord, Enum.Field.mCurProcState);
            if (mCurProcStateField != null) {
                processRecordStateField = tryGetDeclaredField(classLoader, Enum.Class.ProcessRecord, Enum.Field.mState);
                if (processRecordStateField != null)
                    safeAccessible(processRecordStateField);
            } else {
                // A17 布局: 直接读 ProcessRecord 实例上的 mCurProcState (字段实际定义在其基类)
                mCurProcStateField = tryGetDeclaredField(classLoader, Enum.Class.ProcessRecordInternal, Enum.Field.mCurProcState);
            }
            if (mCurProcStateField != null)
                safeAccessible(mCurProcStateField);

            // mCurProcState 是前台判定与冻结的核心依据, 取不到则整体初始化失败
            if (mCurProcStateField == null)
                throw new NoSuchFieldException(Enum.Field.mCurProcState);

            // [A17-FIX] 字段解析结果落盘, 便于定位 AOSP 漂移
            XpUtils.log("Frozen[InitField]",
                    "uid=" + (processRecordUidField != null)
                            + " owningUid=" + (broadcastFilterOwningUidField != null)
                            + " callingUid=" + (broadcastRecordCallingUidField != null)
                            + " delivery=" + (broadcastRecordDeliveryField != null)
                            + " definingUid=" + (serviceRecordDefiningUidField != null)
                            + " alarmUid=" + (alarmUidField != null)
                            + " screenState=" + (mScreenStateField != null)
                            + " mCurProcState=" + (mCurProcStateField != null)
                            + " mState=" + (processRecordStateField != null));

            initField = true;
            return "[SUCCESS]";
        } catch (Exception e) {
            initField = false;

            return "\n[ !!! FAIL !!! ]\n[ !!! 失败 !!! ]\n[ !!! FAIL !!! ]\n" +
                    (mCurProcStateField != null ? 'O' : 'X') +
                    (processRecordUidField != null ? 'O' : 'X') +
                    (broadcastFilterOwningUidField != null ? 'O' : 'X') +
                    (broadcastRecordCallingUidField != null ? 'O' : 'X') +
                    (broadcastRecordDeliveryField != null ? 'O' : 'X') +
                    (serviceRecordDefiningUidField != null ? 'O' : 'X') +
                    (alarmUidField != null ? 'O' : 'X') +
                    (processRecordStateField != null ? 'O' : 'X') +
                    (mScreenStateField != null ? 'O' : 'X') +
                    e;
        }
    }

    public final int getProcessRecordUid(@NonNull Object obj) {
        try {
            return processRecordUidField == null ? -1 : processRecordUidField.getInt(obj);
        } catch (Exception e) {
            return -1;
        }
    }

    public final Object getProcessRecordState(@NonNull Object obj) {
        // A17: processRecordStateField 为 null, mCurProcState 直接挂在 ProcessRecord 上(实际定义于基类),
        // 因此直接把 ProcessRecord 自身作为读取目标返回
        if (processRecordStateField == null) return obj;
        try {
            return processRecordStateField.get(obj);
        } catch (Exception e) {
            return null;
        }
    }

    public final int getCurProcState(@NonNull Object obj) {
        try {
            return mCurProcStateField == null ? -1 : mCurProcStateField.getInt(obj);
        } catch (Exception e) {
            return -1;
        }
    }

    public final int getBroadcastFilterOwningUid(@NonNull Object obj) {
        try {
            return broadcastFilterOwningUidField == null ? -1 : broadcastFilterOwningUidField.getInt(obj);
        } catch (Exception e) {
            return -1;
        }
    }

    public final int getBroadcastRecordCallingUid(@NonNull Object obj) {
        try {
            return broadcastRecordCallingUidField == null ? -1 : broadcastRecordCallingUidField.getInt(obj);
        } catch (Exception e) {
            return -1;
        }
    }

    public final int[] getBroadcastRecordDelivery(@NonNull Object obj) {
        try {
            return broadcastRecordDeliveryField == null ? null : (int[]) broadcastRecordDeliveryField.get(obj);
        } catch (Exception e) {
            return null;
        }
    }

    public final int getServiceRecordDefiningUid(@NonNull Object obj) {
        try {
            return serviceRecordDefiningUidField == null ? -1 : serviceRecordDefiningUidField.getInt(obj);
        } catch (Exception e) {
            return -1;
        }
    }

    public final int getAlarmUid(@NonNull Object obj) {
        try {
            return alarmUidField == null ? -1 : alarmUidField.getInt(obj);
        } catch (Exception e) {
            return -1;
        }
    }

    public final int getScreenState(@NonNull Object obj) {
        try {
            return mScreenStateField == null ? 0 : mScreenStateField.getInt(obj);
        } catch (Exception e) {
            return 0;
        }
    }

}
