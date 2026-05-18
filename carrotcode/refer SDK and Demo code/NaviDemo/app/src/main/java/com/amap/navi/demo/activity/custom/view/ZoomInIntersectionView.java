package com.amap.navi.demo.activity.custom.view;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.ImageView;

import com.amap.api.navi.model.AMapNaviCross;


/**
 * 路口放大图view类
 */
public class ZoomInIntersectionView extends ImageView {

    /**
     * 路口放大图原图
     */
    private Bitmap zoomInBitmap = null;

    /**
     * @exclude
     */
    public ZoomInIntersectionView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    /**
     * @exclude
     */
    public ZoomInIntersectionView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /**
     * @exclude
     */
    public ZoomInIntersectionView(Context context) {
        super(context);
    }

    /**
     * 将路口放大图类中的bitmap输入
     *
     * @param cross 路口放大图类
     */
    public void setIntersectionBitMap(AMapNaviCross cross) {
        try {
            zoomInBitmap = cross.getBitmap();
            setImageBitmap(zoomInBitmap);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    /**
     * @param event
     * @return
     * @exclude
     */
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return true;
    }

    /**
     * 资源回收
     */
    public void recycleResource() {
        try {
            if (zoomInBitmap != null) {
                zoomInBitmap.recycle();
                zoomInBitmap = null;
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

}
