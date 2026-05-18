package com.amap.navi.demo.activity.custom.view;

import android.content.Context;
import android.graphics.BitmapFactory;

import com.amap.api.maps.AMap;
import com.amap.api.maps.AMapUtils;
import com.amap.api.maps.CameraUpdateFactory;
import com.amap.api.maps.TextureMapView;
import com.amap.api.maps.model.BitmapDescriptor;
import com.amap.api.maps.model.BitmapDescriptorFactory;
import com.amap.api.maps.model.CameraPosition;
import com.amap.api.maps.model.LatLng;
import com.amap.api.maps.model.Marker;
import com.amap.api.maps.model.MarkerOptions;
import com.amap.api.maps.model.Polyline;
import com.amap.navi.demo.R;
import com.amap.navi.demo.util.BasicThreadFactory;
import com.amap.navi.demo.util.NaviUtil;
import com.autonavi.amap.mapcore.IPoint;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 包名： com.amap.api.navi.core
 * <p>
 * 创建时间：2018/3/1
 * 项目名称：AndroidNavigationSDK
 *
 * @author guibao.ggb
 * @email guibao.ggb@alibaba-inc.com
 * <p>
 * 类说明：自车位置管理Overlay类
 */
public class CarOverlay {

    protected static final int CAR_MOVE_ANIMATION_PERIOD = 50;
    protected int carMoveAnimationFrameNum = 2;
    protected boolean mIsLock = true;
    protected IPoint mapAnchorBackup = null;
    protected double dXOffStep;
    protected double dYOffStep;
    protected float dAngleOffStep;
    protected int currentFrameIndex;
    protected float angleStart = 0;
    protected boolean isMoveStarted = false;
    protected float newAngle = 0;
    protected BitmapDescriptor carDescriptor = null;
    protected BitmapDescriptor fourCornersDescriptor = null;
    protected Marker carMarker;
    protected Marker directionMarker;
    protected AMap mAmap = null;
    protected TextureMapView mapView;
    protected boolean isDirectionVisible = true;
    protected LatLng endLatLng = null;
    protected Polyline leaderLine = null;
    protected final int DISTANCE_OFFSET = 150;// 默认 500 偏差

    // API 默认 1800  UI 默认  360
    protected int angleModValue = 1800;


    private ScheduledExecutorService executorService;

    public CarOverlay(Context context, TextureMapView mapView) {
        this.mapView = mapView;

        fourCornersDescriptor = BitmapDescriptorFactory.fromBitmap(BitmapFactory
                .decodeResource(context.getResources(),
                        R.drawable.navi_direction));

        carDescriptor = BitmapDescriptorFactory.fromBitmap(BitmapFactory
                .decodeResource(context.getResources(),
                        R.drawable.caricon));
        angleModValue = 1800;

    }

    /**
     * 设置自车状态
     *
     * @param lock true 锁车 false 非锁车
     */
    public void setLock(boolean lock) {
        mIsLock = lock;
        if (carMarker == null) {
            return;
        }
        if (mAmap == null) {
            return;
        }
        if (directionMarker == null) {
            return;
        }
        carMarker.setFlat(true);
        directionMarker.setGeoPoint(carMarker.getGeoPoint());
        carMarker.setGeoPoint(carMarker.getGeoPoint());
        carMarker.setRotateAngle(carMarker.getRotateAngle());
        if (mIsLock) {
            CameraPosition cameraPosition = new CameraPosition.Builder().target(carMarker.getPosition()).bearing(newAngle).tilt(0).zoom(16).build();
            mAmap.moveCamera(CameraUpdateFactory.newCameraPosition(cameraPosition));
        }
    }

    public void reset() {
        if (carMarker != null) {
            carMarker.remove();
        }
        if (directionMarker != null) {
            directionMarker.remove();
        }
        if (leaderLine != null) {
            leaderLine.remove();
        }
        leaderLine = null;
        carMarker = null;
        directionMarker = null;

        if (executorService != null) {
            if (!executorService.isShutdown()) {
                executorService.shutdown();
            }
            isMoveStarted = false;

            executorService = null;
        }
    }

    /**
     * 绘制自车
     *
     * @param aMap
     * @param mLatLng
     * @param bearing
     */
    public void draw(AMap aMap, LatLng mLatLng, float bearing) {
        if (aMap == null || mLatLng == null || carDescriptor == null) {
            return;
        }
        mAmap = aMap;
        try {
            if (carMarker == null) {
                carMarker = aMap.addMarker(new MarkerOptions().anchor(0.5f, 0.5f).setFlat(true).icon(carDescriptor).position(mLatLng));
            }

            if (directionMarker == null) {
                directionMarker = aMap.addMarker(new MarkerOptions().anchor(0.5f, 0.5f).setFlat(true).icon(fourCornersDescriptor).position(mLatLng));
                if (isDirectionVisible) {
                    directionMarker.setVisible(true);
                } else {
                    directionMarker.setVisible(false);
                }
            }
            carMarker.setVisible(true);
            newAngle = bearing;
            IPoint resultGeoPnt = IPoint.obtain();
            resultGeoPnt = NaviUtil.lonlat2Geo(mLatLng.latitude, mLatLng.longitude, 20);
            updateCarPosition(resultGeoPnt);
            resultGeoPnt.recycle();

        } catch (Throwable e) {
            e.printStackTrace();
        }
    }


    private void updateCarPosition(IPoint p) {
        carMarker.setGeoPoint(p);
        carMarker.setFlat(true);
        carMarker.setRotateAngle(360 - newAngle);
        if (directionMarker != null) {
            directionMarker.setGeoPoint(p);
        }

        if (mIsLock) {
            CameraPosition cameraPosition = new CameraPosition.Builder().target(carMarker.getPosition()).bearing(newAngle).tilt(0).zoom(16).build();
            mAmap.moveCamera(CameraUpdateFactory.changeBearingGeoCenter(newAngle, p));
        }
    }


    public void setEndPoi(LatLng latlng) {
        endLatLng = latlng;
    }

    /**
     * 释放自车资源
     */
    public void destroy() {
        if (carMarker != null) {
            carMarker.remove();
            carMarker = null;
        }
        if (directionMarker != null) {
            directionMarker.remove();
            directionMarker = null;
        }
        carDescriptor = null;

        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
            isMoveStarted = false;

            executorService = null;
        }
    }

    private void calculateCarSmoothMoveOffset(IPoint newCenter, float newAngle) {
        if (carMarker == null) {
            return;
        }
        IPoint currentAnchorGeoPoint = carMarker.getGeoPoint();
        if (currentAnchorGeoPoint == null || currentAnchorGeoPoint.x == 0 || currentAnchorGeoPoint.y == 0) {
            currentAnchorGeoPoint = newCenter;
        }
        currentFrameIndex = 0;
        mapAnchorBackup = currentAnchorGeoPoint;
        dXOffStep = (newCenter.x - currentAnchorGeoPoint.x) / carMoveAnimationFrameNum;
        dYOffStep = (newCenter.y - currentAnchorGeoPoint.y) / carMoveAnimationFrameNum;
        // 获取当前的旋转角度
        angleStart = carMarker.getRotateAngle();
        boolean isFirst = false;

        if (Float.compare(angleStart, newAngle) == 0) {
            isFirst = true;
        } else {
            angleStart = 360 - angleStart;
        }
        // 校正旋转角度问题
        float dAngleDelta = newAngle - angleStart;
        if (isFirst) {
            dAngleDelta = 0;
        }
        if (dAngleDelta > 180) {
            dAngleDelta = dAngleDelta - 360;
        }
        else if (dAngleDelta < -180) {
            dAngleDelta = dAngleDelta + 360;
        }
        dAngleOffStep = dAngleDelta / carMoveAnimationFrameNum;
        isMoveStarted = true;
    }

    protected void startSmoothMoveTimer() {
        if (executorService == null) {
            executorService = new ScheduledThreadPoolExecutor(1, new BasicThreadFactory.Builder().namingPattern("caroverlay-schedule-pool-%d").daemon(true).build());

            executorService.scheduleAtFixedRate(new Runnable() {
                long currentSeconds;
                @Override
                public void run() {
                    try{
                        currentSeconds = System.currentTimeMillis();
                        mapSmoothMoveTimerTick();
                    } catch(Throwable e){
                        e.printStackTrace();
                    }
                }
            }, 0, CAR_MOVE_ANIMATION_PERIOD, TimeUnit.MILLISECONDS);
        }
    }

    private void mapSmoothMoveTimerTick() {
        if (!isMoveStarted) {
            return;
        }
        if (carMarker == null) {
            return;
        }
        if (mAmap == null) {
            return;
        }
        try {
            IPoint p = carMarker.getGeoPoint();
            double newX = 0, newY = 0;
            if (currentFrameIndex++ < carMoveAnimationFrameNum) {
                newX = mapAnchorBackup.x + dXOffStep * currentFrameIndex;
                newY = mapAnchorBackup.y + dYOffStep * currentFrameIndex;
                newAngle = angleStart + dAngleOffStep * currentFrameIndex;
                newAngle %= angleModValue;
                if (newX != 0 || newY != 0) {
                    p = new IPoint((int)newX, (int)newY);
                }
                updateCarPosition(p);
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

}
