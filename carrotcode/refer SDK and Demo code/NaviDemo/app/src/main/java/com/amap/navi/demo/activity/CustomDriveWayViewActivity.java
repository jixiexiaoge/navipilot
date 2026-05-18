package com.amap.navi.demo.activity;


import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import com.amap.api.navi.AMapNaviListener;
import com.amap.api.navi.AMapNaviView;
import com.amap.api.navi.AMapNaviViewOptions;
import com.amap.api.navi.enums.NaviType;
import com.amap.api.navi.model.AMapCalcRouteResult;
import com.amap.api.navi.model.AMapLaneInfo;
import com.amap.api.navi.model.NaviLatLng;
import com.amap.api.navi.view.DriveWayView;
import com.amap.navi.demo.R;

public class CustomDriveWayViewActivity extends BaseActivity implements AMapNaviListener {
    private DriveWayView mDriveWayView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        //为了能最快的看到效果
        mStartLatlng = new NaviLatLng(39.92458861111111, 116.43543861111111);
        setContentView(R.layout.activity_custom_drive_way_view);
        mAMapNaviView = (AMapNaviView) findViewById(R.id.navi_view);
        mAMapNaviView.onCreate(savedInstanceState);

        //设置布局完全不可见
        AMapNaviViewOptions options = mAMapNaviView.getViewOptions();
        options.setLayoutVisible(false);
        mAMapNaviView.setViewOptions(options);

        mDriveWayView = (DriveWayView) findViewById(R.id.myDriveWayView);
        mAMapNaviView.setLazyDriveWayView(mDriveWayView);

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
    }

    /**
     * 已废弃
     * @param laneInfos
     * @param laneBackgroundInfo
     * @param laneRecommendedInfo
     */
    @Override
    public void showLaneInfo(AMapLaneInfo[] laneInfos, byte[] laneBackgroundInfo, byte[] laneRecommendedInfo) {
    }

    @Override
    public void showLaneInfo(AMapLaneInfo aMapLaneInfo) {

        StringBuffer sb = new StringBuffer();
        sb.append("共" + aMapLaneInfo.frontLane.length + "车道");
        for (int i = 0; i < aMapLaneInfo.frontLane.length; i++) {
            //当前车道可以选择的动作
            int background = aMapLaneInfo.backgroundLane[i];
            //当前用户要执行的动作
            int recommend = aMapLaneInfo.frontLane[i];

            Log.e("ggb", "---->>> background is " + background + " ; recommend is " + recommend);
            //根据文档中每个动作对应的枚举类型，显示对应的图片
            try {
                sb.append("，第" + (i + 1) + "车道为" + array[background]);
                if (recommend != 255) {
                    sb.append("，当前车道可 " + actions[recommend]);
                }

            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        Log.e("showLaneInfo", sb.toString());
        Toast.makeText(this, sb.toString(), Toast.LENGTH_LONG).show();
    }

    /**
     * 车道信息说明：
     * <p>
     * 0xFF, 无对应车道
     * 0,    直行
     * 1,    左转
     * 2,    直行+左转
     * 3,    右转
     * 4,    直行+右转
     * 5,    左掉头
     * 6,    左转+右转
     * 7,    直行+左转+右转
     * 8,    右掉头
     * 9,    直行+左掉头
     * 10,   直行+右掉头
     * 11,   左转+左掉头
     * 12,   右转+右掉头
     * 13,   直行+扩展
     * 14,   左转+左掉头+扩展
     * 15,   保留
     * 16,   直行+左转+左掉头
     * 17,   右转+左掉头
     * 18,   左转+右转+左掉头
     * 19,   直行+右转+左掉头
     * 20,   左转+右掉头
     * 21,   公交车道
     * 22,   空车道
     * 23    可变车道
     */

    String[] array = {
            "直行车道"
            , "左转车道"
            , "左转或直行车道"
            , "右转车道"
            , "右转或直行车道"
            , "左掉头车道"
            , "左转或者右转车道"
            , " 左转或右转或直行车道"
            , "右转掉头车道"
            , "直行或左转掉头车道"
            , "直行或右转掉头车道"
            , "左转或左掉头车道"
            , "右转或右掉头车道"
            , "直行并且车道扩展"
            , "左转+左掉头+扩展"
            , "不可以选择该车道"
            , "直行+左转+左掉头车道"
            , "右转+左掉头"
            , "左转+右转+左掉头"
            , "直行+右转+左掉头"
            , "左转+右掉头"
            , "公交车道"
            , "空车道"
            , "可变车道"
    };

    String[] actions = {
              "直行"
            , "左转"
            , "左转或直行"
            , "右转"
            , "右转或这行"
            , "左掉头"
            , "左转或者右转"
            , " 左转或右转或直行"
            , "右转掉头"
            , "直行或左转掉头"
            , "直行或右转掉头"
            , "左转或左掉头"
            , "右转或右掉头"
            , "直行并且车道扩展"
            , "左转+左掉头+扩展"
            , "不可以选择"
            , "直行+左转+左掉头"
            , "右转+左掉头"
            , "左转+右转+左掉头"
            , "直行+右转+左掉头"
            , "左转+右掉头"
            , "公交车道"
            , "空车道"
            , "可变车道"
    };


    @Override
    public void hideLaneInfo() {
    }
}

