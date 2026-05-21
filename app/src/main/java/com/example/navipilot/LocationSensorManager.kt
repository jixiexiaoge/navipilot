package com.example.navipilot

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.core.app.ActivityCompat
import kotlin.math.atan2

/**
 * 位置和传感器管理器
 * 负责 GPS 位置更新、传感器监听
 */
class LocationSensorManager(
    private val context: Context,
    private val carrotManFields: MutableState<CarrotManFields>
) : SensorEventListener {

    companion object {
        private const val TAG = "LocationSensorManager"
    }

    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager
    private var rotationVectorSensor: Sensor? = null
    private var accelerometer: Sensor? = null
    private var magnetometer: Sensor? = null

    private val accelerometerReading = FloatArray(3)
    private val magnetometerReading = FloatArray(3)
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    private var isLocationUpdatesStarted = false

    /**
     * 初始化传感器
     */
    fun initializeSensors() {
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        magnetometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        rotationVectorSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }

        Log.i(TAG, "✅ 传感器已初始化")
    }

    /**
     * 启动位置更新
     */
    fun startLocationUpdates() {
        if (isLocationUpdatesStarted) return

        locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "❌ 缺少定位权限")
            return
        }

        try {
            // 优先使用 GPS 提供者
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    500L,  // 500ms
                    0f,    // 0米
                    locationListener
                )
            }

            // 回退到网络提供者
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    1000L,
                    0f,
                    locationListener
                )
            }

            isLocationUpdatesStarted = true
            Log.i(TAG, "✅ 位置更新已启动")
        } catch (e: Exception) {
            Log.e(TAG, "❌ 位置更新启动失败: ${e.message}")
        }
    }

    /**
     * GPS 状态检查
     */
    @Suppress("DEPRECATION")
    fun checkGpsStatus(): Map<String, Any> {
        val gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val satellites = try {
            val gpsStatus = locationManager.getGpsStatus(null)
            gpsStatus?.let {
                val sb = StringBuilder()
                sb.append("卫星数: ${it.maxSatellites}")
                mapOf(
                    "enabled" to gpsEnabled,
                    "satellites" to it.maxSatellites,
                    "info" to sb.toString()
                )
            } ?: mapOf("enabled" to gpsEnabled, "satellites" to 0, "info" to "无")
        } catch (e: Exception) {
            mapOf("enabled" to gpsEnabled, "satellites" to 0, "info" to "错误: ${e.message}")
        }
        @Suppress("UNCHECKED_CAST")
        return satellites as Map<String, Any>
    }

    /**
     * 获取 GPS 实时报告
     */
    fun getGpsRealtimeReport(): String {
        val fields = carrotManFields.value
        return buildString {
            append("位置: ${fields.vpPosPointLat}, ${fields.vpPosPointLon}\n")
            append("速度: ${fields.vEgoKph} km/h\n")
            append("角度: ${fields.nPosAngle}°\n")
        }
    }

    /**
     * 清理资源
     */
    fun cleanup() {
        try {
            locationManager.removeUpdates(locationListener)
            sensorManager.unregisterListener(this)
            isLocationUpdatesStarted = false
            Log.i(TAG, "✅ 位置管理器已清理")
        } catch (e: Exception) {
            Log.e(TAG, "清理失败: ${e.message}")
        }
    }

    /**
     * 获取位置状态
     */
    fun getLocationStatus(): Map<String, Any?> {
        val fields = carrotManFields.value
        return mapOf(
            "latitude" to fields.vpPosPointLat,
            "longitude" to fields.vpPosPointLon,
            "speed" to fields.vEgoKph,
            "bearing" to fields.nPosAngle,
            "accuracy" to fields.accuracy
        )
    }

    /**
     * 重置错误状态
     */
    fun resetErrorState() {
        // 重新启动位置更新
        cleanup()
        startLocationUpdates()
    }

    /**
     * 刷新GPS位置
     */
    fun refreshGpsLocation() {
        if (!isLocationUpdatesStarted) {
            startLocationUpdates()
        }
    }

    // 位置监听器
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            updateLocation(location)
        }

        override fun onProviderEnabled(provider: String) {
            Log.d(TAG, "Provider enabled: $provider")
        }

        override fun onProviderDisabled(provider: String) {
            Log.d(TAG, "Provider disabled: $provider")
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    /**
     * 更新位置到 CarrotManFields
     */
    private fun updateLocation(location: Location) {
        val lat = location.latitude
        val lon = location.longitude
        val speed = if (location.hasSpeed()) location.speed else 0f
        val bearing = if (location.hasBearing()) location.bearing else 0f
        val accuracy = if (location.hasAccuracy()) location.accuracy else 0f

        // 计算方向（基于加速度计和磁力计）
        var compassAngle = orientationAngles[0]
        if (compassAngle.isNaN()) compassAngle = bearing

        carrotManFields.value = carrotManFields.value.copy(
            // 🌍 主 GPS 坐标（build7706Payload 发送的字段）
            latitude = lat,
            longitude = lon,
            heading = compassAngle.toDouble(),
            accuracy = accuracy.toDouble(),
            gps_speed = speed.toDouble(),
            vEgoKph = (speed * 3.6).toInt()
        )

        //Log.d(TAG, "📍 位置更新: lat=$lat, lon=$lon, speed=${speed * 3.6}km/h")
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, accelerometerReading, 0, 3)
                updateOrientation()
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, magnetometerReading, 0, 3)
                updateOrientation()
            }
            Sensor.TYPE_ROTATION_VECTOR -> {
                val rotationMatrix = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                val orientation = FloatArray(3)
                SensorManager.getOrientation(rotationMatrix, orientation)
                orientationAngles[0] = Math.toDegrees(orientation[0].toDouble()).toFloat()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun updateOrientation() {
        if (SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerReading, magnetometerReading)) {
            SensorManager.getOrientation(rotationMatrix, orientationAngles)
            orientationAngles[0] = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
        }
    }
}