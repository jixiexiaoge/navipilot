package com.amap.navi.demo.activity;

import android.graphics.Rect;
import android.os.Bundle;

import com.amap.api.navi.AMapNaviListener;
import com.amap.api.navi.AMapNaviView;
import com.amap.api.navi.AMapNaviViewOptions;
import com.amap.api.navi.enums.NaviType;
import com.amap.api.navi.enums.PathPlanningStrategy;
import com.amap.api.navi.model.AMapCalcRouteResult;
import com.amap.api.navi.view.ZoomInIntersectionView;
import com.amap.navi.demo.R;


public class CustomZoomInIntersectionViewActivity extends BaseActivity implements AMapNaviListener {

    private ZoomInIntersectionView mZoomInIntersectionView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_custom_zoomin_intersection_view);
        mZoomInIntersectionView = (ZoomInIntersectionView) findViewById(R.id.myZoomInIntersectionView);
        mAMapNaviView = (AMapNaviView) findViewById(R.id.navi_view);
        mAMapNaviView.onCreate(savedInstanceState);

        //设置布局完全不可见
        AMapNaviViewOptions options = mAMapNaviView.getViewOptions();
        options.setLayoutVisible(false);
        // 设置是否显示路口放大图(路口模型图)
        options.setModeCrossDisplayShow(true);
        // 设置是否显示路口放大图(实景图)
        options.setRealCrossDisplayShow(true);

        /**
         * 设置路口放大图的显示位置。
         * 路口放大图分为模型图和实景图，这里设置的是模型图的位置.
         * 实景图可通过自定义ZoomInIntersectionView进行设置.
         *
         *  第一个参数：横屏路口放大图显示位置。
         *  第二个参数： 竖屏路口放大图显示位置。
         */
        options.setCrossLocation(new Rect(0,30,260,300), new Rect(60,10,320,200));
        mAMapNaviView.setViewOptions(options);
        mAMapNaviView.setLazyZoomInIntersectionView(mZoomInIntersectionView);
        sList.clear();
        sList.add(p1);
    }

    @Override
    public void onInitNaviSuccess() {
        super.onInitNaviSuccess();
        mAMapNavi.calculateDriveRoute(sList, eList, null, PathPlanningStrategy.DRIVING_SHORTEST_DISTANCE);
    }

    @Override
    public void onCalculateRouteSuccess(AMapCalcRouteResult aMapCalcRouteResult) {
        super.onCalculateRouteSuccess(aMapCalcRouteResult);
        mAMapNavi.startNavi(NaviType.EMULATOR);
    }
}
