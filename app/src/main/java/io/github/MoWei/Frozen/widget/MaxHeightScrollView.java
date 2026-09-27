package io.github.MoWei.Frozen.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ScrollView;

public class MaxHeightScrollView extends ScrollView {
    private int maxHeight = 0;

    public MaxHeightScrollView(Context context) {
        super(context);
        init(context);
    }

    public MaxHeightScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public MaxHeightScrollView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        // 限制内容滚动区域最大不超过屏幕高度的 52%
        maxHeight = (int) (context.getResources().getDisplayMetrics().heightPixels * 0.52);
    }

    public void setMaxHeight(int maxHeight) {
        this.maxHeight = maxHeight;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (maxHeight > 0) {
            int hSize = MeasureSpec.getSize(heightMeasureSpec);
            int hMode = MeasureSpec.getMode(heightMeasureSpec);
            if (hMode == MeasureSpec.UNSPECIFIED || hSize > maxHeight) {
                heightMeasureSpec = MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST);
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}
