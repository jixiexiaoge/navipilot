package com.jixiexiaoge.drivingassist.navigation

import kotlin.math.*

/**
 * 坐标系转换工具
 * 
 * 中国地图使用 GCJ-02（火星坐标系），需要与 WGS-84（GPS坐标系）互转
 * 
 * 坐标系说明：
 * - WGS-84: GPS原始坐标，国际标准
 * - GCJ-02: 中国国家测绘局加密坐标（火星坐标），高德/腾讯使用
 * - BD-09: BD09 偏移坐标系（与 GCJ-02 可互转）
 */
object CoordinateConverter {
    // 长半轴
    private const val A = 6378245.0
    // 扁率
    private const val EE = 0.00669342162296594323
    
    // 中国境内判断（粗略矩形）
    private const val LAT_MIN = 0.8293
    private const val LAT_MAX = 55.8271
    private const val LON_MIN = 72.004
    private const val LON_MAX = 137.8347
    
    /**
     * 判断坐标是否在中国境内（粗略判断）
     */
    fun isInChina(lat: Double, lon: Double): Boolean {
        return lon in LON_MIN..LON_MAX && lat in LAT_MIN..LAT_MAX
    }
    
    /**
     * WGS-84 → GCJ-02（GPS → 火星坐标）
     * 用于：将GPS坐标转换为腾讯/高德地图坐标
     */
    fun wgs84ToGcj02(wgsLat: Double, wgsLon: Double): Pair<Double, Double> {
        if (!isInChina(wgsLat, wgsLon)) {
            // 国外不需要转换
            return Pair(wgsLat, wgsLon)
        }
        
        val (dLat, dLon) = delta(wgsLat, wgsLon)
        return Pair(wgsLat + dLat, wgsLon + dLon)
    }
    
    /**
     * GCJ-02 → WGS-84（火星坐标 → GPS）
     * 用于：将腾讯/高德地图坐标转换为GPS坐标
     * 
     * 使用迭代法求精确逆变换
     * 优化：添加收敛监控和日志记录
     */
    fun gcj02ToWgs84(gcjLat: Double, gcjLon: Double): Pair<Double, Double> {
        if (!isInChina(gcjLat, gcjLon)) {
            return Pair(gcjLat, gcjLon)
        }
        
        // 迭代逼近
        var wgsLat = gcjLat
        var wgsLon = gcjLon
        var converged = false
        var iterations = 0
        
        // 收敛阈值：约0.01毫米精度
        val convergenceThreshold = 1e-9
        
        for (i in 0 until 10) {
            iterations = i + 1
            val (tmpLat, tmpLon) = wgs84ToGcj02(wgsLat, wgsLon)
            val dLat = gcjLat - tmpLat
            val dLon = gcjLon - tmpLon
            
            wgsLat += dLat
            wgsLon += dLon
            
            // 精度足够，提前退出
            if (abs(dLat) < convergenceThreshold && abs(dLon) < convergenceThreshold) {
                converged = true
                break
            }
        }
        
        // 记录未收敛情况（用于监控）
        if (!converged) {
            val (finalLat, finalLon) = wgs84ToGcj02(wgsLat, wgsLon)
            val finalErrorLat = gcjLat - finalLat
            val finalErrorLon = gcjLon - finalLon
            val finalErrorMeters = sqrt(finalErrorLat * finalErrorLat + finalErrorLon * finalErrorLon) * 111000
            
            android.util.Log.w(
                "CoordinateConverter",
                "坐标转换未完全收敛: 迭代${iterations}次, 最终误差${String.format("%.3f", finalErrorMeters)}米 " +
                "(lat=$gcjLat, lon=$gcjLon)"
            )
        }
        
        return Pair(wgsLat, wgsLon)
    }
    
    /**
     * 计算偏移量
     */
    private fun delta(lat: Double, lon: Double): Pair<Double, Double> {
        val dLat = transformLat(lon - 105.0, lat - 35.0)
        val dLon = transformLon(lon - 105.0, lat - 35.0)
        
        val radLat = lat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        
        val deltaLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI)
        val deltaLon = (dLon * 180.0) / (A / sqrtMagic * cos(radLat) * PI)
        
        return Pair(deltaLat, deltaLon)
    }
    
    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * PI) + 320.0 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }
    
    private fun transformLon(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }
    
    /**
     * 对一对经纬度应用 WGS-84 → GCJ-02 转换
     */
    fun Pair<Double, Double>.toGcj02(): Pair<Double, Double> {
        return wgs84ToGcj02(this.first, this.second)
    }

    /**
     * 对一对经纬度应用 GCJ-02 → WGS-84 转换
     */
    fun Pair<Double, Double>.toWgs84(): Pair<Double, Double> {
        return gcj02ToWgs84(this.first, this.second)
    }
}
