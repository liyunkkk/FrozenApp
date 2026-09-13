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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.MoWei.Frozen.model.AppFreezeInfo;

public class FreezeDetailScanner {
    private static final String TAG = "FreezeScanner";

    private static long sLastScanTime = 0;
    private static List<AppFreezeInfo> sCachedResult = null;

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

        SparseArray<StatItem> statMap = scanViaRoot();
        if (statMap == null || statMap.size() == 0) {
            statMap = scanViaSocket();
        }

        List<AppFreezeInfo> result = new ArrayList<>();
        Set<Integer> visitedUids = new HashSet<>();

        // 1. 先加入扫描到运行/冻结中的应用
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
                    String label = String.valueOf(item.uid);
                    result.add(new AppFreezeInfo(
                            item.uid,
                            "uid." + item.uid,
                            label,
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

        // 2. 补充已安装的未运行应用，便于搜索
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

        // 3. 排序：冻结中优先 > 运行中 (按内存降序) > 未运行应用
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

    private static SparseArray<StatItem> scanViaRoot() {
        SparseArray<StatItem> map = new SparseArray<>();
        Process process = null;
        try {
            process = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(process.getOutputStream());
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

            String cmd = "toybox ps -A -o UID,PID,RSS,NAME,WCHAN | awk 'BEGIN {while((getline f<"/sys/fs/cgroup/frozen/cgroup.procs")>0) fr[f]=1} NR>1 && $1>=10000 {u=$1; p[u]++; r[u]+=$3; if (fr[$2] || index($5,"do_freezer")>0) fc[u]++; while((getline s<("/proc/"$2"/status"))>0) {if (s ~ /^VmSwap:/) {split(s, a); sw[u]+=a[2]; break}}; close("/proc/"$2"/status")} END {for (u in p) print u, p[u], fc[u]+0, int(r[u]/1024), int(sw[u]/1024)}'\n" +
                    "exit\n";
            os.write(cmd.getBytes(StandardCharsets.UTF_8));
            os.flush();

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split("\s+");
                if (parts.length >= 5) {
                    try {
                        int uid = Integer.parseInt(parts[0]);
                        int procs = Integer.parseInt(parts[1]);
                        int frozen = Integer.parseInt(parts[2]);
                        int rss = Integer.parseInt(parts[3]);
                        int swap = Integer.parseInt(parts[4]);
                        map.put(uid, new StatItem(uid, procs, frozen, rss, swap));
                    } catch (Exception ignored) {
                    }
                }
            }
            process.waitFor();
            if (map.size() > 0) return map;
        } catch (Exception e) {
            Log.w(TAG, "Root scan failed, fallback to socket: " + e.getMessage());
        } finally {
            if (process != null) {
                try {
                    process.destroy();
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private static SparseArray<StatItem> scanViaSocket() {
        SparseArray<StatItem> map = new SparseArray<>();
        try {
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

                String[] p = l.split("\s+");
                if (p.length < 3) continue;

                boolean isFrozen = l.contains("冻结");

                // 新版守护进程格式: PID RSS SWAP UID 状态 进程名 (前4项均为数字)
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
                    } catch (Exception ignored) {
                    }
                }

                // 旧版守护进程格式兼容: PID RSS 状态 进程名 (前2项为数字)
                if (isInteger(p[0]) && isInteger(p[1])) {
                    try {
                        int mib = Integer.parseInt(p[1]);
                        String appName = p[p.length - 1];
                        int targetUid = findUidByAppName(appName);
                        if (targetUid != -1) {
                            StatItem existing = map.get(targetUid);
                            if (existing != null) {
                                existing.procCount++;
                                if (isFrozen) existing.frozenCount++;
                                existing.rssMb += mib;
                            } else {
                                map.put(targetUid, new StatItem(targetUid, 1, isFrozen ? 1 : 0, mib, 0));
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Socket fallback scan failed: " + e.getMessage());
        }
        return map;
    }

    private static boolean isInteger(String str) {
        if (str == null || str.isEmpty()) return false;
        for (int i = 0; i < str.length(); i++) {
            if (!Character.isDigit(str.charAt(i))) return false;
        }
        return true;
    }

    private static int findUidByAppName(String appName) {
        List<Integer> uids = AppInfoCache.getUidList();
        for (int uid : uids) {
            AppInfoCache.Info info = AppInfoCache.get(uid);
            if (info != null && (info.label.equals(appName) || info.packName.contains(appName))) {
                return uid;
            }
        }
        return -1;
    }
}
