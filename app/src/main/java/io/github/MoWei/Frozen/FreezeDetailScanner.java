package io.github.MoWei.Frozen;

import android.content.Context;
import android.util.Log;
import android.util.SparseArray;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.MoWei.Frozen.model.AppFreezeInfo;

public class FreezeDetailScanner {
    private static final String TAG = "FreezeScanner";
    private static long sLastScanTime = 0;
    private static List<AppFreezeInfo> sCachedResult = null;
    private static final Map<Integer, String> sFakeUidNames = new HashMap<>();

    public static class StatItem {
        public int uid;
        public int procCount;
        public int frozenCount;
        public int rssMb;
        public int swapMb;

        public StatItem(int uid, int procCount, int frozenCount, int rssMb, int swapMb) {
            this.uid = uid;
            this.procCount = procCount;
            this.frozenCount = frozenCount;
            this.rssMb = rssMb;
            this.swapMb = swapMb;
        }
    }

    public static List<AppFreezeInfo> getFastInstalledList() {
        List<AppFreezeInfo> result = new ArrayList<>();
        List<Integer> allUids = AppInfoCache.getUidList();
        for (int uid : allUids) {
            AppInfoCache.Info info = AppInfoCache.get(uid);
            if (info != null && !info.isSystemApp) {
                result.add(new AppFreezeInfo(
                        uid,
                        info.packName,
                        info.label,
                        info.icon,
                        0,
                        0,
                        0,
                        0,
                        info.isSystemApp
                ));
            }
        }
        Collections.sort(result, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return result;
    }

    public static List<AppFreezeInfo> scan(Context context) {
        return scan(context, false);
    }

    public static synchronized List<AppFreezeInfo> scan(Context context, boolean forceRefresh) {
        long now = System.currentTimeMillis();
        if (!forceRefresh && sCachedResult != null && (now - sLastScanTime < 3000)) {
            return sCachedResult;
        }

        if (AppInfoCache.getUidList().isEmpty()) {
            AppInfoCache.refreshCache(context);
        }

        // 1. 优先通过本地 Socket 向 Frozen 守护进程索取权威进程快照 (零日志副作用，零 Root 依赖)
        SparseArray<StatItem> statMap = scanViaSocket();

        // 2. 若守护进程未响应，降级尝试通过 Root 执行系统级扫描
        if (statMap == null || statMap.size() == 0) {
            statMap = scanViaRoot();
        }

        List<AppFreezeInfo> result = new ArrayList<>();
        Set<Integer> visitedUids = new HashSet<>();

        // 3. 将扫描到的正在运行/已冻结的应用加入列表
        if (statMap != null) {
            for (int i = 0; i < statMap.size(); i++) {
                StatItem item = statMap.valueAt(i);
                visitedUids.add(item.uid);

                AppInfoCache.Info info = AppInfoCache.get(item.uid);
                if (info != null) {
                    result.add(new AppFreezeInfo(
                            item.uid,
                            info.packName,
                            info.label,
                            info.icon,
                            item.procCount,
                            item.frozenCount,
                            item.rssMb,
                            item.swapMb,
                            info.isSystemApp
                    ));
                } else {
                    String fallbackLabel = sFakeUidNames.containsKey(item.uid) ?
                            sFakeUidNames.get(item.uid) : ("UID " + item.uid);
                    result.add(new AppFreezeInfo(
                            item.uid,
                            "proc." + item.uid,
                            fallbackLabel,
                            null,
                            item.procCount,
                            item.frozenCount,
                            item.rssMb,
                            item.swapMb,
                            false
                    ));
                }
            }
        }

        // 4. 补充已安装的未运行应用，便于全局搜索与白名单配置
        List<Integer> allUids = AppInfoCache.getUidList();
        for (int uid : allUids) {
            if (!visitedUids.contains(uid)) {
                AppInfoCache.Info info = AppInfoCache.get(uid);
                if (info != null && !info.isSystemApp) {
                    result.add(new AppFreezeInfo(
                            uid,
                            info.packName,
                            info.label,
                            info.icon,
                            0,
                            0,
                            0,
                            0,
                            info.isSystemApp
                    ));
                }
            }
        }

        // 5. 排序：冻结中优先 > 运行中 (按内存占用降序) > 未运行应用 (按名称首字母)
        Collections.sort(result, (a, b) -> {
            if (a.isFrozen() != b.isFrozen()) {
                return a.isFrozen() ? -1 : 1;
            }
            if (a.isRunning() != b.isRunning()) {
                return a.isRunning() ? -1 : 1;
            }
            if (a.rssMb != b.rssMb) {
                return Integer.compare(b.rssMb, a.rssMb);
            }
            return a.label.compareToIgnoreCase(b.label);
        });

        sCachedResult = result;
        sLastScanTime = now;
        return result;
    }

    private static SparseArray<StatItem> scanViaSocket() {
        SparseArray<StatItem> map = new SparseArray<>();
        try {
            // 命令 62 (printFreezerProc) 底层 server.hpp 中 logToGlobal=false，零日志副作用
            int len = Utils.freezeitTask(ManagerCmd.printFreezerProc, null);
            if (len <= 0) return map;

            String text = new String(StaticData.response, 0, len, StandardCharsets.UTF_8);
            int pIdx = text.lastIndexOf("进程冻结状态:");
            String sec = (pIdx != -1) ? text.substring(pIdx) : text;
            String[] lines = sec.split("\n");

            for (String l : lines) {
                l = l.trim();
                if (l.isEmpty() || l.startsWith("进程冻结") || l.startsWith("PID") || l.startsWith("总计") || l.startsWith("后台很干净") || l.startsWith("发现")) {
                    continue;
                }

                String[] p = l.split("\\s+");
                if (p.length < 3) continue;

                boolean isFrozen = l.contains("冻结");

                // 1. 标准新格式: PID(0) RSS(1) SWAP(2) UID(3) 状态(4) 进程名(5...)
                if (p.length >= 5 && isInteger(p[0]) && isInteger(p[1]) && isInteger(p[2]) && isInteger(p[3])) {
                    try {
                        int rss = Integer.parseInt(p[1]);
                        int swap = Integer.parseInt(p[2]);
                        int uid = Integer.parseInt(p[3]);

                        StatItem existing = map.get(uid);
                        if (existing != null) {
                            existing.procCount++;
                            if (isFrozen) existing.frozenCount++;
                            existing.rssMb += rss;
                            existing.swapMb += swap;
                        } else {
                            map.put(uid, new StatItem(uid, 1, isFrozen ? 1 : 0, rss, swap));
                        }
                        continue;
                    } catch (Exception ignored) {}
                }

                // 2. 当前守护进程真实格式: PID(0) RSS(1) 状态(2) 应用名(3...)
                if (p.length >= 4 && isInteger(p[0]) && isInteger(p[1])) {
                    try {
                        int rss = Integer.parseInt(p[1]);
                        StringBuilder nameBuilder = new StringBuilder();
                        for (int k = 3; k < p.length; k++) {
                            if (nameBuilder.length() > 0) nameBuilder.append(" ");
                            nameBuilder.append(p[k]);
                        }
                        String appName = nameBuilder.toString();

                        int targetUid = findUidByAppName(appName);
                        if (targetUid != -1) {
                            StatItem existing = map.get(targetUid);
                            if (existing != null) {
                                existing.procCount++;
                                if (isFrozen) existing.frozenCount++;
                                existing.rssMb += rss;
                            } else {
                                map.put(targetUid, new StatItem(targetUid, 1, isFrozen ? 1 : 0, rss, 0));
                            }
                        } else {
                            // 若系统未查到 UID，生成伪 UID 兜底，绝不丢弃任何被冻结应用
                            int fakeUid = 90000 + Math.abs(appName.hashCode() % 9000);
                            sFakeUidNames.put(fakeUid, appName);
                            StatItem existing = map.get(fakeUid);
                            if (existing != null) {
                                existing.procCount++;
                                if (isFrozen) existing.frozenCount++;
                                existing.rssMb += rss;
                            } else {
                                map.put(fakeUid, new StatItem(fakeUid, 1, isFrozen ? 1 : 0, rss, 0));
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Socket scan failed: " + e.getMessage());
        }
        return map;
    }

    private static SparseArray<StatItem> scanViaRoot() {
        SparseArray<StatItem> map = new SparseArray<>();
        Process process = null;
        try {
            process = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(process.getOutputStream());
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

            String cmd = "export PATH=/system/bin:/system/xbin:$PATH\n" +
                    "cat /sys/fs/cgroup/frozen/cgroup.procs 2>/dev/null\n" +
                    "echo ---FROZEN_END---\n" +
                    "ps -A -o UID,PID,RSS,WCHAN\n" +
                    "exit\n";
            os.write(cmd.getBytes(StandardCharsets.UTF_8));
            os.flush();

            String line;
            boolean readingFrozen = true;
            Set<Integer> frozenPids = new HashSet<>();
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if ("---FROZEN_END---".equals(line)) {
                    readingFrozen = false;
                    continue;
                }

                if (readingFrozen) {
                    try {
                        frozenPids.add(Integer.parseInt(line));
                    } catch (Exception ignored) {}
                    continue;
                }

                if (line.startsWith("UID")) continue;

                String[] parts = line.split("\\s+");
                if (parts.length >= 3) {
                    try {
                        int uid = Integer.parseInt(parts[0]);
                        if (uid < 10000) continue;
                        int pid = Integer.parseInt(parts[1]);
                        int rssKb = Integer.parseInt(parts[2]);
                        String wchan = parts.length >= 4 ? parts[3] : "";

                        boolean isFrozen = frozenPids.contains(pid) || wchan.contains("do_freezer");

                        StatItem item = map.get(uid);
                        if (item == null) {
                            item = new StatItem(uid, 0, 0, 0, 0);
                            map.put(uid, item);
                        }
                        item.procCount++;
                        if (isFrozen) {
                            item.frozenCount++;
                        }
                        item.rssMb += (rssKb / 1024);
                    } catch (Exception ignored) {}
                }
            }
            process.waitFor();
            if (map.size() > 0) return map;
        } catch (Exception e) {
            Log.w(TAG, "Root scan failed: " + e.getMessage());
        } finally {
            if (process != null) {
                try {
                    process.destroy();
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private static boolean isInteger(String str) {
        if (str == null || str.isEmpty()) return false;
        for (int i = 0; i < str.length(); i++) {
            if (!Character.isDigit(str.charAt(i))) return false;
        }
        return true;
    }

    private static int findUidByAppName(String rawName) {
        if (rawName == null || rawName.trim().isEmpty()) return -1;
        String appName = rawName.trim();
        int colon = appName.indexOf(':');
        String baseName = colon != -1 ? appName.substring(0, colon).trim() : appName;

        List<Integer> uids = AppInfoCache.getUidList();

        // 1. 精确匹配应用标签 (label)
        for (int uid : uids) {
            AppInfoCache.Info info = AppInfoCache.get(uid);
            if (info != null && (info.label.equalsIgnoreCase(appName) || info.label.equalsIgnoreCase(baseName))) {
                return uid;
            }
        }

        // 2. 忽略空格/大小写匹配 (例如 "Scene Cracked" 匹配 "Scene")
        String noSpaceBase = baseName.replace(" ", "").toLowerCase();
        for (int uid : uids) {
            AppInfoCache.Info info = AppInfoCache.get(uid);
            if (info != null) {
                String infoLabelNoSpace = info.label.replace(" ", "").toLowerCase();
                if (infoLabelNoSpace.equals(noSpaceBase) ||
                        noSpaceBase.contains(infoLabelNoSpace) ||
                        infoLabelNoSpace.contains(noSpaceBase)) {
                    return uid;
                }
            }
        }

        // 3. 匹配包名 (package name)
        for (int uid : uids) {
            AppInfoCache.Info info = AppInfoCache.get(uid);
            if (info != null) {
                String pkg = info.packName.toLowerCase();
                if (pkg.contains(noSpaceBase) || noSpaceBase.contains(pkg)) {
                    return uid;
                }
            }
        }
        return -1;
    }
}