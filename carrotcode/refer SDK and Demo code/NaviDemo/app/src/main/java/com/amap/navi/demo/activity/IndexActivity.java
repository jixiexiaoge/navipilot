package com.amap.navi.demo.activity;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;

import com.amap.api.maps.MapsInitializer;
import com.amap.api.maps.model.LatLng;
import com.amap.api.maps.model.Poi;
import com.amap.api.navi.AMapNavi;
import com.amap.api.navi.AmapNaviPage;
import com.amap.api.navi.AmapNaviParams;
import com.amap.api.navi.AmapNaviType;
import com.amap.api.navi.AmapPageType;
import com.amap.api.navi.INaviInfoCallback;
import com.amap.api.navi.model.AMapNaviLocation;
import com.amap.navi.demo.R;
import com.amap.navi.demo.activity.custom.AllCustomCameraActivity;
import com.amap.navi.demo.activity.custom.AllCustomCarRouteActivity;
import com.amap.navi.demo.activity.custom.AllCustomCrossingActivity;
import com.amap.navi.demo.activity.custom.AllCustomDriveWayActivity;
import com.amap.navi.demo.activity.custom.AllCustomNaviActivity;
import com.amap.navi.demo.activity.custom.AllCustomNextRoadInfoActivity;
import com.amap.navi.demo.activity.custom.AllCustomTrafficBarActivity;
import com.amap.navi.demo.activity.view.FeatureView;
import com.amap.navi.demo.util.CheckPermissionsActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by shixin on 16/8/23.
 * bug反馈QQ:1438734562
 */
public class IndexActivity extends CheckPermissionsActivity implements INaviInfoCallback {

    private static class DemoDetails {
        private final int titleId;
        private final int descriptionId;
        private final Class<? extends android.app.Activity> activityClass;

        public DemoDetails(int titleId, int descriptionId,
                           Class<? extends android.app.Activity> activityClass) {
            super();
            this.titleId = titleId;
            this.descriptionId = descriptionId;
            this.activityClass = activityClass;
        }
    }

    private static class CustomArrayAdapter extends ArrayAdapter<DemoDetails> {
        public CustomArrayAdapter(Context context, DemoDetails[] demos) {
            super(context, R.layout.feature, R.id.title, demos);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            FeatureView featureView;
            if (convertView instanceof FeatureView) {
                featureView = (FeatureView) convertView;
            } else {
                featureView = new FeatureView(getContext());
            }
            DemoDetails demo = getItem(position);
            featureView.setTitleId(demo.titleId, demo.activityClass!=null);
            return featureView;
        }
    }

    private static final DemoDetails[] DEMOS = {
//		    // 导航组件
            new DemoDetails(R.string.navi_ui, R.string.blank, null),
			// 组件起终点算路
            new DemoDetails(R.string.navi_start_end_poi_calculate_title, R.string.navi_start_end_poi_calculate_desc, IndexActivity.class),
            // 组件起终点算路
            new DemoDetails(R.string.navi_end_poi_calculate_title, R.string.navi_end_poi_calculate_desc, IndexActivity.class),
            // 组件起终点算路
            new DemoDetails(R.string.navi_bywayof_poi_calculate_title, R.string.navi_bywayof_poi_calculate_desc, IndexActivity.class),
            // 直接导航
            new DemoDetails(R.string.navi_ui_navi_title, R.string.navi_ui_navi_desc, IndexActivity.class),
            // 组件起终点算路（白色主题）
            new DemoDetails(R.string.navi_ui_custom_activity, R.string.navi_ui_custom_activity, IndexActivity.class),

            // 路径规划
            new DemoDetails(R.string.navi_route_line, R.string.blank, null),
            // 驾车路径规划
            new DemoDetails(R.string.navi_route_driver_title, R.string.navi_route_driver_desc, DriverListActivity.class),
            // 摩托车路径规划
            new DemoDetails(R.string.navi_route_motorcycle_title, R.string.navi_route_motorcycle_desc, MotorcycleRouteCalculateActivity.class),
            // 骑行电动车路径规划
            new DemoDetails(R.string.navi_route_ele_bike_title, R.string.navi_route_ele_bike_desc, EleBikeRouteCalculateActivity.class),
            // 步行路径规划
            new DemoDetails(R.string.navi_route_walk_title, R.string.navi_route_walk_desc, WalkRouteCalculateActivity.class),
            // 骑行路径规划
            new DemoDetails(R.string.navi_route_ride_title, R.string.navi_route_ride_desc, RideRouteCalculateActivity.class),
            // 货车路径规划导航
            new DemoDetails(R.string.navi_route_truck_title, R.string.navi_route_truck_title, TruckRouteCalculateActivity.class),
            // 独立路线规划
            new DemoDetails(R.string.navi_route_independent_title, R.string.navi_route_independent_title, IndependentRouteCalculateActivity.class),
            // 导航类型
            new DemoDetails(R.string.navi_type, R.string.blank, null),
            // 内置语音导航
            new DemoDetails(R.string.navi_type_inner_voice, R.string.blank, EmulatorActivity.class),
            // 实时导航
            new DemoDetails(R.string.navi_type_gps_title, R.string.navi_type_gps_desc, GPSNaviActivity.class),
            // 模拟导航
            new DemoDetails(R.string.navi_type_emu_title, R.string.navi_type_emu_desc, EmulatorActivity.class),
            // 货车导航
            new DemoDetails(R.string.navi_type_truck, R.string.blank,SetTruckParamsActivity.class),
            // 智能巡航
            new DemoDetails(R.string.navi_type_intelligent_title, R.string.navi_type_intelligent_desc, IntelligentBroadcastActivity.class),
            // HUD导航
            new DemoDetails(R.string.navi_type_hud_title, R.string.navi_type_hud_desc, HudDisplayActivity.class),

            // 导航UI在自定义
            new DemoDetails(R.string.navi_ui_custom, R.string.blank, null),
            // 自定义车标
            new DemoDetails(R.string.navi_ui_custom_car_icon, R.string.blank, CustomCarActivity.class),
            // 自定义路线UI
            new DemoDetails(R.string.navi_ui_custom_route, R.string.blank, CustomRouteActivity.class),
            // 自定义路线纹理
            new DemoDetails(R.string.navi_ui_custom_route_style, R.string.blank, CustomRouteTextureInAMapNaviViewActivity.class),
            // 自定义路口转向提示
            new DemoDetails(R.string.navi_ui_custom_trun_across_tip, R.string.blank, CustomNextTurnTipViewActivity.class),
            // 正被漠视
            new DemoDetails(R.string.navi_ui_custom_northmode, R.string.blank, NorthModeActivity.class),
            // 自定义全览模式
            new DemoDetails(R.string.navi_ui_custom_wholescan_button, R.string.blank, OverviewModeActivity.class),
            // 自定义指南针
            new DemoDetails(R.string.navi_ui_custom_compass, R.string.blank, CustomDirectionViewActivity.class),
            // 自定义路况按钮
            new DemoDetails(R.string.navi_ui_custom_traffic_button, R.string.blank, CustomTrafficButtonViewActivity.class),
            // 自定义放大缩小按钮
            new DemoDetails(R.string.navi_ui_custom_zoom_button, R.string.blank, CustomZoomButtonViewActivity.class),
            // 自定义路口放大图
            new DemoDetails(R.string.navi_ui_custom_across_overlay, R.string.blank, CustomZoomInIntersectionViewActivity.class),
            // 自定义导航光柱(New)
            new DemoDetails(R.string.navi_ui_custom_traffic_bar_new, R.string.blank, CustomTrafficProgressBarActivity.class),
            // 自定义道路选择
            new DemoDetails(R.string.navi_ui_custom_route_select, R.string.blank, CustomDriveWayViewActivity.class),

            // 导航完全自定义示例
            new DemoDetails(R.string.navi_custom_all, R.string.blank, null),
            // 完全自定义自车位置和绘制路线
            new DemoDetails(R.string.navi_custom_car_route, R.string.blank, AllCustomCarRouteActivity.class),
            // 完全自定义路名、距离、下一路口图标
            new DemoDetails(R.string.navi_custom_road_distance_nexttip, R.string.blank, AllCustomNextRoadInfoActivity.class),
            // 完全自定义路况导航条
            new DemoDetails(R.string.navi_custom_traffic_bar, R.string.blank, AllCustomTrafficBarActivity.class),
            // 完全自定义车道信息
            new DemoDetails(R.string.navi_custom_route_way, R.string.blank, AllCustomDriveWayActivity.class),
            // 完全自定义路口放大图
            new DemoDetails(R.string.navi_custom_crossing, R.string.blank, AllCustomCrossingActivity.class),
            // 完全自定义摄像头违章摄影
            new DemoDetails(R.string.navi_custom_camera, R.string.blank, AllCustomCameraActivity.class),
            // 完全自定义导航
            new DemoDetails(R.string.navi_custom_navi, R.string.blank, AllCustomNaviActivity.class),


            // 导航扩展
            new DemoDetails(R.string.navi_expand, R.string.blank, null),
            // 传入GPS数据导航
            new DemoDetails(R.string.navi_expand_set_gps_data, R.string.blank, UseExtraGpsDataActivity.class),
            // 展示导航路径详情
            new DemoDetails(R.string.navi_expand_route_detail, R.string.blank, GetNaviStepsAndLinksActivity.class),
            // 主辅路切换
            new DemoDetails(R.string.navi_expand_switch_road, R.string.blank, SwitchMasterRoadNaviActivity.class),
            // 科大讯飞语音集成播报
            new DemoDetails(R.string.navi_expand_iflyt_voice, R.string.blank, IflyVoiceActivity.class),

    };

    LatLng p1 = new LatLng(39.993266, 116.473193);//首开广场
    LatLng p2 = new LatLng(39.917337, 116.397056);//故宫博物院
    LatLng p3 = new LatLng(39.904556, 116.427231);//北京站
    LatLng p4 = new LatLng(39.773801, 116.368984);//新三余公园(南5环)
    LatLng p5 = new LatLng(40.041986, 116.414496);//立水桥(北5环)

    private AdapterView.OnItemClickListener mItemClickListener = new AdapterView.OnItemClickListener() {
        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {

            if (position == 1) {
                AmapNaviParams params = new AmapNaviParams(new Poi("北京站", p3, ""), null, new Poi("故宫博物院", p2, ""), AmapNaviType.DRIVER);
                params.setUseInnerVoice(true);
                AmapNaviPage.getInstance().showRouteActivity(getApplicationContext(), params, IndexActivity.this);
            } else if (position == 2) {
                AmapNaviPage.getInstance().showRouteActivity(getApplicationContext(), new AmapNaviParams(null, null, new Poi("故宫博物院", p2, ""), AmapNaviType.DRIVER), IndexActivity.this);
            } else if (position == 3) {
                List<Poi> poiList = new ArrayList();
                poiList.add(new Poi("首开广场", p1, ""));
                poiList.add(new Poi("故宫博物院", p2, ""));
                poiList.add(new Poi("北京站", p3, ""));

                AmapNaviParams params = new AmapNaviParams(new Poi("立水桥(北5环)", p5, ""), poiList, new Poi("新三余公园(南5环)", p4, ""), AmapNaviType.DRIVER);
                params.setUseInnerVoice(true);
                AmapNaviPage.getInstance().showRouteActivity(getApplicationContext(), params, IndexActivity.this);
            } else if (position == 4) {
                //起点
                Poi start = new Poi("立水桥(北5环)", p5, "");
                //途经点
                List<Poi> poiList = new ArrayList();
                poiList.add(new Poi("首开广场", p1, ""));
                poiList.add(new Poi("故宫博物院", p2, ""));
                poiList.add(new Poi("北京站", p3, ""));
                //终点
                Poi end = new Poi("新三余公园(南5环)", p4, "");
                AmapNaviParams amapNaviParams = new AmapNaviParams(start, poiList, end, AmapNaviType.DRIVER, AmapPageType.NAVI);
                amapNaviParams.setUseInnerVoice(true);
                AmapNaviPage.getInstance().showRouteActivity(getApplicationContext(), amapNaviParams, IndexActivity.this);
            } else if (position == 5) {
                AmapNaviParams params = new AmapNaviParams(new Poi("北京站", p3, ""), null, new Poi("故宫博物院", p2, ""), AmapNaviType.DRIVER);
                params.setUseInnerVoice(true);
                AmapNaviPage.getInstance().showRouteActivity(getApplicationContext(), params, IndexActivity.this,CustomAmapRouteActivity.class);
            } else if (position == 12) {
                Intent intent = new Intent(IndexActivity.this, EmulatorActivity.class);
                intent.putExtra("useInnerVoice", true);
                startActivity(intent);
            } else {
                DemoDetails demo = (DemoDetails) adapter.getItem(position);
                if (demo.activityClass != null) {
                    startActivity(new Intent(IndexActivity.this, demo.activityClass));
                }
            }

        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_index);
        initView();
        privacyCompliance();
    }
    private void privacyCompliance() {
        MapsInitializer.updatePrivacyShow(IndexActivity.this,true,true);
        SpannableStringBuilder spannable = new SpannableStringBuilder("\"亲，感谢您对XXX一直以来的信任！我们依据最新的监管要求更新了XXX《隐私权政策》，特向您说明如下\n1.为向您提供交易相关基本功能，我们会收集、使用必要的信息；\n2.基于您的明示授权，我们可能会获取您的位置（为您提供附近的商品、店铺及优惠资讯等）等信息，您有权拒绝或取消授权；\n3.我们会采取业界先进的安全措施保护您的信息安全；\n4.未经您同意，我们不会从第三方处获取、共享或向提供您的信息；\n");
        spannable.setSpan(new ForegroundColorSpan(Color.BLUE), 35, 42, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        new AlertDialog.Builder(this)
                .setTitle("温馨提示(隐私合规示例)")
                .setMessage(spannable)
                .setPositiveButton("同意", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        MapsInitializer.updatePrivacyAgree(IndexActivity.this,true);
                    }
                })
                .setNegativeButton("不同意", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        MapsInitializer.updatePrivacyAgree(IndexActivity.this,false);
                    }
                })
                .show();
    }

    ListAdapter adapter;
    private void initView() {
        ListView listView = (ListView) findViewById(R.id.list);
        setTitle("导航SDK " + AMapNavi.getVersion());

        adapter = new CustomArrayAdapter(
                this.getApplicationContext(), DEMOS);
        listView.setAdapter(adapter);

        listView.setOnItemClickListener(mItemClickListener);
    }

    /**
     * 返回键处理事件
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {

        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish();
            System.exit(0);// 退出程序
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onInitNaviFailure() {

    }

    @Override
    public void onGetNavigationText(String s) {

    }

    @Override
    public void onLocationChange(AMapNaviLocation aMapNaviLocation) {

    }

    @Override
    public void onArriveDestination(boolean b) {

    }

    @Override
    public void onStartNavi(int i) {

    }

    @Override
    public void onCalculateRouteSuccess(int[] ints) {

    }

    @Override
    public void onCalculateRouteFailure(int i) {

    }

    @Override
    public void onStopSpeaking() {

    }

    @Override
    public void onReCalculateRoute(int i) {

    }

    @Override
    public void onExitPage(int i) {

    }

    @Override
    public void onStrategyChanged(int i) {

    }

    @Override
    public View getCustomNaviBottomView() {
        //返回null则不显示自定义区域
        return getCustomView("底部自定义区域");
    }

    @Override
    public View getCustomNaviView() {
        //返回null则不显示自定义区域
        return getCustomView("中部自定义区域");
    }

    @Override
    public void onArrivedWayPoint(int i) {

    }

    @Override
    public void onMapTypeChanged(int i) {

    }

    @Override
    public View getCustomMiddleView() {
        return null;
    }

    @Override
    public void onNaviDirectionChanged(int i) {

    }

    @Override
    public void onDayAndNightModeChanged(int i) {

    }

    @Override
    public void onBroadcastModeChanged(int i) {

    }

    @Override
    public void onScaleAutoChanged(boolean b) {

    }

    TextView text1;
    TextView text2;
    private View getCustomView(String title) {
        LinearLayout linearLayout = new LinearLayout(this);
        try {
            linearLayout.setOrientation(LinearLayout.HORIZONTAL);
            text1 = new TextView(this);
            text1.setGravity(Gravity.CENTER);
            text1.setHeight(90);
            text1.setMinWidth(300);
            text1.setText(title);

            text2 = new TextView(this);
            text2.setGravity(Gravity.CENTER);
            text1.setHeight(90);
            text2.setMinWidth(300);
            text2.setText(title);
            linearLayout.setGravity(Gravity.CENTER);
            linearLayout.addView(text1, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            linearLayout.addView(text2, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            ViewGroup.LayoutParams params = new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.height = 100;
            linearLayout.setLayoutParams(params);
        } catch (Throwable e) {
            e.printStackTrace();
        }
        return linearLayout;
    }
}
