package com.amap.navi.demo.activity.custom.view;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.os.Build;
import android.util.Log;
import android.view.Gravity;
import android.widget.TextView;

import com.amap.api.maps.AMap;
import com.amap.api.maps.model.BitmapDescriptor;
import com.amap.api.maps.model.BitmapDescriptorFactory;
import com.amap.api.maps.model.LatLng;
import com.amap.api.maps.model.Marker;
import com.amap.api.maps.model.MarkerOptions;
import com.amap.api.navi.enums.CameraType;
import com.amap.api.navi.model.AMapNaviCameraInfo;
import com.amap.navi.demo.R;
import com.amap.navi.demo.util.DensityUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 路线上的电子眼类
 *
 */
@SuppressLint("NewApi")
public class AMapCameraOverlay {
    /**
     * 电子眼图片资源
     */
    private BitmapDescriptor mCameraIcon = null;
    /**
     * 公交图片
     */
    private BitmapDescriptor mBusLeftIcon = null;
    private BitmapDescriptor mBusRightIcon = null;
    /**
     * 监控图片
     */
    private BitmapDescriptor mCameraRightIcon = null;
    private BitmapDescriptor mCameraLeftIcon = null;

    /**
     * 违章拍照<>应急车道</>
     */
    private BitmapDescriptor mYingjiRightIcon = null;
    private BitmapDescriptor mYingjiLeftIcon = null;
    /**
     * 闯红灯拍照
     */
    private BitmapDescriptor mRedRightIcon = null;
    private BitmapDescriptor mRedLeftIcon = null;

    private Map<String, List<Marker>> markerMap = new HashMap();

    private Context mContext;
    private boolean mLastFlag = false;
    private boolean isVisible = true;

    /**
     * 构造cameraoverlay
     *
     */
    public AMapCameraOverlay(Context context) {
        try {
            mCameraIcon = BitmapDescriptorFactory.fromBitmap(BitmapFactory.decodeResource(context.getResources(), R.drawable.cameraicon));
            //
            mBusLeftIcon = BitmapDescriptorFactory.fromBitmap(BitmapFactory.decodeResource(context.getResources(), R.drawable.edog_bus_lane_left));
            mBusRightIcon = BitmapDescriptorFactory.fromBitmap(BitmapFactory.decodeResource(context.getResources(), R.drawable.edog_bus_lane_right));
            //
            mCameraLeftIcon = BitmapDescriptorFactory.fromBitmap(BitmapFactory.decodeResource(context.getResources(), R.drawable.edog_camera_left));
            mCameraRightIcon = BitmapDescriptorFactory.fromBitmap(BitmapFactory.decodeResource(context.getResources(), R.drawable.edog_camera_right));
            //
            mYingjiLeftIcon = BitmapDescriptorFactory.fromBitmap(BitmapFactory.decodeResource(context.getResources(), R.drawable.edog_emergency_left));
            mYingjiRightIcon = BitmapDescriptorFactory.fromBitmap(BitmapFactory.decodeResource(context.getResources(), R.drawable.edog_emergency_right));
            //
            mRedLeftIcon = BitmapDescriptorFactory.fromBitmap(BitmapFactory.decodeResource(context.getResources(), R.drawable.edog_light_left));
            mRedRightIcon = BitmapDescriptorFactory.fromBitmap(BitmapFactory.decodeResource(context.getResources(), R.drawable.edog_light_right));
            //
            mContext = context;
        } catch (Throwable e) {
            e.printStackTrace();
        }

    }

    /**
     * 绘制路线上的监控小气泡，需要传入地图对象。
     *
     * @param aMap    地图对象
     * @param cameras 摄像头信息数组，可通过{@link com.amap.api.navi.AMapNaviListener#updateCameraInfo(AMapNaviCameraInfo[])}回调获取
     */
    public void draw(AMap aMap, AMapNaviCameraInfo[] cameras) {
        try {
            for (int i = 0; cameras != null && i < cameras.length; i++) {
                AMapNaviCameraInfo camera = cameras[i];
                if (camera.getCameraType() == 0 && camera.getCameraSpeed() == 0) {
                    return;
                }
                if (aMap == null) {
                    return;
                }
                String mapKey = camera.getX() + "-" + camera.getCameraType()
                        + "-" + camera.getY();
                if (markerMap.keySet().contains(mapKey)) {
                    Log.i("CameraOverlay",
                            "key 包含在 map 中,距离摄像头:" + camera.getCameraDistance()
                                    + "米");
                } else {
                    boolean isLeft;
                    if (mLastFlag) {
                        isLeft = false;
                        mLastFlag = false;
                    } else {
                        isLeft = true;
                        mLastFlag = true;
                    }
                    List<Marker> markers = new ArrayList<Marker>();
                    /**
                     * 电子眼类型: 0 测速摄像头 1为监控摄像头 2为闯红灯拍照 3为违章拍照 4为公交专用道摄像头
                     */
                    int cameraType = camera.getCameraType();
                    float anchorx = 0;
                    float anchory = 0;
                    if (isLeft) {
                        anchorx = 1f;
                        anchory = 0.7f;
                    } else {
                        anchorx = 0;
                        anchory = 0.7f;
                    }
                    Log.e("CameraOverlay", "key 不包含在 map 中,摄像头类型="
                            + cameraType + ",距离:" + camera.getCameraDistance());
                    switch (cameraType) {
                        case CameraType.SPEED:
                            TextView speedText = new TextView(mContext);
                            speedText.setTextColor(Color.RED);
                            speedText.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.TOP);
                            int limitSpeed = camera.getCameraSpeed();
                            if (limitSpeed > 99) {
                                speedText.setTextSize(20);
                                speedText.setPadding(0, DensityUtils.dp2px(mContext, 8),0, 0);
                            } else {
                                speedText.setTextSize(24);
                                speedText.setPadding(0, DensityUtils.dp2px(mContext, 5), 0, 0);
                            }
                            speedText.setText(String.valueOf(limitSpeed));
                            Bitmap bitmap = BitmapFactory.decodeResource(mContext.getResources(), isLeft ? R.drawable.edog_unknown_left
                                            : R.drawable.edog_unknown_right);
                            if (Build.VERSION.SDK_INT >= 17) {
                                speedText.setBackground(new BitmapDrawable(mContext.getResources(), bitmap));
                            } else {
                                speedText.setBackgroundDrawable(new BitmapDrawable(mContext.getResources(), bitmap));
                            }
                            BitmapDescriptor bitmapDes = BitmapDescriptorFactory
                                    .fromView(speedText);
                            Marker speedMarker = aMap
                                    .addMarker(new MarkerOptions()
                                            .position(new LatLng(camera.getY(), camera.getX()))
                                            .anchor(anchorx, anchory)
                                            .icon(bitmapDes));
                            speedMarker.setVisible(isVisible);
                            markers.add(speedMarker);
                            break;
                        case CameraType.SURVEILLANCE:
                        case CameraType.BREAKRULE:
                            Marker cameraMarker = aMap
                                    .addMarker(new MarkerOptions()
                                            .position(new LatLng(camera.getY(), camera.getX()))
                                            .anchor(anchorx, anchory)
                                            .icon(isLeft ? mCameraLeftIcon : mCameraRightIcon));
                            cameraMarker.setVisible(isVisible);
                            markers.add(cameraMarker);
                            break;
                        case CameraType.TRAFFICLIGHT:
                            Marker redMarker = aMap
                                    .addMarker(new MarkerOptions()
                                            .position(new LatLng(camera.getY(), camera.getX()))
                                            .anchor(anchorx, anchory)
                                            .icon(isLeft ? mRedLeftIcon : mRedRightIcon));
                            redMarker.setVisible(isVisible);
                            markers.add(redMarker);
                            break;
                        case CameraType.EMERGENCY :
                            Marker jMarker = aMap
                                    .addMarker(new MarkerOptions()
                                            .position(new LatLng(camera.getY(), camera.getX()))
                                            .anchor(anchorx, anchory)
                                            .icon(isLeft ? mYingjiLeftIcon : mYingjiRightIcon));
                            jMarker.setVisible(isVisible);
                            markers.add(jMarker);
                            break;
                        case CameraType.BUSWAY:
                            Marker busMarker = aMap
                                    .addMarker(new MarkerOptions()
                                            .position(new LatLng(camera.getY(), camera.getX()))
                                            .anchor(anchorx, anchory)
                                            .icon(isLeft ? mBusLeftIcon : mBusRightIcon));
                            busMarker.setVisible(isVisible);
                            markers.add(busMarker);
                            break;
                        default:
                            // 添加摄像头
                            Marker routeCameraMarker = aMap
                                    .addMarker(new MarkerOptions()
                                            .position(new LatLng(camera.getY(), camera.getX()))
                                            .anchor(0.5f, 0.5f).icon(mCameraIcon));
                            routeCameraMarker.setVisible(isVisible);
                            markers.add(routeCameraMarker);
                    }
                    markerMap.put(mapKey, markers);
                }
            }
            //检查地图上是否有已经经过的marker,有的话移除掉
            if (markerMap != null) {
                List<String> delKey = new ArrayList<String>();
                Set<String> sets = markerMap.keySet();
                for (String key : sets) {
                    boolean isContains = false;
                    for (int i = 0; cameras != null && i < cameras.length; i++) {
                        AMapNaviCameraInfo camera = cameras[i];
                        String mapKey = camera.getX() + "-" + camera.getCameraType()
                                + "-" + camera.getY();
                        if (mapKey.equals(key)) {
                            isContains = true;
                        }
                    }
                    if (!isContains) {
                        List<Marker> list = markerMap.get(key);
                        delKey.add(key);
                        for (int i = 0; i < list.size(); i++) {
                            Marker marker = list.get(i);
                            marker.remove();
                        }
                        list.clear();
                    }
                }
                for (int i = 0; i < delKey.size(); i++) {
                    markerMap.remove(delKey.get(i));
                }
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    /**
     * 删除所有的摄像头气泡
     */
    public void removeAllCamera() {
        try {
            if (markerMap != null) {
                Set<String> sets = markerMap.keySet();
                for (String key : sets) {
                    List<Marker> list = markerMap.get(key);
                    for (int i = 0; i < list.size(); i++) {
                        Marker marker = list.get(i);
                        marker.remove();
                    }
                    list.clear();
                }
                markerMap.clear();
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    /**
     * 销毁电子眼，释放图片资源
     */
    public void destroy() {
        try {
            if (markerMap != null) {
                removeAllCamera();
                markerMap.clear();
                ;
            }
            if (mCameraIcon != null) {
                mCameraIcon.recycle();
            }
            if (mBusLeftIcon != null) {
                mBusLeftIcon.recycle();
                mBusLeftIcon = null;
            }
            if (mBusRightIcon != null) {
                mBusRightIcon.recycle();
                mBusRightIcon = null;
            }
            if (mCameraRightIcon != null) {
                mCameraRightIcon.recycle();
                mCameraRightIcon = null;
            }
            if (mCameraLeftIcon != null) {
                mCameraLeftIcon.recycle();
                mCameraLeftIcon = null;
            }
            if (mYingjiRightIcon != null) {
                mYingjiRightIcon.recycle();
                mYingjiRightIcon = null;
            }
            if (mYingjiLeftIcon != null) {
                mYingjiLeftIcon.recycle();
                mYingjiLeftIcon = null;
            }
            if (mRedRightIcon != null) {
                mRedRightIcon.recycle();
                mRedRightIcon = null;
            }
            if (mRedLeftIcon != null) {
                mRedLeftIcon.recycle();
                mRedLeftIcon = null;
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

}
