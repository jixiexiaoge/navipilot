package com.amap.navi.demo.activity.custom;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Window;
import android.widget.Toast;

import com.amap.api.maps.AMap;
import com.amap.api.maps.AMapException;
import com.amap.api.maps.TextureMapView;
import com.amap.api.maps.model.BitmapDescriptor;
import com.amap.api.maps.model.BitmapDescriptorFactory;
import com.amap.api.maps.model.LatLng;
import com.amap.api.navi.AMapNavi;
import com.amap.api.navi.AMapNaviListener;
import com.amap.api.navi.AMapNaviViewListener;
import com.amap.api.navi.AmapPageType;
import com.amap.api.navi.ParallelRoadListener;
import com.amap.api.navi.enums.AMapNaviParallelRoadStatus;
import com.amap.api.navi.enums.NaviType;
import com.amap.api.navi.model.AMapCalcRouteResult;
import com.amap.api.navi.model.AMapLaneInfo;
import com.amap.api.navi.model.AMapModelCross;
import com.amap.api.navi.model.AMapNaviCameraInfo;
import com.amap.api.navi.model.AMapNaviCross;
import com.amap.api.navi.model.AMapNaviLocation;
import com.amap.api.navi.model.AMapNaviPath;
import com.amap.api.navi.model.AMapNaviRouteNotifyData;
import com.amap.api.navi.model.AMapNaviTrafficFacilityInfo;
import com.amap.api.navi.model.AMapServiceAreaInfo;
import com.amap.api.navi.model.AimLessModeCongestionInfo;
import com.amap.api.navi.model.AimLessModeStat;
import com.amap.api.navi.model.NaviInfo;
import com.amap.api.navi.model.NaviLatLng;
import com.amap.api.navi.model.RouteOverlayOptions;
import com.amap.api.navi.view.RouteOverLay;
import com.amap.navi.demo.R;
import com.amap.navi.demo.activity.custom.view.CarOverlay;
import com.amap.navi.demo.util.NaviUtil;
import com.amap.navi.demo.util.TTSController;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;


public class BaseCustomActivity extends Activity implements AMapNaviListener, AMapNaviViewListener, ParallelRoadListener {

    protected TextureMapView mAMapNaviView;
    protected AMapNavi mAMapNavi;
    protected TTSController mTtsManager;
    protected NaviLatLng mEndLatlng = new NaviLatLng(40.084894,116.603039);
    protected NaviLatLng mStartLatlng = new NaviLatLng(39.825934,116.342972);
    protected final List<NaviLatLng> sList = new ArrayList<NaviLatLng>();
    protected final List<NaviLatLng> eList = new ArrayList<NaviLatLng>();
    protected List<NaviLatLng> mWayPointList;

    NaviLatLng p1 = new NaviLatLng(39.993266, 116.473193);//首开广场
    NaviLatLng p2 = new NaviLatLng(39.917337, 116.397056);//故宫博物院
    NaviLatLng p3 = new NaviLatLng(39.904556, 116.427231);//北京站
    NaviLatLng p4 = new NaviLatLng(39.773801, 116.368984);//新三余公园(南5环)
    NaviLatLng p5 = new NaviLatLng(40.041986, 116.414496);//立水桥(北5环)


    /**
     * 锁车状态
     */
    private static final int MESSAGE_CAR_LOCK = 0;
    /**
     * 非锁车状态
     */
    private static final int MESSAGE_CAR_UNLOCK = 1;

    private Handler handler = new UiHandler(this);

    class UiHandler extends Handler {

        private final WeakReference context;
        public UiHandler(Context context) {
            this.context = new WeakReference<>(context);
        }

        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            try {
                if (context.get() == null) {
                    return;
                }

                switch (msg.what) {
                    case MESSAGE_CAR_LOCK:
                        if (carOverlay != null) {
                            carOverlay.setLock(true);
                        }
                        break;
                    case MESSAGE_CAR_UNLOCK:
                        if (carOverlay != null) {
                            carOverlay.setLock(false);
                        }
                        break;
                    default:
                }
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 自车位置类
     */
    CarOverlay carOverlay;
    /**
     * 路线Overlay
     */
    RouteOverLay routeOverLay;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        //实例化语音引擎
        mTtsManager = TTSController.getInstance(getApplicationContext());
        mTtsManager.init();

        //
        try {
            mAMapNavi = AMapNavi.getInstance(getApplicationContext());
            mAMapNavi.addAMapNaviListener(this);
            mAMapNavi.addAMapNaviListener(mTtsManager);
            mAMapNavi.addParallelRoadListener(this);
            mAMapNavi.setUseInnerVoice(true);

            //设置模拟导航的行车速度
            mAMapNavi.setEmulatorNaviSpeed(75);
            sList.add(p3);
            eList.add(p2);
        } catch (AMapException e) {
            e.printStackTrace();
        }

    }

    @Override
    protected void onStart() {
        super.onStart();

        mAMapNaviView.getMap().setOnMapTouchListener(new AMap.OnMapTouchListener() {
            @Override
            public void onTouch(MotionEvent motionEvent) {

                handler.sendEmptyMessage(MESSAGE_CAR_UNLOCK);
                handler.removeMessages(MESSAGE_CAR_LOCK);
                handler.sendEmptyMessageDelayed(MESSAGE_CAR_LOCK, 3000);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        mAMapNaviView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mAMapNaviView.onPause();

//        仅仅是停止你当前在说的这句话，一会到新的路口还是会再说的
        mTtsManager.stopSpeaking();
//
//        停止导航之后，会触及底层stop，然后就不会再有回调了，但是讯飞当前还是没有说完的半句话还是会说完
//        mAMapNavi.stopNavi();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mAMapNaviView.onDestroy();
        //since 1.6.0 不再在naviview destroy的时候自动执行AMapNavi.stopNavi();请自行执行
        if (mAMapNavi!=null){
            mAMapNavi.stopNavi();
            mAMapNavi.destroy();
        }
        mTtsManager.destroy();



        if (carOverlay != null) {
            carOverlay.destroy();
        }

        if (routeOverLay != null) {
            routeOverLay.removeFromMap();
            routeOverLay.destroy();
        }

        if (mAMapNaviView != null) {
            mAMapNaviView.onDestroy();
        }

    }

    @Override
    public void onInitNaviFailure() {
        Toast.makeText(this, "init navi Failed", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onInitNaviSuccess() {
        // 初始化成功
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
    public void onStartNavi(int type) {
        //开始导航回调
    }

    @Override
    public void onTrafficStatusUpdate() {
        //
    }

    @Override
    public void onLocationChange(AMapNaviLocation location) {
        //当前位置回调
    }

    @Override
    public void onGetNavigationText(int type, String text) {
        //播报类型和播报文字回调
    }

    @Override
    public void onGetNavigationText(String s) {

    }

    @Override
    public void onEndEmulatorNavi() {
        //结束模拟导航
    }

    @Override
    public void onArriveDestination() {
        //到达目的地
    }

    @Override
    public void onCalculateRouteFailure(int errorInfo) {

    }

    @Override
    public void onReCalculateRouteForYaw() {
        //偏航后重新计算路线回调
    }

    @Override
    public void onReCalculateRouteForTrafficJam() {
        //拥堵后重新计算路线回调
    }

    @Override
    public void onArrivedWayPoint(int wayID) {
        //到达途径点
    }

    @Override
    public void onGpsOpenStatus(boolean enabled) {
        //GPS开关状态回调
    }

    @Override
    public void onNaviSetting() {
        //底部导航设置点击回调
    }

    @Override
    public void onNaviMapMode(int isLock) {
        //地图的模式，锁屏或锁车
    }

    @Override
    public void onNaviCancel() {
        finish();
    }


    @Override
    public void onNaviTurnClick() {
        //转弯view的点击回调
    }

    @Override
    public void onNextRoadClick() {
        //下一个道路View点击回调
    }


    @Override
    public void onScanViewButtonClick() {
        //全览按钮点击回调
    }

    @Override
    public void updateCameraInfo(AMapNaviCameraInfo[] aMapCameraInfos) {

    }

    @Override
    public void onServiceAreaUpdate(AMapServiceAreaInfo[] amapServiceAreaInfos) {

    }

    @Override
    public void onNaviInfoUpdate(NaviInfo naviinfo) {
        //导航过程中的信息更新，请看NaviInfo的具体说明
    }

    @Override
    public void OnUpdateTrafficFacility(AMapNaviTrafficFacilityInfo aMapNaviTrafficFacilityInfo) {
        //已过时
    }

    @Override
    public void showCross(AMapNaviCross aMapNaviCross) {
        //显示转弯回调
    }

    @Override
    public void hideCross() {
        //隐藏转弯回调
    }

    @Override
    public void showLaneInfo(AMapLaneInfo[] laneInfos, byte[] laneBackgroundInfo, byte[] laneRecommendedInfo) {
        //显示车道信息

    }

    @Override
    public void hideLaneInfo() {
        //隐藏车道信息
    }

    @Override
    public void onCalculateRouteSuccess(int[] ints) {
        //多路径算路成功回调
    }

    @Override
    public void notifyParallelRoad(AMapNaviParallelRoadStatus aMapNaviParallelRoadStatus) {
        if (aMapNaviParallelRoadStatus.getmElevatedRoadStatusFlag() == 1) {
            Toast.makeText(this, "当前在高架上", Toast.LENGTH_SHORT).show();
            Log.d("wlx", "当前在高架上");
        } else if (aMapNaviParallelRoadStatus.getmElevatedRoadStatusFlag() == 2) {
            Toast.makeText(this, "当前在高架下", Toast.LENGTH_SHORT).show();
            Log.d("wlx", "当前在高架下");
        }

        if (aMapNaviParallelRoadStatus.getmParallelRoadStatusFlag() == 1) {
            Toast.makeText(this, "当前在主路", Toast.LENGTH_SHORT).show();
            Log.d("wlx", "当前在主路");
        } else if (aMapNaviParallelRoadStatus.getmParallelRoadStatusFlag() == 2) {
            Toast.makeText(this, "当前在辅路", Toast.LENGTH_SHORT).show();
            Log.d("wlx", "当前在辅路");
        }
    }

    @Override
    public void notifyParallelRoad(int i) {

    }

    @Override
    public void OnUpdateTrafficFacility(AMapNaviTrafficFacilityInfo[] aMapNaviTrafficFacilityInfos) {
        //更新交通设施信息
    }

    @Override
    public void updateAimlessModeStatistics(AimLessModeStat aimLessModeStat) {
        //更新巡航模式的统计信息
    }


    @Override
    public void updateAimlessModeCongestionInfo(AimLessModeCongestionInfo aimLessModeCongestionInfo) {
        //更新巡航模式的拥堵信息
    }

    @Override
    public void onPlayRing(int i) {

    }


    @Override
    public void onLockMap(boolean isLock) {
        //锁地图状态发生变化时回调
    }

    @Override
    public void onNaviViewLoaded() {
        Log.d("wlx", "导航页面加载成功");
        Log.d("wlx", "请不要使用AMapNaviView.getMap().setOnMapLoadedListener();会overwrite导航SDK内部画线逻辑");
    }

    @Override
    public void onMapTypeChanged(int i) {

    }

    @Override
    public void onNaviViewShowMode(int i) {

    }

    @Override
    public void onStopSpeaking() {

    }

    @Override
    public void onViewTypeChanged(AmapPageType amapPageType) {

    }

    @Override
    public void onAMapNaviViewExit() {

    }

    @Override
    public void onStrategyChanged(int i) {

    }

    @Override
    public void onBroadcastModeChanged(int i) {

    }

    @Override
    public void onDayAndNightModeChanged(int i) {

    }

    @Override
    public void onScaleAutoChanged(boolean b) {

    }

    @Override
    public void onListenToVoiceDuringCallChanged(boolean b) {

    }

    @Override
    public void onControlMusicVolumeModeChanged(int i) {

    }

    @Override
    public void onEagleChanged(boolean b) {

    }

    @Override
    public void onNaviRouteHighlightChange(long l, int i) {

    }

    @Override
    public boolean onNaviBackClick() {
        return false;
    }


    @Override
    public void showModeCross(AMapModelCross aMapModelCross) {

    }

    @Override
    public void hideModeCross() {

    }

    @Override
    public void updateIntervalCameraInfo(AMapNaviCameraInfo aMapNaviCameraInfo, AMapNaviCameraInfo aMapNaviCameraInfo1, int i) {

    }

    @Override
    public void showLaneInfo(AMapLaneInfo aMapLaneInfo) {

    }

    @Override
    public void onCalculateRouteSuccess(AMapCalcRouteResult aMapCalcRouteResult) {
        AMapNaviPath naviPath = mAMapNavi.getNaviPath();
        if (naviPath != null) {

            if (routeOverLay == null) {
                /**
                 * 初始化路线参数
                 */
                routeOverLay = new RouteOverLay(mAMapNaviView.getMap(), naviPath, this);
                BitmapDescriptor smoothTraffic = BitmapDescriptorFactory.fromResource(R.drawable.custtexture_green);
                BitmapDescriptor unknownTraffic = BitmapDescriptorFactory.fromResource(R.drawable.custtexture_no);
                BitmapDescriptor slowTraffic = BitmapDescriptorFactory.fromResource(R.drawable.custtexture_slow);
                BitmapDescriptor jamTraffic = BitmapDescriptorFactory.fromResource(R.drawable.custtexture_bad);
                BitmapDescriptor veryJamTraffic = BitmapDescriptorFactory.fromResource(R.drawable.custtexture_grayred);

                RouteOverlayOptions routeOverlayOptions = new RouteOverlayOptions();
                routeOverlayOptions.setSmoothTraffic(smoothTraffic.getBitmap());
                routeOverlayOptions.setUnknownTraffic(unknownTraffic.getBitmap());
                routeOverlayOptions.setSlowTraffic(slowTraffic.getBitmap());
                routeOverlayOptions.setJamTraffic(jamTraffic.getBitmap());
                routeOverlayOptions.setVeryJamTraffic(veryJamTraffic.getBitmap());

                routeOverLay.setRouteOverlayOptions(routeOverlayOptions);
            }
            if (routeOverLay != null) {
                routeOverLay.setAMapNaviPath(naviPath);
                routeOverLay.addToMap();
            }

            float bearing = NaviUtil.getRotate(mStartLatlng, naviPath.getCoordList().get(1));
            if (mStartLatlng != null) {
                carOverlay.reset();
                /**
                 * 绘制自车位置
                 */
                carOverlay.draw(mAMapNaviView.getMap(), new LatLng(mStartLatlng.getLatitude(), mStartLatlng.getLongitude()), bearing);
                if (naviPath.getEndPoint() != null) {
                    LatLng latlng = new LatLng(naviPath.getEndPoint().getLatitude(), naviPath.getEndPoint().getLongitude());
                    carOverlay.setEndPoi(latlng);
                }
            }
        }

        /**
         * 开启模拟导航
          */
        mAMapNavi.startNavi(NaviType.EMULATOR);

    }

    /**
     * 绘制转弯箭头
     */
    private int roadIndex;
    public void drawArrow(NaviInfo naviInfo) {
        try {
            if (roadIndex != naviInfo.getCurStep()) {
                List<NaviLatLng> arrow = routeOverLay.getArrowPoints(naviInfo.getCurStep());
                if (routeOverLay != null && arrow != null && arrow.size() > 0) {
                    routeOverLay.drawArrow(arrow);
                    roadIndex = naviInfo.getCurStep();
                }
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }


    @Override
    public void onCalculateRouteFailure(AMapCalcRouteResult result) {
        //路线计算失败
        Log.e("dm", "--------------------------------------------");
        Log.i("dm", "路线计算失败：错误码=" + result.getErrorCode() + ",Error Message= " + result.getErrorDescription());
        Log.i("dm", "错误码详细链接见：http://lbs.amap.com/api/android-navi-sdk/guide/tools/errorcode/");
        Log.e("dm", "--------------------------------------------");
        Toast.makeText(this, "errorInfo：" + result.getErrorDetail() + ", Message：" + result.getErrorDescription(), Toast.LENGTH_LONG).show();
    }

    @Override
    public void onNaviRouteNotify(AMapNaviRouteNotifyData aMapNaviRouteNotifyData) {

    }

    @Override
    public void onGpsSignalWeak(boolean b) {

    }
}
