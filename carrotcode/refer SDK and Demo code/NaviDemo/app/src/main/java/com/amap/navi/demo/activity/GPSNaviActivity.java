package com.amap.navi.demo.activity;

import android.os.Bundle;

import com.amap.api.navi.AMapNaviView;
import com.amap.api.navi.AMapNaviViewOptions;
import com.amap.api.navi.enums.NaviType;
import com.amap.api.navi.model.AMapCalcRouteResult;
import com.amap.api.navi.model.AMapCarInfo;
import com.amap.navi.demo.R;

public class GPSNaviActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 驾车和骑步行视图切换时，需要重置视图类型，避免底层不同视图处理异常。
        mAMapNavi.setIsNaviTravelView(false);

        setContentView(R.layout.activity_basic_navi);
        mAMapNaviView = (AMapNaviView) findViewById(R.id.navi_view);
        mAMapNaviView.onCreate(savedInstanceState);
        mAMapNaviView.setAMapNaviViewListener(this);
        if (mAMapNavi!=null){
            mAMapNavi.getNaviSetting().setScreenAlwaysBright(false);

        }
    }

    @Override
    public void onInitNaviSuccess() {
        super.onInitNaviSuccess();

        int strategy = 0;
        try {
            strategy = mAMapNavi.strategyConvert(true, false, false, false, false);
        } catch (Exception e) {
            e.printStackTrace();
        }
        AMapCarInfo carInfo = new AMapCarInfo();
        carInfo.setCarNumber("京DFZ588");
        mAMapNavi.setCarInfo(carInfo);
        mAMapNavi.calculateDriveRoute(sList, eList, mWayPointList, strategy);

    }

    @Override
    public void onCalculateRouteSuccess(AMapCalcRouteResult aMapCalcRouteResult) {
        super.onCalculateRouteSuccess(aMapCalcRouteResult);
        mAMapNavi.startNavi(NaviType.GPS);
    }
}
