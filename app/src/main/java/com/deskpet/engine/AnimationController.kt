package com.deskpet.engine

import android.graphics.Bitmap
import com.deskpet.data.model.PetAnimationState
import com.deskpet.data.model.PetInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.withLock

class AnimationController(
    private val petEngine: PetEngine,
    private val scope: CoroutineScope
) : IAnimationStateController {

    /**
     * 帧包装：携带递增序号。
     * StateFlow 按 equals 去重，若直接发 Bitmap，双缓冲复用同一对象时不会触发重组；
     * seq 保证每帧都是"新值"，位图可以放心复用。
     */
    data class Frame(val bitmap: Bitmap, val seq: Long)

    companion object {
        /** 每帧时长的下限（防抖）；真实时长来自 [PetFrameTiming] 的规范表 */
        private const val MIN_FRAME_DELAY_NS = 16_000_000L
        private const val LOOP_COUNT_BEFORE_IDLE = 2
        private const val MIN_PET_SIZE_PX = 32

        /** 与 UI 缩放滑块范围一致（50%~500%），见 PetListScreen */
        private const val MIN_SCALE = 0.5f
        private const val MAX_SCALE = 5f
    }

    private val _state = MutableStateFlow(PetAnimationState.IDLE)
    override val currentState: StateFlow<PetAnimationState> = _state
    private val _frame = MutableStateFlow<Frame?>(null)
    val currentFrame: StateFlow<Frame?> = _frame
    /** 动画循环在 Main 线程读、reload 在 IO 线程写，必须 volatile 保证可见性 */
    @Volatile private var frameSeq = 0L

    /**
     * 系统省电模式联动（由 Service 的广播接收器维护）：
     * 用户主动开启省电 = 明确在乎电量，此时每帧时长整体 ×2
     * （规范节奏 12.5FPS → 6FPS 左右），桌宠变成"用电良民"；平时按规范走。
     */
    @Volatile var powerSaveMode = false
    private val _w = MutableStateFlow(CodexPetSpec.CELL_W)
    val petWidth: StateFlow<Int> = _w
    private val _h = MutableStateFlow(CodexPetSpec.CELL_H)
    val petHeight: StateFlow<Int> = _h

    @Volatile private var fi = 0
    @Volatile private var pc = 0

    /** 动画循环在 Main 线程读、reload 在 IO 线程写，必须 volatile 保证可见性 */
    @Volatile private var loader: PetEngine.PetLoader? = null
    private var fj: Job? = null
    private var s = 1f
    private var baseW = CodexPetSpec.CELL_W
    private var baseH = CodexPetSpec.CELL_H

    /** 加载宠物；会按当前缩放比例重新计算窗口尺寸。文件读取发生在线程池中，调用方需自行切 IO。 */
    fun load(p: PetInfo): Boolean {
        // 先清掉上一只的帧再创建新 loader：createLoader 内部会释放旧引擎资源
        // （如 GIF 双缓冲位图），必须保证渲染端不再引用它们
        _frame.value = null
        loader = petEngine.createLoader(p) ?: return false
        val (w, h) = petEngine.getPetSize(loader!!)
        baseW = w
        baseH = h
        frameSeq = 0
        applyScale()
        reset()
        return true
    }

    /**
     * 重载宠物。必须串行化：reload 是纯阻塞函数（无挂起点），协程取消
     * 不能中断它——快速连续切换宠物时，旧协程的 reload 会和新协程的
     * reload 并发交错（loader 互相覆盖、引擎资源双重释放、最终显示错误
     * 的宠物）。Mutex 保证按调用顺序完整执行，最后一个 caller 胜出。
     */
    private val reloadMutex = kotlinx.coroutines.sync.Mutex()

    suspend fun reload(p: PetInfo): Boolean = reloadMutex.withLock {
        stop()
        load(p)
    }

    fun start() {
        fj?.cancel()
        fj = scope.launch(Dispatchers.Main) {
            var lt = System.nanoTime()
            while (isActive) {
                val l = loader ?: break
                // 这一帧是用哪个状态、哪个序号画出来的：时长必须按"已显示的那帧"算，
                // 不能用自增后的 fi（否则整条动画会差一帧，循环点还会跳到别的时长）
                val shownState = _state.value
                val shownIdx = fi
                val bitmap = withContext(Dispatchers.IO) {
                    petEngine.getCurrentFrame(l, shownState, shownIdx)
                }
                if (bitmap != null) {
                    _frame.value = Frame(bitmap, ++frameSeq)
                }
                fi++
                val tf = petEngine.getFrameCount(l, _state.value)
                if (tf <= 0) {
                    // 该状态没有可用帧（pet.json 配置 frames=0 等）：
                    // 必须立刻回 IDLE，否则状态机永远卡在这里，宠物"冻住"且
                    // AutonomousBehavior / 单击互动（都等 IDLE）全部失效
                    fi = 0
                    if (_state.value != PetAnimationState.IDLE) _state.value = PetAnimationState.IDLE
                } else if (fi >= tf) {
                    fi = 0
                    if (isCodexPet()) {
                        pc++
                        if (_state.value != PetAnimationState.IDLE && pc >= LOOP_COUNT_BEFORE_IDLE) {
                            _state.value = PetAnimationState.IDLE
                            pc = 0
                        }
                    }
                }
                // 时长按 Codex 规范的逐帧表走（待机一轮 1100ms、跑动 1060ms），
                // 不再统一 500/200ms —— 旧实现比规范慢 2.7 倍，观感就是幻灯片。
                // GIF 没有逐帧表，沿用 200ms；用户开省电时整体 ×2。
                val baseMs = if (isCodexPet()) {
                    PetFrameTiming.frameMs(shownState, shownIdx)
                } else {
                    PetFrameTiming.FALLBACK_FRAME_MS
                }
                var ns = baseMs * 1_000_000L
                if (powerSaveMode) ns *= 2
                delay(((ns - (System.nanoTime() - lt)).coerceAtLeast(MIN_FRAME_DELAY_NS)) / 1_000_000)
                lt = System.nanoTime()
            }
        }
    }

    fun stop() {
        fj?.cancel()
        fj = null
    }

    override fun switchState(st: PetAnimationState) {
        // 拖拽时会连续回调；状态没变就不要重置帧序号，否则动画会卡在第一帧
        if (_state.value == st) return
        _state.value = st
        fi = 0
        pc = 0
    }

    fun reset() {
        _state.value = PetAnimationState.IDLE
        fi = 0
        pc = 0
    }

    fun setScale(v: Float) {
        s = v.coerceIn(MIN_SCALE, MAX_SCALE)
        if (loader != null) applyScale()
    }

    fun isCodexPet() = loader is PetEngine.PetLoader.CodexPet

    private fun applyScale() {
        _w.value = (baseW * s).toInt().coerceAtLeast(MIN_PET_SIZE_PX)
        _h.value = (baseH * s).toInt().coerceAtLeast(MIN_PET_SIZE_PX)
    }
}
