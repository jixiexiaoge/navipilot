package com.amap.navi.demo.activity;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import com.amap.api.navi.AMapNaviView;
import com.amap.api.navi.ParallelRoadListener;
import com.amap.api.navi.enums.AMapNaviParallelRoadStatus;
import com.amap.api.navi.enums.NaviType;
import com.amap.api.navi.model.AMapCalcRouteResult;
import com.amap.api.navi.model.NaviLatLng;
import com.amap.navi.demo.R;

public class SwitchMasterRoadNaviActivity extends BaseActivity implements ParallelRoadListener {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sList.clear();
        sList.add(new NaviLatLng(40.023002, 116.367429));
        setContentView(R.layout.activity_switch_master_navi);
        mAMapNaviView = (AMapNaviView) findViewById(R.id.navi_view);
        mAMapNaviView.onCreate(savedInstanceState);
        mAMapNaviView.setAMapNaviViewListener(this);
        if (mAMapNavi!=null){
            mAMapNavi.addParallelRoadListener(this);
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
        mAMapNavi.startNavi(NaviType.GPS);
    }

    /**
     * 注意：主辅路切换只适用于 GPS实际导航， 模拟导航是失效的。
     * @param view
     */
    public void switchMasterRoad(View view) {
        // 1 主辅路切换，2高架上下切换
        if(paralleFlag != 0){
            mAMapNavi.switchParallelRoad(paralleFlag);
        }
    }


    private void showToast(String toast) {
        Toast.makeText(this, toast, Toast.LENGTH_LONG).show();
    }
    int paralleFlag = 0;

    /**
     * 需要GPS导航有GPS点进来才会有正确的主辅路切换回调
     * @param aMapNaviParallelRoadStatus
     */
    @Override
    public void notifyParallelRoad(AMapNaviParallelRoadStatus aMapNaviParallelRoadStatus) {
        //高架上下标识（默认0） 0：无高架 1：车标在高架上（车标所在道路有对应高架下） 2：车标在高架下（车标所在道路有对应高架上）
        if(aMapNaviParallelRoadStatus.getmElevatedRoadStatusFlag() == 1){
            paralleFlag = 2;
            showToast("车标在高架上（车标所在道路有对应高架下）");
        }else if(aMapNaviParallelRoadStatus.getmElevatedRoadStatusFlag() == 2){
            paralleFlag = 2;
            showToast("车标在高架下（车标所在道路有对应高架上）");
        }

        //主辅路标识（默认0） 0：无主辅路（车标所在道路旁无主辅路） 1：车标在主路（车标所在道路旁有辅路） 2：车标在辅路（车标所在道路旁有主路）
        if(aMapNaviParallelRoadStatus.getmParallelRoadStatusFlag() == 1){
            paralleFlag = 1;
            showToast("车标在主路（车标所在道路旁有辅路）");
        }else if(aMapNaviParallelRoadStatus.getmParallelRoadStatusFlag() == 2){
            paralleFlag = 1;
            showToast("车标在辅路（车标所在道路旁有主路）");
        }

        if(aMapNaviParallelRoadStatus.getmParallelRoadStatusFlag() == 0 && aMapNaviParallelRoadStatus.getmElevatedRoadStatusFlag() == 0){
            paralleFlag = 0;
        }
    }
}
