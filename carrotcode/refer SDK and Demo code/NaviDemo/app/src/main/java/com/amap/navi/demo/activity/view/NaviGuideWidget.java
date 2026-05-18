package com.amap.navi.demo.activity.view;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ExpandableListView;


import com.amap.api.navi.model.AMapNaviRouteGuideGroup;

import java.util.List;

/**
 * Created by hongming.wang on 2017/6/22.
 */

public class NaviGuideWidget extends ExpandableListView {

    public NaviGuideWidget(Context context) {
        super(context);
    }

    public NaviGuideWidget(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public NaviGuideWidget(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setGuideData(String start, String end, List<AMapNaviRouteGuideGroup> dataList) {
        //隐藏分割线
        setDivider(null);
        //去掉默认的箭头
        setGroupIndicator(null);

        if (null != dataList && dataList.size() > 0) {
            AMapNaviRouteGuideGroup header = new AMapNaviRouteGuideGroup();
            header.setGroupIconType(NaviGuideAdapter.ACTION_START);
            header.setGroupName(start);
            dataList.add(0, header);

            AMapNaviRouteGuideGroup footer = new AMapNaviRouteGuideGroup();
            footer.setGroupIconType(NaviGuideAdapter.ACTION_END);
            footer.setGroupName(end);
            dataList.add(dataList.size(), footer);

            setAdapter(new NaviGuideAdapter(getContext(), dataList));
        }
    }

}
