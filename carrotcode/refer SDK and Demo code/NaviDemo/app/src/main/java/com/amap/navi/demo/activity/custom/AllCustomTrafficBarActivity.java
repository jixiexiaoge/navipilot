package com.amap.navi.demo.activity.custom;

import android.os.Bundle;

import com.amap.api.maps.AMap;
import com.amap.api.maps.CameraUpdateFactory;
import com.amap.api.maps.TextureMapView;
import com.amap.api.maps.model.LatLng;
import com.amap.api.navi.enums.NaviType;
import com.amap.api.navi.model.AMapCalcRouteResult;
import com.amap.api.navi.model.AMapNaviLocation;
import com.amap.api.navi.model.AMapTrafficStatus;
import com.amap.api.navi.model.NaviInfo;
import com.amap.navi.demo.R;
import com.amap.navi.demo.activity.custom.view.CarOverlay;
import com.amap.navi.demo.activity.custom.view.TrafficProgressBar;

import java.util.List;

/**
 * 包名： com.amap.navi.demo.activity.custom
 * <p>
 * 创建时间：2018/4/18
 * 项目名称：NaviDemo
 *
 * @author guibao.ggb
 * @email guibao.ggb@alibaba-inc.com
 * <p>
 * 类说明：自定义路况条
 */
public class AllCustomTrafficBarActivity extends BaseCustomActivity {


    private TrafficProgressBar mTrafficBarView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_all_custom_traffic_progress_bar_view);
        mAMapNaviView = (TextureMapView) findViewById(R.id.navi_view);
        mAMapNaviView.onCreate(savedInstanceState);


        mTrafficBarView = (TrafficProgressBar) findViewById(R.id.myTrafficBar);

        mAMapNaviView.getMap().getUiSettings().setZoomControlsEnabled(false);
        mAMapNaviView.getMap().setOnMapLoadedListener(new AMap.OnMapLoadedListener() {
            @Override
            public void onMapLoaded() {
                mAMapNaviView.getMap().setPointToCenter(mAMapNaviView.getWidth()/2, mAMapNaviView.getHeight()/2);

                mAMapNaviView.getMap().moveCamera(CameraUpdateFactory.zoomTo(16f));
                carOverlay = new CarOverlay(AllCustomTrafficBarActivity.this, mAMapNaviView);

            }
        });


    }

    @Override
    public void onCalculateRouteSuccess(AMapCalcRouteResult aMapCalcRouteResult) {
        super.onCalculateRouteSuccess(aMapCalcRouteResult);
        mAMapNavi.startNavi(NaviType.EMULATOR);
    }

    /** ------- 导航基本信息的回调 ----- */
    @Override
    public void onNaviInfoUpdate(NaviInfo naviInfo) {
        super.onNaviInfoUpdate(naviInfo);
        int allLength = mAMapNavi.getNaviPath().getAllLength();

        /**
         * 导航路况条 更新
         */
        List<AMapTrafficStatus> trafficStatuses = mAMapNavi.getTrafficStatuses(0, 0);
        mTrafficBarView.update(allLength, naviInfo.getPathRetainDistance(), trafficStatuses);

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

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (carOverlay != null) {
            carOverlay.destroy();
        }

        if (routeOverLay != null) {
            routeOverLay.removeFromMap();
            routeOverLay.destroy();
        }

        if (mAMapNaviView != null) {
            mAMapNaviView.onDestroy();
        }
    }
}
