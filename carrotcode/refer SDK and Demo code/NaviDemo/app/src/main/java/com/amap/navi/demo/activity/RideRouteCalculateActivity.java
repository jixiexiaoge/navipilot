package com.amap.navi.demo.activity;

import android.os.Bundle;

import com.amap.api.navi.AMapNaviView;
import com.amap.api.navi.enums.NaviType;
import com.amap.api.navi.enums.TransportType;
import com.amap.api.navi.model.AMapCalcRouteResult;
import com.amap.api.navi.model.AMapTravelInfo;
import com.amap.api.navi.model.NaviLatLng;
import com.amap.navi.demo.R;


public class RideRouteCalculateActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 设置是否为骑步行视图
        mAMapNavi.setIsNaviTravelView(true);

        setContentView(R.layout.activity_basic_navi);
        mAMapNaviView = (AMapNaviView) findViewById(R.id.navi_view);
        mAMapNaviView.onCreate(savedInstanceState);
        mAMapNaviView.setAMapNaviViewListener(this);
    }

    @Override
    public void onInitNaviSuccess() {
        super.onInitNaviSuccess();
        // 设置骑行导航类型
        mAMapNavi.setTravelInfo(new AMapTravelInfo(TransportType.Ride));
        mAMapNavi.calculateRideRoute(new NaviLatLng(39.925846, 116.435765), new NaviLatLng(39.925846, 116.532765));
    }

    @Override
    public void onCalculateRouteSuccess(AMapCalcRouteResult aMapCalcRouteResult) {
        super.onCalculateRouteSuccess(aMapCalcRouteResult);
        mAMapNavi.startNavi(NaviType.GPS);
    }
}
