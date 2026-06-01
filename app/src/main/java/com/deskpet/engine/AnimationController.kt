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
    companion object {
        private const val IDLE_FRAME_INTERVAL_NS = 333_000_000L
        private const val ANIM_FRAME_INTERVAL_NS = 200_000_000L
        private const val MIN_FRAME_DELAY_NS = 500_000L
        private const val LOOP_COUNT_BEFORE_IDLE = 2
        private const val MIN_PET_SIZE_PX = 32
        private const val MIN_SCALE = 1f
        private const val MAX_SCALE = 5f
    }

    private val _state = MutableStateFlow(PetAnimationState.IDLE)
    override val currentState: StateFlow<PetAnimationState> = _state
    private val _frame = MutableStateFlow<Bitmap?>(null)
    val currentFrame: StateFlow<Bitmap?> = _frame
    private val _w = MutableStateFlow(192)
    val petWidth: StateFlow<Int> = _w
    private val _h = MutableStateFlow(208)
    val petHeight: StateFlow<Int> = _h

    @Volatile private var fi = 0
    private var pc = 0
    private var loader: PetEngine.PetLoader? = null
    private var fj: Job? = null
    private var s = 1f

    fun load(p: PetInfo): Boolean {
        loader = petEngine.createLoader(p) ?: return false
        val (w, h) = petEngine.getPetSize(loader!!)
        _w.value = w; _h.value = h; reset(); return true
    }

    fun reload(p: PetInfo): Boolean { stop(); return load(p) }

    fun start() {
        fj?.cancel()
        fj = scope.launch(Dispatchers.Main) {
            var lt = System.nanoTime()
            while (isActive) {
                loader?.let { l ->
                    petEngine.getCurrentFrame(l, _state.value, fi)?.let { _frame.value = it }
                    fi++
                    val tf = petEngine.getFrameCount(l, _state.value)
                    if (fi >= tf && tf > 0) { fi = 0; if (isCodexPet()) { pc++; if (_state.value != PetAnimationState.IDLE && pc >= LOOP_COUNT_BEFORE_IDLE) { _state.value = PetAnimationState.IDLE; pc = 0 } } }
                } ?: break
                val ns = if (_state.value == PetAnimationState.IDLE) IDLE_FRAME_INTERVAL_NS else ANIM_FRAME_INTERVAL_NS
                delay(((ns - (System.nanoTime() - lt)).coerceAtLeast(MIN_FRAME_DELAY_NS)) / 1_000_000)
                lt = System.nanoTime()
            }
        }
    }

    fun stop() { fj?.cancel(); fj = null }
    override fun switchState(st: PetAnimationState) { _state.value = st; fi = 0; pc = 0 }
    fun reset() { _state.value = PetAnimationState.IDLE; fi = 0; pc = 0 }
    fun setScale(v: Float) { s = v.coerceIn(MIN_SCALE, MAX_SCALE); loader?.let { val (w, h) = petEngine.getPetSize(it); _w.value = (w * s).toInt().coerceAtLeast(MIN_PET_SIZE_PX); _h.value = (h * s).toInt().coerceAtLeast(MIN_PET_SIZE_PX) } }
    fun isCodexPet() = loader is PetEngine.PetLoader.CodexPet
}
