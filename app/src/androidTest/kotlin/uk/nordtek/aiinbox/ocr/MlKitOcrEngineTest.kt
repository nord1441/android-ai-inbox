package uk.nordtek.aiinbox.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class MlKitOcrEngineTest {

    @Test
    fun recognize_extractsLatinText() = runBlocking {
        val bmp = renderText("HELLO WORLD")
        val engine = MlKitOcrEngine()
        val out = engine.recognize(bmp)
        assertThat(out.uppercase()).contains("HELLO")
    }

    private fun renderText(text: String): Bitmap {
        val bmp = Bitmap.createBitmap(800, 200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        val paint = Paint().apply {
            color = Color.BLACK
            textSize = 80f
            isAntiAlias = true
        }
        canvas.drawText(text, 50f, 130f, paint)
        return bmp
    }
}
