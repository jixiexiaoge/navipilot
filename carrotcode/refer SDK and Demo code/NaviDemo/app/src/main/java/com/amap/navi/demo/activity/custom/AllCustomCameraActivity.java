package com.amap.navi.demo.activity.custom;

import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import com.amap.api.maps.AMap;
import com.amap.api.maps.CameraUpdateFactory;
import com.amap.api.maps.TextureMapView;
import com.amap.api.maps.model.CrossOverlay;
import com.amap.api.maps.model.CrossOverlayOptions;
import com.amap.api.maps.model.LatLng;
import com.amap.api.navi.enums.NaviType;
import com.amap.api.navi.model.AMapLaneInfo;
import com.amap.api.navi.model.AMapModelCross;
import com.amap.api.navi.model.AMapNaviCameraInfo;
import com.amap.api.navi.model.AMapNaviCross;
import com.amap.api.navi.model.AMapNaviLocation;
import com.amap.api.navi.model.AMapTrafficStatus;
import com.amap.api.navi.model.NaviInfo;
import com.amap.navi.demo.R;
import com.amap.navi.demo.activity.custom.view.AMapCameraOverlay;
import com.amap.navi.demo.activity.custom.view.CarOverlay;
import com.amap.navi.demo.activity.custom.view.DriveWayLinear;
import com.amap.navi.demo.activity.custom.view.NextTurnTipView;
import com.amap.navi.demo.activity.custom.view.TrafficProgressBar;
import com.amap.navi.demo.activity.custom.view.ZoomInIntersectionView;
import com.amap.navi.demo.util.DensityUtils;
import com.amap.navi.demo.util.NaviUtil;

import java.io.InputStream;
import java.util.List;

/**
 * 包名： com.amap.navi.demo.activity.custom
 * <p>
 * 创建时间：2018/4/24
 * 项目名称：NaviDemo
 *
 * @author guibao.ggb
 * @email guibao.ggb@alibaba-inc.com
 * <p>
 * 类说明：摄像头违章摄影示例
 */
public class AllCustomCameraActivity extends BaseCustomActivity {


    private AMapCameraOverlay cameraOverlay;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_all_custom_drive_way_view);
        mAMapNaviView = (TextureMapView) findViewById(R.id.navi_view);
        mAMapNaviView.onCreate(savedInstanceState);


        cameraOverlay = new AMapCameraOverlay(this);

        mAMapNaviView.getMap().getUiSettings().setZoomControlsEnabled(false);
        mAMapNaviView.getMap().setOnMapLoadedListener(new AMap.OnMapLoadedListener() {
            @Override
            public void onMapLoaded() {
                mAMapNaviView.getMap().setPointToCenter(mAMapNaviView.getWidth()/2, mAMapNaviView.getHeight()/2);

                mAMapNaviView.getMap().moveCamera(CameraUpdateFactory.zoomTo(16f));
                carOverlay = new CarOverlay(AllCustomCameraActivity.this, mAMapNaviView);

            }
        });


    }

    /** ------- 导航基本信息的回调 ----- */
    @Override
    public void onNaviInfoUpdate(NaviInfo naviInfo) {
        super.onNaviInfoUpdate(naviInfo);

        /**
         * 绘制转弯的箭头
         */
        drawArrow(naviInfo);

    }

    /**
     * GPS 定位信息的回调函数
     * @param location 当前定位的GPS信息
     */
    @Override
    public void onLocationChange(AMapNaviLocation location) {

        if (carOverlay != null && location != null) {
            carOverlay.draw(mAMapNaviView.getMap(), new LatLng(location.getCoord().getLatitude(), location.getCoord().getLongitude()), location.getBearing());
        }
    }

    /** ----- start 电子眼  start --------*/
    @Override
    public void updateCameraInfo(AMapNaviCameraInfo[] aMapCameraInfos) {

        if (cameraOverlay != null) {
            cameraOverlay.draw(mAMapNaviView.getMap(), aMapCameraInfos);
        }

    }

    /** ----- end 电子眼  end --------*/

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (cameraOverlay != null) {
            cameraOverlay.destroy();
        }

    }
}
