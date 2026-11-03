package com.deskpet.overlay

import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.app.PendingIntent
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.deskpet.data.PetRepository
import com.deskpet.data.model.DragDirection
import com.deskpet.data.model.PetAnimationState
import com.deskpet.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlin.math.abs

class PetOverlayService : LifecycleService(), ViewModelStoreOwner, SavedStateRegistryOwner {

    override val viewModelStore = ViewModelStore()
    override val savedStateRegistry = SavedStateRegistryController.create(this).also { it.performRestore(null) }.savedStateRegistry

    private lateinit var wm: WindowManager
    private var cv: ComposeView? = null
    private lateinit var repo: PetRepository
    private lateinit var engine: PetEngine
    private lateinit var anim: AnimationController
    private lateinit var gesture: GestureHandler
    private lateinit var parser: SpritesheetParser
    private var auto: AutonomousBehavior? = null
    private var petId: String? = null
    private var job: Job? = null
    private var scaleJob: Job? = null
    /** 捏合缩放回写 DataStore 的防抖任务 */
    private var scaleSyncJob: Job? = null
    private var wx = 0f
    private var wy = 200f
    private var savedScale = 1f
    private val sc = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /** 亮灭屏状态：省电广播到来时需要据此决定是否恢复自主行为（灭屏时不能开） */
    @Volatile private var screenOn = true

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: android.content.Context?, i: Intent?) {
            if (!::anim.isInitialized) return
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            when (i?.action) {
                // 灭屏把动画循环和自主行为一起停，否则 auto 会在灭屏期间切状态，
                // 亮屏后宠物会播放一段"没来由"的动作
                Intent.ACTION_SCREEN_OFF -> { screenOn = false; anim.stop(); auto?.stop() }
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    anim.start()
                    // 省电模式下自主乱跑属于"非必要活动"，不开
                    if (!pm.isPowerSaveMode) auto?.start()
                }
                // 系统省电开关切换（官方 isPowerSaveMode + 广播的标准联动）：
                // 帧率减半交给 AnimationController.powerSaveMode；自主行为
                // 按需停/恢复（恢复仅限亮屏状态）
                android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                    anim.powerSaveMode = pm.isPowerSaveMode
                    if (pm.isPowerSaveMode) auto?.stop()
                    else if (screenOn) auto?.start()
                }
            }
        }
    }

    private fun onDrag(dx: Float, dy: Float, d: DragDirection) {
        wx += dx
        wy += dy
        updatePosition()
        if (anim.isCodexPet()) {
            anim.switchState(gesture.mapDirectionToState(d))
        }
        auto?.onUserTouch()
    }

    private fun onTap() {
        if (anim.isCodexPet()) {
            anim.switchState(PetAnimationState.WAVING)
        } else {
            bounce()
        }
        auto?.onUserTouch()
    }

    private fun onScale(zoom: Float, focusX: Float, focusY: Float) {
        // 与 AnimationController / UI 滑块保持一致：50%~500%
        val ns = (savedScale * zoom).coerceIn(0.5f, 5f)
        // clamp 截断后实际生效的比例，位置补偿必须用它，否则锚点会漂
        val applied = if (savedScale > 0f) ns / savedScale else 1f
        savedScale = ns
        // 先更新尺寸（petWidth 状态同步生效），后续 clampPosition 才能用新尺寸
        // 把放大后的窗口夹回屏幕内，而不是放行一帧溢出
        anim.setScale(ns)
        // 捏合缩放同步回 DataStore（防抖落盘，捏合过程中不写）：否则列表页
        // 滑块还显示旧值，之后任意一次滑块微调会让宠物从捏合后的大小"瞬移"
        // 回滑块值。observeScale 收到回写后差值≈0 不会回环
        val pid = petId
        if (pid != null && applied != 1f) {
            scaleSyncJob?.cancel()
            scaleSyncJob = sc.launch {
                delay(400)
                try {
                    repo.setScale(pid, savedScale)
                } catch (e: Exception) {
                    Log.w(TAG, "scale sync failed", e)
                }
            }
        }
        if (applied != 1f) {
            // 以双指中心（centroid）为锚点缩放：窗口尺寸从 W 变为 W*applied 时，
            // 把窗口左上角反向平移 focus*(1-applied)，手指夹住的点保持不动。
            // 旧实现只改大小不补位置，放大时宠物朝右下"逃"、缩小时朝左上"缩"。
            wx += focusX * (1f - applied)
            wy += focusY * (1f - applied)
            updatePosition()
        }
        auto?.onUserTouch()
    }

    /** 手势结束时立刻落盘，避免进程被杀导致位置丢失 */
    private fun persistPositionSoon() {
        if (petId == null) return
        sc.launch {
            try {
                withTimeout(2000L) { persistPosition() }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save position", e)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        repo = (application as com.deskpet.DeskPetApp).petRepository
        parser = SpritesheetParser()
        engine = PetEngine(parser, GifRenderer())
        anim = AnimationController(engine, sc)
        gesture = GestureHandler(
            onDrag = ::onDrag,
            onTap = ::onTap,
            isCodexPet = anim::isCodexPet,
            onScale = ::onScale,
            onDragEnd = ::persistPositionSoon
        )
        registerReceiverSafely()
        startForeground()
    }

    /**
     * targetSdk 34+ 要求动态注册 receiver 时声明 exported 标志；
     * SCREEN_ON/OFF 是系统 protected broadcast，用 NOT_EXPORTED 即可正常收到。
     */
    private fun registerReceiverSafely() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        androidx.core.content.ContextCompat.registerReceiver(
            this, screenReceiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // 注册时同步一次省电/亮灭屏状态：服务可能在省电已开启或灭屏期间才启动
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        if (::anim.isInitialized) {
            anim.powerSaveMode = pm.isPowerSaveMode
        }
        // START_STICKY 可能在灭屏期间拉起服务，此时不会有 SCREEN_OFF 广播到来，
        // 不主动查一次就会在锁屏上白跑动画（loadPet 据此决定是否立即启动）
        screenOn = pm.isInteractive
    }

    private fun startForeground() {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.createNotificationChannel(android.app.NotificationChannel(
                CHANNEL_ID, "桌面宠物", android.app.NotificationManager.IMPORTANCE_LOW
            ).apply { description = "宠物悬浮窗运行中" })

            val contentIntent = android.app.PendingIntent.getActivity(
                this, 0,
                Intent(this, com.deskpet.MainActivity::class.java).apply {
                    // NEW_TASK 保证服务里能启动 Activity；CLEAR_TOP|SINGLE_TOP
                    // 复用已存在的实例走 onNewIntent——否则应用开着时点通知，
                    // 返回栈里会再叠一个重复的列表页
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                },
                android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val ntf = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("桌面宠物")
                .setContentText("正在运行")
                .setSmallIcon(android.R.drawable.ic_menu_gallery)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                // 通知栏直接关闭宠物，不用回到 App 点「关闭」
                .addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "关闭宠物",
                    PendingIntent.getService(
                        this, 1,
                        Intent(this, PetOverlayService::class.java).setAction(ACTION_STOP),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFY_ID, ntf, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFY_ID, ntf)
            }
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed", e)
            Toast.makeText(this@PetOverlayService, "前台服务失败: ${e.message}", Toast.LENGTH_LONG).show()
            // 只 catch 不停服没用：系统 5 秒超时后仍会抛 RemoteServiceException
            // （"did not then call startForeground"）直接崩溃，必须主动止损
            stopSelf()
        }
    }

    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        // LifecycleService 需要 super 来派发生命周期事件（否则 lifecycle 一直停在 CREATED）
        super.onStartCommand(i, f, s)
        // 通知栏「关闭宠物」按钮：这是用户主动关闭，要清掉自动恢复标志，
        // 否则下次打开 App 宠物会"复活"（违背用户刚才的关闭意图）
        if (i?.action == ACTION_STOP) {
            repo.setOverlayAutoResumeAsync(false)
            stopSelf()
            return START_NOT_STICKY
        }
        job?.cancel()
        job = sc.launch {
            try {
                val id = i?.getStringExtra(EXTRA_PET_ID) ?: repo.getActivePetId().first()
                if (id.isNullOrBlank()) {
                    Log.w(TAG, "No pet id to show")
                    stopSelf()
                    return@launch
                }
                show(id)
            } catch (e: Exception) {
                Log.e(TAG, "show failed", e)
                Toast.makeText(this@PetOverlayService, "启动失败: ${e.message}", Toast.LENGTH_LONG).show()
                OverlayState.setRunning(null)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        // 异步落盘，不用 runBlocking 阻塞主线程（最多 2s 的 ANR 余量风险）；
        // 手势结束时的同步保存已经兜底了主要的数据丢失路径
        persistPositionAsync()
        OverlayState.setRunning(null)
        scaleJob?.cancel()
        removeOverlayView()
        if (::anim.isInitialized) anim.stop()
        auto?.stop()
        if (::engine.isInitialized) engine.release()
        try {
            unregisterReceiver(screenReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister screen receiver", e)
        }
        viewModelStore.clear()
        sc.cancel()
        super.onDestroy()
    }

    /** Manifest 里 stopWithTask="false"（悬浮窗应用标准做法）：划掉最近任务
     *  只关 App 界面，本服务带着宠物继续显示，本方法在划掉时回调——只做
     *  位置落盘这类收尾，绝不 stopSelf（否则配置白改、宠物照样消失）。
     *  关闭路径 = 通知栏「关闭宠物」/ 收纳坞开关 / 用户强停。 */
    override fun onTaskRemoved(r: Intent?) {
        super.onTaskRemoved(r)
        persistPositionAsync()
    }

    /** 在独立 IO 作用域异步保存位置，服务销毁后仍能完成写入 */
    private fun persistPositionAsync() {
        petId?.let { repo.savePositionAsync(it, wx, wy, savedScale) }
    }

    override fun onConfigurationChanged(c: Configuration) {
        super.onConfigurationChanged(c)
        val p = displaySize()
        if (wx > p.x || wy > p.y) {
            wx = 100f
            wy = 100f
        }
        clampPosition()
        updatePosition()
    }

    private suspend fun persistPosition() {
        val id = petId ?: return
        repo.savePosition(id, wx, wy, savedScale)
    }

    private suspend fun show(id: String) {
        // 权限可能在运行期间被用户撤销（撤销时系统会立刻移除悬浮窗但服务还在）：
        // START_STICKY 重启或切换宠物时会走到这里，先快速失败，不做无谓的加载 IO
        if (!Settings.canDrawOverlays(this)) {
            OverlayState.setRunning(null)
            stopSelf()
            return
        }
        val info = withContext(Dispatchers.IO) { repo.getPet(id) }
        if (info == null) {
            Log.w(TAG, "Pet not found: $id")
            Toast.makeText(this, "宠物不存在，请重新选择", Toast.LENGTH_SHORT).show()
            OverlayState.setRunning(null)
            stopSelf()
            return
        }
        // 切换宠物前保存上一只的位置
        if (petId != null && petId != id) {
            try {
                withTimeout(2000L) { persistPosition() }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save previous pet position", e)
            }
        }
        petId = id
        wx = info.positionX
        wy = info.positionY
        // 解析 pet.json / 读取图片尺寸都是磁盘 IO，必须离开主线程
        val loaded = withContext(Dispatchers.IO) { anim.reload(info) }
        if (!loaded) {
            Toast.makeText(this, "宠物加载失败，文件可能已损坏", Toast.LENGTH_SHORT).show()
            OverlayState.setRunning(null)
            stopSelf()
            return
        }
        anim.setScale(info.scale)
        savedScale = info.scale
        // 灭屏期间被系统拉起时不启动动画，等 SCREEN_ON 广播再开（registerReceiverSafely 已同步 screenOn）
        if (screenOn) anim.start()
        if (!add()) {
            OverlayState.setRunning(null)
            // 不停服会留下"正在运行"的通知和常驻前台服务，但宠物根本没显示
            stopSelf()
            return
        }
        observeScale(id)
        auto?.stop()
        auto = AutonomousBehavior(anim, sc)
        // 亮屏且非省电才启动自主行为；灭屏/省电场景由广播负责恢复
        if (screenOn && !anim.powerSaveMode) auto?.start()
        OverlayState.setRunning(id)
    }

    /** 悬浮窗运行期间跟随仓库里的缩放值（界面上的缩放滑块）*/
    private fun observeScale(id: String) {
        scaleJob?.cancel()
        scaleJob = sc.launch {
            repo.getAllPets().collect { pets ->
                val info = pets.find { it.id == id } ?: return@collect
                if (abs(info.scale - savedScale) > 0.001f) {
                    savedScale = info.scale
                    anim.setScale(info.scale)
                }
            }
        }
    }

    private fun add(): Boolean {
        if (!::wm.isInitialized) return false
        if (!Settings.canDrawOverlays(this)) {
            // 权限引导由 MainActivity 负责（它有 pendingStart + ON_RESUME 重试的完整流程）。
            // 这里不要跳设置页：Android 10+ 服务在"无权限"状态下启动 Activity 会被
            // 系统静默拦截（有 SYSTEM_ALERT_WINDOW 的 App 才豁免），跳了也白跳。
            return false
        }
        removeOverlayView()
        cv = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@PetOverlayService)
            setViewTreeViewModelStoreOwner(this@PetOverlayService)
            setViewTreeSavedStateRegistryOwner(this@PetOverlayService)
            setContent { PetOverlayRenderer(anim, gesture.modifier) }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            // 用绝对方向 LEFT 而非 START：RTL 语言（阿拉伯语/希伯来语等）下
            // START 会镜像成"距右边缘"，而 wx/clampPosition 全按左边缘计算——
            // 宠物位置镜像错位、拖拽方向相反、夹取越界。LEFT 对任何 locale 稳定
            gravity = Gravity.TOP or Gravity.LEFT
            x = wx.toInt()
            y = wy.toInt()
        }
        return try {
            wm.addView(cv, params)
            true
        } catch (e: Exception) {
            Log.e(TAG, "addView", e)
            cv = null
            Toast.makeText(this, "悬浮窗添加失败: ${e.message}", Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun removeOverlayView() {
        cv?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove overlay view", e)
            }
        }
        cv = null
    }

    private fun updatePosition() {
        clampPosition()
        cv?.let { v ->
            (v.layoutParams as? WindowManager.LayoutParams)?.let { p ->
                p.x = wx.toInt()
                p.y = (wy + bounceOffset).toInt()
                try {
                    wm.updateViewLayout(v, p)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to update overlay position", e)
                }
            }
        }
    }

    private fun clampPosition() {
        // 安全区夹取（与主界面 safeDrawing 同一病根的悬浮窗版）：本窗口带
        // FLAG_LAYOUT_NO_LIMITS，可以画进状态栏/导航条/挖孔底下——底部是
        // 系统手势区（拖宠物的手势会被抢走）、挖孔会遮宠物。用
        // currentWindowMetrics.windowInsets 取显示区域的安全边距（它报告
        // 的是显示器自身的 insets，不受本窗口 NO_LIMITS 影响）；API < 30
        // 没有此 API，回退为旧行为（全屏边界）。垂直方向完全收进安全区，
        // 水平方向保留"露把手"的部分出屏玩法
        val sb = safeBounds()
        val w = anim.petWidth.value
        val h = anim.petHeight.value
        // 允许宠物左右留一部分在屏幕外，但必须留出"把手"能拖回来。
        // 旧代码硬编码 32px，高分屏（3x 密度）上只有约 10dp，几乎抓不住。
        val margin = 24f * resources.displayMetrics.density
        wx = wx.coerceIn((sb.left - w + margin), (sb.right - margin))
        wy = wy.coerceIn(sb.top.toFloat(), (sb.bottom - h).toFloat().coerceAtLeast(sb.top.toFloat()))
    }

    /** 安全区矩形（扣除系统栏 + 挖孔）。API 30+ 精确；旧版本回退全屏。 */
    private fun safeBounds(): android.graphics.Rect {
        val b = displayBounds()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val types = android.view.WindowInsets.Type.systemBars() or
                android.view.WindowInsets.Type.displayCutout()
            val ins = wm.currentWindowMetrics.windowInsets.getInsets(types)
            return android.graphics.Rect(
                b.left + ins.left, b.top + ins.top,
                b.right - ins.right, b.bottom - ins.bottom
            )
        }
        return b
    }

    /** 当前旋转下的显示区域边界（区分于 displaySize() 的宽高形式） */
    private fun displayBounds(): android.graphics.Rect {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wm.currentWindowMetrics.bounds
        } else {
            val p = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getSize(p)
            android.graphics.Rect(0, 0, p.x, p.y)
        }
    }

    /** API 30+ 用 currentWindowMetrics（跟随当前旋转）；旧版本回退 defaultDisplay。
     *  注意不能用 maximumWindowMetrics——它不随旋转变化，旋转后 clamp 维度错乱，
     *  宠物会被"夹"到当前屏幕之外再也找不到。 */
    private fun displaySize(): Point {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wm.currentWindowMetrics.bounds.let { Point(it.width(), it.height()) }
        } else {
            @Suppress("DEPRECATION")
            Point().also { wm.defaultDisplay.getSize(it) }
        }
    }

    /** 单击 GIF 宠物的弹跳反馈。同一时间只允许一个弹跳：
     *  旧实现并发协程各自捕获基准 wy，连点会导致位置永久漂移（每次 +20px 漂向一边）。 */
    private var bounceJob: Job? = null
    /** 弹跳的瞬时视觉偏移（px）。只影响渲染窗口位置，绝不写入 wy——
     *  旧实现 delay 后把 wy 硬改回旧值，100ms 窗口内的拖拽/缩放会被静默丢弃。 */
    @Volatile
    private var bounceOffset = 0f

    private fun bounce() {
        if (bounceJob?.isActive == true) return
        bounceJob = sc.launch {
            try {
                bounceOffset = -8f * resources.displayMetrics.density
                updatePosition()
                delay(100)
            } finally {
                bounceOffset = 0f
                updatePosition()
            }
        }
    }

    companion object {
        const val EXTRA_PET_ID = "pet_id"
        const val ACTION_STOP = "com.deskpet.action.STOP_OVERLAY"
        private const val TAG = "PO"
        private const val NOTIFY_ID = 1
        private const val CHANNEL_ID = "deskpet_fg"
    }
}
