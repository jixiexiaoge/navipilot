package com.amap.navi.demo.activity.view;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseExpandableListAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import com.amap.api.navi.enums.IconType;
import com.amap.api.navi.model.AMapNaviRouteGuideGroup;
import com.amap.api.navi.model.AMapNaviRouteGuideSegment;
import com.amap.navi.demo.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Created by hongming.wang on 2017/6/21.
 */

public class NaviGuideAdapter extends BaseExpandableListAdapter {

    public static final int ACTION_START = -1;
    public static final int ACTION_END = -2;

    private List<AMapNaviRouteGuideGroup> groupList;
    private Context mContext;

    public NaviGuideAdapter(Context context, List<AMapNaviRouteGuideGroup> guideGroupList) {
        mContext = context;
        groupList = guideGroupList;
    }

    @Override
    public int getGroupCount() {
        return groupList.size();
    }

    @Override
    public int getChildrenCount(int groupPosition) {
        return groupList.get(groupPosition).getSegments().size();
    }

    @Override
    public Object getGroup(int groupPosition) {
        return groupList.get(groupPosition);
    }

    @Override
    public Object getChild(int groupPosition, int childPosition) {
        return groupList.get(groupPosition).getSegments().get(childPosition);
    }

    @Override
    public long getGroupId(int groupPosition) {
        return groupPosition;
    }

    @Override
    public long getChildId(int groupPosition, int childPosition) {
        return childPosition;
    }

    @Override
    public boolean hasStableIds() {
        return false;
    }

    @Override
    public View getGroupView(int groupPosition, boolean isExpanded, View convertView, ViewGroup parent) {
        try {
            GroupHolder holder;
            if (convertView == null) {
                holder = new GroupHolder();
                convertView = LayoutInflater.from(mContext).inflate(R.layout.lbs_naviguide_item_group, null);
                holder.ivGroupIcon = (ImageView) convertView.findViewById(R.id.iv_groupIcon);
                holder.tvBefore = (TextView) convertView.findViewById(R.id.tv_before);
                holder.tvGroupName = (TextView) convertView.findViewById(R.id.tv_groupName);
                holder.tvAfter = (TextView) convertView.findViewById(R.id.tv_after);
                holder.tvGroupDetail = (TextView) convertView.findViewById(R.id.tv_groupDetail);
                holder.ivAction = (ImageView) convertView.findViewById(R.id.iv_action);
                holder.line = convertView.findViewById(R.id.line);

                convertView.setTag(holder);
            } else {
                holder = (GroupHolder) convertView.getTag();
            }

            AMapNaviRouteGuideGroup group = groupList.get(groupPosition);
            if (null != group) {
                int iconType = group.getGroupIconType();
                holder.ivGroupIcon.setBackgroundResource(getDrawableId(iconType));
                holder.tvGroupName.setText(group.getGroupName());
                if (iconType == ACTION_START || iconType == ACTION_END) {
                    holder.tvGroupDetail.setVisibility(View.GONE);
                    holder.ivAction.setVisibility(View.GONE);
                    holder.tvBefore.setVisibility(View.VISIBLE);
                    if (iconType == ACTION_START) {
                        holder.tvBefore.setText(mContext.getResources().getString(R.string.poi_input_type_start));
                        holder.tvAfter.setVisibility(View.VISIBLE);
                        holder.tvAfter.setText(mContext.getResources().getString(R.string.navi_guide_from));
                    } else {
                        holder.tvAfter.setVisibility(View.GONE);
                        holder.tvBefore.setText(mContext.getResources().getString(R.string.navi_guide_end));
                    }
                } else {
                    holder.tvBefore.setVisibility(View.GONE);
                    holder.tvAfter.setVisibility(View.GONE);
                    holder.tvGroupDetail.setVisibility(View.VISIBLE);
                    StringBuffer sb = new StringBuffer();
                    sb.append(formatKM(group.getGroupLen())).append(" ");
                    if (group.getTrafficLightsCount() > 0) {
                        sb.append("红绿灯").append(group.getTrafficLightsCount()).append("个");
                    }
                    holder.tvGroupDetail.setText(sb.toString());
                    holder.ivAction.setVisibility(View.VISIBLE);
                    if (isExpanded) {
                        holder.ivAction.setBackgroundResource(R.drawable.up);
                        holder.line.setVisibility(View.GONE);
                    } else {
                        holder.ivAction.setBackgroundResource(R.drawable.down);
                        holder.line.setVisibility(View.VISIBLE);
                    }
                }

            }

        } catch (Throwable e) {
        }
        return convertView;
    }

    @Override
    public View getChildView(int groupPosition, int childPosition, boolean isLastChild, View convertView, ViewGroup parent) {
        try {
            ChildHolder holder;
            if (convertView == null) {
                holder = new ChildHolder();
                convertView = LayoutInflater.from(mContext).inflate(R.layout.lbs_naviguide_item_child, null);

                holder.ivChildIcon = (ImageView) convertView.findViewById(R.id.iv_childIcon);
                holder.tvChildDetail = (TextView) convertView.findViewById(R.id.tv_childDetail);
                holder.line = convertView.findViewById(R.id.line);
                convertView.setTag(holder);
            } else {
                holder = (ChildHolder) convertView.getTag();
            }

            AMapNaviRouteGuideSegment guidStep = groupList.get(groupPosition).getSegments().get(childPosition);
            if (null != guidStep) {
                holder.ivChildIcon.setBackgroundResource(getDrawableId(guidStep.getStepIconType()));
                holder.tvChildDetail.setText(guidStep.getDescription());
            }
            holder.line.setVisibility(isLastChild ? View.VISIBLE : View.GONE);

        } catch (Throwable e) {
        }
        return convertView;
    }

    @Override
    public boolean isChildSelectable(int groupPosition, int childPosition) {
        return false;
    }

    //    public static final int MainAction_NULL = 0x0;    //!< 无基本导航动作
//    public static final int MainAction_Turn_Left        = 0x1;  //!< 左转
//    public static final int MainAction_Turn_Right        = 0x2;    //!< 右转
//    public static final int MainAction_Slight_Left        = 0x3;    //!< 向左前方行驶
//    public static final int MainAction_Slight_Right        = 0x4;    //!< 向右前方行驶
//    public static final int MainAction_Turn_Hard_Left      = 0x5;    //!< 向左后方行驶
//    public static final int MainAction_Turn_Hard_Right      = 0x6;    //!< 向右后方行驶
//    public static final int MainAction_UTurn          = 0x7;    //!< 左转调头
//    public static final int MainAction_Continue          = 0x8;    //!< 直行
//    public static final int MainAction_Merge_Left        = 0x9;    //!< 靠左
//    public static final int MainAction_Merge_Right        = 0x0A;    //!< 靠右
//    public static final int MainAction_Entry_Ring        = 0x0B;    //!< 进入环岛
//    public static final int MainAction_Leave_Ring         = 0x0C;    //!< 离开环岛
//    public static final int MainAction_Slow            = 0x0D;    //!< 减速行驶
//    public static final int MainAction_Plug_Continue      = 0x0E;      //!< 插入直行（泛亚特有）
//    public static final int MainAction_Count          = 0x0F;
    private int[] defaultIconTypes = {
            R.drawable.action0,
            R.drawable.action0,
            R.drawable.action2,
            R.drawable.action3,
            R.drawable.action4,
            R.drawable.action5,
            R.drawable.action6,
            R.drawable.action7,
            R.drawable.action8,
            R.drawable.action9,
            R.drawable.action10,
            R.drawable.action11,
            R.drawable.action12,
            R.drawable.action13,
            R.drawable.action14,
            R.drawable.action9
    };


    private int getCustomIconType(int iconType) {
        if (iconType == -1) {
            return R.drawable.action_start;
        }
        if (iconType == -2) {
            return R.drawable.action_end;
        }
        return R.drawable.action0;
    }

    private int getDrawableId(int iconType) {
        int id = R.drawable.action0;
        if (iconType >= 0) {
            try {
                if (iconType == 51) {
                    return R.drawable.action4;
                }
                if (iconType == 52) {
                    return R.drawable.action5;
                }
                if (iconType == 53) {
                    return R.drawable.action9;
                }
                id = defaultIconTypes[iconType];
            } catch (Throwable e) {
            }
        } else {
            id = getCustomIconType(iconType);
        }
        return id;
    }


    private Drawable getIconDrawable(int iconType) {
        Drawable drawable = mContext.getResources().getDrawable(R.drawable.action0);
        int defaultSize = defaultIconTypes.length;
        if (iconType >= 0) {
            try {
                drawable = mContext.getResources().getDrawable(defaultIconTypes[iconType]);
            } catch (Throwable e) {
            }
        } else {
            drawable = mContext.getResources().getDrawable(getCustomIconType(iconType));
        }
        return drawable;
    }

    //  public static final int MainAction_NULL = 0x0;    //!< 无基本导航动作
//  public static final int MainAction_Turn_Left        = 0x1;  //!< 左转
//  public static final int MainAction_Turn_Right        = 0x2;    //!< 右转
//  public static final int MainAction_Slight_Left        = 0x3;    //!< 向左前方行驶
//  public static final int MainAction_Slight_Right        = 0x4;    //!< 向右前方行驶
//  public static final int MainAction_Turn_Hard_Left      = 0x5;    //!< 向左后方行驶
//  public static final int MainAction_Turn_Hard_Right      = 0x6;    //!< 向右后方行驶
//  public static final int MainAction_UTurn          = 0x7;    //!< 左转调头
//  public static final int MainAction_Continue          = 0x8;    //!< 直行
//  public static final int MainAction_Merge_Left        = 0x9;    //!< 靠左
//  public static final int MainAction_Merge_Right        = 0x0A;    //!< 靠右
//  public static final int MainAction_Entry_Ring        = 0x0B;    //!< 进入环岛
//  public static final int MainAction_Leave_Ring         = 0x0C;    //!< 离开环岛
//  public static final int MainAction_Slow            = 0x0D;    //!< 减速行驶
//  public static final int MainAction_Plug_Continue      = 0x0E;      //!< 插入直行（泛亚特有）
//  public static final int MainAction_Count          = 0x0F;
    private String iconType2Str(int iconType) {
        String str = "";
        switch (iconType) {
            case 2:
                str = "左转";
                break;
            case 3:
                str = "右转";
                break;
            case 4:
                str = "向左前方转";
                break;
            case 5:
                str = "向右前方转";
                break;
            case 6:
                str = "向左后方行驶";
                break;
            case 7:
                str = "向右后方行驶";
                break;
            case 8:
                str = "左转调头";
                break;
            case 9:
                str = "直行";
                break;
            case 10:
                str = "到达途径点";
                break;
            case 51:
                str = "靠左";
                break;
            case 52:
                str = "靠右";
                break;
            case 11:
                str = "进入环岛";
                break;
            case 12:
                str = "驶出环岛";
                break;
            case 13:
                break;
            case 14:
                break;
            default:
                break;
        }
        return str;
    }

    class GroupHolder {
        ImageView ivGroupIcon;
        TextView tvBefore;
        TextView tvGroupName;
        TextView tvAfter;
        TextView tvGroupDetail;
        ImageView ivAction;
        View line;
    }


    class ChildHolder {
        ImageView ivChildIcon;
        TextView tvChildDetail;
        View line;
    }

    public static String formatKM(int d) {
        if (d == 0) {
            return "0米";
        }
        if (d < 100) {
            return d + "米";
        }
        if ((100 <= d) && (d < 1000)) {
            return d + "米";
        }
        if ((1000 <= d) && (d < 10000)) {
            return (d / 10) * 10 / 1000.0D + "公里";
        }
        if ((10000 <= d) && (d < 100000)) {
            return (d / 100) * 100 / 1000.0D + "公里";
        }
        return (d / 1000) + "公里";
    }
}
