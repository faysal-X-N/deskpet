package com.deskpet.engine

import android.graphics.Bitmap
import com.deskpet.data.model.PetAnimationState
import com.deskpet.data.model.PetInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
        private const val IDLE_FRAME_INTERVAL_NS = 500_000_000L
        private const val ANIM_FRAME_INTERVAL_NS = 200_000_000L
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
    private val _w = MutableStateFlow(PetEngine.DEFAULT_PET_WIDTH)
    val petWidth: StateFlow<Int> = _w
    private val _h = MutableStateFlow(PetEngine.DEFAULT_PET_HEIGHT)
    val petHeight: StateFlow<Int> = _h

    @Volatile private var fi = 0
    private var pc = 0

    /** 动画循环在 Main 线程读、reload 在 IO 线程写，必须 volatile 保证可见性 */
    @Volatile private var loader: PetEngine.PetLoader? = null
    private var fj: Job? = null
    private var s = 1f
    private var baseW = PetEngine.DEFAULT_PET_WIDTH
    private var baseH = PetEngine.DEFAULT_PET_HEIGHT

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

    fun reload(p: PetInfo): Boolean {
        stop()
        return load(p)
    }

    fun start() {
        fj?.cancel()
        fj = scope.launch(Dispatchers.Main) {
            var lt = System.nanoTime()
            while (isActive) {
                val l = loader ?: break
                val bitmap = withContext(Dispatchers.IO) {
                    petEngine.getCurrentFrame(l, _state.value, fi)
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
                // Codex Pet 待机时降到 2FPS 省电；GIF 没有状态机，始终按动画帧率播放
                val idle = _state.value == PetAnimationState.IDLE && isCodexPet()
                val ns = if (idle) IDLE_FRAME_INTERVAL_NS else ANIM_FRAME_INTERVAL_NS
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
