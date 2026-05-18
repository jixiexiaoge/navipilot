package com.amap.navi.demo.activity.custom;

import android.graphics.Rect;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import com.amap.api.maps.AMap;
import com.amap.api.maps.CameraUpdateFactory;
import com.amap.api.maps.TextureMapView;
import com.amap.api.maps.model.LatLng;
import com.amap.api.navi.model.AMapLaneInfo;
import com.amap.api.navi.model.AMapModelCross;
import com.amap.api.navi.model.AMapNaviCameraInfo;
import com.amap.api.navi.model.AMapNaviCross;
import com.amap.api.navi.model.AMapNaviLocation;
import com.amap.api.navi.model.AMapTrafficStatus;
import com.amap.api.navi.model.NaviInfo;
import com.amap.api.navi.view.AMapModeCrossOverlay;
import com.amap.navi.demo.R;
import com.amap.navi.demo.activity.custom.view.AMapCameraOverlay;
import com.amap.navi.demo.activity.custom.view.CarOverlay;
import com.amap.navi.demo.activity.custom.view.DriveWayLinear;
import com.amap.navi.demo.activity.custom.view.NextTurnTipView;
import com.amap.navi.demo.activity.custom.view.TrafficProgressBar;
import com.amap.navi.demo.activity.custom.view.ZoomInIntersectionView;
import com.amap.navi.demo.util.DensityUtils;
import com.amap.navi.demo.util.NaviUtil;

import java.util.List;

/**
 * 包名： com.amap.navi.demo.activity.custom
 * <p>
 * 创建时间：2018/4/24
 * 项目名称：NaviDemo
 *
 * @author guibao.ggb
 * @email guibao.ggb@alibaba-inc.com
 * <p>
 * 类说明：各组件整合的导航示例
 */
public class AllCustomNaviActivity extends BaseCustomActivity {

    private TrafficProgressBar mTrafficBarView;
    private DriveWayLinear mDriveWayView;
    // 自定义实景放大图
    private ZoomInIntersectionView mRealCrossView;
    // 自定义模型放大图
    private AMapModeCrossOverlay mModeCrossView;
    private AMapCameraOverlay cameraOverlay;

    private TextView textNextRoadDistance;
    private TextView textNextRoadName;
    private NextTurnTipView nextTurnTipView;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_all_custom_navi_view);
        mAMapNaviView = (TextureMapView) findViewById(R.id.navi_view);
        mAMapNaviView.onCreate(savedInstanceState);
        final AMap aMap = mAMapNaviView.getMap();

        mTrafficBarView = (TrafficProgressBar) findViewById(R.id.myTrafficBar);
        mDriveWayView = (DriveWayLinear) findViewById(R.id.myDriveWayView);
        mRealCrossView = (ZoomInIntersectionView) findViewById(R.id.myZoomInIntersectionView);

        textNextRoadDistance = (TextView) findViewById(R.id.text_next_road_distance);
        textNextRoadName = (TextView) findViewById(R.id.text_next_road_name);
        nextTurnTipView = (NextTurnTipView) findViewById(R.id.icon_next_turn_tip);

        cameraOverlay = new AMapCameraOverlay(this);

        mModeCrossView = new AMapModeCrossOverlay(this, aMap);
        mModeCrossView.setCrossOverlayLocation(new Rect(0, DensityUtils.dp2px(getApplicationContext(),50),
                DensityUtils.getScreenWidth(getApplicationContext()), DensityUtils.dp2px(getApplicationContext(), 300)));

        aMap.getUiSettings().setZoomControlsEnabled(false);
        aMap.setOnMapLoadedListener(new AMap.OnMapLoadedListener() {
            @Override
            public void onMapLoaded() {
                aMap.setPointToCenter(mAMapNaviView.getWidth()/2, mAMapNaviView.getHeight()/2);

                aMap.moveCamera(CameraUpdateFactory.zoomTo(16f));
                carOverlay = new CarOverlay(AllCustomNaviActivity.this, mAMapNaviView);

            }
        });


    }

    /** ------- 导航基本信息的回调 ----- */
    @Override
    public void onNaviInfoUpdate(NaviInfo naviInfo) {
        super.onNaviInfoUpdate(naviInfo);
        int allLength = mAMapNavi.getNaviPath().getAllLength();

        /**
         * 导航路况条 更新
         */
        List<AMapTrafficStatus> trafficStatuses = mAMapNavi.getTrafficStatuses(0, 0);
        mTrafficBarView.update(allLength, naviInfo.getPathRetainDistance(), trafficStatuses);

        /**
         * 更新路口转向图标
         */
        nextTurnTipView.setIconType(naviInfo.getIconType());

        /**
         * 更新下一路口 路名及 距离
         */
        textNextRoadName.setText(naviInfo.getNextRoadName());
        textNextRoadDistance.setText(NaviUtil.formatKM(naviInfo.getCurStepRetainDistance()));

        /**
         * 绘制转弯的箭头
         */
        drawArrow(naviInfo);

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

    /** ----- start 车道信息的回调 start ------- */
    @Override
    public void showLaneInfo(AMapLaneInfo aMapLaneInfo) {
        mDriveWayView.setVisibility(View.VISIBLE);
        mDriveWayView.buildDriveWay(aMapLaneInfo);

    }

    @Override
    public void hideLaneInfo() {
        mDriveWayView.hide();
    }
    /** ----- ebd 车道信息的回调 end -------*/


    /** ----- start 路口放大图 start ------- */
    @Override
    public void showCross(AMapNaviCross aMapNaviCross) {
        mRealCrossView.setIntersectionBitMap(aMapNaviCross);
        mRealCrossView.setVisibility(View.VISIBLE);
    }

    @Override
    public void hideCross() {
        mRealCrossView.setVisibility(View.GONE);
    }

    @Override
    public void showModeCross(AMapModelCross aMapModelCross) {
        try {
            mModeCrossView.showCrossOverlay(aMapModelCross.getPicBuf1());
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    @Override
    public void hideModeCross() {
        mModeCrossView.hideCrossOverlay();
    }
    /** ----- end 路口放大图 end ------- */

    /** ----- start 电子眼  start --------*/
    @Override
    public void updateCameraInfo(AMapNaviCameraInfo[] aMapCameraInfos) {
        if (cameraOverlay != null) {
            cameraOverlay.draw(mAMapNaviView.getMap(), aMapCameraInfos);
        }

    }
    /** ----- end 电子眼  end --------*/

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (mRealCrossView != null) {
            mRealCrossView.recycleResource();
        }

        if (mModeCrossView != null) {
            mModeCrossView.hideCrossOverlay();
        }

        if (cameraOverlay != null) {
            cameraOverlay.destroy();
        }

    }
}
