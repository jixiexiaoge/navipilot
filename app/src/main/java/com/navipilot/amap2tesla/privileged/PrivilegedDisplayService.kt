package com.navipilot.amap2tesla.privileged

import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class PrivilegedDisplayService : IPrivilegedDisplayService.Stub() {
    private val displays = ConcurrentHashMap<Int, DisplayRecord>()
    private val touchLock = Any()
    private val gestures = mutableMapOf<GestureKey, GestureState>()
    private val inputManager by lazy { initializeInputManager() }
    private val injectInputEventMethod by lazy {
        inputManager.javaClass.getMethod(
            "injectInputEvent",
            InputEvent::class.java,
            Int::class.javaPrimitiveType
        )
    }
    private val setDisplayIdMethod by lazy {
        InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
    }

    override fun probeCapabilities(): String {
        return try {
            val context = systemContext()
            val configClass = Class.forName("android.hardware.display.VirtualDisplayConfig")
            val globalClass = Class.forName("android.hardware.display.DisplayManagerGlobal")
            val hasCreateMethod = globalClass.declaredMethods.any { method ->
                method.name == "createVirtualDisplay" &&
                    method.parameterTypes.any { it == configClass }
            }
            if (!hasCreateMethod) {
                "不可用：找不到 DisplayManagerGlobal.createVirtualDisplay"
            } else {
                "可用：uid=${Process.myUid()}, sdk=${Build.VERSION.SDK_INT}, " +
                    "package=${context.packageName}, opPackage=${context.opPackageName}"
            }
        } catch (error: Throwable) {
            "不可用：${error.rootMessage()}"
        }
    }

    override fun createVirtualDisplay(
        name: String,
        width: Int,
        height: Int,
        densityDpi: Int,
        refreshRate: Float,
        surface: Surface
    ): Int {
        require(width > 0 && height > 0 && densityDpi > 0)
        require(surface.isValid)
        releaseAllDisplays()

        val context = systemContext()
        val configClass = Class.forName("android.hardware.display.VirtualDisplayConfig")
        val builderClass = Class.forName("android.hardware.display.VirtualDisplayConfig\$Builder")
        val builder = builderClass
            .getConstructor(String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .newInstance(name, width, height, densityDpi)

        builderClass.getMethod("setFlags", Int::class.javaPrimitiveType)
            .invoke(builder, virtualDisplayFlags())
        builderClass.getMethod("setSurface", Surface::class.java).invoke(builder, surface)
        if (refreshRate > 0f) {
            runCatching {
                builderClass.getMethod("setRequestedRefreshRate", Float::class.javaPrimitiveType)
                    .invoke(builder, refreshRate)
            }
        }
        val config = builderClass.getMethod("build").invoke(builder)

        val globalClass = Class.forName("android.hardware.display.DisplayManagerGlobal")
        val global = globalClass.getMethod("getInstance").invoke(null)
        val createMethod = globalClass.declaredMethods.firstOrNull { method ->
            method.name == "createVirtualDisplay" &&
                method.parameterTypes.any { it == configClass }
        } ?: error("找不到兼容的 createVirtualDisplay 方法")

        createMethod.isAccessible = true
        val arguments = createMethod.parameterTypes.map { type ->
            when {
                type == configClass -> config
                type == Context::class.java -> context
                type == String::class.java -> "com.android.shell"
                type == Boolean::class.javaPrimitiveType -> false
                type == Int::class.javaPrimitiveType -> 0
                type == Long::class.javaPrimitiveType -> 0L
                else -> null
            }
        }.toTypedArray()

        val display = createMethod.invoke(global, *arguments) as? VirtualDisplay
            ?: error("系统没有返回 VirtualDisplay")
        if (display.surface !== surface) {
            display.surface = surface
        }
        val displayId = display.display.displayId
        check(displayId > 0) { "系统返回了无效虚拟屏 ID：$displayId" }
        displays[displayId] = DisplayRecord(display, width, height)
        Log.i(TAG, "Created display $displayId: ${width}x$height@$densityDpi")
        return displayId
    }

    override fun launchPackage(displayId: Int, packageName: String): Boolean {
        if (!displays.containsKey(displayId)) return false
        val component = resolveLauncherComponent(packageName)
        if (component == null) {
            Log.e(TAG, "Could not resolve launcher for $packageName")
            return false
        }
        Log.i(TAG, "Launching $component on display $displayId")
        val result = shell(
            "am start -W --user 0 --display $displayId -f 0x10000000 -n ${quote(component)}"
        )
        if (result.exitCode != 0) {
            Log.e(TAG, "Launch failed: ${result.output}")
        }
        return result.exitCode == 0
    }

    override fun ensurePackageOnDisplay(displayId: Int, packageName: String): Int {
        if (!displays.containsKey(displayId)) return ENSURE_UNKNOWN_DISPLAY

        val stackList = shell("am stack list")
        if (stackList.exitCode != 0) {
            Log.e(TAG, "Could not inspect activity stacks: ${stackList.output}")
            return ENSURE_INSPECTION_FAILED
        }

        val task = ROOT_TASK_PATTERN.findAll(stackList.output)
            .firstOrNull { match ->
                match.value.contains("$packageName/", ignoreCase = true)
            }
        if (task == null) {
            return if (launchPackage(displayId, packageName)) {
                ENSURE_RESTORED
            } else {
                ENSURE_RESTORE_FAILED
            }
        }

        val rootTaskId = task.groupValues[1].toIntOrNull() ?: return ENSURE_INSPECTION_FAILED
        val currentDisplayId = task.groupValues[2].toIntOrNull() ?: return ENSURE_INSPECTION_FAILED
        if (currentDisplayId == displayId) return ENSURE_UNCHANGED

        Log.w(
            TAG,
            "$packageName moved from display $displayId to $currentDisplayId; " +
                "restoring root task $rootTaskId"
        )
        val move = shell("am display move-stack $rootTaskId $displayId")
        if (move.exitCode != 0) {
            Log.e(TAG, "Could not move root task $rootTaskId: ${move.output}")
            return ENSURE_RESTORE_FAILED
        }

        // Relaunching refreshes the layer stack after some vendor task managers move the task.
        return if (launchPackage(displayId, packageName)) {
            ENSURE_RESTORED
        } else {
            ENSURE_RESTORE_FAILED
        }
    }

    override fun injectTouch(
        displayId: Int,
        gestureId: Int,
        action: Int,
        x: Float,
        y: Float,
        pointerId: Int
    ): Boolean {
        val display = displays[displayId] ?: return false
        if (gestureId < 0 || pointerId !in 0 until MAX_POINTERS) return false
        if (!x.isFinite() || !y.isFinite()) return false

        synchronized(touchLock) {
            val key = GestureKey(displayId, gestureId)
            val now = SystemClock.uptimeMillis()
            val pointer = TrackedPointer(
                pointerId,
                x.coerceIn(0f, 1f) * (display.width - 1),
                y.coerceIn(0f, 1f) * (display.height - 1)
            )
            val state = gestures[key]
            val command = when (action and MotionEvent.ACTION_MASK) {
                MotionEvent.ACTION_DOWN -> {
                    val active = state ?: GestureState(now).also { gestures[key] = it }
                    if (active.pointers.containsKey(pointerId) ||
                        active.pointers.size >= MAX_POINTERS
                    ) {
                        return false
                    }
                    active.pointers[pointerId] = pointer
                    val pointerIndex = active.pointers.keys.indexOf(pointerId)
                    TouchCommand(
                        if (active.pointers.size == 1) {
                            MotionEvent.ACTION_DOWN
                        } else {
                            MotionEvent.ACTION_POINTER_DOWN or
                                (pointerIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                        },
                        active
                    )
                }
                MotionEvent.ACTION_MOVE -> {
                    val active = state ?: return false
                    if (!active.pointers.containsKey(pointerId)) return false
                    active.pointers[pointerId] = pointer
                    TouchCommand(MotionEvent.ACTION_MOVE, active)
                }
                MotionEvent.ACTION_UP -> {
                    val active = state ?: return false
                    if (!active.pointers.containsKey(pointerId)) return false
                    active.pointers[pointerId] = pointer
                    val pointerIndex = active.pointers.keys.indexOf(pointerId)
                    val eventAction = if (active.pointers.size == 1) {
                        MotionEvent.ACTION_UP
                    } else {
                        MotionEvent.ACTION_POINTER_UP or
                            (pointerIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                    }
                    TouchCommand(eventAction, active, removePointerId = pointerId)
                }
                MotionEvent.ACTION_CANCEL -> {
                    val active = state ?: return false
                    TouchCommand(MotionEvent.ACTION_CANCEL, active, cancelGesture = true)
                }
                else -> return false
            }

            val injected = injectMotionEvent(displayId, now, command)
            if (!injected || command.cancelGesture) {
                gestures.remove(key)
            } else if (command.removePointerId != null) {
                command.state.pointers.remove(command.removePointerId)
                if (command.state.pointers.isEmpty()) gestures.remove(key)
            }
            return injected
        }
    }

    override fun cancelTouch(displayId: Int, gestureId: Int) {
        synchronized(touchLock) {
            val key = GestureKey(displayId, gestureId)
            val state = gestures.remove(key) ?: return
            injectMotionEvent(
                displayId,
                SystemClock.uptimeMillis(),
                TouchCommand(MotionEvent.ACTION_CANCEL, state, cancelGesture = true)
            )
        }
    }

    override fun injectKey(displayId: Int, keyCode: Int): Boolean {
        if (!displays.containsKey(displayId)) return false
        if (keyCode != KeyEvent.KEYCODE_BACK) return false
        val now = SystemClock.uptimeMillis()
        return injectKeyEvent(displayId, now, KeyEvent.ACTION_DOWN, keyCode) &&
            injectKeyEvent(displayId, now, KeyEvent.ACTION_UP, keyCode)
    }

    override fun releaseDisplay(displayId: Int) {
        synchronized(touchLock) {
            gestures.keys.removeAll { it.displayId == displayId }
        }
        displays.remove(displayId)?.virtualDisplay?.release()
    }

    private fun releaseAllDisplays() {
        displays.keys.toList().forEach(::releaseDisplay)
    }

    private fun resolveLauncherComponent(packageName: String): String? {
        val result = shell(
            "cmd package resolve-activity --brief -a android.intent.action.MAIN " +
                "-c android.intent.category.LAUNCHER ${quote(packageName)}"
        )
        Log.i(TAG, "resolve-activity exit=${result.exitCode}: ${result.output}")
        if (result.exitCode != 0) return null
        return result.output.lineSequence()
            .map(String::trim)
            .lastOrNull { it.contains('/') && !it.contains("No activity", ignoreCase = true) }
    }

    private fun initializeInputManager(): Any {
        val context = systemContext()
        val inputManagerClass = Class.forName("android.hardware.input.InputManager")
        return context.getSystemService("input")
            ?: inputManagerClass.getMethod("getInstance").invoke(null)
            ?: error("InputManager system service is unavailable")
    }

    private fun injectMotionEvent(
        displayId: Int,
        eventTime: Long,
        command: TouchCommand
    ): Boolean {
        val pointers = command.state.pointers.values.toList()
        if (pointers.isEmpty()) return false
        val properties = pointers.map { pointer ->
            MotionEvent.PointerProperties().apply {
                id = pointer.id
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }.toTypedArray()
        val coordinates = pointers.map { pointer ->
            MotionEvent.PointerCoords().apply {
                x = pointer.x
                y = pointer.y
                pressure = 1f
                size = 1f
            }
        }.toTypedArray()
        val event = MotionEvent.obtain(
            command.state.downTime,
            eventTime,
            command.action,
            pointers.size,
            properties,
            coordinates,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0
        )
        return try {
            setDisplayIdMethod.invoke(event, displayId)
            val accepted = injectInputEventMethod.invoke(
                inputManager,
                event,
                INJECT_INPUT_EVENT_MODE_ASYNC
            ) == true
            if ((command.action and MotionEvent.ACTION_MASK) != MotionEvent.ACTION_MOVE) {
                Log.d(
                    TAG,
                    "Touch action=${command.action} display=$displayId " +
                        "pointers=${pointers.map { it.id }} accepted=$accepted"
                )
            }
            accepted
        } catch (error: Throwable) {
            Log.e(TAG, "Touch injection failed for display $displayId", error)
            false
        } finally {
            event.recycle()
        }
    }

    private fun injectKeyEvent(
        displayId: Int,
        eventTime: Long,
        action: Int,
        keyCode: Int
    ): Boolean {
        val event = KeyEvent(
            eventTime,
            eventTime,
            action,
            keyCode,
            0,
            0,
            0,
            0,
            0,
            InputDevice.SOURCE_KEYBOARD
        )
        return try {
            setDisplayIdMethod.invoke(event, displayId)
            injectInputEventMethod.invoke(
                inputManager,
                event,
                INJECT_INPUT_EVENT_MODE_ASYNC
            ) == true
        } catch (error: Throwable) {
            Log.e(TAG, "Key injection failed for display $displayId", error)
            false
        }
    }

    private fun systemContext(): Context {
        val activityThread = Class.forName("android.app.ActivityThread")
        val thread = activityThread.getMethod("currentActivityThread").invoke(null)
            ?: activityThread.getMethod("systemMain").invoke(null)
        val base = activityThread.getMethod("getSystemContext").invoke(thread) as Context
        val attributionSource = AttributionSource.Builder(Process.myUid())
            .setPackageName(SHELL_PACKAGE)
            .build()
        return ShellContext(base, attributionSource)
    }

    private fun virtualDisplayFlags(): Int {
        return when {
            Build.VERSION.SDK_INT >= 34 -> 23883
            Build.VERSION.SDK_INT >= 33 -> 7499
            else -> 331
        }
    }

    private fun shell(command: String): ShellResult {
        return try {
            val process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            if (!process.waitFor(SHELL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor(1, TimeUnit.SECONDS)
                return ShellResult(-1, "命令超时：$command")
            }
            val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            ShellResult(process.exitValue(), output.trim())
        } catch (error: Throwable) {
            ShellResult(-1, error.rootMessage())
        }
    }

    private fun quote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private data class ShellResult(val exitCode: Int, val output: String)

    private data class DisplayRecord(
        val virtualDisplay: VirtualDisplay,
        val width: Int,
        val height: Int
    )

    private data class GestureKey(val displayId: Int, val gestureId: Int)

    private data class TrackedPointer(val id: Int, val x: Float, val y: Float)

    private data class GestureState(
        val downTime: Long,
        val pointers: LinkedHashMap<Int, TrackedPointer> = LinkedHashMap()
    )

    private data class TouchCommand(
        val action: Int,
        val state: GestureState,
        val removePointerId: Int? = null,
        val cancelGesture: Boolean = false
    )

    companion object {
        private const val TAG = "Amap2TeslaPrivileged"
        private const val SHELL_PACKAGE = "com.android.shell"
        private const val SHELL_TIMEOUT_SECONDS = 8L
        private const val ENSURE_UNKNOWN_DISPLAY = -1
        private const val ENSURE_INSPECTION_FAILED = -2
        private const val ENSURE_RESTORE_FAILED = -3
        private const val ENSURE_UNCHANGED = 0
        private const val ENSURE_RESTORED = 1
        private const val MAX_POINTERS = 2
        private const val INJECT_INPUT_EVENT_MODE_ASYNC = 0
        private val ROOT_TASK_PATTERN = Regex(
            """RootTask id=(\d+).*?displayId=(\d+).*?(?=\r?\nRootTask id=|\z)""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
    }
}

private class ShellContext(
    base: Context,
    private val shellAttributionSource: AttributionSource
) : ContextWrapper(base) {
    override fun getPackageName(): String = "com.android.shell"

    override fun getOpPackageName(): String = "com.android.shell"

    override fun getAttributionTag(): String? = null

    override fun getAttributionSource(): AttributionSource = shellAttributionSource
}

private fun Throwable.rootMessage(): String {
    var current: Throwable = this
    while (current.cause != null) current = current.cause!!
    return current.message ?: current.javaClass.simpleName
}
