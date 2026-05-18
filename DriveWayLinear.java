package com.amap.navi.demo.activity.custom.view;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.amap.api.navi.enums.LaneAction;
import com.amap.api.navi.model.AMapLaneInfo;
import com.amap.navi.demo.R;
import com.amap.navi.demo.util.DensityUtils;

/**
 * 车道信息View
 */
public class DriveWayLinear extends LinearLayout {

    public static final int IMG_WIDTH = 22;

    public static final int IMG_HEIGHT = 39;

    private final int[] driveWayGrayBgId = {
            R.drawable.landback_0,
            R.drawable.landback_1, R.drawable.landback_2,
            R.drawable.landback_3, R.drawable.landback_4,
            R.drawable.landback_5, R.drawable.landback_6,
            R.drawable.landback_7, R.drawable.landback_8,
            R.drawable.landback_9, R.drawable.landback_a,
            R.drawable.landback_b, R.drawable.landback_c,
            R.drawable.landback_d, R.drawable.landback_e,
            R.drawable.landback_f, R.drawable.landback_g,
            R.drawable.landback_h, R.drawable.landback_i,
            R.drawable.landback_j, R.drawable.landfront_kk,
            R.drawable.landback_l};

    private final int[] driveWayFrontId = {
            R.drawable.landfront_0,
            R.drawable.landfront_1, R.drawable.landback_2,
            R.drawable.landfront_3, R.drawable.landback_4,
            R.drawable.landfront_5, R.drawable.landback_6,
            R.drawable.landback_7, R.drawable.landfront_8,
            R.drawable.landback_9, R.drawable.landback_a,
            R.drawable.landback_b, R.drawable.landback_c,
            R.drawable.landfront_d, R.drawable.landback_e,
            R.drawable.landback_f, R.drawable.landback_g,
            R.drawable.landback_h, R.drawable.landback_i,
            R.drawable.landback_j, R.drawable.landfront_kk,
            R.drawable.landback_l};

    LayoutParams lp;

    LayoutParams imgLp;


    public DriveWayLinear(Context context) {
        this(context, null);
    }

    public DriveWayLinear(Context context, AttributeSet set) {
        super(context, set);
        init(context);
    }

    private void init(Context context) {
        lp = new LayoutParams(LayoutParams.WRAP_CONTENT, DensityUtils.dp2px(context, IMG_HEIGHT));
        imgLp = new LayoutParams(DensityUtils.dp2px(context, IMG_WIDTH), DensityUtils.dp2px(context, IMG_HEIGHT));
        lp.gravity = Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
        imgLp.gravity = Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;

        setGravity(Gravity.CENTER_HORIZONTAL);
    }

    public void buildDriveWay(AMapLaneInfo laneInfo) {
        this.removeAllViews();
        int length = laneInfo.laneCount;
        int childSize = length;
        for (int i = 0; i < length; ++i) {
            if (laneInfo.backgroundLane[i] == 255) {
                childSize = i;
                break;
            }
        }

        for (int i = 0; i < childSize; i++) {
            int guideImg = getGuideImg(laneInfo.backgroundLane[i], laneInfo.frontLane[i]);
            guideImg = guideImg == -1 ? driveWayGrayBgId[laneInfo.backgroundLane[i]]
                    : guideImg;
            int bgImg = -1;
            if (childSize == 1) {
                bgImg = R.drawable.navi_lane_shape_bg_over;
            } else if (childSize > 1 && i == 0) {
                bgImg = R.drawable.navi_lane_shape_bg_left;
            } else if (childSize > 1 && i == childSize - 1) {
                bgImg = R.drawable.navi_lane_shape_bg_right;
            } else {
                bgImg = R.drawable.navi_lane_shape_bg_center;
            }
            addView(createImageView(guideImg, bgImg), imgLp);
            if (childSize > 1 && i < childSize - 1) {
                addView(createLine(), lp);
            }
        }
    }

    public void hide() {
        int count = getChildCount();
        if (count > 0) {
            for (int i = 0; i < count; i++) {
                View view = getChildAt(i);
                if (view != null && view instanceof ImageView) {
                    Drawable d = ((ImageView) view).getDrawable();
                    if (d != null) {
                        d.setCallback(null);
                    }
                }
            }
        }
        this.removeAllViews();
        this.setVisibility(View.GONE);
    }

    @SuppressWarnings("deprecation")
    private View createImageView(int src, int bg) {
        ImageView img = new ImageView(getContext());
        img.setImageDrawable(getResources().getDrawable(src));
        img.setBackgroundDrawable(getResources().getDrawable(bg));
        img.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        return img;
    }

    private View createLine() {
        ImageView img = new ImageView(getContext());
        img.setImageDrawable(getResources().getDrawable(R.drawable.navi_arrow_leftline));
        img.setBackgroundColor(0xff0091ff);
        return img;
    }

    private int getGuideImg(int backInfo, int selectInfo) {
        if (isComplexLane(backInfo)) {
            return complexGuide(backInfo, selectInfo);
        }
        if (isLoadLaneSelectInfo(backInfo, selectInfo)) {
            return driveWayFrontId[selectInfo];
        }
        return -1;
    }

    /**
     * 判断是否是复杂车道
     *
     */
    private boolean isComplexLane(int laneBackInfoIndex) {
        return laneBackInfoIndex == LaneAction.LANE_ACTION_LEFT_IN_LEFT_LU_TURN || laneBackInfoIndex == LaneAction.LANE_ACTION_AHEAD_LEFT
                || laneBackInfoIndex == LaneAction.LANE_ACTION_AHEAD_RIGHT || laneBackInfoIndex == LaneAction.LANE_ACTION_AHEAD_LU_TURN
                || laneBackInfoIndex == LaneAction.LANE_ACTION_AHEAD_RU_TURN || laneBackInfoIndex == LaneAction.LANE_ACTION_LEFT_LU_TURN
                || laneBackInfoIndex == LaneAction.LANE_ACTION_RIGHT_RU_TURN || laneBackInfoIndex == LaneAction.LANE_ACTION_LEFT_RIGHT
                || laneBackInfoIndex == LaneAction.LANE_ACTION_AHEAD_LEFT_RIGHT || laneBackInfoIndex == LaneAction.LANE_ACTION_AHEAD_LEFT_LU_TURN
                || laneBackInfoIndex >= LaneAction.LANE_ACTION_RIGHT_RU_TURN_EX;
    }

    private int complexGuide(int laneBackInfoIndex, int laneSelectIndex) {
        int guide = -1;
        // 直行和右转调头 (0+8)
        if (laneBackInfoIndex == LaneAction.LANE_ACTION_AHEAD_RU_TURN) {
            if (laneSelectIndex == LaneAction.LANE_ACTION_AHEAD) {
                guide = R.drawable.landfront_a0;
            } else if (laneSelectIndex == LaneAction.LANE_ACTION_RU_TURN) {
                guide = R.drawable.landfront_a8;
            }
        } else if (laneBackInfoIndex == LaneAction.LANE_ACTION_AHEAD_LU_TURN) {// 直行和左转调头(0+5)
            if (laneSelectIndex == LaneAction.LANE_ACTION_AHEAD) {
                guide = R.drawable.landfront_90;
            } else if (laneSelectIndex == LaneAction.LANE_ACTION_LU_TURN) {
                guide = R.drawable.landfront_95;
            }
        } else if (laneBackInfoIndex == LaneAction.LANE_ACTION_AHEAD_LEFT) {// 直行和左转 (0+1)
            if (laneSelectIndex == LaneAction.LANE_ACTION_AHEAD) {
                guide = R.drawable.landfront_20;
            } else if (laneSelectIndex == LaneAction.LANE_ACTION_LEFT) {
                guide = R.drawable.landfront_21;
            }
        } else if (laneBackInfoIndex == LaneAction.LANE_ACTION_AHEAD_RIGHT) {// 直行和右转(0+3)
            if (laneSelectIndex == LaneAction.LANE_ACTION_AHEAD) {
                guide = R.drawable.landfront_40;
            } else if (laneSelectIndex == LaneAction.LANE_ACTION_RIGHT) {
                guide = R.drawable.landfront_43;
            }
        } else if (laneBackInfoIndex == LaneAction.LANE_ACTION_LEFT_RIGHT) {// 左转和右转(1+3)
            if (laneSelectIndex == LaneAction.LANE_ACTION_LEFT) {// 推荐左转
                guide = R.drawable.landfront_61;
            } else if (laneSelectIndex == LaneAction.LANE_ACTION_RIGHT) {// 推荐右转
                guide = R.drawable.landfront_63;
            }

        } else if (laneBackInfoIndex == LaneAction.LANE_ACTION_AHEAD_LEFT_RIGHT) {// 直行，左转，右转 (0+1+3)
            if (laneSelectIndex == LaneAction.LANE_ACTION_AHEAD) {// 推荐直行
                guide = R.drawable.landfront_70;
            } else if (laneSelectIndex == LaneAction.LANE_ACTION_LEFT) {// 推荐左转
                guide = R.drawable.landfront_71;
            } else if (laneSelectIndex == LaneAction.LANE_ACTION_RIGHT) {// 推荐右转
                guide = R.drawable.landfront_73;
            }
            // 左转和左转调头(1+5)
        } else if (laneBackInfoIndex == LaneAction.LANE_ACTION_LEFT_LU_TURN) {
            if (laneSelectIndex == LaneAction.LANE_ACTION_LU_TURN) {// 推荐左转调头
                guide = R.drawable.landfront_b5;
            } else if (laneSelectIndex == LaneAction.LANE_ACTION_LEFT) {// 推荐左转
                guide = R.drawable.landfront_b1;
            }
        } else if (laneBackInfoIndex == LaneAction.LANE_ACTION_LEFT_IN_LEFT_LU_TURN || laneBackInfoIndex == LaneAction.LANE_ACTION_LEFT_LU_TURN_EX) {
            if (laneSelectIndex == LaneAction.LANE_ACTION_LEFT) {// 推荐左转
                guide = R.drawable.landfront_e1;
            } else if (laneSelectIndex == LaneAction.LANE_ACTION_LU_TURN) {// 推荐左转调头
                guide = R.drawable.landfront_e5;
            }
        } else if (laneBackInfoIndex == LaneAction.LANE_ACTION_AHEAD_LEFT_LU_TURN) { // 直行+左转+左掉头
            if (laneSelectIndex == LaneAction.LANE_ACTION_AHEAD) {// 推荐直行
                guide = R.drawable.landfront_f0;
            } else if (laneSelectIndex == LaneAction.LANE_ACTION_LEFT) {// 推荐左转
                guide = R.drawable.landfront_f1;
            } else if (laneSelectIndex == LaneAction.LANE_ACTION_LU_TURN) {// 推荐左调头
                guide = R.drawable.landfront_f5;
            }
        } else if (laneBackInfoIndex == LaneAction.LANE_ACTION_RIGHT_RU_TURN_EX) { // 右转+右掉头,扩展
            if (laneSelectIndex == LaneAction.LANE_ACTION_RU_TURN) {// 推荐右转调头
                guide = R.drawable.landfront_c8;
            } else if (laneSelectIndex == LaneAction.LANE_ACTION_RIGHT) {// 推荐右转
                guide = R.drawable.landfront_c3;
            }
        } else if (laneBackInfoIndex == LaneAction.LANE_ACTION_LEFT_RU_TURN) { // 左转+右掉头
            if (laneSelectIndex == LaneAction.LANE_ACTION_LEFT) {// 推荐左转
                guide = R.drawable.landfront_j1;
            } else if (laneSelectIndex == LaneAction.LANE_ACTION_LU_TURN) {// 推荐右掉头
                guide = R.drawable.landfront_j8;
            }
        } else if (laneBackInfoIndex == LaneAction.LANE_ACTION_AHEAD_RIGHT_RU_TURN) { // 直行+右转+右掉头
            if (laneSelectIndex == LaneAction.LANE_ACTION_AHEAD) {// 推荐直行
                guide = R.drawable.landfront_70;
            } else if (laneSelectIndex == LaneAction.LANE_ACTION_RIGHT) {// 推荐右转
                guide = R.drawable.landfront_73;
            } else if (laneSelectIndex == LaneAction.LANE_ACTION_RU_TURN) {// 推荐右掉头
                guide = R.drawable.landfront_71;
            }
        } else if (laneBackInfoIndex == LaneAction.LANE_ACTION_BUS) { // 公交车道
            guide = R.drawable.landfront_kk;
        } else if (laneBackInfoIndex == LaneAction.LANE_ACTION_VARIABLE) { // 可变车道
            guide = R.drawable.landback_l;
        }

        if (guide == -1) {
            guide = driveWayGrayBgId[laneBackInfoIndex];
        }

        return guide;
    }

    /**
     * @param laneBackInfoIndex 背景对应的索引值
     */
    private boolean isLoadLaneSelectInfo(int laneBackInfoIndex,
                                         int laneSelectIndex) {
        if (laneSelectIndex == 255) {
            return false;
        }
        return true;
    }

}
