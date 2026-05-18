package com.amap.navi.demo.activity;

import android.graphics.Color;
import android.os.Bundle;

import com.amap.api.navi.AMapNaviView;
import com.amap.api.navi.AMapNaviViewOptions;
import com.amap.api.navi.enums.NaviType;
import com.amap.api.navi.model.AMapCalcRouteResult;
import com.amap.api.navi.model.AMapTrafficStatus;
import com.amap.api.navi.model.NaviInfo;
import com.amap.api.navi.view.TrafficProgressBar;
import com.amap.navi.demo.R;

import java.util.List;

public class CustomTrafficProgressBarActivity extends BaseActivity {

    private TrafficProgressBar mTrafficBarView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_custom_traffic_progress_bar_view);
        mAMapNaviView = (AMapNaviView) findViewById(R.id.navi_view);
        mAMapNaviView.onCreate(savedInstanceState);
        mAMapNaviView.setAMapNaviViewListener(this);

        //设置布局完全不可见
        AMapNaviViewOptions viewOptions = mAMapNaviView.getViewOptions();
        viewOptions.setTrafficBarEnabled(false);
        mAMapNaviView.setViewOptions(viewOptions);


        mTrafficBarView = (TrafficProgressBar) findViewById(R.id.myTrafficBar);
        mTrafficBarView.setUnknownTrafficColor(Color.parseColor("#0091FF"));
        mTrafficBarView.setSmoothTrafficColor(Color.parseColor("#00BA1F"));
        mTrafficBarView.setSlowTrafficColor(Color.parseColor("#FFBA00"));
        mTrafficBarView.setJamTrafficColor(Color.parseColor("#F31D20"));
        mTrafficBarView.setVeryJamTrafficColor(Color.parseColor("#A8090B"));

    }
    //如果你想完全自定义一个样式，你可以学习以下注释后的代码

    @Override
    public void onInitNaviSuccess() {
        super.onInitNaviSuccess();
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
    public void onNaviInfoUpdate(NaviInfo naviinfo) {
        super.onNaviInfoUpdate(naviinfo);
        int allLength = mAMapNavi.getNaviPath().getAllLength();
        List<AMapTrafficStatus> trafficStatuses = mAMapNavi.getTrafficStatuses(0, 0);
        mTrafficBarView.update(allLength, naviinfo.getPathRetainDistance(), trafficStatuses);
    }

    @Override
    public void onCalculateRouteSuccess(AMapCalcRouteResult aMapCalcRouteResult) {
        super.onCalculateRouteSuccess(aMapCalcRouteResult);
        mAMapNavi.startNavi(NaviType.EMULATOR);
    }
}
