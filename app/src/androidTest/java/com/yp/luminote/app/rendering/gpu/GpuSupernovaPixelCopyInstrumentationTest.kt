package com.yp.luminote.app.rendering.gpu

import android.app.Activity
import android.content.Intent
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yp.luminote.app.MainActivity
import com.yp.luminote.app.animation.*
import com.yp.luminote.app.animation.definitions.SupernovaAnimation
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

@RunWith(AndroidJUnit4::class)
class GpuSupernovaPixelCopyInstrumentationTest {
    @Test fun `Supernova shader compiles on device`() { RuntimeShader(GpuSupernovaRenderBackend.SUPERNOVA_SHADER) }

    @Test fun `base Supernova submits symmetric travel birth convergence and terminal frames`() {
        assumeTrue("Gate E physical opt-in",InstrumentationRegistry.getArguments().getString("gpuSupernovaGateE") == "true")
        val scenario = ActivityScenario.launch<MainActivity>(Intent(InstrumentationRegistry.getInstrumentation().targetContext,MainActivity::class.java))
        var host: Activity? = null
        var view: Fixture? = null
        fun install() = scenario.onActivity { activity ->
            activity.setTurnScreenOn(true)
            activity.setShowWhenLocked(true)
            // PixelCopy must sample the renderer, without status/navigation content over its contour.
            activity.window.insetsController?.hide(android.view.WindowInsets.Type.systemBars())
            host=activity; view=Fixture(activity).also(activity::setContentView)
        }
        try {
            install(); copy(host!!,view!!).recycle()
            val fixture=view!!
            val generation=fixture.gpu.generationForDebugEvidence()
            for (optical in listOf(false,true)) for (phase in listOf(0f,.045f,.09f,.3f,.7f,.99f,1.09f)) {
                val before=GpuPulseEvidence.snapshot(generation)!!.submissions
                scenario.onActivity { fixture.select(phase,true,optical) }
                assertNotNull("real GPU submission at $phase",GpuPulseEvidence.awaitSubmissionCount(generation,before+1,5000))
                val gpu=copy(host!!,fixture)
                if (phase==.045f || phase==.09f) {
                    val values=fixture.longitudinalProfile(gpu,phase)
                    val curvature=values.zipWithNext { a,b -> b-a }.zipWithNext { a,b -> abs(b-a) }.maxOrNull()!!
                    Log.i("GpuSupernovaContinuity","phase=$phase optical=$optical maxSecondDifference=$curvature samples=${values.size}")
                    assertTrue("continuous optical falloff phase=$phase optical=$optical curvature=$curvature",curvature<=8)
                    assertTrue("luminous core retained",values.first()>values.last()+30)
                }
                assertEquals("interior transparent",Color.BLACK,gpu.getPixel(gpu.width/2,gpu.height/2))
                assertEquals("exterior clipped",Color.BLACK,gpu.getPixel(0,0))
                scenario.onActivity { fixture.select(phase,false,optical) }
                val control=copy(host!!,fixture)
                val g=fixture.maximum(gpu); val c=fixture.maximum(control)
                assertTrue("Canvas/GPU energy phase=$phase optical=$optical $g/$c",abs(g-c)<48)
                if (phase==0f) assertEquals("no ignition energy at cycle zero",0,g)
                else if (phase==.09f) assertTrue("peak release",g>80)
                // Both branches around the top origin share the same sampled release.
                val radius=fixture.contourLength()*(.012f+.026f*GpuSupernovaSourceProfile.intensity(phase))*1.35f
                val origin=fixture.topArc()
                val left=fixture.sample(gpu,origin-radius*.35f)
                val right=fixture.sample(gpu,origin+radius*.35f)
                assertTrue("symmetric release $left/$right",abs(left-right)<35)
                assertTrue("Canvas distribution left",abs(left-fixture.sample(control,origin-radius*.35f))<48)
                assertTrue("Canvas distribution right",abs(right-fixture.sample(control,origin+radius*.35f))<48)
                gpu.recycle(); control.recycle()
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
        val gpu=GpuSupernovaRenderBackend(outline)
        private val config=HaloConfig(motion=SupernovaAnimation.id,color=Color.CYAN,intensity=1f)
        private var plan=HaloEffectVisualPlanMapper.map(config).let { VisualPlanSpec(listOf(it.emitters.single().copy(modifiers=emptyList()))) }
        private val control=CanvasRenderBackend(config,outline)
        private var useGpu=true
        private var waitsForCommit=false
        private val frame=RenderFrame().apply { animationProgress=1f; effectPhase=.09f; intent=RenderIntent.DEBUG_GPU_SUPERNOVA }
        init { keepScreenOn=true; setBackgroundColor(Color.BLACK); gpu.updateDebugVisualPlan(plan); control.updateDebugVisualPlan(plan) }
        override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {
            outline.resize(w,h)
            outline.updateDisplayShape(Path().apply { addRoundRect(RectF(0f,0f,w.toFloat(),h.toFloat()),80f,80f,Path.Direction.CW) })
            gpu.invalidateSurface(); control.invalidateSurface(); frame.viewportWidth=w; frame.viewportHeight=h
        }
        fun select(phase:Float,useGpu:Boolean,optical:Boolean) {
            plan=HaloEffectVisualPlanMapper.map(config).let { VisualPlanSpec(listOf(it.emitters.single().copy(modifiers=if(optical) GpuSupernovaCapability.productionModifiers else emptyList()))) }
            gpu.updateDebugVisualPlan(plan); control.updateDebugVisualPlan(plan)
            drawn=CountDownLatch(1)
            val selectedFrame=drawn
            waitsForCommit=true
            viewTreeObserver.registerFrameCommitCallback { selectedFrame.countDown() }
            frame.effectPhase=phase; this.useGpu=useGpu; invalidate()
        }
        override fun onDraw(canvas: Canvas) {
            val result=if(useGpu) gpu.render(CanvasRenderTarget(canvas),frame) else control.render(CanvasRenderTarget(canvas),frame)
            check(result == RenderOutcome.Rendered) { "Supernova fixture failure: $result" }
            if(!waitsForCommit) drawn.countDown()
        }
        private fun geometry() = (plan.emitters.single().geometry as GeometryRequest.DisplayOutlineContour).let {
            EdgePathGeometryProvider(outline).geometryFor(4f+it.thickness*10f,outline.opticalInsetPx+outline.dpToPx(it.edgeCalibrationDp),outline.dpToPx(it.cornerCalibrationDp),it.cornerShape,0f)!!
        }
        fun contourLength()=geometry().length
        fun topArc():Float {
            val g=geometry(); val measure=PathMeasure(g.path,false); val p=FloatArray(2)
            var nearest=Float.MAX_VALUE; var arc=0f
            for(i in 0..960) {
                val a=g.length*i/960f; measure.getPosTan(a,p,null)
                val dx=p[0]-g.bounds.centerX(); val dy=p[1]-g.bounds.top
                if(dx*dx+dy*dy<nearest) { nearest=dx*dx+dy*dy; arc=a }
            }
            return arc
        }
        fun sample(bitmap:Bitmap,arc:Float):Int {
            val g=geometry(); val p=FloatArray(2); g.measure.getPosTan((arc%g.length+g.length)%g.length,p,null)
            var best=0
            for(y in (p[1].roundToInt()-3)..(p[1].roundToInt()+3)) for(x in (p[0].roundToInt()-3)..(p[0].roundToInt()+3))
                if(x in 0 until bitmap.width && y in 0 until bitmap.height) best=max(best,Color.green(bitmap.getPixel(x,y)))
            return best
        }
        fun longitudinalProfile(bitmap:Bitmap,phase:Float):List<Int> {
            val g=geometry(); val p=FloatArray(2); val tangent=FloatArray(2)
            val radius=g.length*(.012f+.026f*GpuSupernovaSourceProfile.intensity(phase))*1.35f
            val origin=topArc()
            // Dense contour samples; sample only across the normal, without longitudinal smoothing.
            return (0..120).map { index ->
                val arc=origin+radius*(.1f+index*.006f)
                g.measure.getPosTan((arc%g.length+g.length)%g.length,p,tangent)
                var best=0
                for(offset in -2..2) {
                    val x=(p[0]-tangent[1]*offset).roundToInt()
                    val y=(p[1]+tangent[0]*offset).roundToInt()
                    if(x in 0 until bitmap.width && y in 0 until bitmap.height) best=max(best,Color.green(bitmap.getPixel(x,y)))
                }
                best
            }
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
