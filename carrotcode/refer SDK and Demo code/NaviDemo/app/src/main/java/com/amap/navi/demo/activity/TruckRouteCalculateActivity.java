package com.amap.navi.demo.activity;

import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import com.amap.api.navi.AMapNaviView;
import com.amap.api.navi.enums.NaviForbidType;
import com.amap.api.navi.enums.NaviLimitType;
import com.amap.api.navi.enums.NaviType;
import com.amap.api.navi.model.AMapCalcRouteResult;
import com.amap.api.navi.model.AMapCarInfo;
import com.amap.api.navi.model.AMapNaviForbiddenInfo;
import com.amap.api.navi.model.AMapNaviLimitInfo;
import com.amap.api.navi.model.NaviLatLng;
import com.amap.navi.demo.R;

import java.util.List;


public class TruckRouteCalculateActivity extends BaseActivity {

    private final String TAG = "truckRoute";

    AMapCarInfo aMapCarInfo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_basic_navi);
        mAMapNaviView = (AMapNaviView) findViewById(R.id.navi_view);
        mAMapNaviView.onCreate(savedInstanceState);
        mAMapNaviView.setAMapNaviViewListener(this);

        aMapCarInfo = getIntent().getParcelableExtra("info");
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

        if (aMapCarInfo == null) {
            aMapCarInfo = new AMapCarInfo();
            aMapCarInfo.setCarType("1");//设置车辆类型，0小车，1货车
            aMapCarInfo.setCarNumber("黑A12222");//设置车辆的车牌号码. 如:京DFZ239,京ABZ239
            aMapCarInfo.setVehicleSize("1");// * 设置货车的大小
            aMapCarInfo.setVehicleLoad("100");//设置货车的总重，即车重——核定载重，单位：吨。
            aMapCarInfo.setVehicleWeight("99");//设置货车的核定载重
            aMapCarInfo.setVehicleLength("25");//  * 设置货车的最大长度，单位：米。
            aMapCarInfo.setVehicleWidth("2");//设置货车的最大宽度，单位：米。 如:1.8，1.5等等。
            aMapCarInfo.setVehicleHeight("4");//设置货车的高度，单位：米。
            aMapCarInfo.setVehicleAxis("6");//设置货车的轴数
            aMapCarInfo.setVehicleLoadSwitch(true);//设置车辆的载重是否参与算路
            aMapCarInfo.setRestriction(true);//设置是否躲避车辆限行。
        }

        aMapCarInfo.setVehicleLoadSwitch(true);
        aMapCarInfo.setCarType("1");

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
        mAMapNavi.startNavi(NaviType.EMULATOR);

        /**
         * 获取当前路线导航限制信息（例如： 限高，限宽）
         */
        List<AMapNaviLimitInfo> limitInfos = mAMapNavi.getNaviPath().getLimitInfos();

        /**
         * 获取当前路线导航禁行信息 (例如：禁行)
         */
        List<AMapNaviForbiddenInfo> forbiddenInfos = mAMapNavi.getNaviPath().getForbiddenInfos();

        int forbiddenCount = 0;
        int limitHeight = 0;
        int limitWidth = 0;
        if (limitInfos != null) {
            for (int i = 0; i < limitInfos.size(); i++) {
                AMapNaviLimitInfo limitInfo = limitInfos.get(i);
                /**
                 * 81 : 货车限高
                 * 82 : 货车限宽
                 * 83 : 货车限重
                 */
                if (limitInfo.type == NaviLimitType.TYPE_TRUCK_WIDTH_LIMIT) {
                    ++limitWidth;
                } else if (limitInfo.type == NaviLimitType.TYPE_TRUCK_HEIGHT_LIMIT) {
                    ++limitHeight;
                }
            }
        }

        if (forbiddenInfos != null) {
            forbiddenCount = forbiddenInfos.size();

            for (int i = 0; i < forbiddenInfos.size(); i++) {
                AMapNaviForbiddenInfo forbiddenInfo = forbiddenInfos.get(i);
                /**
                 * 0: 禁止左转
                 * 1: 禁止右转
                 * 2: 禁止左掉头
                 * 3: 禁止右掉头
                 * 4: 禁止直行
                 */
                switch (forbiddenInfo.forbiddenType) {
                    case NaviForbidType.FORBID_TURN_LEFT :
                        Log.e(TAG, "当前路线有禁止左转");
                        break;
                    case NaviForbidType.FORBID_TURN_RIGHT :
                        Log.e(TAG, "当前路线有禁止右转");
                        break;
                    case NaviForbidType.FORBID_TURN_LEFT_ROUND :
                        Log.e(TAG, "当前路线有禁止左掉头");
                        break;
                    case NaviForbidType.FORBID_TURN_RIGHT_ROUND :
                        Log.e(TAG, "当前路线有禁止右掉头");
                        break;
                    case NaviForbidType.FORBID_GO_STRAIGHT :
                        Log.e(TAG, "当前路线有禁止直行");
                        break;
                    default:
                }

            }
        }

        String limitStr = "有";
        if (limitHeight > 0) {
            limitStr +=  limitHeight + "处限高，";
        }

        if (limitWidth > 0) {
            limitStr +=  limitHeight + "处限宽，";
        }

        if (forbiddenCount > 0) {
            limitStr += forbiddenCount + "处限行，";
        }

        if (limitStr.length() > 2) {
            limitStr = limitStr.substring(0, limitStr.length() - 1);
            limitStr += "无法避开";
        } else {
            limitStr = null;
        }

        if (!TextUtils.isEmpty(limitStr)) {
            Toast.makeText(this, limitStr, Toast.LENGTH_LONG).show();
        }
    }
}
