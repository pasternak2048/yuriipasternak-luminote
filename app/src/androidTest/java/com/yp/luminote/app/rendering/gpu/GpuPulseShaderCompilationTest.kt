package com.yp.luminote.app.rendering.gpu

import android.graphics.RuntimeShader
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GpuPulseShaderCompilationTest {
    @Test fun derivativeSourceFailsAndProductionSourceCompiles() {
        val corrected = GpuPulseRenderBackend.PULSE_SHADER
        val original = corrected.replace("float aa = max(1.0, abs(dx) + abs(dy));", "float aa = max(1.0, fwidth(distance));")
        val failure = runCatching { RuntimeShader(original) }.exceptionOrNull()
        assertNotNull("Original derivative source must reproduce compiler failure", failure)
        Log.e("HaloGpuWitness", "Original Pulse shader compiler evidence", failure)
        assertTrue(failure!!.message.orEmpty().contains("fwidth"))
        RuntimeShader(corrected)
    }
}
