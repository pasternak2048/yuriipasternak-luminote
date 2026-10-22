package com.yp.luminote.app.rendering.gpu

import android.app.Activity
import android.content.Intent
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yp.luminote.app.MainActivity
import com.yp.luminote.app.animation.definitions.SnakeAnimation
import com.yp.luminote.app.animation.plan.*
import com.yp.luminote.app.effects.geometry.DisplayOutline
import com.yp.luminote.app.effects.geometry.EdgePathGeometryProvider
import com.yp.luminote.app.effects.model.HaloConfig
import com.yp.luminote.app.rendering.api.*
import com.yp.luminote.app.rendering.canvas.CanvasRenderBackend
import com.yp.luminote.app.rendering.canvas.CanvasRenderTarget
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.*

/** Opt-in physical framebuffer proof. All motion comes from frozen shared host-frame inputs. */
@RunWith(AndroidJUnit4::class)
class GpuSnakePixelCopyInstrumentationTest {
    @Test fun `Snake shader compiles on device`() { RuntimeShader(GpuSnakeRenderBackend.SNAKE_SHADER) }

    @Test fun `GPU base and production Snake pixels follow contour and survive recreation`() {
        assumeTrue("Gate C PixelCopy opt-in", InstrumentationRegistry.getArguments().getString("gpuSnakeGateC") == "true")
        val scenario = ActivityScenario.launch<MainActivity>(Intent(InstrumentationRegistry.getInstrumentation().targetContext,MainActivity::class.java))
        var host: Activity? = null
        var view: Fixture? = null
        fun install() = scenario.onActivity { activity ->
            host = activity
            view = Fixture(activity).also { activity.setContentView(it) }
        }
        try {
            install()
            copy(host!!,view!!).recycle() // wait for the real layout/surface
            val fixture = view!!
            val generation = fixture.gpu.generationForDebugEvidence()
            var before = GpuPulseEvidence.snapshot(generation)!!.submissions
            for (phase in listOf(0f,.23f,.91f,.99f)) {
                scenario.onActivity { fixture.select(phase,optical = false,gpu = true) }
                assertNotNull("GPU submits base Snake",GpuPulseEvidence.awaitSubmissionCount(generation,before+1,5000))
                val base = copy(host!!,fixture)
                val tail = fixture.sample(base,phase,.1f)
                val body = fixture.sample(base,phase,.5f)
                val head = fixture.sample(base,phase,.92f)
                assertTrue("defined head/body/tail at $phase: $head/$body/$tail",head > body+15 && body > tail+10)
                assertEquals("absent contour is transparent",0,fixture.sample(base,GpuSnakeSourceProfile.wrap(phase+.45f),.5f))
                assertEquals("interior transparent",Color.BLACK,base.getPixel(base.width/2,base.height/2))
                assertEquals("physical exterior clipped",Color.BLACK,base.getPixel(0,0))
                scenario.onActivity { fixture.select(phase,optical = false,gpu = false) }
                val control = copy(host!!,fixture)
                assertTrue("Canvas/GPU matched head position and energy",abs(fixture.sample(control,phase,.92f)-head) <= 48)
                before = GpuPulseEvidence.snapshot(generation)!!.submissions
                scenario.onActivity { fixture.select(phase,optical = true,gpu = true) }
                assertNotNull("GPU submits production optical Snake",GpuPulseEvidence.awaitSubmissionCount(generation,before+1,5000))
                val optical = copy(host!!,fixture)
                assertTrue("production source remains luminous",fixture.sample(optical,phase,.92f) > 35)
                assertTrue("real soft envelope outside base core",fixture.envelope(optical,phase) > fixture.envelope(base,phase)+1)
                assertEquals("optical exterior stays clipped",Color.BLACK,optical.getPixel(0,0))
                assertEquals("optical interior stays transparent",Color.BLACK,optical.getPixel(optical.width/2,optical.height/2))
                scenario.onActivity { fixture.select(phase,optical = true,gpu = false) }
                val opticalControl = copy(host!!,fixture)
                assertTrue("production optical Canvas comparison",abs(fixture.sample(opticalControl,phase,.92f)-fixture.sample(optical,phase,.92f)) <= 48)
                base.recycle(); control.recycle(); optical.recycle(); opticalControl.recycle()
                before = GpuPulseEvidence.snapshot(generation)!!.submissions
            }
            val cached = GpuPulseEvidence.snapshot(generation)!!
            assertEquals("phase/modifiers do not rebuild field",1,cached.resourceInitializations)
            assertEquals("shader programs cached",1,cached.shaderInitializations)
            assertEquals("no GPU error",null,cached.firstFailure)
            assertEquals("actual GPU", "GPU",cached.latestActualBackend)
            assertEquals(0,cached.fallbacks)
            scenario.onActivity { fixture.calibrate() }
            assertNotNull(GpuPulseEvidence.awaitSubmissionCount(generation,before+1,5000))
            assertEquals("calibration rebuild",2,GpuPulseEvidence.snapshot(generation)!!.resourceInitializations)
            scenario.recreate(); install()
            copy(host!!,view!!).recycle()
            val recreated = view!!.gpu.generationForDebugEvidence()
            assertTrue(recreated > generation)
            assertNotNull(GpuPulseEvidence.awaitSubmitted(recreated,5000))
            assertTrue("old shader/field disposed",GpuPulseEvidence.snapshot(generation)!!.disposals > 0)
            assertTrue(GpuPulseEvidence.snapshot(generation)!!.fieldDisposals > 0)
        } finally { scenario.close() }
    }

    /** Controlled outline is still exclusively resolved by the production geometry provider. */
    private class Fixture(activity: Activity): View(activity) {
        var drawn = CountDownLatch(1)
        private val outline = DisplayOutline(resources.displayMetrics.density)
        val gpu = GpuSnakeRenderBackend(outline)
        private var config = HaloConfig(motion = SnakeAnimation.id,color = Color.CYAN,intensity = 1f)
        private val canvasBackend = CanvasRenderBackend(config,outline)
        private var useGpu = true
        private val frame = RenderFrame().apply { animationProgress=1f; intent=RenderIntent.DEBUG_GPU_SNAKE }
        private var currentPlan = HaloEffectVisualPlanMapper.map(config)
        init { setBackgroundColor(Color.BLACK); gpu.updateConfig(config) }
        override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {
            outline.resize(w,h)
            outline.updateDisplayShape(Path().apply { addRoundRect(RectF(0f,0f,w.toFloat(),h.toFloat()),80f,80f,Path.Direction.CW) })
            gpu.invalidateSurface(); canvasBackend.invalidateSurface()
            frame.viewportWidth=w; frame.viewportHeight=h
        }
        fun select(phase:Float,optical:Boolean,gpu:Boolean) {
            drawn = CountDownLatch(1)
            frame.effectPhase=phase
            currentPlan = HaloEffectVisualPlanMapper.map(config).let { base -> VisualPlanSpec(listOf(base.emitters.single().copy(modifiers=if(optical) GpuSnakeCapability.productionModifiers else emptyList()))) }
            this.gpu.updateDebugVisualPlan(currentPlan); canvasBackend.updateDebugVisualPlan(currentPlan)
            useGpu=gpu; invalidate()
        }
        fun calibrate() {
            drawn = CountDownLatch(1)
            config=config.copy(edgeCalibrationDp=3f)
            gpu.updateConfig(config); canvasBackend.updateConfig(config); useGpu=true; invalidate()
        }
        override fun onDraw(canvas:Canvas) {
            val result = if(useGpu) gpu.render(CanvasRenderTarget(canvas),frame) else canvasBackend.render(CanvasRenderTarget(canvas),frame)
            check(result == RenderOutcome.Rendered) { "Snake fixture failed: $result" }
            drawn.countDown()
        }
        private fun point(phase:Float,u:Float):FloatArray {
            val geometry=currentPlan.emitters.single().geometry as GeometryRequest.DisplayOutlineContour
            val snapshot=EdgePathGeometryProvider(outline).geometryFor(9f,outline.opticalInsetPx+outline.dpToPx(geometry.edgeCalibrationDp),outline.dpToPx(geometry.cornerCalibrationDp),geometry.cornerShape,0f)!!
            val xy=FloatArray(2)
            snapshot.measure.getPosTan(GpuSnakeSourceProfile.wrap(phase+.18f*u)*snapshot.measure.length,xy,null)
            return xy
        }
        fun sample(bitmap:Bitmap,phase:Float,u:Float):Int {
            val p=point(phase,u); var best=0
            for(y in (p[1].roundToInt()-3)..(p[1].roundToInt()+3)) for(x in (p[0].roundToInt()-3)..(p[0].roundToInt()+3)) {
                if(x in 0 until bitmap.width && y in 0 until bitmap.height) best=max(best,Color.green(bitmap.getPixel(x,y)))
            }
            return best
        }
        fun envelope(bitmap:Bitmap,phase:Float):Int {
            val p=point(phase,.92f)
            val dx=width*.5f-p[0]; val dy=height*.5f-p[1]; val distance=hypot(dx,dy)
            val x=(p[0]+dx/distance*12f).roundToInt().coerceIn(0,width-1)
            val y=(p[1]+dy/distance*12f).roundToInt().coerceIn(0,height-1)
            return Color.green(bitmap.getPixel(x,y))
        }
        override fun onDetachedFromWindow() { gpu.dispose(); canvasBackend.dispose(); super.onDetachedFromWindow() }
    }

    private fun copy(activity:Activity,view:View):Bitmap {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertTrue("fixture frame/layout timeout",(view as Fixture).drawn.await(5,TimeUnit.SECONDS))
        val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
        val location=IntArray(2); view.getLocationInWindow(location)
        val latch=CountDownLatch(1); var result=PixelCopy.ERROR_UNKNOWN
        PixelCopy.request(activity.window,Rect(location[0],location[1],location[0]+view.width,location[1]+view.height),bitmap,{ result=it; latch.countDown() },Handler(Looper.getMainLooper()))
        assertTrue(latch.await(5,TimeUnit.SECONDS)); assertEquals(PixelCopy.SUCCESS,result)
        return bitmap
    }
}
