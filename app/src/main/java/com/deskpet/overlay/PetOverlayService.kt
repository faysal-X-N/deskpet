package com.deskpet.overlay

import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.ui.platform.ComposeView
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
import kotlinx.coroutines.flow.first

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
    private var wx = 0f
    private var wy = 200f
    private var savedScale = 1f
    private val sc = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: android.content.Context?, i: Intent?) {
            if (::anim.isInitialized) when (i?.action) {
                Intent.ACTION_SCREEN_OFF -> anim.stop()
                Intent.ACTION_SCREEN_ON -> anim.start()
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
    }

    private fun onTap() {
        if (anim.isCodexPet()) {
            anim.switchState(PetAnimationState.WAVING)
        } else {
            bounce()
        }
        auto?.onUserTouch()
    }

    private fun onScale(zoom: Float) {
        val ns = (savedScale * zoom).coerceIn(0.3f, 5f)
        anim.setScale(ns)
        savedScale = ns
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
            onScale = ::onScale
        )
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        })
    }

    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        i?.getStringExtra(EXTRA_PET_ID)?.let { id ->
            job?.cancel()
            job = sc.launch {
                try {
                    show(id)
                } catch (e: Exception) {
                    Log.e(TAG, "err", e)
                }
            }
        } ?: kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeout(2000L) {
                repo.getActivePetId().first()?.let { id ->
                    show(id)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeout(2000L) {
                    petId?.let { id -> repo.savePosition(id, wx, wy, savedScale) }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save position on destroy", e)
        }
        removeOverlayView()
        if (::anim.isInitialized) anim.stop()
        auto?.stop()
        if (::parser.isInitialized) parser.release()
        try {
            unregisterReceiver(screenReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister screen receiver", e)
        }
        sc.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(r: Intent?) {
        super.onTaskRemoved(r)
        try {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeout(2000L) {
                    petId?.let { id -> repo.savePosition(id, wx, wy, savedScale) }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save position on task removed", e)
        }
        stopSelf()
    }
    override fun onConfigurationChanged(c: Configuration) {
        super.onConfigurationChanged(c)
        val p = Point()
        wm.defaultDisplay.getSize(p)
        if (wx > p.x || wy > p.y) {
            wx = 100f
            wy = 100f
        }
        clampPosition()
        updatePosition()
    }

    private suspend fun show(id: String) {
        val info = withContext(Dispatchers.IO) { repo.getPet(id) } ?: return
        petId = id
        wx = info.positionX
        wy = info.positionY
        if (!anim.reload(info)) return
        anim.setScale(info.scale)
        savedScale = info.scale
        anim.start()
        add()
    }

    private fun add() {
        if (!::wm.isInitialized) return
        if (!android.provider.Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    data = android.net.Uri.parse("package:$packageName")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            return
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
            if (Build.VERSION.SDK_INT >= 26)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = wx.toInt()
            y = wy.toInt()
        }
        try {
            wm.addView(cv, params)
        } catch (e: Exception) {
            Log.e(TAG, "addView", e)
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
                p.y = wy.toInt()
                try {
                    wm.updateViewLayout(v, p)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to update overlay position", e)
                }
            }
        }
    }

    private fun clampPosition() {
        val p = Point()
        wm.defaultDisplay.getSize(p)
        val w = anim.petWidth.value
        wx = wx.coerceIn((-w + 32).toFloat(), (p.x - 32).toFloat())
        wy = wy.coerceIn(0f, (p.y - 32).toFloat())
    }

    private fun bounce() {
        sc.launch {
            val o = wy
            wy = o - 20
            updatePosition()
            delay(100)
            wy = o
            updatePosition()
        }
    }

    companion object { const val EXTRA_PET_ID = "pet_id"; private const val TAG = "PO" }
}
