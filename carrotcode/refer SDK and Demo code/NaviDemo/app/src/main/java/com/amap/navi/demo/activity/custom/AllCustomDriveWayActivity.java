package com.amap.navi.demo.activity.custom;

import android.os.Bundle;
import android.view.View;

import com.amap.api.maps.AMap;
import com.amap.api.maps.CameraUpdateFactory;
import com.amap.api.maps.TextureMapView;
import com.amap.api.maps.model.LatLng;
import com.amap.api.navi.enums.NaviType;
import com.amap.api.navi.model.AMapLaneInfo;
import com.amap.api.navi.model.AMapNaviLocation;
import com.amap.navi.demo.R;
import com.amap.navi.demo.activity.custom.view.CarOverlay;
import com.amap.navi.demo.activity.custom.view.DriveWayLinear;

/**
 * 包名： com.amap.navi.demo.activity.custom
 * <p>
 * 创建时间：2018/4/19
 * 项目名称：NaviDemo
 *
 * @author guibao.ggb
 * @email guibao.ggb@alibaba-inc.com
 * <p>
 * 类说明：自定义车道信息的示例
 */
public class AllCustomDriveWayActivity extends BaseCustomActivity {


    private DriveWayLinear mDriveWayView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_all_custom_drive_way_view);
        mAMapNaviView = (TextureMapView) findViewById(R.id.navi_view);
        mAMapNaviView.onCreate(savedInstanceState);

        mDriveWayView = (DriveWayLinear) findViewById(R.id.myDriveWayView);

        mAMapNaviView.getMap().getUiSettings().setZoomControlsEnabled(false);
        mAMapNaviView.getMap().setOnMapLoadedListener(new AMap.OnMapLoadedListener() {
            @Override
            public void onMapLoaded() {
                mAMapNaviView.getMap().setPointToCenter(mAMapNaviView.getWidth()/2, mAMapNaviView.getHeight()/2);

                mAMapNaviView.getMap().moveCamera(CameraUpdateFactory.zoomTo(16f));
                carOverlay = new CarOverlay(AllCustomDriveWayActivity.this, mAMapNaviView);

            }
        });


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

    /** ----- start 车道信息的回调 start ------- */
    @Override
    public void showLaneInfo(AMapLaneInfo aMapLaneInfo) {
        mDriveWayView.setVisibility(View.VISIBLE);
        mDriveWayView.buildDriveWay(aMapLaneInfo);

    }

    @Override
    public void hideLaneInfo() {
        mDriveWayView.hide();
    }
    /** ----- ebd 车道信息的回调 end -------*/


    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (mDriveWayView != null) {
            mDriveWayView.hide();
        }
    }
}
