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
import com.yp.luminote.app.animation.*
import com.yp.luminote.app.animation.definitions.ImpulseAnimation
import com.yp.luminote.app.animation.plan.*
import com.yp.luminote.app.effects.geometry.DisplayOutline
import com.yp.luminote.app.effects.geometry.EdgePathGeometryProvider
import com.yp.luminote.app.effects.model.HaloConfig
import com.yp.luminote.app.rendering.api.*
import com.yp.luminote.app.rendering.canvas.CanvasRenderBackend
import com.yp.luminote.app.rendering.canvas.CanvasRenderTarget
import com.yp.luminote.app.rendering.canvas.lightImpulseEndpoints
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.*

@RunWith(AndroidJUnit4::class)
class GpuImpulsePixelCopyInstrumentationTest {
    @Test fun `Impulse shader compiles on device`() { RuntimeShader(GpuImpulseRenderBackend.IMPULSE_SHADER) }

    @Test fun `base Impulse submits symmetric travel birth convergence and terminal frames`() {
        assumeTrue("D1 physical opt-in",InstrumentationRegistry.getArguments().getString("gpuImpulseGateD1") == "true")
        val scenario = ActivityScenario.launch<MainActivity>(Intent(InstrumentationRegistry.getInstrumentation().targetContext,MainActivity::class.java))
        var host: Activity? = null
        var view: Fixture? = null
        fun install() = scenario.onActivity { activity ->
            // PixelCopy must sample the renderer, without status/navigation content over its contour.
            activity.window.insetsController?.hide(android.view.WindowInsets.Type.systemBars())
            host=activity; view=Fixture(activity).also(activity::setContentView)
        }
        try {
            install(); copy(host!!,view!!).recycle()
            val fixture=view!!
            val generation=fixture.gpu.generationForDebugEvidence()
            for (phase in listOf(.045f,.14f,.4f,.6f,.8f,.86f,.94f,.99f,1f)) {
                val before=GpuPulseEvidence.snapshot(generation)!!.submissions
                scenario.onActivity { fixture.select(phase,true) }
                assertNotNull("real GPU submission at $phase",GpuPulseEvidence.awaitSubmissionCount(generation,before+1,5000))
                val gpu=copy(host!!,fixture)
                assertEquals("interior transparent",Color.BLACK,gpu.getPixel(gpu.width/2,gpu.height/2))
                assertEquals("exterior clipped",Color.BLACK,gpu.getPixel(0,0))
                if (phase in .4f.. .6f) {
                    val travel=lightImpulseTravelProgress(phase)
                    val e=fixture.endpoints()
                    val core=fixture.contourLength()*LIGHT_CORE_FRACTION*LIGHT_CORE_SPAN_MULTIPLIER*.22f
                    val forward=fixture.sample(gpu,e.topFraction*fixture.contourLength()+e.forwardDistance*travel-core)
                    val reverse=fixture.sample(gpu,e.topFraction*fixture.contourLength()-e.reverseDistance*travel+core)
                    assertTrue("two luminous owned heads $forward/$reverse at $phase; max=${fixture.maximum(gpu)} ${fixture.sourceDetail(gpu)} endpoints=$e travel=$travel length=${fixture.contourLength()}",forward>80 && reverse>80)
                    scenario.onActivity { fixture.select(phase,false) }
                    val control=copy(host!!,fixture)
                    val canvasHead=fixture.sample(control,e.topFraction*fixture.contourLength()+e.forwardDistance*travel-core)
                    assertTrue("Canvas source comparison $forward/$canvasHead",abs(forward-canvasHead)<65)
                    control.recycle()
                } else if (phase == 1f) {
                    scenario.onActivity { fixture.select(phase,false) }
                    val terminalControl=copy(host!!,fixture)
                    val controlMaximum=fixture.maximum(terminalControl)
                    terminalControl.recycle()
                    assertEquals("Canvas terminal control gone",0,controlMaximum)
                    assertEquals("terminal source gone ${fixture.sourceDetail(gpu)} Canvas=$controlMaximum",0,fixture.maximum(gpu))
                } else assertTrue("active source at $phase",fixture.maximum(gpu)>0)
                gpu.recycle()
            }
            val evidence=GpuPulseEvidence.snapshot(generation)!!
            assertEquals("GPU",evidence.latestActualBackend)
            assertNull(evidence.firstFailure); assertEquals(0,evidence.fallbacks)
            assertEquals("cached field",1,evidence.resourceInitializations)
            assertEquals("cached shader",1,evidence.shaderInitializations)
            scenario.recreate(); install(); copy(host!!,view!!).recycle()
            assertTrue(view!!.gpu.generationForDebugEvidence()>generation)
            assertTrue(GpuPulseEvidence.snapshot(generation)!!.disposals>0)
        } finally { scenario.close() }
    }

    private class Fixture(activity: Activity): View(activity) {
        var drawn=CountDownLatch(1)
        private val outline=DisplayOutline(resources.displayMetrics.density)
        val gpu=GpuImpulseRenderBackend(outline)
        private val config=HaloConfig(motion=ImpulseAnimation.id,color=Color.CYAN,intensity=1f)
        private val plan=HaloEffectVisualPlanMapper.map(config).let { VisualPlanSpec(listOf(it.emitters.single().copy(modifiers=emptyList()))) }
        private val control=CanvasRenderBackend(config,outline)
        private var useGpu=true
        private var waitsForCommit=false
        private val frame=RenderFrame().apply { animationProgress=1f; effectPhase=.4f; intent=RenderIntent.DEBUG_GPU_IMPULSE }
        init { keepScreenOn=true; setBackgroundColor(Color.BLACK); gpu.updateDebugVisualPlan(plan); control.updateDebugVisualPlan(plan) }
        override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {
            outline.resize(w,h)
            outline.updateDisplayShape(Path().apply { addRoundRect(RectF(0f,0f,w.toFloat(),h.toFloat()),80f,80f,Path.Direction.CW) })
            gpu.invalidateSurface(); control.invalidateSurface(); frame.viewportWidth=w; frame.viewportHeight=h
        }
        fun select(phase:Float,useGpu:Boolean) {
            drawn=CountDownLatch(1)
            val selectedFrame=drawn
            waitsForCommit=true
            viewTreeObserver.registerFrameCommitCallback { selectedFrame.countDown() }
            frame.effectPhase=phase; this.useGpu=useGpu; invalidate()
        }
        override fun onDraw(canvas: Canvas) {
            val result=if(useGpu) gpu.render(CanvasRenderTarget(canvas),frame) else control.render(CanvasRenderTarget(canvas),frame)
            check(result == RenderOutcome.Rendered) { "Impulse fixture failure: $result" }
            if(!waitsForCommit) drawn.countDown()
        }
        private fun geometry() = (plan.emitters.single().geometry as GeometryRequest.DisplayOutlineContour).let {
            EdgePathGeometryProvider(outline).geometryFor(4f+it.thickness*10f,outline.opticalInsetPx+outline.dpToPx(it.edgeCalibrationDp),outline.dpToPx(it.cornerCalibrationDp),it.cornerShape,0f)!!
        }
        fun contourLength()=geometry().length
        fun endpoints()=geometry().let { lightImpulseEndpoints(it.measure,it.length,it.bounds) }
        fun sample(bitmap:Bitmap,arc:Float):Int {
            val g=geometry(); val p=FloatArray(2); g.measure.getPosTan((arc%g.length+g.length)%g.length,p,null)
            var best=0
            for(y in (p[1].roundToInt()-3)..(p[1].roundToInt()+3)) for(x in (p[0].roundToInt()-3)..(p[0].roundToInt()+3))
                if(x in 0 until bitmap.width && y in 0 until bitmap.height) best=max(best,Color.green(bitmap.getPixel(x,y)))
            return best
        }
        fun maximum(bitmap:Bitmap):Int {
            var best=0
            for(i in 0 until 960) best=max(best,sample(bitmap,i/960f*contourLength()))
            return best
        }
        fun sourceDetail(bitmap:Bitmap):String {
            var best=0; var at=0f
            for(i in 0 until 960) { val v=sample(bitmap,i/960f*contourLength()); if(v>best) { best=v; at=i/960f } }
            return "peak=$best fraction=$at"
        }
        override fun onDetachedFromWindow() { gpu.dispose(); control.dispose(); super.onDetachedFromWindow() }
    }

    private fun copy(activity:Activity,view:Fixture):Bitmap {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertTrue(view.drawn.await(5,TimeUnit.SECONDS))
        val presented=CountDownLatch(1)
        view.postOnAnimation { view.postOnAnimation { presented.countDown() } }
        assertTrue("hardware presentation boundary",presented.await(5,TimeUnit.SECONDS))
        val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
        val location=IntArray(2); view.getLocationInWindow(location)
        val latch=CountDownLatch(1); var result=PixelCopy.ERROR_UNKNOWN
        PixelCopy.request(activity.window,Rect(location[0],location[1],location[0]+view.width,location[1]+view.height),bitmap,{result=it;latch.countDown()},Handler(Looper.getMainLooper()))
        assertTrue(latch.await(5,TimeUnit.SECONDS)); assertEquals(PixelCopy.SUCCESS,result)
        return bitmap
    }
}
