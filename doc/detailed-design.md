# 桌面宠物 App — 详细设计文档

> 版本：v1.0  
> 日期：2026-05-25  
> 依赖：`doc/proposal.md`、`doc/high-level-design.md`

> ⚠️ **实现差异说明**（2026-06-01 更新）  
> 以下章节的设计与最终代码实现有差异，标注了 **[⚠️ 已变更]** 的节请以实际代码为准：
> - §1.1.2 OnboardingGuide：设计为 3 页，实际简化为 AlertDialog（用户需求）
> - §1.1.3 状态管理 / PetListViewModel：设计为独立 ViewModel，实际直接在 MainActivity 管理（延后处理）
> - §1.3.3 GifRenderer：设计为 Coil 方案，实际使用 android.graphics.Movie（用户确认不需要 Coil）
> - §1.5.2 ImageDecoder：设计为独立类，实际合入 SpritesheetParser
>
> 正文保留原始设计供参考，**[⚠️ 已变更]** 标记处请参见实际代码。

---

## 1. 模块详细设计

### 1.1 UI 层

#### 1.1.1 MainActivity

**职责**：唯一 Activity，Compose 入口，管理悬浮窗启停。

```
class MainActivity : ComponentActivity() {
    // --- 生命周期 ---
    override fun onCreate(savedInstanceState: Bundle?)
    // 初始化：检查首次启动 → OnboardingGuide，加载宠物列表，绑定 ViewModel

    // --- 悬浮窗控制 ---
    fun startPetOverlay(petId: String)
    // 检查权限 → startService(PetOverlayService, petId)

    fun stopPetOverlay()
    // stopService(PetOverlayService)

    // --- 状态 ---
    val isOverlayRunning: StateFlow<Boolean>
    val isFirstLaunch: Boolean  // DataStore 读取
}
```

**函数签名**：

| 函数 | 签名 | 说明 |
|------|------|------|
| `onCreate` | `(savedInstanceState: Bundle?) -> Unit` | 标准 Activity 生命周期 |
| `startPetOverlay` | `(petId: String) -> Unit` | 启动悬浮窗，无返回；权限不足时引导设置 |
| `stopPetOverlay` | `() -> Unit` | 停止悬浮窗 Service |
| `isOverlayRunning` | `StateFlow<Boolean>` | 响应式悬浮窗状态 |

**异常处理**：`startPetOverlay` 中 `SecurityException`（权限拒绝）→ 引导用户开启权限，不崩溃。

---

#### 1.1.2 OnboardingGuide  [⚠️ 已变更：实际简化为 AlertDialog]

**职责**：首次启动时以 Dialog 展示权限说明和操作教程。

```
@Composable
fun OnboardingGuide(
    onDismiss: () -> Unit
)
```

**界面结构**：
- 第 1 页：悬浮窗权限说明 + "去开启"按钮 → 跳转系统设置
- 第 2 页：操作教程（点击=招手 / 拖拽=移动 / 双指=缩放）
- 第 3 页：导入教程（如何下载 Codex Pet 素材）
- 底部："开始使用"按钮 → onDismiss()

**状态**：用 `remember { mutableIntStateOf(0) }` 控制页码。

---

#### 1.1.3 PetListScreen

**职责**：展示已导入宠物列表，支持切换和删除（扩展）。

```
@Composable
fun PetListScreen(
    petList: List<PetInfo>,
    activePetId: String?,
    onPetSelected: (String) -> Unit,
    onStartOverlay: () -> Unit,
    onStopOverlay: () -> Unit,
    onImportClick: () -> Unit,
    isOverlayRunning: Boolean
)
```

**关键算法 — 宠物切换流程**：
```
1. 用户点击某只宠物
2. onPetSelected(petId) → ViewModel 更新 activePetId
3. ViewModel 通知 PetOverlayService.switchPet(petId)（若悬浮窗运行中）
4. PetListScreen 高亮当前激活的宠物（边框/图标标记）
5. 未选中宠物时，不显示高亮
```

**状态管理**：`[⚠️ 已变更]` 设计为独立 `PetListViewModel`，实际直接在 MainActivity 中管理。原设计如下供参考：
```
class PetListViewModel(
    private val petRepository: PetRepository
) : ViewModel() {
    val petList: StateFlow<List<PetInfo>>  // 从 DataStore 读取
    val activePetId: StateFlow<String?>
    
    fun selectPet(id: String)
    fun deletePet(id: String)  // 扩展 E-02
}
```

---

#### 1.1.4 ImportScreen

**职责**：文件选择器入口，区分 Codex Pet 包和 GIF 导入。

```
@Composable
fun ImportScreen(
    onImportResult: (Result<PetInfo>) -> Unit,
    onDismiss: () -> Unit
)
```

**界面结构**：
- 两个大按钮：「导入 Codex Pet 素材包」「导入 GIF 动图」
- `Codex Pet` → 启动 `ActivityResultContracts.OpenDocument`（MIME: `application/zip`, `*/*`）
- `GIF` → 启动 `ActivityResultContracts.OpenDocument`（MIME: `image/gif`）

**导入流程调用链**：
```
ImportScreen.onFileSelected(uri)
  → FileManager.importCodexPet(uri) 或 FileManager.importGif(uri)
    → PetRepository.addPet(petInfo)
      → DataStore 写入
        → onImportResult(Success(petInfo))
```

**异常处理**：
- `FileManager` 抛出 `ImportException(reason)` → 显示 Snackbar 错误提示
- 用户取消选择 → `uri == null`，不执行任何操作

---

### 1.2 悬浮窗层

#### 1.2.1 PetOverlayService

**职责**：管理悬浮窗生命周期，持有 WindowManager 和 ComposeView。

```
class PetOverlayService : LifecycleService(), ViewModelStoreOwner, SavedStateRegistryOwner {
    // LifecycleService 提供 LifecycleOwner
    // ViewModelStoreOwner + SavedStateRegistryOwner 供 ComposeView 绑定
    // 依赖：androidx.lifecycle:lifecycle-service
    
    override val viewModelStore = ViewModelStore()
    override val savedStateRegistry = SavedStateRegistryController.create(this).also {
        it.performRestore(null)
    }.savedStateRegistry
    // --- 核心成员 ---
    private lateinit var windowManager: WindowManager
    private lateinit var composeView: ComposeView
    private lateinit var petEngine: PetEngine
    private lateinit var animationController: AnimationController
    private lateinit var gestureHandler: GestureHandler
    private var currentPetId: String? = null
    
    // --- 生命周期 ---
    override fun onCreate()
    // 初始化 windowManager, petEngine
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int
    // 解析 intent 中的 petId → loadPet() → addOverlayView()
    
    override fun onDestroy()
    // 保存位置 → removeView → 清理资源
    
    // --- 核心函数 ---
    private fun loadPet(petId: String): PetInfo
    // 从 PetRepository 加载元数据，恢复位置(x,y,scale)
    
    private fun addOverlayView()
    // 创建 ComposeView → 设置 LifecycleOwner → windowManager.addView()
    
    private fun removeOverlayView()
    // windowManager.removeView(composeView)
    
    fun switchPet(newPetId: String)
    // 1. saveCurrentPosition()
    // 2. loadPet(newPetId)
    // 3. petEngine.reload(newPet)
    // 4. animationController.reset()
    // 5. updateViewLayout(位置, 缩放)
    
    private fun saveCurrentPosition()
    // 将当前 x, y, scale 写入 PetRepository
    
    private fun onGestureDrag(dx: Float, dy: Float)
    // layoutParams.x += dx; layoutParams.y += dy
    // windowManager.updateViewLayout(composeView, layoutParams)
    // 同时通知 animationController 切换方向动画
    
    private fun onGestureScale(scale: Float)
    // 计算新的窗口尺寸
    // windowManager.updateViewLayout(composeView, layoutParams)
}
```

**WindowManager LayoutParams 配置**：

```
val layoutParams = WindowManager.LayoutParams(
    WindowManager.LayoutParams.WRAP_CONTENT,
    WindowManager.LayoutParams.WRAP_CONTENT,
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    else
        WindowManager.LayoutParams.TYPE_PHONE,
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
    PixelFormat.TRANSLUCENT
).apply {
    gravity = Gravity.TOP or Gravity.START
    x = savedPositionX
    y = savedPositionY
}
```

**ComposeView 绑定生命周期**：

```
composeView.apply {
    setViewTreeLifecycleOwner(this@PetOverlayService)
    setViewTreeViewModelStoreOwner(this@PetOverlayService)
    setViewTreeSavedStateRegistryOwner(this@PetOverlayService)
    setContent {
        PetOverlayRenderer(
            petEngine = petEngine,
            animationController = animationController,
            gestureHandler = gestureHandler
        )
    }
}
```

**关键算法 — onTaskRemoved**：
```
override fun onTaskRemoved(rootIntent: Intent?) {
    super.onTaskRemoved(rootIntent)
    saveCurrentPosition()
    stopSelf()  // 悬浮窗跟随 App 消失
}
```

---

#### 1.2.2 PetOverlayRenderer

**职责**：Compose Canvas 渲染宠物当前帧。

```
@Composable
fun PetOverlayRenderer(
    petEngine: PetEngine,
    animationController: AnimationController,
    gestureHandler: GestureHandler
) {
    val currentFrame by animationController.currentFrame.collectAsState()
    val petSize by animationController.petSize.collectAsState()  // 基础尺寸 × scale
    
    Box(
        modifier = Modifier
            .size(petSize.width.dp, petSize.height.dp)
            .then(gestureHandler.modifier)  // 绑定手势
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            currentFrame?.let { frame ->
                drawImage(
                    image = frame,
                    dstSize = IntSize(size.width.toInt(), size.height.toInt())
                )
            }
        }
    }
}
```

**60FPS 渲染循环**（在 AnimationController 中）：

```
// 使用 LaunchedEffect + withFrameMillis 实现帧率控制
LaunchedEffect(animationState) {
    while (isActive) {
        withFrameMillis { frameTimeMillis ->
            val elapsed = frameTimeMillis - lastFrameTime
            if (elapsed >= frameIntervalMs) {  // 60FPS → 16.67ms
                advanceFrame()
                lastFrameTime = frameTimeMillis
            }
        }
    }
}
```

**性能优化**：
- 使用 `remember { mutableStateOf() }` 缓存 Bitmap，避免重组
- `drawImage` 而非 `Image` composable，减少重组次数
- 帧 Bitmap 预调用 `prepareToDraw()` 加速 GPU 上传

---

#### 1.2.3 GestureHandler

**职责**：处理触摸手势，区分 Codex Pet / GIF 宠物行为。

```
class GestureHandler(
    private val onDrag: (dx: Float, dy: Float, direction: DragDirection) -> Unit,
    private val onTap: () -> Unit,
    private val onScale: (scale: Float) -> Unit,
    private val petType: PetType
) {
    enum class DragDirection { LEFT, RIGHT, UP, DOWN }
    
    val modifier: Modifier
        get() = Modifier
            .pointerInput(petType) {
                detectTapGestures {
                    when (petType) {
                        PetType.CODEX_PET -> onTap()  // → AnimationController.switchState("waving")
                        PetType.GIF -> onTap()        // → 播放物理弹跳效果
                    }
                }
            }
            .pointerInput(petType) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    val direction = when {
                        abs(dragAmount.x) > abs(dragAmount.y) && dragAmount.x > 0 -> DragDirection.RIGHT
                        abs(dragAmount.x) > abs(dragAmount.y) && dragAmount.x < 0 -> DragDirection.LEFT
                        dragAmount.y < 0 -> DragDirection.UP
                        else -> DragDirection.DOWN
                    }
                    onDrag(dragAmount.x, dragAmount.y, direction)
                }
            }
            .pointerInput(petType) {
                detectTransformGestures { _, _, zoom, _ ->
                    onScale(zoom)
                }
            }
}
```

**方向→动画映射**（仅 Codex Pet）：

```
fun mapDirectionToState(direction: DragDirection): PetAnimationState = when (direction) {
    DragDirection.LEFT  -> PetAnimationState.RUNNING_LEFT
    DragDirection.RIGHT -> PetAnimationState.RUNNING_RIGHT
    DragDirection.UP    -> PetAnimationState.JUMPING
    DragDirection.DOWN  -> PetAnimationState.WAVING
}
```

**GIF 弹跳效果算法**：
```
// 点击 GIF 宠物时
suspend fun playBounceEffect(layoutParams: WindowManager.LayoutParams) {
    val originalY = layoutParams.y
    // 向上弹 20px，持续 100ms
    animateY(layoutParams, originalY - 20, 100)
    // 回弹到原位，持续 150ms（弹性曲线）
    animateY(layoutParams, originalY, 150, FastOutSlowInEasing)
}
```

---

### 1.3 引擎层

#### 1.3.1 PetEngine

**职责**：宠物引擎入口，策略模式分发到 Codex Pet 或 GIF 渲染器。

```
class PetEngine(
    private val spritesheetParser: SpritesheetParser,
    private val gifRenderer: GifRenderer,
    private val imageDecoder: ImageDecoder
) {
    sealed class PetLoader {
        data class CodexPet(val config: CodexPetConfig, val spritesheetPath: String) : PetLoader()
        data class Gif(val filePath: String) : PetLoader()
    }
    
    fun createLoader(petInfo: PetInfo): PetLoader {
        return when (petInfo.type) {
            PetType.CODEX_PET -> {
                val config = spritesheetParser.parse(petInfo.petJsonPath!!, petInfo.spritesheetPath)
                PetLoader.CodexPet(config, petInfo.spritesheetPath)
            }
            PetType.GIF -> PetLoader.Gif(petInfo.spritesheetPath)
        }
    }
    
    fun getCurrentFrame(loader: PetLoader, state: PetAnimationState, frameIndex: Int): Bitmap? {
        return when (loader) {
            is PetLoader.CodexPet -> spritesheetParser.extractFrame(loader, state, frameIndex)
            is PetLoader.Gif -> gifRenderer.getFrame(loader, frameIndex)
        }
    }
}
```

---

#### 1.3.2 SpritesheetParser

**职责**：解析 pet.json + spritesheet.webp，提取指定状态+帧序号的 Bitmap。

```
class SpritesheetParser(
    private val imageDecoder: ImageDecoder
) {
    // --- 解析 pet.json ---
    fun parse(petJsonPath: String, spritesheetPath: String): CodexPetConfig
    // 1. 读取 pet.json 文件内容
    // 2. kotlinx.serialization.json.Json.decodeFromString<CodexPetConfig>
    // 3. 若 states 为 null → 使用默认 9 行标准映射
    // 4. 校验 spritesheet 文件存在
    
    // --- 帧提取 ---
    fun extractFrame(
        config: CodexPetConfig,
        state: PetAnimationState,
        frameIndex: Int
    ): Bitmap?
    // 1. 获取 state.row 对应的 AnimationState（从 config.states 或硬编码映射）
    // 2. 计算 cell 坐标：col = frameIndex % totalFrames, cellWidth = 192, cellHeight = 208
    // 3. BitmapRegionDecoder.decodeRegion(Rect(col*192, row*208, (col+1)*192, (row+1)*208))
    // 4. 若帧为空（全透明）→ 该行结束，返回 null 或 wrap 到第 0 帧
    
    // --- 帧计数 ---
    fun getFrameCount(config: CodexPetConfig, state: PetAnimationState): Int
    // 若有 config.states → 返回对应 state.frames
    // 若无 → 从 spritesheet 扫描该行连续非空帧数
    
    // --- 非空帧检测 ---
    private fun isNonEmptyFrame(bitmap: Bitmap): Boolean
    // 采样检测：检查 bitmap 是否有非透明像素
    // 优化：仅检查四角和中心 5 个像素的 alpha 值
}
```

**关键算法 — 默认 9 行映射（无 states 时）**：

```
private val DEFAULT_STATE_MAP = mapOf(
    "idle"           to AnimationState("idle", 0, 6),
    "running-right"  to AnimationState("running-right", 1, 8),
    "running-left"   to AnimationState("running-left", 2, 8),
    "waving"         to AnimationState("waving", 3, 4),
    "jumping"        to AnimationState("jumping", 4, 5),
    "failed"         to AnimationState("failed", 5, 8),
    "waiting"        to AnimationState("waiting", 6, 6),
    "running"        to AnimationState("running", 7, 6),
    "review"         to AnimationState("review", 8, 6)
)
```

**关键算法 — Cell 坐标计算**：

```
fun calculateCellRect(row: Int, col: Int, spritesheetWidth: Int, spritesheetHeight: Int): Rect {
    val cellWidth = spritesheetWidth / 8   // 8 列
    val cellHeight = spritesheetHeight / 9 // 9 行
    return Rect(
        col * cellWidth,
        row * cellHeight,
        (col + 1) * cellWidth,
        (row + 1) * cellHeight
    )
}
```

**异常处理**：
- `FileNotFoundException` → 抛出 `ImportException("spritesheet 文件不存在")`
- `JsonDecodingException` → 抛出 `ImportException("pet.json 格式错误：${message}")`
- 帧超出 spritesheet 范围 → 返回 null（调用方回退到 idle 第 0 帧）

---

#### 1.3.3 GifRenderer  [⚠️ 已变更：实际使用 android.graphics.Movie]

**职责**：解码 GIF 帧序列，按帧间隔循环输出。

```
class GifRenderer {
    // --- Coil 解码 ---
    private var imageLoader: ImageLoader  // Coil ImageLoader 实例
    
    fun initialize(context: Context)
    // 配置 Coil ImageLoader，注册 GifDecoder
    
    suspend fun decodeFrames(filePath: String): List<GifFrame>
    // 1. 使用 Coil 的 ImageDecoder.decode() 获取 AnimatedImage
    // 2. 遍历所有帧：frameCount, frameInterval, bitmap
    // 3. 返回 List<GifFrame>
    
    data class GifFrame(
        val bitmap: Bitmap,
        val delayMs: Int  // 该帧应显示的时长
    )
    
    fun getFrame(loader: PetEngine.PetLoader.Gif, frameIndex: Int): Bitmap?
    // 返回 frames[frameIndex % frames.size].bitmap
}
```

**Coil 初始化**（在 Application 中）：

```
val imageLoader = ImageLoader.Builder(context)
    .components {
        if (Build.VERSION.SDK_INT >= 28) {
            add(AnimatedImageDecoder.Factory())
        } else {
            add(GifDecoder.Factory())
        }
    }
    .build()
```

**异常处理**：
- 解码失败 → 抛出 `ImportException("GIF 解码失败，文件可能已损坏")`
- 超大 GIF（>10MB）→ 导入时 FileManager 拦截，拒绝导入

---

#### 1.3.4 AnimationController

**职责**：动画状态机 + 帧率控制，管理从状态→帧序列→渲染输出的全流程。

```
class AnimationController(
    private val petEngine: PetEngine,
    coroutineScope: CoroutineScope
) {
    // --- 状态 ---
    private var _currentState = MutableStateFlow(PetAnimationState.IDLE)
    val currentState: StateFlow<PetAnimationState> = _currentState
    
    private var _currentFrame = MutableStateFlow<Bitmap?>(null)
    val currentFrame: StateFlow<Bitmap?> = _currentFrame
    
    private var _petSize = MutableStateFlow(Size(192f, 208f))  // 基础尺寸
    val petSize: StateFlow<Size> = _petSize
    
    // --- 内部状态 ---
    private var frameIndex: Int = 0
    private var petLoader: PetEngine.PetLoader? = null
    private var isRunning: Boolean = false
    
    // --- 控制接口 ---
    fun load(petInfo: PetInfo)
    // petLoader = petEngine.createLoader(petInfo)
    // reset()
    
    fun start()
    // isRunning = true; 启动帧循环协程
    
    fun stop()
    // isRunning = false; 取消帧循环协程
    
    fun switchState(state: PetAnimationState)
    // 如果 petType == GIF → 忽略（GIF 不切换状态）
    // _currentState.value = state; frameIndex = 0
    
    fun reset()
    // _currentState.value = IDLE; frameIndex = 0
    
    fun setScale(scale: Float)
    // _petSize.value = Size(192 * scale, 208 * scale)
    
    // --- 帧循环 ---
    private suspend fun frameLoop() = coroutineScope.launch {
        while (isRunning) {
            withFrameMillis { frameTimeMs ->
                val frame = petEngine.getCurrentFrame(petLoader!!, _currentState.value, frameIndex)
                _currentFrame.value = frame
                
                frameIndex++
                val totalFrames = petEngine.getFrameCount(petLoader!!, _currentState.value)
                if (frameIndex >= totalFrames) {
                    frameIndex = 0
                    // 若是一次性动画（非 idle），播完后自动回到 idle
                    if (_currentState.value != PetAnimationState.IDLE) {
                        _currentState.value = PetAnimationState.IDLE
                    }
                }
            }
        }
    }
}
```

**关键算法 — 一次性动画 vs 循环动画**：

```
enum class AnimationLoopMode {
    LOOP,       // 循环播放（idle, running 等）
    PLAY_ONCE   // 播放一次后回到 idle（waving, jumping 等）
}

fun getLoopMode(state: PetAnimationState): AnimationLoopMode = when (state) {
    PetAnimationState.IDLE,
    PetAnimationState.RUNNING,
    PetAnimationState.RUNNING_RIGHT,
    PetAnimationState.RUNNING_LEFT,
    PetAnimationState.WAITING,
    PetAnimationState.REVIEW -> AnimationLoopMode.LOOP
    
    PetAnimationState.WAVING,
    PetAnimationState.JUMPING,
    PetAnimationState.FAILED -> AnimationLoopMode.PLAY_ONCE
}
```

---

#### 1.3.5 AutonomousBehavior

**职责**：随机间隔触发自主行为（仅 Codex Pet）。

```
class AutonomousBehavior(
    private val animationController: AnimationController,
    private val coroutineScope: CoroutineScope
) {
    private val autoStates = listOf(
        PetAnimationState.RUNNING,
        PetAnimationState.JUMPING,
        PetAnimationState.WAITING,
        PetAnimationState.REVIEW
    )
    
    private var lastTouchTime: Long = System.currentTimeMillis()
    private var behaviorJob: Job? = null
    
    // --- 触摸重置 ---
    fun onUserTouch()
    // lastTouchTime = System.currentTimeMillis()
    // 重启计时器
    
    // --- 启动自主行为 ---
    fun start()
    // behaviorJob = coroutineScope.launch { loop() }
    
    fun stop()
    // behaviorJob?.cancel()
    
    private suspend fun loop() {
        while (isActive) {
            delay(randomDelay())  // 5~30 秒随机间隔
            if (isIdleFor(30_000)) {  // 30 秒无触摸
                val randomState = autoStates.random()
                animationController.switchState(randomState)
                delay(animationDuration(randomState))  // 等待动画播完
                // AnimationController 自动回到 idle
            }
        }
    }
    
    private fun randomDelay(): Long = Random.nextLong(5_000, 30_001)
    private fun isIdleFor(ms: Long): Boolean = 
        System.currentTimeMillis() - lastTouchTime >= ms
}
```

---

### 1.4 数据层

#### 1.4.1 PetRepository

**职责**：宠物 CRUD 接口，屏蔽 DataStore 细节。

```
class PetRepository(private val petStore: PetStore) {
    // --- 查询 ---
    fun getAllPets(): Flow<List<PetInfo>>
    // petStore.petListFlow
    
    fun getActivePetId(): Flow<String?>
    // petStore.activePetIdFlow
    
    fun getActivePet(): Flow<PetInfo?>
    // combine(petListFlow, activePetIdFlow) → find
    
    // --- 写入 ---
    suspend fun addPet(petInfo: PetInfo)
    // 生成 UUID → petStore.petListFlow.value + petInfo
    
    suspend fun deletePet(petId: String)
    // 从列表中移除 → 同时删除关联文件
    
    suspend fun setActivePet(petId: String)
    // petStore.activePetId = petId
    
    // --- 位置/缩放 ---
    suspend fun savePosition(petId: String, x: Float, y: Float, scale: Float)
    // 更新对应 PetInfo 的 positionX/Y, scale
    
    suspend fun getPet(petId: String): PetInfo?
    // petStore.petListFlow.value.find { it.id == petId }
    
    // --- 首次启动 ---
    suspend fun isFirstLaunch(): Boolean
    fun setFirstLaunchDone()
}
```

---

#### 1.4.2 PetStore（DataStore 封装）

**职责**：DataStore 键值读写封装。

```
class PetStore(private val context: Context) {
    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
        name = "pet_prefs"
    )
    
    // --- 键定义 ---
    companion object {
        val PET_LIST_KEY = stringPreferencesKey("pet_list_json")     // JSON 序列化后的列表
        val ACTIVE_PET_KEY = stringPreferencesKey("active_pet_id")
        val FIRST_LAUNCH_KEY = booleanPreferencesKey("is_first_launch")
    }
    
    // --- 读写 ---
    val petListFlow: Flow<List<PetInfo>> = context.dataStore.data.map { prefs ->
        val json = prefs[PET_LIST_KEY] ?: "[]"
        Json.decodeFromString<List<PetInfo>>(json)
    }
    
    suspend fun updatePetList(list: List<PetInfo>) {
        context.dataStore.edit { prefs ->
            prefs[PET_LIST_KEY] = Json.encodeToString(list)
        }
    }
    
    val activePetIdFlow: Flow<String?> = context.dataStore.data.map {
        it[ACTIVE_PET_KEY]
    }
    
    suspend fun setActivePetId(id: String) {
        context.dataStore.edit { it[ACTIVE_PET_KEY] = id }
    }
    
    // --- 位置记忆 ---
    // 位置(x,y)和 scale 已编码在 PetInfo 中，整体序列化到 pet_list_json
    // 无需单独键
}
```

**存储结构示例**：

```
pet_list_json: [
  {
    "id": "uuid-1",
    "type": "CODEX_PET",
    "displayName": "Boba",
    "petJsonPath": "/data/.../boba/pet.json",
    "spritesheetPath": "/data/.../boba/spritesheet.webp",
    "positionX": 100.0,
    "positionY": 300.0,
    "scale": 1.5,
    "isActive": true
  },
  {
    "id": "uuid-2",
    "type": "GIF",
    "displayName": "nyan_cat.gif",
    "petJsonPath": null,
    "spritesheetPath": "/data/.../nyan_cat.gif",
    "positionX": 50.0,
    "positionY": 100.0,
    "scale": 1.0,
    "isActive": false
  }
]

active_pet_id: "uuid-1"
is_first_launch: false
```

---

#### 1.4.3 FileManager

**职责**：文件导入管理，复制到 App 内部存储。

```
class FileManager(private val context: Context) {
    private val petDir: File
        get() = File(context.filesDir, "pets").also { it.mkdirs() }
    
    // --- Codex Pet 导入 ---
    suspend fun importCodexPet(uri: Uri): PetInfo
    // 1. 创建 petDir/{uuid}/ 目录
    // 2. 从 uri 复制 zip 内容到该目录
    // 3. 校验：目录下存在 pet.json 和 spritesheet.webp/.png
    // 4. 解析 pet.json 获取 displayName
    // 5. 返回 PetInfo(type=CODEX_PET, ...)
    // 异常：校验失败 → ImportException
    
    // --- GIF 导入 ---
    suspend fun importGif(uri: Uri): PetInfo
    // 1. 校验文件魔数（GIF87a / GIF89a）
    // 2. 校验文件大小 ≤ 10MB
    // 3. 复制到 petDir/{uuid}.gif
    // 4. 文件名作为 displayName
    // 5. 返回 PetInfo(type=GIF, ...)
    // 异常：非 GIF 格式 → ImportException
    // 异常：超大文件 → ImportException
    
    // --- 删除 ---
    fun deletePet(petInfo: PetInfo)
    // 删除 petDir/{petInfo.id}/ 目录或文件
    
    // --- 校验 ---
    private fun isGifFormat(inputStream: InputStream): Boolean
    // 读取前 6 字节，验证 "GIF87a" 或 "GIF89a"
    
    private fun validateCodexPetPackage(dir: File): Boolean
    // dir 下存在 pet.json
    // dir 下存在 spritesheet.webp 或 spritesheet.png
}
```

**异常类型**：

```
sealed class ImportException(message: String) : Exception(message) {
    class FileNotFound(path: String) : ImportException("文件不存在：$path")
    class InvalidFormat(reason: String) : ImportException("格式错误：$reason")
    class FileTooLarge(size: Long, max: Long) : ImportException("文件过大：${size}MB，限制${max}MB")
    class ExtractionFailed(cause: Throwable) : ImportException("解压失败：${cause.message}")
}
```

---

### 1.5 基础层

#### 1.5.1 PermissionManager

**职责**：检查和请求悬浮窗权限。

```
object PermissionManager {
    fun canDrawOverlays(context: Context): Boolean
    // Settings.canDrawOverlays(context)
    
    fun openOverlaySettings(context: Context)
    // Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}")
    // context.startActivity(intent)
    
    fun isGranted(context: Context): Boolean = canDrawOverlays(context)
}
```

---

#### 1.5.2 ImageDecoder  [⚠️ 已变更：功能合入 SpritesheetParser]

**职责**：统一图片解码接口。

```
class ImageDecoder(private val context: Context) {
    fun decodeBitmapRegion(
        filePath: String,
        rect: Rect
    ): Bitmap?
    // 使用 BitmapRegionDecoder.newInstance(filePath)
    // decoder.decodeRegion(rect, null)
    
    fun decodeFullBitmap(filePath: String): Bitmap?
    // BitmapFactory.decodeFile(filePath)
    
    fun getImageDimensions(filePath: String): Pair<Int, Int>?
    // BitmapFactory.Options(inJustDecodeBounds = true)
    // 返回 (width, height)
}
```

---

## 2. 全局编码规范

### 2.1 命名规范

| 类别 | 规范 | 示例 |
|------|------|------|
| **文件** | PascalCase，`.kt` 后缀 | `PetEngine.kt`、`GestureHandler.kt` |
| **类/接口** | PascalCase，名词或名词短语 | `PetRepository`、`AnimationController` |
| **函数** | camelCase，动词或动词短语 | `startPetOverlay()`、`extractFrame()` |
| **变量/参数** | camelCase，名词 | `petId`、`frameIndex`、`isRunning` |
| **常量** | UPPER_SNAKE_CASE | `DEFAULT_CELL_WIDTH`、`MAX_GIF_SIZE` |
| **Composable** | PascalCase，返回 Unit 的 `@Composable` 函数 | `PetListScreen()`、`OnboardingGuide()` |
| **枚举** | PascalCase 类名，UPPER_SNAKE_CASE 枚举值 | `PetType.CODEX_PET`、`DragDirection.LEFT` |
| **StateFlow/MutableStateFlow** | 公开用 `val` 暴露 `StateFlow`，`MutableStateFlow` 为 `private` | `val currentFrame: StateFlow<Bitmap?>` |
| **资源文件** | snake_case | `ic_pet_placeholder.xml` |

### 2.2 目录结构（完整版）

```
deskpet/
├── build.gradle.kts                 # 根构建配置
├── settings.gradle.kts              # 项目设置
├── gradle.properties                # Gradle 属性
├── app/
│   ├── build.gradle.kts             # App 模块构建配置
│   └── src/
│       └── main/
│           ├── AndroidManifest.xml  # 清单文件
│           ├── java/com/deskpet/
│           │   ├── DeskPetApp.kt           # Application 类
│           │   ├── MainActivity.kt         # 唯一 Activity
│           │   ├── ui/
│           │   │   ├── PetListScreen.kt
│           │   │   ├── ImportScreen.kt
│           │   │   ├── OnboardingGuide.kt
│           │   │   └── theme/
│           │   │       ├── Theme.kt
│           │   │       ├── Color.kt
│           │   │       └── Type.kt
│           │   ├── overlay/
│           │   │   ├── PetOverlayService.kt
│           │   │   ├── PetOverlayRenderer.kt
│           │   │   └── GestureHandler.kt
│           │   ├── engine/
│           │   │   ├── PetEngine.kt
│           │   │   ├── SpritesheetParser.kt
│           │   │   ├── GifRenderer.kt
│           │   │   ├── AnimationController.kt
│           │   │   └── AutonomousBehavior.kt
│           │   ├── data/
│           │   │   ├── PetRepository.kt
│           │   │   ├── PetStore.kt
│           │   │   ├── FileManager.kt
│           │   │   └── model/
│           │   │       ├── PetInfo.kt
│           │   │       ├── CodexPetConfig.kt
│           │   │       ├── PetAnimationState.kt
│           │   │       ├── ImportException.kt
│           │   │       └── DragDirection.kt
│           │   └── foundation/
│           │       ├── PermissionManager.kt
│           │       └── ImageDecoder.kt
│           └── res/
│               ├── values/
│               │   └── strings.xml
│               ├── drawable/
│               │   └── ic_launcher.xml
│               └── mipmap/
│                   └── ic_launcher.webp
```

### 2.3 代码风格约定

- **缩进**：4 空格，不使用 Tab
- **行宽**：≤ 120 字符
- **Kotlin 风格**：遵循 [Google Kotlin Style Guide](https://developer.android.com/kotlin/style-guide)
- **尾随逗号**：多行参数/列表使用尾随逗号
- **可见性**：默认 `private`，最小化公开 API
- **不可变性**：优先 `val`，必要时用 `var`
- **空安全**：禁止 `!!` 强制解包，使用 `?.`、`?:`、`let`/`also` 等
- **协程**：所有 IO 操作在 `Dispatchers.IO`；UI 更新在 `Dispatchers.Main`
- **Compose**：使用 `remember` + `derivedStateOf` 避免不必要重组；`Modifier` 链保持顺序一致

---

## 3. 错误处理机制与日志规范

### 3.1 异常分类与处理策略

| 异常类型 | 发生场景 | 处理策略 |
|----------|----------|----------|
| `ImportException` | 导入文件格式/大小/完整性错误 | 捕获 → Snackbar 提示用户 → 不崩溃 |
| `SecurityException` | 悬浮窗权限被拒绝 | 捕获 → 引导用户开启权限 → 不崩溃 |
| `FileNotFoundException` | 宠物文件被手动删除 | 捕获 → 从宠物列表移除 → 提示用户 |
| `JsonDecodingException` | pet.json 解析失败 | 捕获 → `ImportException` → 用户提示 |
| `OutOfMemoryError` | 图片解码超出内存 | 捕获 → 降级采样/跳过 → 日志记录 |
| `CancellationException` | 协程正常取消 | 不捕获（自动传播） |
| 其他 `Exception` | 未预期错误 | 全局捕获 → Toast"出错了" → 日志记录 → 不崩溃 |

### 3.2 错误码体系

| 错误码 | 含义 | 用户提示 |
|--------|------|----------|
| `E001` | 无法读取文件 | "文件读取失败，请检查文件是否完整" |
| `E002` | pet.json 格式错误 | "宠物配置文件格式不正确" |
| `E003` | spritesheet 尺寸不符 | "精灵表尺寸不符合 1536×1872 规范" |
| `E004` | GIF 格式校验失败 | "所选文件不是 GIF 格式" |
| `E005` | GIF 文件过大 | "GIF 文件不能超过 10MB" |
| `E006` | 悬浮窗权限未授予 | "请在设置中授予悬浮窗权限" |
| `E007` | 数据存储异常 | "数据保存失败，请重试" |

### 3.3 日志规范

使用 Android `Log` 类，TAG 格式为类名。

| 级别 | 使用场景 | 示例 |
|------|----------|------|
| **DEBUG** | 开发调试（Release 关闭） | `Log.d(TAG, "frameIndex=$frameIndex")` |
| **INFO** | 关键操作记录 | `Log.i(TAG, "Pet imported: ${petInfo.displayName}")` |
| **WARN** | 可恢复的异常 | `Log.w(TAG, "Spritesheet frame empty at row=$row col=$col", e)` |
| **ERROR** | 不可恢复的错误 | `Log.e(TAG, "Failed to decode GIF", e)` |

**实现方式**：

```
// 每个类定义：
companion object {
    private const val TAG = "PetEngine"
}

// 使用示例：
Log.w(TAG, "Unknown animation state: $name, falling back to idle")
```

---

## 4. AndroidManifest.xml 关键声明

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- 悬浮窗权限 -->
    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
    
    <!-- 前台服务（保留以备未来扩展） -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />

    <application
        android:name=".DeskPetApp"
        android:allowBackup="false"
        android:label="桌面宠物"
        android:supportsRtl="true"
        android:theme="@style/Theme.DeskPet">
        
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:configChanges="orientation|screenSize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <service
            android:name=".overlay.PetOverlayService"
            android:exported="false"
            android:stopWithTask="true" />
            <!-- stopWithTask="true" → onTaskRemoved 时自动停止，跟随 App 生命周期 -->
    </application>
</manifest>
```

---

## 5. DeskPetApp（Application 类）

```kotlin
class DeskPetApp : Application() {
    lateinit var petRepository: PetRepository
        private set
    
    lateinit var imageDecoder: ImageDecoder
        private set
    
    override fun onCreate() {
        super.onCreate()
        
        // 初始化 DataStore → PetStore → PetRepository
        val petStore = PetStore(this)
        petRepository = PetRepository(petStore)
        
        // 初始化图片解码器
        imageDecoder = ImageDecoder(this)
        
        // 初始化 Coil（全局 ImageLoader）
        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .components {
                    add(GifDecoder.Factory())
                }
                .build()
        )
    }
}
```

---

> **下一步**：请审阅此详细设计文档，确认无误后进入 Phase 4（任务拆分与进度管理）。
