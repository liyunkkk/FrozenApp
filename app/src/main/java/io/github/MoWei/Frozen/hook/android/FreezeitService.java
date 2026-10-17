package io.github.MoWei.Frozen.hook.android;

import static io.github.MoWei.Frozen.hook.XpUtils.log;

import android.content.Context;
import android.net.LocalServerSocket;
import android.os.Build;
import android.os.Handler;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import io.github.MoWei.Frozen.Utils;
import io.github.MoWei.Frozen.hook.Config;
import io.github.MoWei.Frozen.hook.Enum;
import io.github.MoWei.Frozen.hook.XpUtils;

public class FreezeitService {
    final static String TAG = "[Service]";

    final static String CFG_TAG = "[Config]";
    final static String AMS_TAG = "[AMS]";
    final static String NMS_TAG = "[Netd]";
    final static String WAK_TAG = "[AppOps]";
    final static String DPC_TAG = "[Display]";
    final static String FGD_TAG = "[Foreground]";
    final static String PED_TAG = "[Pending]";

    final int REPLY_SUCCESS = 2;
    final int REPLY_FAILURE = 0;
    Config config;

    ArrayList<?> mLruProcesses;

    Object mPowerState;

    Object appOpsService;
    Method setUidModeMethod;

    Object mNetdService;
    Class<?> UidRangeParcelClazz;

    // ===== 网速监控（照 Cirno 的 NetworkSpeedMonitor / FreezeExemption.NETWORK_SPEED）=====
    // 数据源：netstats 服务的 readNetworkStatsUidDetail(uid, null, -1)，返回该 uid 的
    // 全部 NetworkStats 条目，累加 rxBytes/txBytes 得到权威累计流量。
    // 比 native 侧读 netd 的 BPF map 可靠：不受 SELinux 限制，TUN/代理下也是应用自身 socket 口径。
    final static int NET_SPEED_THRESHOLD = 102400;   // 100 KiB/s，与 Cirno 默认值一致
    final static String NET_TAG = "[NetSpeed]";
    Object netStatsBinder;
    Method readNetworkStatsUidDetailMethod;
    final java.util.HashMap<Integer, long[]> netSnapshots = new java.util.HashMap<>();
    volatile boolean netStatsUnavailable = false;

    LocalSocketServer serverThread = new LocalSocketServer();

    ClassLoader classLoader;

    public FreezeitService(Config config, ClassLoader classLoader) {
        this.config = config;
        this.classLoader = classLoader;

        // [A17-FIX] 通信通道必须最先建立。
        // 原实现把 serverThread.start() 放在构造函数最后一行，前面任何一步 hook 抛异常
        // 都会让 native 侧永远连不上 @FrozenXposedServer，表现为
        // "handlePendingIntent() 工作异常, 请确认LSPosed中Frozen是否已经勾选系统框架"，
        // 且前台列表/后台意图全部拿不到，冻结功能整体停摆。
        serverThread.start();

        // 网速监控：宽松应用只要还在下载（网速 ≥ 100 KiB/s）就不允许冻结
        serverThread.startNetworkSpeedMonitor();

        // A10-13 ActivityManagerService
        // https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/services/core/java/com/android/server/am/ActivityManagerService.java
        XpUtils.hookConstructor(AMS_TAG, classLoader, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object mProcessList = XposedHelpers.getObjectField(param.thisObject, Enum.Field.mProcessList);
                mLruProcesses = mProcessList == null ? null :
                        (ArrayList<?>) XposedHelpers.getObjectField(mProcessList, Enum.Field.mLruProcesses);
                log(AMS_TAG, ((mLruProcesses == null ? "!!! Fail" : "Success")) + " init mLruProcesses");
            }
        }, Enum.Class.ActivityManagerService, Context.class, Enum.Class.ActivityTaskManagerService);


        // A10-A13 NetworkManagementService
        // https://cs.android.com/android/platform/superproject/+/android-13.0.0_r74:frameworks/base/services/core/java/com/android/server/NetworkManagementService.java

        // latest
        // https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/services/core/java/com/android/server/NetworkManagementService.java
        UidRangeParcelClazz = XposedHelpers.findClassIfExists(Enum.Class.UidRangeParcel, classLoader);
        log(NMS_TAG, ((UidRangeParcelClazz == null ? "!!! Fail" : "Success")) + " init UidRangeParcel");
        XpUtils.hookMethod(NMS_TAG, classLoader, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        mNetdService = XposedHelpers.getObjectField(param.thisObject, Enum.Field.mNetdService);
                        log(NMS_TAG, ((mNetdService == null ? "!!! Fail" : "Success")) + " init mNetdService");
                    }
                },
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ?
                        Enum.Class.NetworkManagementServiceU : Enum.Class.NetworkManagementService,
                Enum.Method.connectNativeNetdService);


        // AppOpsService
        // https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/services/core/java/com/android/server/appop/AppOpsService.java;l=1776
        setUidModeMethod = XposedHelpers.findMethodExactIfExists(
                Enum.Class.AppOpsService, classLoader, Enum.Method.setUidMode,
                int.class, int.class, int.class);
        log(WAK_TAG, ((setUidModeMethod == null ? "!!! Fail" : "Success")) + " init setUidModeMethod");

        XC_MethodHook AppOpsHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                appOpsService = param.thisObject;
                log(WAK_TAG, ((appOpsService == null ? "!!! Fail" : "Success")) + " init appOpsService");
            }
        };

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA)
            // Android 16/17: AppOpsService 构造函数为 (File, Handler, Context)
            // (Android 15 的 (File, File, Handler, Context) 重载在 A16 起已被移除)
            XpUtils.hookConstructor(WAK_TAG, classLoader, AppOpsHook, Enum.Class.AppOpsService,
                    File.class, Handler.class, Context.class);
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)  // A14-15
            XpUtils.hookConstructor(WAK_TAG, classLoader, AppOpsHook, Enum.Class.AppOpsService,
                    File.class, File.class, Handler.class, Context.class);
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)   // A11-13
            XpUtils.hookConstructor(WAK_TAG, classLoader, AppOpsHook, Enum.Class.AppOpsService,
                    File.class, Handler.class, Context.class);
        else
            XpUtils.hookConstructor(WAK_TAG, classLoader, AppOpsHook, Enum.Class.AppOpsService,
                File.class, Handler.class);


        // A10-A13 DisplayPowerController
        // https://cs.android.com/android/platform/superproject/+/android-13.0.0_r74:frameworks/base/services/core/java/com/android/server/display/DisplayPowerState.java;l=145
        XpUtils.hookMethod(DPC_TAG, classLoader, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) {
                mPowerState = XposedHelpers.getObjectField(param.thisObject, Enum.Field.mPowerState);
                log(WAK_TAG, ((mPowerState == null ? "!!! Fail" : "Success")) + " init mPowerState");
            }
        }, Enum.Class.DisplayPowerController, Enum.Method.initialize, int.class);

        // DisplayPowerController2 仅 SDK 34 ~ 36 存在, Android 17 (SDK 37) 起已移除
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA)
            XpUtils.hookMethod(DPC_TAG, classLoader, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) {
                    mPowerState = XposedHelpers.getObjectField(param.thisObject, Enum.Field.mPowerState);
                    log(WAK_TAG, ((mPowerState == null ? "!!! Fail" : "Success")) + " init mPowerState");
                }
            }, Enum.Class.DisplayPowerController2, Enum.Method.initialize, int.class);

    }

    class LocalSocketServer extends Thread {
        // Frozen命令识别码, 1668424211 是字符串"Frozen"的10进制CRC32值
        final int baseCode = 1668424211;
        final int GET_FOREGROUND = baseCode + 1;
        final int GET_SCREEN_STATE = baseCode + 2;
        final int GET_XP_LOG = baseCode + 3;
        final int SET_CONFIG = baseCode + 20;
        final int SET_WAKEUP_LOCK = baseCode + 21; // 设置唤醒锁权限
        final int BREAK_NETWORK = baseCode + 41;
        final int UPDATE_PENDING = baseCode + 60;   // 更新待冻结应用
        final int GET_AUDIO = baseCode + 81; // 获取正在播放音频的APP
        final int GET_INTENT = baseCode + 91; // 后台意图
        final int GET_NETWORK_ACTIVE = baseCode + 100; // 网速活跃的宽松应用 uid 列表

        // 有效命令集
        final Set<Integer> requestCodeSet = Set.of(
                GET_FOREGROUND,
                GET_SCREEN_STATE,
                GET_XP_LOG,
                SET_CONFIG,
                SET_WAKEUP_LOCK,
                BREAK_NETWORK,
                UPDATE_PENDING,
                GET_AUDIO,
                GET_INTENT,
                GET_NETWORK_ACTIVE
        );

        byte[] buff = new byte[128 * 1024];// 128 KiB
        LocalServerSocket mSocketServer;

        int[] uidListTemp = new int[128]; // 临时存放UID列表，不可复用
        Exception nothingException = new Exception();
        @Override
        public void run() {
            try {
                mSocketServer = new LocalServerSocket("FrozenXposedServer");
            } catch (Exception e) {
                log(TAG, "创建失败 LocalServerSocket");
                return;
            }

            int i = 5;
            while (--i > 0) {
                try {
                    sleep(3000);
                    acceptHandle();
                } catch (Throwable t) { // 捕获 Throwable 防止 Error(如 Xposed InvocationTargetError) 击穿线程杀死 system_server
                    log(TAG, "clientHandle 第" + i + " 次异常: " + t);
                    t.printStackTrace();
                }
            }
            log(TAG, "mSocketServer 异常次数过多，已退出");
        }

        @SuppressWarnings("InfiniteLoopStatement")
        void acceptHandle() throws IOException {
            while (true) {
                var client = mSocketServer.accept();//堵塞,单线程处理
                if (client == null) continue;

                client.setSoTimeout(3000);
                var is = client.getInputStream();

                final int recvLen = is.read(buff, 0, 8);
                if (recvLen != 8) {
                    log(TAG, "非法连接 接收长度 " + recvLen);
                    is.close();
                    client.close();
                    continue;
                }

                // 前4字节是请求码，后4字节是附加数据长度
                final int requestCode = Utils.Byte2Int(buff, 0);
                if (!requestCodeSet.contains(requestCode)) {
                    log(TAG, "非法请求码 " + requestCode);
                    is.close();
                    client.close();
                    continue;
                }

                final int payloadLen = Utils.Byte2Int(buff, 4);
                if (payloadLen > 0) {
                    if (buff.length <= payloadLen) {
                        log(TAG, "数据量超过承载范围 " + payloadLen);
                        is.close();
                        client.close();
                        continue;
                    }

                    int readCnt = 0;
                    while (readCnt < payloadLen) { //欲求不满
                        int cnt = is.read(buff, readCnt, payloadLen - readCnt);
                        if (cnt < 0) {
                            log(TAG, "接收完毕或错误 " + cnt);
                            break;
                        }
                        readCnt += cnt;
                    }
                    if (payloadLen != readCnt) {
                        log(TAG, "接收错误 payloadLen" + payloadLen + " readCnt" + readCnt);
                        is.close();
                        client.close();
                        continue;
                    }
                }

                var os = client.getOutputStream();
                switch (requestCode) {
                    case GET_FOREGROUND:
                        handleForeground(os, buff);
                        break;
                    case GET_SCREEN_STATE:
                        handleScreen(os, buff);
                        break;
                    case GET_XP_LOG:
                        handleXpLog(os);
                        break;
                    case SET_CONFIG:
                        handleConfig(os, buff, payloadLen);
                        break;
                    case SET_WAKEUP_LOCK:
                        handleWakeupLock(os, buff, payloadLen);
                        break;
                    case BREAK_NETWORK:
                        handleDestroySocket(os, buff, payloadLen);
                        break;
                    case UPDATE_PENDING:
                        handlePendingApp(os, buff, payloadLen);
                        break;
                    case GET_AUDIO:
                        handleGetAudio(os, buff);
                        break;
                    case GET_INTENT:
                        handleGetIntentApps(os, buff);
                        break;
                    case GET_NETWORK_ACTIVE:
                        handleNetworkActive(os, buff);
                        break;
                    default:
                        log(TAG, "请求码功能暂未实现TODO: " + requestCode);
                        break;
                }
                client.close();
            }
        }

        // ===== 网速监控（照 Cirno 的 NetworkSpeedMonitor）=====
        // 只轮询宽松应用（permissiveList），每秒一次；网速 ≥ NET_SPEED_THRESHOLD 的 uid
        // 写进 config.networkActiveUid，native 侧通过 GET_NETWORK_ACTIVE 拉取后放行。
        void startNetworkSpeedMonitor() {
            Thread t = new Thread(() -> {
                while (true) {
                    try {
                        pollNetworkSpeed();
                    } catch (Throwable e) {
                        log(NET_TAG, "轮询异常: " + e);
                    }
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ignored) {
                    }
                }
            }, "FrozenNetSpeed");
            t.setDaemon(true);
            t.start();
            log(NET_TAG, "网速监控已启动 阈值=" + NET_SPEED_THRESHOLD + "B/s");
        }

        void pollNetworkSpeed() {
            if (netStatsUnavailable) return;

            if (netStatsBinder == null) {
                try {
                    Class<?> sm = Class.forName("android.os.ServiceManager");
                    Method getService = sm.getDeclaredMethod("getService", String.class);
                    netStatsBinder = getService.invoke(null, "netstats");
                } catch (Throwable e) {
                    netStatsUnavailable = true;
                    log(NET_TAG, "取 netstats 服务失败: " + e);
                    return;
                }
                if (netStatsBinder == null) {
                    netStatsUnavailable = true;
                    log(NET_TAG, "netstats 服务不存在");
                    return;
                }
                log(NET_TAG, "netstats 已连接");
            }

            if (readNetworkStatsUidDetailMethod == null) {
                try {
                    readNetworkStatsUidDetailMethod = netStatsBinder.getClass()
                            .getDeclaredMethod("readNetworkStatsUidDetail",
                                    int.class, String[].class, int.class);
                    readNetworkStatsUidDetailMethod.setAccessible(true);
                } catch (Throwable e) {
                    netStatsUnavailable = true;
                    log(NET_TAG, "找不到 readNetworkStatsUidDetail: " + e);
                    return;
                }
            }

            if (config.permissiveList.isEmpty()) return;

            final long now = System.currentTimeMillis();
            config.networkActiveUid.clear();

            for (int i = 0; i < config.permissiveList.size(); i++) {
                final int uid = config.permissiveList.get(i);
                try {
                    Object stats = readNetworkStatsUidDetailMethod.invoke(netStatsBinder, uid, null, -1);
                    if (stats == null) continue;

                    long rx = 0, tx = 0;
                    final int size = (int) XposedHelpers.callMethod(stats, "size");
                    Object entry = null;
                    for (int k = 0; k < size; k++) {
                        entry = XposedHelpers.callMethod(stats, "getValues", k, entry);
                        if (entry == null) break;
                        rx += (long) XposedHelpers.getObjectField(entry, "rxBytes");
                        tx += (long) XposedHelpers.getObjectField(entry, "txBytes");
                    }

                    final long[] prev = netSnapshots.get(uid);
                    netSnapshots.put(uid, new long[]{rx, tx, now});
                    if (prev == null) continue;

                    final long dt = now - prev[2];
                    if (dt <= 0) continue;

                    long speed = ((rx - prev[0]) + (tx - prev[1])) * 1000L / dt;
                    if (speed < 0) speed = 0;

                    if (speed >= NET_SPEED_THRESHOLD)
                        config.networkActiveUid.add(uid);
                } catch (Throwable ignored) {
                    // 单个 uid 读取失败不影响其它应用
                }
            }
        }

        void handleNetworkActive(OutputStream os, byte[] replyBuff) throws IOException {
            // 与 handleForeground 同格式: [count][uid1][uid2]...
            final int n = config.networkActiveUid.size();
            Utils.Int2Byte(n, replyBuff, 0);
            config.networkActiveUid.toBytes(replyBuff, 4);
            os.write(replyBuff, 0, (n + 1) * 4);
            os.close();
        }

        void handleForeground(OutputStream os, byte[] replyBuff) throws IOException {
            config.foregroundUid.clear();

            final int lruSize = (mLruProcesses == null) ? 0 : mLruProcesses.size();
            if (lruSize == 0 || !config.isCurProcStateInitialized()) {
                // 拿不到 LRU 时必须留痕，否则 native 侧只会看到"前台为空"然后照冻不误
                log(FGD_TAG, "前台列表不可用 lruSize=" + lruSize
                        + " procStateInit=" + config.isCurProcStateInitialized());
            }

            try {
                // AOSP ProcessList.updateLruProcessLocked() 是 mLruProcesses.add(0, app)：
                // index 0 = 最近活跃，index 越大越老。原实现从 size-1 倒着扫到 11，
                // 恰好跳过最活跃的 11 个进程（后台下载的前台服务都在里面），宽松前台因此失效。
                for (int i = 0; i < lruSize; i++) {
                    var processRecord = mLruProcesses.get(i);
                    if (processRecord == null) continue;

                    final int uid = config.getProcessRecordUid(processRecord);// processRecord

                    if (!config.managedApp.contains(uid))
                        continue;

                    var mState = config.getProcessRecordState(processRecord);
                    if (mState == null) continue;
                    int mCurProcState = config.getCurProcState(mState);

                    // 2在顶层 3绑定了顶层应用, 有前台服务:4常驻状态栏 6悬浮窗
                    // ProcessStateEnum: https://cs.android.com/android/platform/superproject/main/+/main:out/soong/.intermediates/frameworks/base/framework-minus-apex/android_common/xref35/srcjars.xref/android/app/ProcessStateEnum.java;l=10
                    if ((0 <= mCurProcState && mCurProcState <= 3) ||
                            (4 <= mCurProcState && mCurProcState <= 6 && config.permissive.contains(uid)))
                        config.foregroundUid.add(uid);
                }
            } catch (Exception e) {
                log(FGD_TAG, "前台服务错误: " + e);
                e.printStackTrace();
            }

            // 开头的4字节放置UID的个数，往后每4个字节放一个UID  [小端]
            int replyLen = (config.foregroundUid.size() + 1) * 4;
            Utils.Int2Byte(config.foregroundUid.size(), replyBuff, 0);
            config.foregroundUid.toBytes(replyBuff, 4);

            os.write(replyBuff, 0, replyLen);
            os.close();
        }


        // 0未知 1息屏 2亮屏 3Doze...
        void handleScreen(OutputStream os, byte[] replyBuff) throws IOException {
            /*
            https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/core/java/android/view/Display.java;l=387
            enum DisplayStateEnum
            public static final int DISPLAY_STATE_UNKNOWN = 0;
            public static final int DISPLAY_STATE_OFF = 1;
            public static final int DISPLAY_STATE_ON = 2;
            public static final int DISPLAY_STATE_DOZE = 3; //亮屏但处于Doze的非交互状态状态
            public static final int DISPLAY_STATE_DOZE_SUSPEND = 4; // 同上，但CPU不控制显示，由协处理器或其他控制
            public static final int DISPLAY_STATE_VR = 5;
            public static final int DISPLAY_STATE_ON_SUSPEND = 6; //非Doze, 类似4
             */

            if (mPowerState == null) {
                Utils.Int2Byte(0, replyBuff, 0);
                log("屏幕状态", "mPowerState 未初始化");
            } else {
                final int mScreenState = config.getScreenState(mPowerState);
                Utils.Int2Byte(mScreenState, replyBuff, 0);
//                log("屏幕状态", String.valueOf(mScreenState));
            }

            os.write(replyBuff, 0, 4);
            os.close();
        }
        void handleXpLog(OutputStream os) throws IOException {
            os.write(XpUtils.xpLogContent.toString().getBytes());
            os.close();
        }

        /**
         * 总共 2或3 行内容
         * 第一行：冻它设置数据
         * 第二行：受冻它管控的应用 只含杀死后台和冻结配置， 不含自由后台、白名单
         * 第三行：宽松前台UID列表 只含杀死后台和冻结配置， 不含自由后台、白名单 (此行可能为空)
         */
        void handleConfig(OutputStream os, byte[] buff, int payloadLen) throws IOException {
            var splitLine = new String(buff, 0, payloadLen).split("\n");
            if (splitLine.length != 2 && splitLine.length != 3) {
                log(CFG_TAG, "Fail splitLine.length:" + splitLine.length);
                for (String line : splitLine)
                    log(CFG_TAG, "START:" + line);

                Utils.Int2Byte(REPLY_FAILURE, buff, 0);
                os.write(buff, 0, 4);
                os.close();
                return;
            }

            config.managedApp.clear();
            config.uidIndex.clear();
            config.pkgIndex.clear();
            config.permissive.clear();
            config.permissiveList.clear();

            try {
                StringBuilder tmp = new StringBuilder("Parse:");

                String[] elementList = splitLine[0].split(" ");
                for (int i = 0; i < elementList.length; i++)
                    config.settings[i] = Integer.parseInt(elementList[i]);
                tmp.append(" settings:").append(elementList.length);

                elementList = splitLine[1].split(" ");
                for (String element : elementList) {
                    if (element.length() <= 5)
                        continue;
                    // element: "10xxxpackName"
                    final int uid = Integer.parseInt(element.substring(0, 5));
                    final String packName = element.substring(5);
                    config.managedApp.add(uid);
                    config.uidIndex.put(packName, uid);
                    config.pkgIndex.put(uid, packName);
                }
                tmp.append(" managedApp:").append(config.managedApp.size());
                tmp.append(" uidIndex:").append(config.uidIndex.size());
                tmp.append(" pkgIndex:").append(config.pkgIndex.size());
                config.pkgIndex.put(1000, "AndroidSystem");
                config.pkgIndex.put(-1, "Unknown");

                if (splitLine.length == 3) {
                    elementList = splitLine[2].split(" ");
                    for (String uidStr : elementList) {
                        if (uidStr.isEmpty()) continue;
                        try {
                            final int u = Integer.parseInt(uidStr);
                            config.permissive.add(u);
                            config.permissiveList.add(u);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
                tmp.append(" permissive:").append(config.permissive.size());

                log(CFG_TAG, tmp.toString());
                Utils.Int2Byte(REPLY_SUCCESS, buff, 0);
            } catch (Exception e) {
                log(CFG_TAG, "Exception: [" + Arrays.toString(splitLine) + "]: \n" + e);
                Utils.Int2Byte(REPLY_FAILURE, buff, 0);
            }

            os.write(buff, 0, 4);
            os.close();

            if (!config.initField) {
                config.Init(classLoader);
                log("Frozen[InitField]", config.Init(classLoader));
            }
        }


        /**
         * <a href="https://cs.android.com/android/platform/superproject/+/android-mainline-10.0.0_r9:out/soong/.intermediates/frameworks/base/framework-minus-apex/android_common/xref35/srcjars.xref/android/app/AppProtoEnums.java;l=84">...</a>
         * WAKEUP_LOCK CODE is [40] A10-A13
         * public static final String[] MODE_NAMES = new String[] {
         * "allow",        // MODE_ALLOWED
         * "ignore",       // MODE_IGNORED
         * "deny",         // MODE_ERRORED
         * "default",      // MODE_DEFAULT
         * "foreground",   // MODE_FOREGROUND
         * };
         */
        void handleWakeupLock(OutputStream os, byte[] buff, int payloadLen) throws IOException {
            final int WAKEUP_LOCK_IGNORE = 1;
            final int WAKEUP_LOCK_DEFAULT = 3;
            final int WAKEUP_LOCK_CODE = 40;

            try {
                if (setUidModeMethod == null || appOpsService == null) {
                    log(WAK_TAG, "未初始化 setUidModeMethod appOps");
                    throw nothingException;
                }

                if (payloadLen <= 8 || payloadLen % 4 != 0) {
                    log(WAK_TAG, "非法 payloadLen " + payloadLen);
                    throw nothingException;
                }

                final int uidLen = Utils.Byte2Int(buff, 0);
                if (payloadLen != (uidLen + 2) * 4) {
                    log(WAK_TAG, "非法 payloadLen " + payloadLen + " uidLen " + uidLen);
                    throw nothingException;
                }

                final int mode = Utils.Byte2Int(buff, 4); // 1:ignore  3:default
                if (mode != WAKEUP_LOCK_IGNORE && mode != WAKEUP_LOCK_DEFAULT) {
                    log(WAK_TAG, "非法mode:" + mode);
                    throw nothingException;
                }

                Utils.Byte2Int(buff, 8, uidLen * 4, uidListTemp, 0);
                for (int i = 0; i < uidLen; i++) {
                    final int uid = uidListTemp[i];
                    if (config.managedApp.contains(uid))
                        setUidModeMethod.invoke(appOpsService, WAKEUP_LOCK_CODE, uid, mode);
                    else
                        log(WAK_TAG, "非法UID:" + uid);
                }

                if (XpUtils.DEBUG_WAKEUP_LOCK) {
                    var tmp = new StringBuilder(mode == WAKEUP_LOCK_IGNORE ? "禁止 WakeLock: " : "恢复 WakeLock: ");
                    for (int i = 0; i < uidLen; i++) {
                        final int uid = uidListTemp[i];
                        tmp.append(config.pkgIndex.getOrDefault(uid, String.valueOf(uid))).append(", ");
                    }
                    log(WAK_TAG, tmp.toString());
                }

                if (mode == WAKEUP_LOCK_IGNORE) // 此操作会在息屏超时后触发
                    config.foregroundUid.clear();

                Utils.Int2Byte(REPLY_SUCCESS, buff, 0);
            } catch (Exception e) {
                Utils.Int2Byte(REPLY_FAILURE, buff, 0);
            }

            os.write(buff, 0, 4);
            os.close();
        }

        void handleDestroySocket(OutputStream os, byte[] buff, int payloadLen) throws IOException {
            try {
                if (payloadLen != 4) {
                    log(NMS_TAG, "非法 payloadLen " + payloadLen);
                    throw nothingException;
                }

                final int uid = Utils.Byte2Int(buff, 0);
                if (!config.managedApp.contains(uid)) {
                    log(NMS_TAG, "非法UID" + uid);
                    throw nothingException;
                }
                if (mNetdService == null) {
                    log(NMS_TAG, "mNetdService null");
                    throw nothingException;
                }
                if (UidRangeParcelClazz == null) {
                    log(NMS_TAG, "UidRangeParcelClazz null");
                    throw nothingException;
                }

                // Android 16 (SDK 36) 起 netd 的 socketDestroy 接口抛 UnsupportedOperationException,
                // 且 XposedHelpers 会将其包装为继承自 Error 的 InvocationTargetError,
                // 可击穿 catch(Exception) 直接杀死 system_server, 故 SDK 36+ 跳过该调用 (降级: 仅冻结不断网)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                    log(NMS_TAG, "SDK 36+ skip socketDestroy (netd unsupported on A16), uid=" + uid);
                    Utils.Int2Byte(REPLY_SUCCESS, buff, 0);
                } else {
                    Object uidRanges = Array.newInstance(UidRangeParcelClazz, 1);
                    Array.set(uidRanges, 0, XposedHelpers.newInstance(UidRangeParcelClazz, uid, uid));
                    XposedHelpers.callMethod(mNetdService, Enum.Method.socketDestroy, uidRanges, new int[0]);
                    Utils.Int2Byte(REPLY_SUCCESS, buff, 0);
                }
            } catch (Throwable t) { // 必须捕获 Throwable: InvocationTargetError 继承自 Error 而非 Exception
                log(NMS_TAG, "socketDestroy failed: " + t);
                Utils.Int2Byte(REPLY_FAILURE, buff, 0);
            }

            os.write(buff, 0, 4);
            os.close();
        }

        void handlePendingApp(OutputStream os, byte[] buff, int payloadLen) throws IOException {
            try {
                if (payloadLen % 4 != 0) {
                    log(PED_TAG, "非法 payloadLen " + payloadLen);
                    throw nothingException;
                }

                final int uidLen = payloadLen / 4;

                config.pendingUid.clear();
                if (payloadLen > 0) {
                    Utils.Byte2Int(buff, 0, payloadLen, uidListTemp, 0);
                    for (int i = 0; i < uidLen; i++) {
                        final int uid = uidListTemp[i];
                        if (config.managedApp.contains(uid))
                            config.pendingUid.add(uid);
                        else
                            log(PED_TAG, "非法UID:" + uid);
                    }
                }

                if (XpUtils.DEBUG_PENDING_UID) {
                    var tmp = new StringBuilder("待冻结更新: ");
                    for (int i = 0; i < uidLen; i++) {
                        final int uid = uidListTemp[i];
                        tmp.append(config.pkgIndex.getOrDefault(uid, String.valueOf(uid))).append(", ");
                    }
                    log(TAG, tmp.toString());
                }

                Utils.Int2Byte(REPLY_SUCCESS, buff, 0);
            } catch (Exception e) {
                Utils.Int2Byte(REPLY_FAILURE, buff, 0);
            }

            os.write(buff, 0, 4);
            os.close();
        }
        void handleGetAudio(OutputStream os, byte[] buff) throws IOException {
            try {
                int[] uids = Config.AudioFocusUid.stream().mapToInt(Integer::intValue).toArray();

                if (uids.length == 0) {
                    Utils.Int2Byte(REPLY_SUCCESS, buff, 0);
                    os.write(buff,  0, 4);
                    return;
                }

                for (int i = 0; i < uids.length; i++) {
                    Utils.Int2Byte(uids[i], buff, i * 4);
                }

                Utils.Int2Byte(REPLY_SUCCESS, buff, uids.length * 4);

                os.write(buff, 0, (uids.length + 1) * 4);
            } catch (Exception e) {
                Utils.Int2Byte(REPLY_FAILURE, buff, 0);
                os.write(buff, 0, 4);
            }
            os.close();
        }

        void handleGetIntentApps(OutputStream os, byte[] buff) throws IOException {
            synchronized (Config.IntentUid) {
                try {
                    int[] uids = Config.IntentUid.stream().mapToInt(Integer::intValue).toArray();

                    if (uids == null || uids.length == 0) {
                        Utils.Int2Byte(REPLY_SUCCESS, buff, 0);
                        os.write(buff, 0, 4);
                        return;
                    }

                    for (int i = 0; i < uids.length; i++) {
                        Utils.Int2Byte(uids[i], buff, i * 4);
                    }

                    Utils.Int2Byte(REPLY_SUCCESS, buff, uids.length * 4);
                    os.write(buff, 0, (uids.length + 1) * 4);
                    Config.IntentUid.clear();

                } catch (Exception e) {
                    Utils.Int2Byte(REPLY_FAILURE, buff, 0);
                    os.write(buff, 0, 4);
                    Config.IntentUid.clear();
                } finally {
                try {
                    os.close();
                } catch (IOException ignore) {}
                }
            }
        }
    }
}