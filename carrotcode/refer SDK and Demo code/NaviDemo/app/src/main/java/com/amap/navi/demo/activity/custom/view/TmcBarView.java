package com.amap.navi.demo.activity.custom.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;

import com.amap.api.navi.model.AMapTrafficStatus;

import java.util.List;

public class TmcBarView extends View {

    /**
     * 显示Pop距离的最小阈值
     */
    private static final int DISTANCE_MIN = 10;
    /**
     * 显示Pop距离的最大阈值
     */
    private static final int DISTANCE_MID = 5 * 1000;
    /**
     * 显示Pop距离的最大阈值
     */
    private static final int DISTANCE_MAX = 50 * 1000;


    /**
     * 绘制TmcBar的公用画笔
     */
    private Paint mPaint;
    private List<AMapTrafficStatus> mTmcBarItems;
    private int mRouteTotalLength;
    /**
     * 光标位置
     */
    private float mCursorPos; //值由大到小递减
    /**
     * Tmc状态监听
     */
    private TmcBarListener mTmcBarListener;
    /**
     * 由于canvas中操作tmcTag对象过于频繁，性能考虑cache一份
     */
    private final TmcTag mTagCache = new TmcTag();

    //因为上边的遮罩有问题，矩形有可能有透出，所以clip下，防止透出 // fix #9826694
    private int mPathHeight;
    private Path mPath = new Path();
    private float[] radiusArray = new float[8];

    public TmcBarView(Context context) {
        super(context);
        init();
    }


    public TmcBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public TmcBarView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2) {
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }
    }


    public void setData(List<AMapTrafficStatus> items, int totalLength) {
        mTmcBarItems = items;
        mRouteTotalLength = totalLength;
    }
    
    /**
     * 设置自车View对象
     * @param mDefaultTmcBarCarView
     */
    ImageView mDefaultTmcBarCarView = null;
    public void setCarView(ImageView carView){
    		mDefaultTmcBarCarView = carView;
    }
    
    /**
     * 设置游标位置
     *
     * @param cursorPos
     */
    float mCursorRatio = 0;
    public void setCursorPos(int retainLength) {
        mCursorRatio = (retainLength > 20 ? retainLength - 20 : retainLength * 1.0f) / (mRouteTotalLength * 1.0f);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        try {
            int width = getWidth();
            int height = getHeight();
            mCursorPos = mCursorRatio * height;
            if(mDefaultTmcBarCarView != null){
                mDefaultTmcBarCarView.setTranslationY(mCursorPos);
                mDefaultTmcBarCarView.invalidate();
            }
            //计算矩形内的填充高度
            if (mTmcBarItems != null && height > 0) {

                if (mPathHeight != height) {
                    mPath.reset();
                    for (int i = 0; i < radiusArray.length; i++) {
                        radiusArray[i] = width / 4;
                    }
                    mPath.addRoundRect(new RectF(0, 0, width, height), radiusArray, Path.Direction.CW);
                    mPathHeight = height;
                }
                canvas.save();
                canvas.clipPath(mPath);

                int tmcBarLength = mTmcBarItems.size();
                //计算过程中实际距离转换成像素的长度累加
                float pixelDistanceSum = -10;
                //计算过程中实际距离的累加
                int realDistanceSum = 0;
                //标记是否有需要显示Pop信息的Item
                boolean shouldPopItem = false;
                //距离和View高度的比率,用于在view高度和实际距离之间进行转换,单位:像素/米
                float rateDistanceToViewHeight = (height * 1.0f) / (mRouteTotalLength * 1.0f);
                //从上往下绘制
                for (int i = tmcBarLength - 1; i >= 0; i--) {
                    AMapTrafficStatus item = mTmcBarItems.get(i);
                    realDistanceSum += item.getLength();
                    //计算tmcBar在绘制过程中每一小段的长度
                    float itemHeight = Math.round(item.getLength() * rateDistanceToViewHeight * 100) * 0.01f;

                    if (item.getStatus() >= 2) { //只计算拥堵气泡的显示位置
                        //当前item距离车的位置
                        int distanceFromCar = (int) (mCursorPos / rateDistanceToViewHeight - realDistanceSum);
                        if (pixelDistanceSum <= mCursorPos && distanceFromCar < DISTANCE_MAX) {
                            if (distanceFromCar <= DISTANCE_MID) { //2.距离自车位小于5公里，且拥堵长度大于10米才显示。
    //                            if (updateTmcTag(item.status, item.length, pixelDistanceSum, itemHeight) && item.length > 10) {
    //                                shouldPopItem = true;
    //                            }
                            }
                        }
                    }
                    pixelDistanceSum += itemHeight;
                    canvas.drawRect(0, pixelDistanceSum - itemHeight, width, pixelDistanceSum,
                            getPaintInColor(getColor(item.getStatus())));// 画矩形

                }
                //补全光柱图,使用颜色==第一段的颜色
                if (pixelDistanceSum < height) {
                    // 画矩形
                    canvas.drawRect(0, pixelDistanceSum, width, height, getPaintInColor(getColor(mTmcBarItems.get(0).getStatus())));
                }
                //走过的路使用灰色
                if (height > mCursorPos) {
                    canvas.drawRect(0, mCursorPos, width, height, getPaintInColor(getColor(-1)));// 画矩形
                }
                //通知监听需要Pop信息的item
                if (mTmcBarListener != null) {
                    if (!shouldPopItem) {
                        mTmcBarListener.dismissBottomTag();
                    } else {
                        mTmcBarListener.showBottomTag(mTagCache);
                        if (mTagCache.viewHeight < 1) { //如果要画的气泡所在的拥堵段小于1像素，则画出最低的一像素在光柱图上，以便于区分，否则线太细看不见
                            if (mTagCache.translationY > height) { //如果计算出的长度大于总长度，则在光柱图最末尾画出一像素以示意气泡位置
                                canvas.drawRect(0, height - 1, width, height,
                                        getPaintInColor(getColor(mTagCache.status)));// 画矩形
                            } else {
                                canvas.drawRect(0, mTagCache.translationY, width, mTagCache.translationY + 1,
                                        getPaintInColor(getColor(mTagCache.status)));// 画矩形
                            }

                        }
                    }
                }
                canvas.restore();
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    /**
     * 获取指定颜色的画笔.
     *
     * @param color 画笔颜色
     * @return 设置成指定颜色的画笔
     * @author jiawu.ljw
     */
    private Paint getPaintInColor(int color) {
        if (mPaint == null) {
            mPaint = new Paint();
            mPaint.setAntiAlias(true);
            mPaint.setStyle(Paint.Style.FILL);
        }
        mPaint.setColor(color);
        return mPaint;
    }


    /**
     * 根据路况取对应的颜色值
     *
     * @param status
     * @return
     */
    private int getColor(int status) {
        switch (status) {
            case 0:
                return Color.parseColor("#0091FF");
            case 1:
                return Color.parseColor("#00BA1F");
            case 2:
                return Color.parseColor("#FFBA00");
            case 3:
                return Color.parseColor("#F31D20");
            case 4:
                return Color.parseColor("#A8090B");
            default:
                return Color.parseColor("#D1D1D1");
        }
    }


    /**
     * 设置Pop状态监听.
     *
     * @param listener
     */
    public void setTacBarListener(TmcBarListener listener) {
        mTmcBarListener = listener;
    }

    /**
     * TmcBar状态监听，用于是否显示Pop信息
     */
    public interface TmcBarListener {
        void showBottomTag(TmcTag tmcTag); //显示底部tag

        void dismissBottomTag();//隐藏底部tag
    }

    /**
     * 光柱图左侧标签显示内容封装类
     */
    public static class TmcTag {
        public int status;//状态
        public int translationY; //相对坐标
        public int roadLength; //道路长度
        public int bgResId; //textview控件背景资源
        public int textColor; //字体颜色
        public float viewHeight; //tag对应的拥堵段在光柱图上的高度
        public int index; //在数组中的序号
    }
}
