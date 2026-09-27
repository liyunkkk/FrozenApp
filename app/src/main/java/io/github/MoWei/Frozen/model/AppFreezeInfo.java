package io.github.MoWei.Frozen.model;
import android.graphics.drawable.Drawable;
import java.util.Locale;
public class AppFreezeInfo {
    public int uid;
    public String packageName;
    public String label;
    public Drawable icon;
    public int procCount;
    public int frozenCount;
    public int rssMb;
    public int swapMb;
    public boolean isSystemApp;
    public String searchKey;
    public AppFreezeInfo(int uid, String packageName, String label, Drawable icon,
                         int procCount, int frozenCount, int rssMb, int swapMb, boolean isSystemApp) {
        this.uid = uid;
        this.packageName = packageName != null ? packageName : "";
        this.label = label != null ? label : packageName;
        this.icon = icon;
        this.procCount = procCount;
        this.frozenCount = frozenCount;
        this.rssMb = rssMb;
        this.swapMb = swapMb;
        this.isSystemApp = isSystemApp;
        this.searchKey = (this.label + " " + this.packageName + " " + uid).toLowerCase(Locale.ENGLISH);
    }
    public boolean isFrozen() {
        return frozenCount > 0;
    }
    public boolean isRunning() {
        return procCount > 0;
    }
    public boolean matches(String keyword) {
        if (keyword == null || keyword.isEmpty()) return true;
        return searchKey.contains(keyword.toLowerCase(Locale.ENGLISH));
    }
}
