package com.yihuan.autofish

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import kotlinx.coroutines.*
import kotlin.math.abs
import java.io.File

/**
 * Full-screen test page — swipe left/right to switch example images,
 * matching logic identical to ClickScript.
 * Only the highest-confidence match per control is drawn.
 *
 * Logcat filter: 【TestActivity】
 */
class TestActivity : AppCompatActivity() {

    companion object {
        const val TAG = "TestActivity"
        const val HIGH_THRESHOLD = 0.90
        const val CURSOR_THRESHOLD = 0.90
        const val TARGET_THRESHOLD = 0.90
    }

    // Page definitions: file name + match logic type
    private val pages = listOf(
        TestPage("step1example1.jpg", MatchType.STEP1),
        TestPage("step3example1.jpg", MatchType.STEP3),
        TestPage("step4example1.jpg", MatchType.STEP4)
    )

    enum class MatchType { STEP1, STEP3, STEP4 }

    data class TestPage(
        val exampleFile: String,
        val matchType: MatchType
    ) {
        val pageTitle: String get() = when (matchType) {
            MatchType.STEP1 -> "Step 1"
            MatchType.STEP3 -> "Step 3"
            MatchType.STEP4 -> "Step 4"
        }
    }

    private lateinit var viewPager: ViewPager2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        setContentView(R.layout.activity_test)

        // 沉浸式全屏 — 隐藏导航栏，让图片全尺寸显示
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        )

        viewPager = findViewById(R.id.testViewPager)
        viewPager.adapter = TestPagerAdapter(pages)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "══════════════════════════════════════")
        Log.i(TAG, "Log filter: logcat -s TestActivity")
        Log.i(TAG, "══════════════════════════════════════")
    }
}

// ─── Adapter ──────────────────────────────────────────────────────────────

class TestPagerAdapter(
    private val pages: List<TestActivity.TestPage>
) : RecyclerView.Adapter<TestPagerAdapter.PageViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_test_page, parent, false)
        return PageViewHolder(view)
    }

    override fun getItemCount(): Int = pages.size

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        holder.bind(pages[position])
    }

    class PageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val imageView: ImageView = itemView.findViewById(R.id.testImageView)
        private val overlayView: View = itemView.findViewById(R.id.testOverlayView)
        private var job: Job? = null

        fun bind(page: TestActivity.TestPage) {
            val context = itemView.context
            overlayView.background = null

            val exampleBitmap = try {
                // 优先从 filesDir 加载用户导入的图片
                val userFile = File(context.filesDir, page.exampleFile)
                if (userFile.exists()) {
                    Log.i(TestActivity.TAG, "加载用户导入图片: ${userFile.absolutePath}")
                    BitmapFactory.decodeFile(userFile.absolutePath)
                } else {
                    // 回退到 assets 加载内置示例
                    val stream = context.assets.open("${ClickScript.TEMPLATE_DIR}/${page.exampleFile}")
                    val bmp = BitmapFactory.decodeStream(stream)
                    stream.close()
                    bmp
                }
            } catch (e: Exception) {
                Log.e(TestActivity.TAG, "Load failed: ${page.exampleFile}", e)
                null
            }

            if (exampleBitmap == null) {
                imageView.setImageDrawable(null)
                return
            }

            imageView.setImageBitmap(exampleBitmap)
            imageView.scaleType = ImageView.ScaleType.FIT_CENTER

            job?.cancel()
            job = CoroutineScope(Dispatchers.IO).launch {
                if (!OpenCVHelper.isInitialized()) {
                    OpenCVHelper.init()
                }

                Log.i(TestActivity.TAG, "───── ${page.pageTitle} | ${page.exampleFile} (${exampleBitmap.width}x${exampleBitmap.height}) ─────")

                val displayMatches = when (page.matchType) {
                    TestActivity.MatchType.STEP1 -> runStep1Matching(context, exampleBitmap)
                    TestActivity.MatchType.STEP3 -> runStep3Matching(context, exampleBitmap)
                    TestActivity.MatchType.STEP4 -> runStep4Matching(context, exampleBitmap)
                }

                Log.i(TestActivity.TAG, "───── ${page.pageTitle} total ${displayMatches.size} matches ─────")

                withContext(Dispatchers.Main) {
                    overlayView.background = MatchOverlayDrawable(exampleBitmap, displayMatches, page)
                }
            }
        }

        // ─── STEP1: bottom-right corner find step1 ─────────────────────

        private fun runStep1Matching(
            context: android.content.Context,
            screen: Bitmap
        ): List<MatchDisplayInfo> {
            val results = mutableListOf<MatchDisplayInfo>()
            val w = screen.width
            val h = screen.height
            val searchArea = android.graphics.Rect(w * 2 / 3, h * 2 / 3, w, h)

            Log.i(TestActivity.TAG, "  step1 search region: bottom-right (x>${w*2/3}, y>${h*2/3})")
            // Draw search region border
            results.add(MatchDisplayInfo(
                -1,
                org.opencv.core.Rect(searchArea.left, searchArea.top, searchArea.width(), searchArea.height()),
                0.0, "step1搜", Color.parseColor("#FFFF8800")
            ))

            val tmpl = loadTemplate(context, "step1.jpg") ?: return results
            val mask = loadMask(context, "step1mask.jpg")

            val matches = ImageMatcher.matchTemplateMulti(
                screenBitmap = screen,
                templateBitmap = tmpl,
                threshold = TestActivity.HIGH_THRESHOLD,
                maxResults = 20,
                searchArea = searchArea,
                maskBitmap = mask
            )
            val best = matches.maxByOrNull { it.confidence }
            if (best != null) {
                results.add(MatchDisplayInfo(1, best.rect, best.confidence, "step1", Color.parseColor("#FF4444")))
                Log.i(TestActivity.TAG, "  step1 OK conf=${"%.1f".format(best.confidence*100)}% center=(${best.center.x},${best.center.y}) rect=(${best.rect.x},${best.rect.y},${best.rect.width}x${best.rect.height})")
            } else {
                Log.i(TestActivity.TAG, "  step1 not found")
            }

            tmpl.recycle()
            mask?.recycle()
            return results
        }

        // ─── STEP3: refer in top-left corner → cursor in refer-defined region → target left/right ───

        private fun runStep3Matching(
            context: android.content.Context,
            screen: Bitmap
        ): List<MatchDisplayInfo> {
            val results = mutableListOf<MatchDisplayInfo>()
            val w = screen.width
            val h = screen.height
            var matchId = 0

            // 1. refer search area: x < 1/2 W, y < 1/4 H
            val referSearchW = w / 2
            val referSearchH = h / 4
            val referSearchArea = android.graphics.Rect(0, 0, referSearchW, referSearchH)
            Log.i(TestActivity.TAG, "  refer search region: (0,0 ${referSearchW}x${referSearchH}) [x<${w/2}, y<${h/4}]")

            // Draw refer search region border
            results.add(MatchDisplayInfo(
                -1,
                org.opencv.core.Rect(0, 0, referSearchW, referSearchH),
                0.0, "refer搜", Color.parseColor("#664488FF")
            ))

            val referTmpl = loadTemplate(context, "step3refer.jpg") ?: return results
            val referMask = loadMask(context, "step3refermask.jpg")

            val referMatches = ImageMatcher.matchTemplateMulti(
                screenBitmap = screen,
                templateBitmap = referTmpl,
                threshold = TestActivity.HIGH_THRESHOLD,
                maxResults = 20,
                searchArea = referSearchArea,
                maskBitmap = referMask
            )
            val bestRefer = referMatches.maxByOrNull { it.confidence }
            referTmpl.recycle()
            referMask?.recycle()

            if (bestRefer == null) {
                Log.i(TestActivity.TAG, "  refer not found")
                return results
            }
            matchId++
            val referRect = bestRefer.rect  // opencv Rect
            results.add(MatchDisplayInfo(matchId, referRect, bestRefer.confidence, "refer", Color.parseColor("#4488FF")))
            Log.i(TestActivity.TAG, "  refer OK conf=${"%.1f".format(bestRefer.confidence*100)}% rect=(${referRect.x},${referRect.y},${referRect.width}x${referRect.height})")

            // 2. cursor search area: y ∈ [refer.y1, refer.y2], x ∈ [refer_center_x, W - refer_center_x]
            //    where refer center = (m, n), refer y1=referRect.y, refer y2=referRect.y+referRect.height
            val m = referRect.x + referRect.width / 2    // refer center x
            val n = referRect.y + referRect.height / 2   // refer center y
            val y1 = referRect.y
            val y2 = referRect.y + referRect.height
            val cursorSearchL = m + 77                                   // left edge
            val cursorSearchR = m + 873                                  // right edge (absolute from center)
            val cursorSearchT = y1 + 10
            val cursorSearchB = y1 + 35                                   // bottom from refer top
            val cursorSearchW = cursorSearchR - cursorSearchL
            val cursorSearchH = cursorSearchB - cursorSearchT
            val cursorSearchArea = android.graphics.Rect(cursorSearchL, cursorSearchT, cursorSearchR, cursorSearchB)
            Log.i(TestActivity.TAG, "  cursor search region: x=[$cursorSearchL, $cursorSearchR), y=[$cursorSearchT, $cursorSearchB) ${cursorSearchW}x${cursorSearchH}")

            // Draw cursor search region dashed border
            results.add(MatchDisplayInfo(
                -1,
                org.opencv.core.Rect(cursorSearchL, cursorSearchT, cursorSearchW, cursorSearchH),
                0.0, "cursor搜", Color.parseColor("#66FFCC00")
            ))

            if (cursorSearchW <= 0 || cursorSearchH <= 0) {
                Log.w(TestActivity.TAG, "  cursor search area invalid, skip")
                return results
            }

            val cursorTmpl = loadTemplate(context, "step3cursor.jpg")
            val cursorMatches = if (cursorTmpl != null) {
                ImageMatcher.matchTemplateMulti(
                    screenBitmap = screen,
                    templateBitmap = cursorTmpl,
                    threshold = TestActivity.CURSOR_THRESHOLD,
                    maxResults = 20,
                    searchArea = cursorSearchArea,
                    maskBitmap = null
                )
            } else emptyList()
            val bestCursor = cursorMatches.maxByOrNull { it.confidence }
            cursorTmpl?.recycle()

            if (bestCursor != null) {
                matchId++
                results.add(MatchDisplayInfo(matchId, bestCursor.rect, bestCursor.confidence, "cursor", Color.parseColor("#FFCC00")))
                Log.i(TestActivity.TAG, "  cursor OK conf=${"%.1f".format(bestCursor.confidence*100)}% rect=(${bestCursor.rect.x},${bestCursor.rect.y})")

                // 3. target: left and right of cursor, within the cursor search area's y-range
                val targetTmpl = loadTemplate(context, "step3target.jpg") ?: return results

                // Left side: x from referSearchL to cursor.left, y same as cursor search
                val targetLeftL = cursorSearchL
                val targetLeftR = bestCursor.rect.x
                val targetLeftW = targetLeftR - targetLeftL
                if (targetLeftW > 0) {
                    val leftArea = android.graphics.Rect(targetLeftL, cursorSearchT, targetLeftR, cursorSearchB)
                    Log.i(TestActivity.TAG, "  target(L) search region: (${leftArea.left},${leftArea.top},${leftArea.width()}x${leftArea.height()})")

                    results.add(MatchDisplayInfo(
                        -1,
                        org.opencv.core.Rect(leftArea.left, leftArea.top, leftArea.width(), leftArea.height()),
                        0.0, "T左搜", Color.parseColor("#6600CCCC")
                    ))

                    val leftMatches = ImageMatcher.matchTemplateMulti(
                        screenBitmap = screen,
                        templateBitmap = targetTmpl,
                        threshold = TestActivity.TARGET_THRESHOLD,
                        maxResults = 20,
                        searchArea = leftArea,
                        maskBitmap = null
                    )
                    val bestLeft = leftMatches.maxByOrNull { it.confidence }
                    if (bestLeft != null) {
                        matchId++
                        results.add(MatchDisplayInfo(matchId, bestLeft.rect, bestLeft.confidence, "target(L)", Color.parseColor("#00CCCC")))
                        Log.i(TestActivity.TAG, "  target(L) OK conf=${"%.1f".format(bestLeft.confidence*100)}%")
                    } else {
                        Log.i(TestActivity.TAG, "  target(L) not found (${leftMatches.size} candidates)")
                    }
                } else {
                    Log.i(TestActivity.TAG, "  target(L) width=$targetLeftW, skip")
                }

                // Right side: x from cursor.right to cursorSearchR, y same as cursor search
                val targetRightL = bestCursor.rect.x + bestCursor.rect.width
                val targetRightR = cursorSearchR
                val targetRightW = targetRightR - targetRightL
                if (targetRightW > 0) {
                    val rightArea = android.graphics.Rect(targetRightL, cursorSearchT, targetRightR, cursorSearchB)
                    Log.i(TestActivity.TAG, "  target(R) search region: (${rightArea.left},${rightArea.top},${rightArea.width()}x${rightArea.height()})")

                    results.add(MatchDisplayInfo(
                        -1,
                        org.opencv.core.Rect(rightArea.left, rightArea.top, rightArea.width(), rightArea.height()),
                        0.0, "T右搜", Color.parseColor("#66CC00CC")
                    ))

                    val rightMatches = ImageMatcher.matchTemplateMulti(
                        screenBitmap = screen,
                        templateBitmap = targetTmpl,
                        threshold = TestActivity.TARGET_THRESHOLD,
                        maxResults = 20,
                        searchArea = rightArea,
                        maskBitmap = null
                    )
                    val bestRight = rightMatches.maxByOrNull { it.confidence }
                    if (bestRight != null) {
                        matchId++
                        results.add(MatchDisplayInfo(matchId, bestRight.rect, bestRight.confidence, "target(R)", Color.parseColor("#CC00CC")))
                        Log.i(TestActivity.TAG, "  target(R) OK conf=${"%.1f".format(bestRight.confidence*100)}%")
                    } else {
                        Log.i(TestActivity.TAG, "  target(R) not found (${rightMatches.size} candidates)")
                    }
                } else {
                    Log.i(TestActivity.TAG, "  target(R) width=$targetRightW, skip")
                }
                targetTmpl.recycle()
            } else {
                Log.i(TestActivity.TAG, "  cursor not found, skip target")
            }

            // Also draw left/right button positions
            val leftBtnArea = android.graphics.Rect(0, h / 2, w / 3, h)
            val leftTmpl = loadTemplate(context, "step3left.jpg")
            val leftMask = loadMask(context, "step3leftmask.jpg")
            if (leftTmpl != null) {
                val leftMatches = ImageMatcher.matchTemplateMulti(
                    screenBitmap = screen,
                    templateBitmap = leftTmpl,
                    threshold = TestActivity.HIGH_THRESHOLD,
                    maxResults = 20,
                    searchArea = leftBtnArea,
                    maskBitmap = leftMask
                )
                val bestLeftBtn = leftMatches.maxByOrNull { it.confidence }
                if (bestLeftBtn != null) {
                    matchId++
                    results.add(MatchDisplayInfo(matchId, bestLeftBtn.rect, bestLeftBtn.confidence, "left", Color.parseColor("#00CCCC")))
                    Log.i(TestActivity.TAG, "  left button OK conf=${"%.1f".format(bestLeftBtn.confidence*100)}%")
                }
                leftTmpl.recycle()
                leftMask?.recycle()
            }

            val rightBtnArea = android.graphics.Rect(w * 2 / 3, h / 2, w, h)
            val rightTmpl = loadTemplate(context, "step3right.jpg")
            val rightMask = loadMask(context, "step3rightmask.jpg")
            if (rightTmpl != null) {
                val rightMatches = ImageMatcher.matchTemplateMulti(
                    screenBitmap = screen,
                    templateBitmap = rightTmpl,
                    threshold = TestActivity.HIGH_THRESHOLD,
                    maxResults = 20,
                    searchArea = rightBtnArea,
                    maskBitmap = rightMask
                )
                val bestRightBtn = rightMatches.maxByOrNull { it.confidence }
                if (bestRightBtn != null) {
                    matchId++
                    results.add(MatchDisplayInfo(matchId, bestRightBtn.rect, bestRightBtn.confidence, "right", Color.parseColor("#CC00CC")))
                    Log.i(TestActivity.TAG, "  right button OK conf=${"%.1f".format(bestRightBtn.confidence*100)}%")
                }
                rightTmpl.recycle()
                rightMask?.recycle()
            }

            return results
        }

        // ─── STEP4: full-screen search for step4 ─────────────────────

        private fun runStep4Matching(
            context: android.content.Context,
            screen: Bitmap
        ): List<MatchDisplayInfo> {
            val results = mutableListOf<MatchDisplayInfo>()
            val w = screen.width
            val h = screen.height

            // 1. Draw ClickScript 中 step4 的第一次搜索区域（全屏）
            results.add(MatchDisplayInfo(
                -1,
                org.opencv.core.Rect(0, 0, w, h),
                0.0, "step4搜", Color.parseColor("#FF44AA00")
            ))

            // 2. Draw ClickScript 中 step1 的第一次搜索区域（右下角 x>2/3W, y>2/3H）
            results.add(MatchDisplayInfo(
                -1,
                org.opencv.core.Rect(w * 2 / 3, h * 2 / 3, w - w * 2 / 3, h - h * 2 / 3),
                0.0, "step1搜", Color.parseColor("#FFFF8800")
            ))

            // 3. Draw ClickScript 中 refer 的第一次搜索区域（左上角 x<1/2W, y<1/4H）
            results.add(MatchDisplayInfo(
                -1,
                org.opencv.core.Rect(0, 0, w / 2, h / 4),
                0.0, "refer搜", Color.parseColor("#664488FF")
            ))

            // 4. Search step4 template
            val tmpl = loadTemplate(context, "step4.jpg") ?: return results

            val matches = ImageMatcher.matchTemplateMulti(
                screenBitmap = screen,
                templateBitmap = tmpl,
                threshold = TestActivity.HIGH_THRESHOLD,
                maxResults = 20,
                searchArea = null,
                maskBitmap = null
            )
            val best = matches.maxByOrNull { it.confidence }
            if (best != null) {
                results.add(MatchDisplayInfo(1, best.rect, best.confidence, "step4", Color.parseColor("#FF44AA00")))
                Log.i(TestActivity.TAG, "  step4 OK conf=${"%.1f".format(best.confidence*100)}% center=(${best.center.x},${best.center.y}) rect=(${best.rect.x},${best.rect.y},${best.rect.width}x${best.rect.height})")
            } else {
                Log.i(TestActivity.TAG, "  step4 not found")
            }

            tmpl.recycle()
            return results
        }

        // ─── Template loading helpers ─────────────────────────────────

        private fun loadTemplate(context: android.content.Context, name: String): Bitmap? {
            return try {
                val stream = context.assets.open("${ClickScript.TEMPLATE_DIR}/$name")
                val bmp = BitmapFactory.decodeStream(stream)
                stream.close()
                bmp
            } catch (e: Exception) { null }
        }

        private fun loadMask(context: android.content.Context, name: String): Bitmap? {
            return try {
                val stream = context.assets.open("${ClickScript.TEMPLATE_DIR}/$name")
                val bmp = BitmapFactory.decodeStream(stream)
                stream.close()
                bmp
            } catch (e: Exception) { null }
        }
    }
}

// ─── Draw data ────────────────────────────────────────────────────────────

data class MatchDisplayInfo(
    val matchId: Int,           // -1 = search region border
    val rect: org.opencv.core.Rect,
    val confidence: Double,     // 0.0 = search region border
    val label: String,
    val color: Int
)

// ─── Overlay Drawable — dashed borders for search regions, solid for matches ──

class MatchOverlayDrawable(
    private val baseBitmap: Bitmap,
    private val matches: List<MatchDisplayInfo>,
    private val page: TestActivity.TestPage
) : android.graphics.drawable.Drawable() {

    private val rectPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        isAntiAlias = true
    }
    private val dashedPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }
    private val fillPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 26f
        isAntiAlias = true
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val bgPaint = Paint().apply { style = Paint.Style.FILL }

    override fun draw(canvas: Canvas) {
        if (baseBitmap.width <= 0 || baseBitmap.height <= 0) return

        val viewW = bounds.width().toFloat()
        val viewH = bounds.height().toFloat()
        val scale = minOf(viewW / baseBitmap.width, viewH / baseBitmap.height)
        val offsetX = (viewW - baseBitmap.width * scale) / 2f
        val offsetY = (viewH - baseBitmap.height * scale) / 2f

        for (match in matches) {
            val left = offsetX + match.rect.x * scale
            val top = offsetY + match.rect.y * scale
            val right = left + match.rect.width * scale
            val bottom = top + match.rect.height * scale

            // Search region border: dashed with semi-transparent fill
            if (match.matchId == -1) {
                // Semi-transparent fill
                fillPaint.color = (match.color and 0x00FFFFFF) or 0x20000000
                canvas.drawRect(left, top, right, bottom, fillPaint)
                // Dashed border
                dashedPaint.color = match.color or 0xFF000000.toInt()
                canvas.drawRect(left, top, right, bottom, dashedPaint)
                // Label
                val tagW = textPaint.measureText(match.label) + 18f
                val tagH = textPaint.textSize + 14f
                bgPaint.color = match.color or 0xFF000000.toInt()
                canvas.drawRoundRect(left + 4f, top + 4f,
                    left + 4f + tagW, top + 4f + tagH, 8f, 8f, bgPaint)
                canvas.drawText(match.label, left + 12f, top + textPaint.textSize + 14f, textPaint)
            } else {
                // Match result: solid border with semi-transparent fill
                fillPaint.color = (match.color and 0x00FFFFFF) or 0x40000000
                canvas.drawRect(left, top, right, bottom, fillPaint)

                rectPaint.color = match.color or 0xFF000000.toInt()
                canvas.drawRect(left, top, right, bottom, rectPaint)

                val tag = "${match.label} ${"%.0f".format(match.confidence * 100)}%"
                val tagW = textPaint.measureText(tag) + 18f
                val tagH = textPaint.textSize + 14f

                bgPaint.color = match.color or 0xFF000000.toInt()
                canvas.drawRoundRect(left + 4f, top + 4f,
                    left + 4f + tagW, top + 4f + tagH, 8f, 8f, bgPaint)
                canvas.drawText(tag, left + 12f, top + textPaint.textSize + 14f, textPaint)
            }
        }

        // Top-left stats
        val titleText = "${page.pageTitle} | ${matches.size} matches"
        val pad = 16f
        val titleW = textPaint.measureText(titleText) + pad * 2
        val titleH = textPaint.textSize + pad * 2
        bgPaint.color = 0xCC1A1A2E.toInt()
        canvas.drawRoundRect(12f, 12f, 12f + titleW, 12f + titleH, 10f, 10f, bgPaint)
        canvas.drawText(titleText, 12f + pad, 12f + textPaint.textSize + pad * 0.3f, textPaint)

        // Top-right size
        val dimText = "${baseBitmap.width}x${baseBitmap.height}"
        val dimW = textPaint.measureText(dimText) + pad * 2
        canvas.drawRoundRect(viewW - dimW - 12f, 12f, viewW - 12f, 12f + titleH, 10f, 10f, bgPaint)
        canvas.drawText(dimText, viewW - dimW - 12f + pad, 12f + textPaint.textSize + pad * 0.3f, textPaint)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}
