package com.amap.navi.demo.activity;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.method.CharacterPickerDialog;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.amap.api.maps.model.LatLng;
import com.amap.api.maps.model.Poi;
import com.amap.api.navi.AmapNaviPage;
import com.amap.api.navi.AmapNaviParams;
import com.amap.api.navi.AmapNaviType;
import com.amap.api.navi.AmapPageType;
import com.amap.api.navi.INaviInfoCallback;
import com.amap.api.navi.model.AMapCarInfo;
import com.amap.api.navi.model.AMapNaviLocation;
import com.amap.navi.demo.R;

import org.w3c.dom.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * 包名： com.example.navigationsdk
 * <p>
 * 创建时间：2018/1/19
 * 项目名称：AndroidNavigationSDK
 *
 * @author guibao.ggb
 * @email guibao.ggb@alibaba-inc.com
 * <p>
 * 类说明：
 */
public class SetTruckParamsActivity extends Activity implements INaviInfoCallback {

    private EditText textCarNum;
    private TextView textCarType;
    private EditText textCarTotalWeight;
    private EditText textCarWeight;
    private EditText textCarLength;
    private EditText textCarWidth;
    private EditText textCarHeight;
    private TextView textCarAxles;
    private int truckType = 0;
    private int truckAxles = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.layout_truck_params);

        initViews();
    }

    private void initViews() {
        textCarNum = (EditText) findViewById(R.id.text_car_num);
        textCarType = (TextView) findViewById(R.id.text_car_type);
        textCarTotalWeight = (EditText) findViewById(R.id.text_car_total_weight);
        textCarWeight = (EditText) findViewById(R.id.text_car_weight);
        textCarLength = (EditText) findViewById(R.id.text_car_length);
        textCarWidth = (EditText) findViewById(R.id.text_car_width);
        textCarHeight = (EditText) findViewById(R.id.text_car_height);
        textCarAxles = (TextView) findViewById(R.id.text_car_axles);
    }

    /**
     * 首开广场
     */
    LatLng p1 = new LatLng(39.993266, 116.473193);
    /**
     * 故宫博物院
     */
    LatLng p2 = new LatLng(39.917337, 116.397056);
    public void actionFinish(View view) {

        String number = textCarNum.getText().toString().trim();
        String totalWeight = textCarTotalWeight.getText().toString().trim();
        String weight = textCarWeight.getText().toString().trim();
        String length = textCarLength.getText().toString().trim();
        String width = textCarWidth.getText().toString().trim();
        String height = textCarHeight.getText().toString().trim();
        String carType = textCarType.getText().toString().trim();
        String carAxles = textCarAxles.getText().toString().trim();
        if (TextUtils.isEmpty(number)) {
            showToast("请填写车牌号码");
            return;
        } else if (TextUtils.isEmpty(carType)) {
            showToast("请选择货车类型");
            return;
        } else if (TextUtils.isEmpty(totalWeight)) {
            showToast("请填写货车总承重");
            return;
        } else if (TextUtils.isEmpty(weight)) {
            showToast("请填写货车核定承重");
            return;
        } else if (TextUtils.isEmpty(length)) {
            showToast("请填写货车车长");
            return;
        } else if (TextUtils.isEmpty(width)) {
            showToast("请填写货车车宽");
            return;
        } else if (TextUtils.isEmpty(height)) {
            showToast("请填写货车车高");
            return;
        } else if (TextUtils.isEmpty(carAxles)) {
            showToast("请选择货车车轴数");
            return;
        }

        AMapCarInfo carInfo = new AMapCarInfo();
        carInfo.setCarType("1");
        carInfo.setCarNumber(number);
        carInfo.setVehicleSize(String.valueOf(truckType));
        carInfo.setVehicleLoad(totalWeight);
        carInfo.setVehicleWeight(weight);
        carInfo.setVehicleWidth(width);
        carInfo.setVehicleLength(length);
        carInfo.setVehicleHeight(height);
        carInfo.setVehicleAxis(String.valueOf(truckAxles));
        carInfo.setRestriction(true);

        Poi start = new Poi("立水桥(北5环)", p1, "");//起点
        Poi end = new Poi("新三余公园(南5环)", p2, "");//终点
        AmapNaviParams amapNaviParams = new AmapNaviParams(start, null, end, AmapNaviType.DRIVER, AmapPageType.ROUTE);
        amapNaviParams.setUseInnerVoice(true);
        amapNaviParams.setCarInfo(carInfo);
        AmapNaviPage.getInstance().showRouteActivity(getApplicationContext(), amapNaviParams,this);
    }


    private void showToast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }


    public void showtruckTypeDialog(View view){
        final String[] items = { "微型货车","轻型货车","中型货车","重型货车" };
        truckType = 1;
        AlertDialog.Builder singleChoiceDialog =
                new AlertDialog.Builder(this);
        singleChoiceDialog.setTitle("请选择货车类型");
        // 第二个参数是默认选项，此处设置为0
        singleChoiceDialog.setSingleChoiceItems(items, 0,
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        truckType = which + 1;
                    }
                });
        singleChoiceDialog.setPositiveButton("确定",
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (truckType != -1) {
                            textCarType.setText(items[truckType - 1]);
                        }
                    }
                });
        singleChoiceDialog.show();
    }

    public void showtruckAlixsDialog(View view){
        final String[] items = { "1轴", "2轴","3轴","4轴", "5轴", "6轴", "6轴以上"};
        truckAxles = -1;
        AlertDialog.Builder singleChoiceDialog =
                new AlertDialog.Builder(this);
        singleChoiceDialog.setTitle("请选择车辆轴数");
        // 第二个参数是默认选项，此处设置为0
        singleChoiceDialog.setSingleChoiceItems(items, 0,
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        truckAxles = which + 1;
                    }
                });
        singleChoiceDialog.setPositiveButton("确定",
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (truckAxles != -1) {
                            textCarAxles.setText(items[truckAxles]);
                        }
                    }
                });
        singleChoiceDialog.show();
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
        return null;
    }

    @Override
    public View getCustomNaviView() {
        return null;
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

    @Override
    public void onArrivedWayPoint(int i) {

    }

    @Override
    public void onMapTypeChanged(int i) {

    }
}
