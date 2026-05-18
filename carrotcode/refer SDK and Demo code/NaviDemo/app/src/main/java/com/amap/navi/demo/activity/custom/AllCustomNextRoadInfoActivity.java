package com.amap.navi.demo.activity.custom;

import android.os.Bundle;
import android.widget.TextView;

import com.amap.api.maps.AMap;
import com.amap.api.maps.CameraUpdateFactory;
import com.amap.api.maps.TextureMapView;
import com.amap.api.maps.model.LatLng;
import com.amap.api.navi.enums.NaviType;
import com.amap.api.navi.model.AMapCalcRouteResult;
import com.amap.api.navi.model.AMapNaviLocation;
import com.amap.api.navi.model.NaviInfo;
import com.amap.navi.demo.R;
import com.amap.navi.demo.activity.custom.view.CarOverlay;
import com.amap.navi.demo.activity.custom.view.NextTurnTipView;
import com.amap.navi.demo.util.NaviUtil;


/**
 * 包名： com.amap.navi.demo.activity.custom
 * <p>
 * 创建时间：2018/4/24
 * 项目名称：NaviDemo
 *
 * @author guibao.ggb
 * @email guibao.ggb@alibaba-inc.com
 * <p>
 * 类说明：路名剩余距离转向图标示例
 */
public class AllCustomNextRoadInfoActivity extends BaseCustomActivity {


    private TextView textNextRoadDistance;
    private TextView textNextRoadName;
    private NextTurnTipView nextTurnTipView;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_all_custom_next_road_info_view);
        mAMapNaviView = (TextureMapView) findViewById(R.id.navi_view);
        mAMapNaviView.onCreate(savedInstanceState);


        textNextRoadDistance = (TextView) findViewById(R.id.text_next_road_distance);
        textNextRoadName = (TextView) findViewById(R.id.text_next_road_name);
        nextTurnTipView = (NextTurnTipView) findViewById(R.id.icon_next_turn_tip);

        mAMapNaviView.getMap().getUiSettings().setZoomControlsEnabled(false);
        mAMapNaviView.getMap().setOnMapLoadedListener(new AMap.OnMapLoadedListener() {
            @Override
            public void onMapLoaded() {
                mAMapNaviView.getMap().setPointToCenter(mAMapNaviView.getWidth()/2, mAMapNaviView.getHeight()/2);

                mAMapNaviView.getMap().moveCamera(CameraUpdateFactory.zoomTo(16f));
                carOverlay = new CarOverlay(AllCustomNextRoadInfoActivity.this, mAMapNaviView);

            }
        });


    }

    @Override
    public void onCalculateRouteSuccess(AMapCalcRouteResult aMapCalcRouteResult) {
        super.onCalculateRouteSuccess(aMapCalcRouteResult);
        mAMapNavi.startNavi(NaviType.EMULATOR);
    }

    /** ------- 导航基本信息的回调 ----- */
    @Override
    public void onNaviInfoUpdate(NaviInfo naviInfo) {
        super.onNaviInfoUpdate(naviInfo);

        /**
         * 更新路口转向图标
         */
        if(naviInfo.getIconBitmap() != null){
            nextTurnTipView.setImageBitmap(naviInfo.getIconBitmap());
        }else{
            nextTurnTipView.setIconType(naviInfo.getIconType());
        }
        /**
         * 更新下一路口 路名及 距离
         */
        textNextRoadName.setText(naviInfo.getNextRoadName());
        textNextRoadDistance.setText(NaviUtil.formatKM(naviInfo.getCurStepRetainDistance()));

    }

    /**
     * GPS 定位信息的回调函数
     * @param location 当前定位的GPS信息
     */
    @Override
    public void onLocationChange(AMapNaviLocation location) {

        if (carOverlay != null && location != null) {
            carOverlay.draw(mAMapNaviView.getMap(), new LatLng(location.getCoord().getLatitude(), location.getCoord().getLongitude()), location.getBearing());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

    }
}
