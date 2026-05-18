package com.amap.navi.demo.activity.custom;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

import com.amap.api.maps.AMap;
import com.amap.api.maps.CameraUpdateFactory;
import com.amap.api.maps.TextureMapView;
import com.amap.api.maps.model.LatLng;
import com.amap.api.navi.model.AMapModelCross;
import com.amap.api.navi.model.AMapNaviCross;
import com.amap.api.navi.model.AMapNaviLocation;
import com.amap.api.navi.view.AMapModeCrossOverlay;
import com.amap.navi.demo.R;
import com.amap.navi.demo.activity.custom.view.CarOverlay;
import com.amap.navi.demo.activity.custom.view.ZoomInIntersectionView;
import com.amap.navi.demo.util.DensityUtils;

/**
 * 包名： com.amap.navi.demo.activity.custom
 * <p>
 * 创建时间：2018/4/24
 * 项目名称：NaviDemo
 *
 * @author guibao.ggb
 * @email guibao.ggb@alibaba-inc.com
 * <p>
 * 类说明：自定义路口放大图的示例
 */
public class AllCustomCrossingActivity extends BaseCustomActivity {

    private ZoomInIntersectionView mRealCrossView;
    private AMapModeCrossOverlay mModeCrossView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_all_custom_crossing_view);
        mAMapNaviView = (TextureMapView) findViewById(R.id.navi_view);
        mAMapNaviView.onCreate(savedInstanceState);
        final AMap aMap = mAMapNaviView.getMap();
        // 注意，如果要使用自定义放大图功能，必须将地图风格设置为NAVI或NIGHT类型 ！
        aMap.setMapType(AMap.MAP_TYPE_NAVI);
        aMap.getUiSettings().setZoomControlsEnabled(false);
        aMap.setOnMapLoadedListener(new AMap.OnMapLoadedListener() {
            @Override
            public void onMapLoaded() {
                aMap.setPointToCenter(mAMapNaviView.getWidth()/2, mAMapNaviView.getHeight()/2);

                aMap.moveCamera(CameraUpdateFactory.zoomTo(16f));
                carOverlay = new CarOverlay(AllCustomCrossingActivity.this, mAMapNaviView);

            }
        });

        mModeCrossView = new AMapModeCrossOverlay(this, aMap);
        mModeCrossView.setCrossOverlayLocation(new Rect(0, DensityUtils.dp2px(getApplicationContext(),48),
                DensityUtils.getScreenWidth(getApplicationContext()), DensityUtils.dp2px(getApplicationContext(),300)));

        mRealCrossView = (ZoomInIntersectionView) findViewById(R.id.myZoomInIntersectionView);

        sList.clear();
        sList.add(p1);
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

    /** ----- start 路口放大图 start ------- */
    @Override
    public void showCross(AMapNaviCross aMapNaviCross) {
        mRealCrossView.setVisibility(View.VISIBLE);
        mRealCrossView.setIntersectionBitMap(aMapNaviCross);
    }

    @Override
    public void hideCross() {
        mRealCrossView.setVisibility(View.GONE);
    }

    @Override
    public void showModeCross(AMapModelCross aMapModelCross) {
        mModeCrossView.showCrossOverlay(aMapModelCross.getPicBuf1());
    }

    @Override
    public void hideModeCross() {
        mModeCrossView.hideCrossOverlay();
    }

    /** ----- end 路口放大图 end ------- */

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (mRealCrossView != null) {
            mRealCrossView.recycleResource();
        }

        if (mModeCrossView != null) {
            mModeCrossView.hideCrossOverlay();
        }

    }
}
