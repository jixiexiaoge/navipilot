package com.amap.navi.demo.activity;

import android.os.Bundle;
import android.util.Log;

import com.amap.api.navi.AMapNaviView;
import com.amap.api.navi.AMapNaviViewOptions;
import com.amap.api.navi.enums.NaviType;
import com.amap.api.navi.model.AMapCalcRouteResult;
import com.amap.api.navi.model.AMapCarInfo;
import com.amap.api.navi.model.NaviLatLng;
import com.amap.navi.demo.R;

public class MotorcycleRouteCalculateActivity extends BaseActivity{
    private static final String TAG = MotorcycleRouteCalculateActivity.class.getSimpleName();

    private AMapCarInfo aMapCarInfo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_basic_navi);
        mAMapNaviView = (AMapNaviView) findViewById(R.id.navi_view);
        mAMapNaviView.onCreate(savedInstanceState);
        mAMapNaviView.setAMapNaviViewListener(this);

        aMapCarInfo = getIntent().getParcelableExtra("info");

        AMapNaviViewOptions aMapNaviViewOptions = mAMapNaviView.getViewOptions();
        aMapNaviViewOptions.setAfterRouteAutoGray(true);
        mAMapNaviView.setViewOptions(aMapNaviViewOptions);
    }

    @Override
    public void onInitNaviSuccess() {
        super.onInitNaviSuccess();
        Log.e(TAG, "onInitNaviSuccess: ");
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

        if (aMapCarInfo == null) {
            aMapCarInfo = new AMapCarInfo();
        }

        aMapCarInfo.setCarType("11");//设置车辆类型，0小车，1货车, 11摩托车
        aMapCarInfo.setCarNumber("京B5W369");//设置车辆的车牌号码. 如:京DFZ239,京ABZ239
        aMapCarInfo.setMotorcycleCC(4); // 排量
        aMapCarInfo.setRestriction(true);//设置是否躲避车辆限行。
        mAMapNavi.setCarInfo(aMapCarInfo);

        int strategy = 0;
        try {
            //再次强调，最后一个参数为true时代表多路径，否则代表单路径
            strategy = mAMapNavi.strategyConvert(true, false, false, false, false);
        } catch (Exception e) {
            e.printStackTrace();
        }

        sList.clear();
        eList.clear();
        NaviLatLng mStartLatlng = new NaviLatLng(39.894914, 116.322062);
        NaviLatLng mEndLatlng = new NaviLatLng(39.903785, 116.423285);

        sList.add(mStartLatlng);
        eList.add(mEndLatlng);
        mAMapNavi.calculateDriveRoute(sList, eList, mWayPointList, strategy);
    }



    @Override
    public void onCalculateRouteSuccess(AMapCalcRouteResult aMapCalcRouteResult) {
        super.onCalculateRouteSuccess(aMapCalcRouteResult);
        mAMapNavi.startNavi(NaviType.EMULATOR); //模拟驾驶：EMULATOR， 实时导航：GPS， 巡航模式：CRUISE
    }
}
