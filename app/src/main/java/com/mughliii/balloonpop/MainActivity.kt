package com.mughliii.balloonpop

import android.content.Context
import android.graphics.*
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(BalloonGameView(this))
    }
}

enum class GameMode(val title: String) {
    ENDLESS("Endless Pop"),
    COLOR_HUNT("Color Hunt"),
    NUMBERS("123 Pop")
}

data class BalloonColor(val name: String, val mainColor: Int, val lightColor: Int)

data class Balloon(
    var x: Float,
    var y: Float,
    var radius: Float,
    var speedY: Float,
    var wobbleOffset: Float,
    val colorInfo: BalloonColor,
    val isGoldenStar: Boolean = false,
    val numberLabel: Int = 0
)

data class Particle(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    var radius: Float,
    val color: Int,
    var alpha: Int = 255
)

data class FloatingText(
    var x: Float,
    var y: Float,
    val text: String,
    val color: Int,
    var alpha: Int = 255
)

data class Cloud(
    var x: Float,
    var y: Float,
    val scale: Float,
    val speed: Float
)

class BalloonGameView(context: Context) : View(context) {

    private val prefs = context.getSharedPreferences("balloon_pop_prefs", Context.MODE_PRIVATE)
    private var highScore = prefs.getInt("high_score", 0)
    private var score = 0
    private var soundOn = true
    private var currentMode = GameMode.ENDLESS
    private var targetNumber = 1

    private val palette = listOf(
        BalloonColor("RED", Color.parseColor("#FF4B5C"), Color.parseColor("#FF8E99")),
        BalloonColor("BLUE", Color.parseColor("#2EC4B6"), Color.parseColor("#80E5DC")),
        BalloonColor("YELLOW", Color.parseColor("#FFBE0B"), Color.parseColor("#FFE07D")),
        BalloonColor("PURPLE", Color.parseColor("#9B5DE5"), Color.parseColor("#C9A0FF")),
        BalloonColor("GREEN", Color.parseColor("#06D6A0"), Color.parseColor("#7CF2D2")),
        BalloonColor("ORANGE", Color.parseColor("#FB5607"), Color.parseColor("#FF9B6A"))
    )

    private var targetColor: BalloonColor = palette[0]

    private val balloons = mutableListOf<Balloon>()
    private val particles = mutableListOf<Particle>()
    private val floatingTexts = mutableListOf<FloatingText>()
    private val clouds = mutableListOf<Cloud>()

    private var lastFrameTime = System.nanoTime()
    private var spawnTimer = 0f
    private var totalTime = 0f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private var modeButtonRect = RectF()
    private var soundButtonRect = RectF()

    private val toneGen: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_MUSIC, 85)
    } catch (_: Exception) {
        null
    }

    private val popNotes = listOf(
        ToneGenerator.TONE_DTMF_1,
        ToneGenerator.TONE_DTMF_2,
        ToneGenerator.TONE_DTMF_3,
        ToneGenerator.TONE_DTMF_5,
        ToneGenerator.TONE_DTMF_6,
        ToneGenerator.TONE_DTMF_8,
        ToneGenerator.TONE_DTMF_9
    )

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (clouds.isEmpty() && w > 0) {
            for (i in 0..4) {
                clouds.add(
                    Cloud(
                        x = Random.nextFloat() * w,
                        y = h * (0.12f + i * 0.14f),
                        scale = 0.7f + Random.nextFloat() * 0.6f,
                        speed = 18f + Random.nextFloat() * 20f
                    )
                )
            }
        }
        val btnTop = 135f
        val btnHeight = 82f
        modeButtonRect.set(36f, btnTop, w * 0.64f, btnTop + btnHeight)
        soundButtonRect.set(w * 0.68f, btnTop, w - 36f, btnTop + btnHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return

        val now = System.nanoTime()
        val dt = ((now - lastFrameTime) / 1_000_000_000f).coerceAtMost(0.05f)
        lastFrameTime = now
        totalTime += dt

        drawBackground(canvas, w, h, dt)
        updateAndDrawBalloons(canvas, w, h, dt)
        updateAndDrawParticles(canvas, dt)
        updateAndDrawFloatingTexts(canvas, dt)
        drawHUD(canvas, w)

        postInvalidateOnAnimation()
    }

    private fun drawBackground(canvas: Canvas, w: Float, h: Float, dt: Float) {
        val skyShader = LinearGradient(
            0f, 0f, 0f, h,
            Color.parseColor("#6EC6FF"),
            Color.parseColor("#E3F6FF"),
            Shader.TileMode.CLAMP
        )
        paint.shader = skyShader
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        // Sun in top right
        paint.color = Color.parseColor("#FFD166")
        canvas.drawCircle(w - 90f, 90f, 55f, paint)

        // Floating fluffy clouds
        paint.color = Color.argb(210, 255, 255, 255)
        for (cloud in clouds) {
            cloud.x += cloud.speed * dt
            if (cloud.x - 140f * cloud.scale > w) {
                cloud.x = -140f * cloud.scale
            }
            val r = 42f * cloud.scale
            canvas.drawCircle(cloud.x, cloud.y, r, paint)
            canvas.drawCircle(cloud.x + r * 0.9f, cloud.y - r * 0.25f, r * 1.15f, paint)
            canvas.drawCircle(cloud.x + r * 1.8f, cloud.y, r * 0.95f, paint)
        }
    }

    private fun updateAndDrawBalloons(canvas: Canvas, w: Float, h: Float, dt: Float) {
        spawnTimer -= dt
        val spawnInterval = if (currentMode == GameMode.ENDLESS) 0.55f else 0.65f
        if (spawnTimer <= 0f && balloons.size < 14) {
            spawnBalloon(w, h)
            spawnTimer = spawnInterval
        }

        val iterator = balloons.iterator()
        while (iterator.hasNext()) {
            val b = iterator.next()
            b.y -= b.speedY * dt
            val swayX = sin((totalTime * 2.5f + b.wobbleOffset).toDouble()).toFloat() * 22f
            val drawX = (b.x + swayX).coerceIn(b.radius + 16f, w - b.radius - 16f)

            if (b.y + b.radius * 1.6f < 0f) {
                iterator.remove()
                continue
            }

            drawSingleBalloon(canvas, drawX, b.y, b)
        }
    }

    private fun drawSingleBalloon(canvas: Canvas, cx: Float, cy: Float, b: Balloon) {
        val rx = b.radius
        val ry = b.radius * 1.18f

        // Balloon string
        paint.color = Color.argb(160, 90, 90, 90)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f
        val path = Path()
        path.moveTo(cx, cy + ry)
        path.quadTo(cx + 14f, cy + ry + 35f, cx - 6f, cy + ry + 75f)
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL

        if (b.isGoldenStar) {
            drawStar(canvas, cx, cy, b.radius * 1.15f, Color.parseColor("#FFD700"))
            textPaint.color = Color.parseColor("#8A4B00")
            textPaint.textSize = b.radius * 0.55f
            canvas.drawText("+5", cx, cy + b.radius * 0.2f, textPaint)
            return
        }

        // Balloon body
        paint.color = b.colorInfo.mainColor
        val oval = RectF(cx - rx, cy - ry, cx + rx, cy + ry)
        canvas.drawOval(oval, paint)

        // Knot at bottom
        val knot = Path().apply {
            moveTo(cx - 12f, cy + ry + 10f)
            lineTo(cx + 12f, cy + ry + 10f)
            lineTo(cx, cy + ry - 2f)
            close()
        }
        canvas.drawPath(knot, paint)

        // Glossy shine highlight
        paint.color = b.colorInfo.lightColor
        val shine = RectF(cx - rx * 0.55f, cy - ry * 0.65f, cx - rx * 0.15f, cy - ry * 0.15f)
        canvas.drawOval(shine, paint)

        // Cute eyes or number label
        if (currentMode == GameMode.NUMBERS && b.numberLabel > 0) {
            textPaint.color = Color.WHITE
            textPaint.textSize = b.radius * 0.85f
            canvas.drawText(b.numberLabel.toString(), cx, cy + b.radius * 0.3f, textPaint)
        } else {
            // Cute smiley face
            paint.color = Color.WHITE
            canvas.drawCircle(cx - rx * 0.26f, cy - ry * 0.08f, rx * 0.13f, paint)
            canvas.drawCircle(cx + rx * 0.26f, cy - ry * 0.08f, rx * 0.13f, paint)

            paint.color = Color.parseColor("#2B2D42")
            canvas.drawCircle(cx - rx * 0.24f, cy - ry * 0.08f, rx * 0.06f, paint)
            canvas.drawCircle(cx + rx * 0.28f, cy - ry * 0.08f, rx * 0.06f, paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 5f
            paint.strokeCap = Paint.Cap.ROUND
            val smile = RectF(cx - rx * 0.22f, cy - ry * 0.05f, cx + rx * 0.22f, cy + ry * 0.26f)
            canvas.drawArc(smile, 15f, 150f, false, paint)
            paint.style = Paint.Style.FILL
        }
    }

    private fun drawStar(canvas: Canvas, cx: Float, cy: Float, outerR: Float, color: Int) {
        val innerR = outerR * 0.48f
        val path = Path()
        for (i in 0 until 10) {
            val angle = Math.toRadians((i * 36 - 90).toDouble())
            val r = if (i % 2 == 0) outerR else innerR
            val x = (cx + cos(angle) * r).toFloat()
            val y = (cy + sin(angle) * r).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        paint.color = color
        canvas.drawPath(path, paint)
    }

    private fun spawnBalloon(w: Float, h: Float) {
        val radius = (w * 0.105f).coerceIn(72f, 115f)
        val x = Random.nextFloat() * (w - radius * 2.4f) + radius * 1.2f
        val speed = h * (0.16f + Random.nextFloat() * 0.12f)

        val chosenColor = when {
            currentMode == GameMode.COLOR_HUNT && Random.nextFloat() < 0.45f -> targetColor
            else -> palette.random()
        }

        val isStar = currentMode == GameMode.ENDLESS && Random.nextFloat() < 0.14f
        val num = if (currentMode == GameMode.NUMBERS) {
            if (Random.nextFloat() < 0.5f) targetNumber else Random.nextInt(1, 11)
        } else 0

        balloons.add(
            Balloon(
                x = x,
                y = h + radius * 1.4f,
                radius = radius,
                speedY = speed,
                wobbleOffset = Random.nextFloat() * 6.28f,
                colorInfo = chosenColor,
                isGoldenStar = isStar,
                numberLabel = num
            )
        )
    }

    private fun updateAndDrawParticles(canvas: Canvas, dt: Float) {
        val it = particles.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.vy += 450f * dt
            p.alpha = (p.alpha - (420f * dt).toInt()).coerceAtLeast(0)
            if (p.alpha == 0) {
                it.remove()
                continue
            }
            paint.color = p.color
            paint.alpha = p.alpha
            canvas.drawCircle(p.x, p.y, p.radius, paint)
            paint.alpha = 255
        }
    }

    private fun updateAndDrawFloatingTexts(canvas: Canvas, dt: Float) {
        val it = floatingTexts.iterator()
        while (it.hasNext()) {
            val ft = it.next()
            ft.y -= 110f * dt
            ft.alpha = (ft.alpha - (260f * dt).toInt()).coerceAtLeast(0)
            if (ft.alpha == 0) {
                it.remove()
                continue
            }
            textPaint.color = ft.color
            textPaint.alpha = ft.alpha
            textPaint.textSize = 54f
            canvas.drawText(ft.text, ft.x, ft.y, textPaint)
            textPaint.alpha = 255
        }
    }

    private fun drawHUD(canvas: Canvas, w: Float) {
        // Top score bar
        paint.color = Color.argb(215, 255, 255, 255)
        canvas.drawRoundRect(RectF(28f, 28f, w - 28f, 118f), 28f, 28f, paint)

        textPaint.color = Color.parseColor("#1D3557")
        textPaint.textSize = 44f
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("Score: $score", 56f, 88f, textPaint)

        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.color = Color.parseColor("#E63946")
        canvas.drawText("Best: $highScore", w - 56f, 88f, textPaint)

        // Mode switcher button
        paint.color = Color.parseColor("#FF9F1C")
        canvas.drawRoundRect(modeButtonRect, 24f, 24f, paint)
        textPaint.color = Color.WHITE
        textPaint.textSize = 36f
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(
            "Mode: ${currentMode.title}",
            modeButtonRect.centerX(),
            modeButtonRect.centerY() + 13f,
            textPaint
        )

        // Sound toggle button
        paint.color = if (soundOn) Color.parseColor("#2EC4B6") else Color.parseColor("#8D99AE")
        canvas.drawRoundRect(soundButtonRect, 24f, 24f, paint)
        canvas.drawText(
            if (soundOn) "Sound ON" else "Muted",
            soundButtonRect.centerX(),
            soundButtonRect.centerY() + 13f,
            textPaint
        )

        // Mission banner for Color Hunt or Numbers mode
        if (currentMode == GameMode.COLOR_HUNT) {
            paint.color = targetColor.mainColor
            canvas.drawRoundRect(RectF(w * 0.2f, 235f, w * 0.8f, 315f), 40f, 40f, paint)
            textPaint.color = Color.WHITE
            textPaint.textSize = 42f
            canvas.drawText("Pop ${targetColor.name}!", w * 0.5f, 290f, textPaint)
        } else if (currentMode == GameMode.NUMBERS) {
            paint.color = Color.parseColor("#7209B7")
            canvas.drawRoundRect(RectF(w * 0.2f, 235f, w * 0.8f, 315f), 40f, 40f, paint)
            textPaint.color = Color.WHITE
            textPaint.textSize = 42f
            canvas.drawText("Find Number: $targetNumber", w * 0.5f, 290f, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val tx = event.x
            val ty = event.y

            if (modeButtonRect.contains(tx, ty)) {
                switchMode()
                return true
            }
            if (soundButtonRect.contains(tx, ty)) {
                soundOn = !soundOn
                playTone(ToneGenerator.TONE_PROP_BEEP, 80)
                return true
            }

            // Check balloons from front to back
            for (i in balloons.indices.reversed()) {
                val b = balloons[i]
                val swayX = sin((totalTime * 2.5f + b.wobbleOffset).toDouble()).toFloat() * 22f
                val cx = (b.x + swayX).coerceIn(b.radius + 16f, width - b.radius - 16f)
                val dist = hypot((tx - cx).toDouble(), (ty - b.y).toDouble()).toFloat()

                if (dist <= b.radius * 1.32f) {
                    popBalloon(i, cx, b.y)
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    private fun switchMode() {
        val modes = GameMode.values()
        currentMode = modes[(currentMode.ordinal + 1) % modes.size]
        balloons.clear()
        targetColor = palette.random()
        targetNumber = 1
        playTone(ToneGenerator.TONE_PROP_ACK, 120)
    }

    private fun popBalloon(index: Int, cx: Float, cy: Float) {
        val b = balloons.removeAt(index)
        var pointsEarned = 1
        var popupLabel = "+1"
        var popupColor = Color.parseColor("#1D3557")

        when {
            b.isGoldenStar -> {
                pointsEarned = 5
                popupLabel = "STAR +5!"
                popupColor = Color.parseColor("#FF9F1C")
                playTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 160)
            }
            currentMode == GameMode.COLOR_HUNT -> {
                if (b.colorInfo.name == targetColor.name) {
                    pointsEarned = 3
                    popupLabel = "GREAT! +3"
                    popupColor = Color.parseColor("#06D6A0")
                    targetColor = palette.random()
                    playTone(ToneGenerator.TONE_PROP_ACK, 130)
                } else {
                    pointsEarned = 0
                    popupLabel = "Try ${targetColor.name}"
                    popupColor = Color.parseColor("#E63946")
                    playTone(ToneGenerator.TONE_PROP_NACK, 110)
                }
            }
            currentMode == GameMode.NUMBERS -> {
                if (b.numberLabel == targetNumber) {
                    pointsEarned = 3
                    popupLabel = "$targetNumber! +3"
                    popupColor = Color.parseColor("#7209B7")
                    targetNumber = if (targetNumber >= 10) 1 else targetNumber + 1
                    playTone(ToneGenerator.TONE_PROP_ACK, 130)
                } else {
                    pointsEarned = 0
                    popupLabel = "Find $targetNumber"
                    popupColor = Color.parseColor("#E63946")
                    playTone(ToneGenerator.TONE_PROP_NACK, 110)
                }
            }
            else -> {
                playTone(popNotes.random(), 95)
            }
        }

        score += pointsEarned
        if (score > highScore) {
            highScore = score
            prefs.edit().putInt("high_score", highScore).apply()
        }

        // Spawn confetti burst
        val burstColor = if (b.isGoldenStar) Color.parseColor("#FFD700") else b.colorInfo.mainColor
        repeat(22) {
            val angle = Random.nextFloat() * 6.283f
            val speed = 140f + Random.nextFloat() * 340f
            particles.add(
                Particle(
                    x = cx,
                    y = cy,
                    vx = cos(angle) * speed,
                    vy = sin(angle) * speed - 120f,
                    radius = 8f + Random.nextFloat() * 10f,
                    color = if (it % 3 == 0) Color.WHITE else burstColor
                )
            )
        }

        floatingTexts.add(FloatingText(cx, cy - 20f, popupLabel, popupColor))
    }

    private fun playTone(tone: Int, durationMs: Int) {
        if (!soundOn) return
        try {
            toneGen?.startTone(tone, durationMs)
        } catch (_: Exception) {
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        try {
            toneGen?.release()
        } catch (_: Exception) {
        }
    }
}
