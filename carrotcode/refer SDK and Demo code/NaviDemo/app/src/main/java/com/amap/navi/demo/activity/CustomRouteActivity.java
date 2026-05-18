package com.amap.navi.demo.activity;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;

import com.amap.api.maps.AMap;
import com.amap.api.maps.model.Polyline;
import com.amap.api.navi.AMapNaviView;
import com.amap.api.navi.AMapNaviViewOptions;
import com.amap.api.navi.MyNaviListener;
import com.amap.api.navi.enums.NaviType;
import com.amap.api.navi.model.AMapCalcRouteResult;
import com.amap.api.navi.model.AMapNaviPath;
import com.amap.api.navi.model.InnerNaviInfo;
import com.amap.api.navi.model.NaviCongestionInfo;
import com.amap.api.navi.model.NaviLatLng;
import com.amap.api.navi.model.NaviPath;
import com.amap.api.navi.model.RouteOverlayOptions;
import com.amap.api.navi.view.RouteOverLay;
import com.amap.navi.demo.R;

import java.util.ArrayList;


public class CustomRouteActivity extends BaseActivity implements MyNaviListener, AMap.OnPolylineClickListener {

    NaviLatLng wayPoint = new NaviLatLng(39.935041, 116.447901);
    NaviLatLng wayPoint1 = new NaviLatLng(39.945041, 116.447901);
    NaviLatLng wayPoint2 = new NaviLatLng(39.955041, 116.447901);
    NaviLatLng wayPoint3 = new NaviLatLng(39.965041, 116.447901);

    ArrayList<RouteOverLay> mCustomRouteOverlays = new ArrayList<RouteOverLay>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_basic_navi);
        mAMapNaviView = (AMapNaviView) findViewById(R.id.navi_view);
        mAMapNaviView.onCreate(savedInstanceState);

        AMapNaviViewOptions options = mAMapNaviView.getViewOptions();
        //关闭自动绘制路线（如果你想自行绘制路线的话，必须关闭！！！）
        options.setAutoDrawRoute(false);
        options.setTrafficLine(false);
        options.setAutoLockCar(true);
        mAMapNaviView.setViewOptions(options);
        if (mAMapNavi!=null){
            mAMapNavi.setMultipleRouteNaviMode(true);
            // 设置路线点击监听，从而点击备选路线时实现主路线的切换
            mAMapNaviView.getMap().addOnPolylineClickListener(this);
        }


    }

    @Override
    public void onInitNaviSuccess() {
        super.onInitNaviSuccess();
        /**
         * 方法: int strategy=mAMapNavi.strategyConvert(congestion, avoidhightspeed, cost, hightspeed, multipleroute); 参数:
         *
         * @congestion 躲避拥堵
         * @avoidhightspeed 不走高速
         * @cost 避免收费
         * @hightspeed 高速优先
         * @multipleroute 多路径
         *
         *  说明: 以上参数都是boolean类型，其中multipleroute参数表示是否多条路线，如果为true则此策略会算出多条路线。
         *  注意: 不走高速与高速优先不能同时为true 高速优先与避免收费不能同时为true
         */
        int strategy = 0;
        try {
            //再次强调，最后一个参数为true时代表多路径，否则代表单路径
            strategy = mAMapNavi.strategyConvert(true, false, false, false, false);
        } catch (Exception e) {
            e.printStackTrace();
        }
        mAMapNavi.calculateDriveRoute(sList, eList, mWayPointList, strategy);

    }

    @Override
    public void onCalculateRouteSuccess(AMapCalcRouteResult aMapCalcRouteResult) {
        drawCustomRoute();

        mAMapNavi.startNavi(NaviType.GPS);
    }

    private void drawCustomRoute() {
        // 清除旧路线
        clearRouteOverLay();

        // 主路线自定义纹理
        Bitmap unknownTraffic = BitmapFactory.decodeResource(getResources(), R.drawable.custtexture_no);
        Bitmap smoothTraffic = BitmapFactory.decodeResource(getResources(), R.drawable.custtexture_green);
        Bitmap slowTraffic = BitmapFactory.decodeResource(getResources(), R.drawable.custtexture_slow);
        Bitmap jamTraffic = BitmapFactory.decodeResource(getResources(), R.drawable.custtexture_bad);
        Bitmap veryJamTraffic = BitmapFactory.decodeResource(getResources(), R.drawable.custtexture_grayred);
        // 备选路线自定义纹理
        Bitmap unSelectedUnknownTraffic = BitmapFactory.decodeResource(getResources(), R.drawable.amap_navi_lbs_custtexture_no_unselected);
        Bitmap unSelectedSmoothTraffic = BitmapFactory.decodeResource(getResources(), R.drawable.amap_navi_lbs_custtexture_green_unselected);
        Bitmap unSelectedSlowTraffic = BitmapFactory.decodeResource(getResources(), R.drawable.amap_navi_lbs_custtexture_slow_unselected);
        Bitmap unSelectedJamTraffic = BitmapFactory.decodeResource(getResources(), R.drawable.amap_navi_lbs_custtexture_bad_unselected);
        Bitmap unSelectedVeryJamTraffic = BitmapFactory.decodeResource(getResources(),R.drawable.amap_navi_lbs_custtexture_serious_unselected);

        // 绘制自定义主路线
        RouteOverLay mainRouteOverlay = new RouteOverLay(mAMapNaviView.getMap(), mAMapNavi.getNaviPath(), this);
        mainRouteOverlay.setStartPointBitmap(BitmapFactory.decodeResource(this.getResources(), R.drawable.r1));
        mainRouteOverlay.setEndPointBitmap(BitmapFactory.decodeResource(this.getResources(), R.drawable.b1));
        mainRouteOverlay.setWayPointBitmap(BitmapFactory.decodeResource(this.getResources(), R.drawable.b2));

        RouteOverlayOptions options = new RouteOverlayOptions();
        options.setUnknownTraffic(unknownTraffic);
        options.setSmoothTraffic(smoothTraffic);
        options.setSlowTraffic(slowTraffic);
        options.setJamTraffic(jamTraffic);
        options.setVeryJamTraffic(veryJamTraffic);
        options.setLineWidth(60);
        mainRouteOverlay.setRouteOverlayOptions(options);
        mainRouteOverlay.addToMap();
        mCustomRouteOverlays.add(mainRouteOverlay);

        // 绘制自定义备选路线
        for (AMapNaviPath path : mAMapNavi.getNaviPaths().values()) {
            if (path.getPathid() == mAMapNavi.getNaviPath().getPathid()) {
                continue;
            }

            RouteOverLay backupRoute = new RouteOverLay(mAMapNaviView.getMap(), path, this);
            RouteOverlayOptions option = new RouteOverlayOptions();
            option.setUnknownTraffic(unSelectedUnknownTraffic);
            option.setSmoothTraffic(unSelectedSmoothTraffic);
            option.setSlowTraffic(unSelectedSlowTraffic);
            option.setJamTraffic(unSelectedJamTraffic);
            option.setVeryJamTraffic(unSelectedVeryJamTraffic);
            option.setLineWidth(50);
            backupRoute.setRouteOverlayOptions(option);
            backupRoute.setZindex(-1);
            backupRoute.showStartMarker(false);
            backupRoute.showEndMarker(false);
            backupRoute.showViaMarker(false);
            backupRoute.addToMap();
            mCustomRouteOverlays.add(backupRoute);
        }
    }

    private void clearRouteOverLay() {
        for (RouteOverLay routeOverLay : mCustomRouteOverlays) {
            routeOverLay.destroy();
        }
        mCustomRouteOverlays.clear();
    }

    @Override
    public void onPolylineClick(Polyline polyline) {
        for (RouteOverLay routeOverLay : mCustomRouteOverlays) {
            if (routeOverLay != null && routeOverLay.getPolylineIdList().contains(polyline.getId())) {
                // 拿到自定义路线对应的pathId，来切换路线
                long pathId = routeOverLay.getAMapNaviPath().getPathid();
                mAMapNavi.selectMainPathID(pathId);
            }
        }
    }

    @Override
    public void updateBackupPath(NaviPath[] naviPaths) {
        // 备选路线更新，需要重新绘制路线
        drawCustomRoute();
    }

    @Override
    public void onUpdateNaviPath() {
        // 主路线更新，需要重新绘制路线
        drawCustomRoute();
    }

    @Override
    public void onUpdateGpsSignalStrength(int i) {

    }

    @Override
    public void onInnerNaviInfoUpdate(InnerNaviInfo innerNaviInfo) {

    }

    @Override
    public void onInnerNaviInfoUpdate(InnerNaviInfo[] innerNaviInfos) {

    }

    @Override
    public void onUpdateTmcStatus(NaviCongestionInfo naviCongestionInfo) {

    }

    @Override
    public void onStopNavi() {

    }

    @Override
    public void onSelectRouteId(int i) {

    }

    @Override
    public void onSuggestChangePath(long l, long l1, int i, String s) {

    }
}
