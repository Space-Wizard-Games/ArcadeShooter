package com.example.helloworld

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.awaitFirstDown
import androidx.compose.ui.input.pointer.awaitPointerEvent
import androidx.compose.ui.input.pointer.awaitPointerEventScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    GameScreen()
                }
            }
        }
    }
}

private data class Player(val position: Offset)

private data class PlayerBullet(
    val position: Offset,
    val velocity: Offset,
    val radius: Float
)

private data class EnemyBullet(
    val position: Offset,
    val velocity: Offset,
    val radius: Float
)

private data class Enemy(
    val position: Offset,
    val seed: Float,
    val age: Float = 0f,
    val health: Int = 3,
    val timeToNextSeries: Float = randomRange(3f, 6f),
    val seriesShotsRemaining: Int = 0,
    val seriesDirection: Offset = Offset.Zero,
    val shotInterval: Float = 0.25f,
    val timeUntilNextShot: Float = 0f
)

private class GameState(
    private var width: Float,
    private var height: Float
) {
    var showReady by mutableStateOf(true)
        private set
    var running by mutableStateOf(false)
        private set

    var player by mutableStateOf(Player(Offset(width / 2f, height * 0.8f)))
        private set

    val playerBullets = mutableStateListOf<PlayerBullet>()
    val enemyBullets = mutableStateListOf<EnemyBullet>()
    val enemies = mutableStateListOf<Enemy>()

    var joystickDirection by mutableStateOf(Offset.Zero)
    var shootPressed by mutableStateOf(false)

    private var lastShotTime by mutableFloatStateOf(-100f)
    private var elapsedGameTime by mutableFloatStateOf(0f)
    var spawnGeneration by mutableStateOf(0)
        private set

    fun updateBounds(newWidth: Float, newHeight: Float) {
        width = newWidth
        height = newHeight
        if (!running) {
            resetPlayer()
        }
    }

    fun start() {
        showReady = false
        running = true
        resetPlayer()
        playerBullets.clear()
        enemyBullets.clear()
        enemies.clear()
        elapsedGameTime = 0f
        lastShotTime = -100f
        spawnGeneration++
    }

    fun restart() {
        showReady = true
        running = false
        playerBullets.clear()
        enemyBullets.clear()
        enemies.clear()
        resetPlayer()
    }

    private fun resetPlayer() {
        player = Player(Offset(width / 2f, height * 0.8f))
    }

    fun update(deltaSeconds: Float) {
        if (!running) return
        elapsedGameTime += deltaSeconds
        updatePlayer(deltaSeconds)
        updateEnemies(deltaSeconds)
        updateBullets(deltaSeconds)
        checkCollisions()
    }

    private fun updatePlayer(deltaSeconds: Float) {
        val speed = 260f
        val velocity = Offset(joystickDirection.x * speed, joystickDirection.y * speed)
        val nextPosition = player.position + velocity * deltaSeconds
        val clampedX = nextPosition.x.coerceIn(32f, width - 32f)
        val clampedY = nextPosition.y.coerceIn(32f, height - 32f)
        player = Player(Offset(clampedX, clampedY))

        if (shootPressed && elapsedGameTime - lastShotTime >= 1f) {
            playerBullets.add(
                PlayerBullet(
                    position = player.position + Offset(0f, -28f),
                    velocity = Offset(0f, -360f),
                    radius = 10f
                )
            )
            lastShotTime = elapsedGameTime
        }
    }

    private fun updateEnemies(deltaSeconds: Float) {
        var index = 0
        while (index < enemies.size) {
            val enemy = enemies[index]
            val age = enemy.age + deltaSeconds
            val horizontalNoise = organicNoise(age, enemy.seed)
            val driftX = horizontalNoise * 120f
            val driftY = 40f
            var nextPos = enemy.position + Offset(driftX * deltaSeconds, driftY * deltaSeconds)
            if (nextPos.x < 40f) nextPos = nextPos.copy(x = 40f)
            if (nextPos.x > width - 40f) nextPos = nextPos.copy(x = width - 40f)

            var timeToSeries = enemy.timeToNextSeries
            var shotsRemaining = enemy.seriesShotsRemaining
            var seriesDirection = enemy.seriesDirection
            var shotInterval = enemy.shotInterval
            var timeUntilNextShot = enemy.timeUntilNextShot
            val health = enemy.health

            if (shotsRemaining <= 0) {
                timeToSeries -= deltaSeconds
            }

            if (shotsRemaining <= 0 && timeToSeries <= 0f) {
                shotsRemaining = Random.nextInt(3, 11)
                timeToSeries = randomRange(3f, 6f)
                shotInterval = randomRange(0.15f, 0.3f)
                timeUntilNextShot = 0f

                val baseAngle = atan2(
                    player.position.y - nextPos.y,
                    player.position.x - nextPos.x
                )
                val arcOffset = randomRange(-45f, 45f) * (PI.toFloat() / 180f)
                val angle = baseAngle + arcOffset
                seriesDirection = Offset(cos(angle), sin(angle)).normalize()
            }

            if (shotsRemaining > 0) {
                timeUntilNextShot -= deltaSeconds
                if (timeUntilNextShot <= 0f) {
                    enemyBullets.add(
                        EnemyBullet(
                            position = nextPos,
                            velocity = seriesDirection * 140f,
                            radius = 8f
                        )
                    )
                    shotsRemaining -= 1
                    timeUntilNextShot = shotInterval
                }
            }

            if (nextPos.y > height + 60f) {
                enemies.removeAt(index)
                continue
            } else {
                enemies[index] = enemy.copy(
                    position = nextPos,
                    age = age,
                    timeToNextSeries = timeToSeries,
                    seriesShotsRemaining = shotsRemaining,
                    seriesDirection = seriesDirection,
                    shotInterval = shotInterval,
                    timeUntilNextShot = timeUntilNextShot
                )
            }
            index++
        }
    }

    private fun updateBullets(deltaSeconds: Float) {
        var index = 0
        while (index < playerBullets.size) {
            val bullet = playerBullets[index]
            val newPosition = bullet.position + bullet.velocity * deltaSeconds
            if (newPosition.y + bullet.radius < 0f) {
                playerBullets.removeAt(index)
            } else {
                playerBullets[index] = bullet.copy(position = newPosition)
                index++
            }
        }

        index = 0
        while (index < enemyBullets.size) {
            val bullet = enemyBullets[index]
            val newPosition = bullet.position + bullet.velocity * deltaSeconds
            val outside = newPosition.x < -bullet.radius ||
                newPosition.x > width + bullet.radius ||
                newPosition.y < -bullet.radius ||
                newPosition.y > height + bullet.radius
            if (outside) {
                enemyBullets.removeAt(index)
            } else {
                enemyBullets[index] = bullet.copy(position = newPosition)
                index++
            }
        }
    }

    private fun checkCollisions() {
        val playerRadius = 26f
        val enemyRadius = 34f

        val hitPlayer = enemyBullets.indexOfFirst { bullet ->
            distance(bullet.position, player.position) <= playerRadius + bullet.radius
        }
        if (hitPlayer >= 0) {
            restart()
            return
        }

        var bulletIndex = 0
        while (bulletIndex < playerBullets.size) {
            val bullet = playerBullets[bulletIndex]
            val enemyIndex = enemies.indexOfFirst { enemy ->
                distance(enemy.position, bullet.position) <= enemyRadius + bullet.radius
            }
            if (enemyIndex >= 0) {
                val enemy = enemies[enemyIndex]
                val remainingHealth = enemy.health - 1
                if (remainingHealth <= 0) {
                    enemies.removeAt(enemyIndex)
                } else {
                    enemies[enemyIndex] = enemy.copy(health = remainingHealth)
                }
                playerBullets.removeAt(bulletIndex)
            } else {
                bulletIndex++
            }
        }
    }

    fun spawnEnemy() {
        if (!running) return
        val x = randomRange(40f, width - 40f)
        enemies.add(
            Enemy(
                position = Offset(x, -40f),
                seed = Random.nextFloat() * 10_000f
            )
        )
    }
}

@Composable
private fun GameScreen() {
    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(Color(0xFF76A9FF))) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val gameState = remember(widthPx, heightPx) { GameState(widthPx, heightPx) }

        LaunchedEffect(widthPx, heightPx) {
            gameState.updateBounds(widthPx, heightPx)
        }

        LaunchedEffect(gameState.running) {
            if (gameState.running) {
                var lastTime = withFrameNanos { it }
                while (true) {
                    val frameTime = withFrameNanos { it }
                    val delta = (frameTime - lastTime) / 1_000_000_000f
                    lastTime = frameTime
                    if (delta > 0f) {
                        gameState.update(delta)
                    }
                }
            }
        }

        LaunchedEffect(gameState.spawnGeneration) {
            if (!gameState.running) return@LaunchedEffect
            repeat(3) {
                launch {
                    while (gameState.running) {
                        delay((randomRange(3f, 10f) * 1000).toLong())
                        if (!gameState.running) break
                        gameState.spawnEnemy()
                    }
                }
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(Color(0xFF76A9FF))

                gameState.playerBullets.forEach { bullet ->
                    drawCircle(
                        color = Color.Red,
                        radius = bullet.radius,
                        center = bullet.position
                    )
                }

                gameState.enemyBullets.forEach { bullet ->
                    drawCircle(
                        color = Color(0xFFFBE15C),
                        radius = bullet.radius,
                        center = bullet.position
                    )
                }

                gameState.enemies.forEach { enemy ->
                    val path = regularPolygonPath(enemy.position, 6, 34f)
                    drawPath(path, color = Color(0xFFF6C552))
                    drawPath(path, color = Color.Black, style = Stroke(width = 4f))
                }

                val triangle = trianglePath(gameState.player.position, 36f, 44f)
                drawPath(triangle, color = Color.Red)
                drawPath(triangle, color = Color.Black, style = Stroke(width = 4f))
            }

            if (gameState.showReady) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                awaitFirstDown()
                                gameState.start()
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "READY!",
                        color = Color.White,
                        fontSize = 64.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Joystick(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(24.dp),
                onDirectionChange = { dir -> gameState.joystickDirection = dir }
            )

            ShootButton(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(24.dp),
                onShootStateChanged = { pressed -> gameState.shootPressed = pressed }
            )
        }
    }
}

@Composable
private fun Joystick(
    modifier: Modifier = Modifier,
    radius: Dp = 120.dp,
    handleRadius: Dp = 48.dp,
    onDirectionChange: (Offset) -> Unit
) {
    val density = LocalDensity.current
    val radiusPx = with(density) { radius.toPx() }
    var handlePosition by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier
            .size(radius * 2)
            .clip(CircleShape)
            .background(Color(0x33555555))
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitFirstDown()
                        onDirectionChange(Offset.Zero)
                        handlePosition = Offset.Zero
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (change.pressed) {
                                val rawOffset = change.position - Offset(radiusPx, radiusPx)
                                val limited = limitVector(rawOffset, radiusPx)
                                handlePosition = limited
                                onDirectionChange((limited / radiusPx).normalize())
                                change.consume()
                            } else {
                                handlePosition = Offset.Zero
                                onDirectionChange(Offset.Zero)
                                break
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(handlePosition.x.roundToInt(), handlePosition.y.roundToInt())
                }
                .size(handleRadius * 2)
                .clip(CircleShape)
                .background(Color(0x88555555))
        )
    }
}

@Composable
private fun ShootButton(
    modifier: Modifier = Modifier,
    onShootStateChanged: (Boolean) -> Unit
) {
    Box(
        modifier = modifier
            .size(120.dp)
            .clip(CircleShape)
            .background(Color(0x99FFFFFF))
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitFirstDown()
                        onShootStateChanged(true)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull()
                            if (change == null || !change.pressed) {
                                onShootStateChanged(false)
                                break
                            }
                            change.consume()
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(text = "FIRE", fontWeight = FontWeight.Bold, color = Color.Red)
    }
}

private fun trianglePath(center: Offset, width: Float, height: Float): Path {
    val halfWidth = width / 2f
    return Path().apply {
        moveTo(center.x, center.y - height / 2f)
        lineTo(center.x - halfWidth, center.y + height / 2f)
        lineTo(center.x + halfWidth, center.y + height / 2f)
        close()
    }
}

private fun regularPolygonPath(center: Offset, sides: Int, radius: Float): Path {
    val path = Path()
    val angleStep = (2 * PI / sides).toFloat()
    var angle = -PI.toFloat() / 2f
    path.moveTo(
        center.x + radius * cos(angle),
        center.y + radius * sin(angle)
    )
    for (i in 1 until sides) {
        angle += angleStep
        path.lineTo(
            center.x + radius * cos(angle),
            center.y + radius * sin(angle)
        )
    }
    path.close()
    return path
}

private fun organicNoise(time: Float, seed: Float): Float {
    var total = 0f
    var amplitude = 1f
    var frequency = 0.35f
    repeat(4) { octave ->
        val t = time * frequency + seed * 0.0001f * (octave + 1)
        total += amplitude * sin(t * 2f * PI.toFloat())
        total += amplitude * cos((t + 0.5f) * 2f * PI.toFloat())
        amplitude *= 0.5f
        frequency *= 1.8f
    }
    return (total / 8f).coerceIn(-1f, 1f)
}

private fun randomRange(start: Float, end: Float): Float =
    Random.nextFloat() * (end - start) + start

private fun Offset.normalize(): Offset {
    val length = sqrt(x * x + y * y)
    return if (length == 0f) Offset.Zero else Offset(x / length, y / length)
}

private operator fun Offset.times(value: Float): Offset = Offset(x * value, y * value)
private operator fun Offset.plus(other: Offset): Offset = Offset(x + other.x, y + other.y)
private operator fun Offset.div(value: Float): Offset = Offset(x / value, y / value)

private fun limitVector(offset: Offset, limit: Float): Offset {
    val length = sqrt(offset.x * offset.x + offset.y * offset.y)
    return if (length <= limit || length == 0f) offset else offset * (limit / length)
}

private fun distance(a: Offset, b: Offset): Float {
    val dx = a.x - b.x
    val dy = a.y - b.y
    return sqrt(dx * dx + dy * dy)
}
