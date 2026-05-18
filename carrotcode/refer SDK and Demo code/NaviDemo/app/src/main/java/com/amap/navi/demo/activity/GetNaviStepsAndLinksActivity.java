package com.amap.navi.demo.activity;

import android.graphics.Color;
import android.os.Bundle;
import android.support.v4.app.ActionBarDrawerToggle;
import android.support.v4.view.GravityCompat;
import android.support.v4.widget.DrawerLayout;
import android.util.Log;
import android.view.View;

import com.amap.api.navi.AMapNaviView;
import com.amap.api.navi.enums.NaviType;
import com.amap.api.navi.model.AMapCalcRouteResult;
import com.amap.api.navi.model.AMapNaviRouteGuideGroup;
import com.amap.navi.demo.R;
import com.amap.navi.demo.activity.view.NaviGuideWidget;

import java.util.ArrayList;
import java.util.List;


public class GetNaviStepsAndLinksActivity extends BaseActivity {


    private DrawerLayout drawerLayout;
    private ActionBarDrawerToggle toggle;

    private NaviGuideWidget guideWidget;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_basic_navi_1);
        mAMapNaviView = (AMapNaviView) findViewById(R.id.navi_view);
        mAMapNaviView.onCreate(savedInstanceState);
        mAMapNaviView.setAMapNaviViewListener(this);

        guideWidget = (NaviGuideWidget) findViewById(R.id.route_select_guidelist);

        initDrawerLayout();
    }

    private void initDrawerLayout() {
        drawerLayout = (DrawerLayout) super.findViewById(R.id.drawer_layout);
        drawerLayout.setScrimColor(Color.TRANSPARENT);
    }

    public void openDetailRoute(View view) {
        if (drawerLayout.isDrawerOpen(GravityCompat.END)){
            drawerLayout.closeDrawer(GravityCompat.END);
        } else{
            drawerLayout.openDrawer(GravityCompat.END);
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
        super.onCalculateRouteSuccess(aMapCalcRouteResult);
        mAMapNavi.startNavi(NaviType.EMULATOR);

        //主路线概览
        List<AMapNaviRouteGuideGroup> guideList = mAMapNavi.getNaviPath().getNaviGuideList();
        for (int i = 0; i < guideList.size(); i++) {
            //guide step相生相惜，指的是大导航段
            AMapNaviRouteGuideGroup guide = guideList.get(i);
            Log.d("wlx", "AMapNaviRouteGuideGroup 路线名:" + guide.getGroupName() + "");
            Log.d("wlx", "AMapNaviRouteGuideGroup 路线长:" + guide.getGroupLen() + "m");
            Log.d("wlx", "AMapNaviRouteGuideGroup 路线IconType" + guide.getGroupIconType() + "");
            Log.d("wlx", "AMapNaviRouteGuideGroup 红绿灯数量" + guide.getTrafficLightsCount() + "");
        }

        ArrayList<AMapNaviRouteGuideGroup> groups = new ArrayList<>(guideList);
        guideWidget.setGuideData("新发地", "首都国际机场", groups);
    }
}
