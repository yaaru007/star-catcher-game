package com.example.starcatcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

public class GameView extends SurfaceView implements Runnable {

    private static final String PREFS_NAME = "StarCatcherPrefs";
    private static final String KEY_HIGH_SCORE = "high_score";

    private static final int STATE_START = 0;
    private static final int STATE_PLAYING = 1;
    private static final int STATE_GAME_OVER = 2;

    private Thread gameThread;
    private volatile boolean isRunning;
    private final SurfaceHolder holder;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random random = new Random();
    private final SharedPreferences prefs;

    private int screenW = 1080;
    private int screenH = 1920;
    private int gameState = STATE_START;

    private float paddleX;
    private float paddleY;
    private float paddleWidth;
    private float paddleHeight;

    private int score = 0;
    private int highScore = 0;
    private int lives = 3;
    private int level = 1;

    private long lastSpawnTime = 0;
    private final List<FallingItem> items = new ArrayList<>();
    private final List<Particle> particles = new ArrayList<>();
    private final List<BgStar> bgStars = new ArrayList<>();
    private LinearGradient bgGradient;

    private static class FallingItem {
        float x;
        float y;
        float radius;
        float speed;
        int type; // 0 = normal star, 1 = golden star (+3), 2 = bomb (-1 life)
        float angle;
        float rotSpeed;
    }

    private static class Particle {
        float x;
        float y;
        float vx;
        float vy;
        float alpha;
        int color;
        float radius;
    }

    private static class BgStar {
        float x;
        float y;
        float radius;
        int alpha;
    }

    public GameView(Context context) {
        super(context);
        holder = getHolder();
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        highScore = prefs.getInt(KEY_HIGH_SCORE, 0);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0) {
            screenW = w;
            screenH = h;
            paddleWidth = screenW * 0.24f;
            paddleHeight = Math.max(24f, screenH * 0.018f);
            paddleX = screenW / 2f;
            paddleY = screenH - screenH * 0.12f;

            bgGradient = new LinearGradient(
                    0, 0, 0, screenH,
                    Color.rgb(10, 15, 36),
                    Color.rgb(24, 34, 72),
                    Shader.TileMode.CLAMP
            );

            bgStars.clear();
            for (int i = 0; i < 45; i++) {
                BgStar s = new BgStar();
                s.x = random.nextFloat() * screenW;
                s.y = random.nextFloat() * screenH;
                s.radius = 1.5f + random.nextFloat() * 3.0f;
                s.alpha = 80 + random.nextInt(150);
                bgStars.add(s);
            }
        }
    }

    public void resume() {
        isRunning = true;
        gameThread = new Thread(this);
        gameThread.start();
    }

    public void pause() {
        isRunning = false;
        if (gameThread != null) {
            try {
                gameThread.join();
            } catch (InterruptedException ignored) {
            }
            gameThread = null;
        }
    }

    @Override
    public void run() {
        while (isRunning) {
            long frameStart = System.currentTimeMillis();

            if (holder.getSurface().isValid()) {
                update();
                Canvas canvas = holder.lockCanvas();
                if (canvas != null) {
                    try {
                        drawGame(canvas);
                    } finally {
                        holder.unlockCanvasAndPost(canvas);
                    }
                }
            }

            long elapsed = System.currentTimeMillis() - frameStart;
            long sleepTime = 16 - elapsed;
            if (sleepTime > 0) {
                try {
                    Thread.sleep(sleepTime);
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    private void resetGame() {
        score = 0;
        lives = 3;
        level = 1;
        items.clear();
        particles.clear();
        paddleX = screenW / 2f;
        lastSpawnTime = System.currentTimeMillis();
        gameState = STATE_PLAYING;
    }

    private void update() {
        if (gameState != STATE_PLAYING) {
            updateParticles();
            return;
        }

        level = 1 + (score / 15);
        long now = System.currentTimeMillis();
        long spawnInterval = Math.max(320L, 850L - (level * 45L));

        if (now - lastSpawnTime >= spawnInterval) {
            spawnItem();
            lastSpawnTime = now;
        }

        float paddleLeft = paddleX - paddleWidth / 2f;
        float paddleRight = paddleX + paddleWidth / 2f;
        float paddleTop = paddleY - paddleHeight / 2f;
        float paddleBottom = paddleY + paddleHeight / 2f;

        Iterator<FallingItem> it = items.iterator();
        while (it.hasNext()) {
            FallingItem item = it.next();
            item.y += item.speed;
            item.angle += item.rotSpeed;

            boolean hitPaddle = item.x + item.radius >= paddleLeft
                    && item.x - item.radius <= paddleRight
                    && item.y + item.radius >= paddleTop
                    && item.y - item.radius <= paddleBottom;

            if (hitPaddle) {
                if (item.type == 2) {
                    lives--;
                    spawnBurst(item.x, item.y, Color.rgb(239, 68, 68));
                    if (lives <= 0) {
                        triggerGameOver();
                    }
                } else {
                    int pts = (item.type == 1) ? 3 : 1;
                    score += pts;
                    int burstColor = (item.type == 1)
                            ? Color.rgb(250, 204, 21)
                            : Color.rgb(56, 189, 248);
                    spawnBurst(item.x, item.y, burstColor);
                }
                it.remove();
            } else if (item.y - item.radius > screenH) {
                if (item.type != 2) {
                    lives--;
                    if (lives <= 0) {
                        triggerGameOver();
                    }
                }
                it.remove();
            }
        }

        updateParticles();
    }

    private void triggerGameOver() {
        gameState = STATE_GAME_OVER;
        if (score > highScore) {
            highScore = score;
            prefs.edit().putInt(KEY_HIGH_SCORE, highScore).apply();
        }
    }

    private void spawnItem() {
        FallingItem item = new FallingItem();
        float margin = screenW * 0.08f;
        item.x = margin + random.nextFloat() * Math.max(1f, screenW - 2 * margin);
        item.radius = screenW * 0.042f;
        item.y = -item.radius;

        float baseSpeed = (screenH * 0.0055f) + (level * (screenH * 0.0007f));
        item.speed = baseSpeed + random.nextFloat() * (screenH * 0.0025f);
        item.angle = random.nextFloat() * 360f;
        item.rotSpeed = (random.nextFloat() - 0.5f) * 6f;

        int roll = random.nextInt(100);
        if (roll < 20) {
            item.type = 2; // Bomb
        } else if (roll < 35) {
            item.type = 1; // Golden star
        } else {
            item.type = 0; // Cyan star
        }
        items.add(item);
    }

    private void spawnBurst(float x, float y, int color) {
        for (int i = 0; i < 12; i++) {
            Particle p = new Particle();
            p.x = x;
            p.y = y;
            double ang = random.nextDouble() * Math.PI * 2;
            float spd = 4f + random.nextFloat() * 10f;
            p.vx = (float) (Math.cos(ang) * spd);
            p.vy = (float) (Math.sin(ang) * spd);
            p.alpha = 255f;
            p.color = color;
            p.radius = 4f + random.nextFloat() * 5f;
            particles.add(p);
        }
    }

    private void updateParticles() {
        Iterator<Particle> it = particles.iterator();
        while (it.hasNext()) {
            Particle p = it.next();
            p.x += p.vx;
            p.y += p.vy;
            p.alpha -= 14f;
            if (p.alpha <= 0) {
                it.remove();
            }
        }
    }

    private void drawGame(Canvas canvas) {
        if (bgGradient != null) {
            paint.setShader(bgGradient);
            canvas.drawRect(0, 0, screenW, screenH, paint);
            paint.setShader(null);
        } else {
            canvas.drawColor(Color.rgb(15, 23, 42));
        }

        // Background stars
        paint.setColor(Color.WHITE);
        for (BgStar s : bgStars) {
            paint.setAlpha(s.alpha);
            canvas.drawCircle(s.x, s.y, s.radius, paint);
        }
        paint.setAlpha(255);

        // Falling items
        for (FallingItem item : items) {
            if (item.type == 2) {
                drawBomb(canvas, item);
            } else {
                int color = (item.type == 1)
                        ? Color.rgb(250, 204, 21)
                        : Color.rgb(56, 189, 248);
                drawStar(canvas, item.x, item.y, item.radius, item.angle, color);
            }
        }

        // Particles
        for (Particle p : particles) {
            paint.setColor(p.color);
            paint.setAlpha((int) Math.max(0, p.alpha));
            canvas.drawCircle(p.x, p.y, p.radius, paint);
        }
        paint.setAlpha(255);

        // Paddle
        RectF paddleRect = new RectF(
                paddleX - paddleWidth / 2f,
                paddleY - paddleHeight / 2f,
                paddleX + paddleWidth / 2f,
                paddleY + paddleHeight / 2f
        );
        paint.setColor(Color.rgb(34, 211, 238));
        canvas.drawRoundRect(paddleRect, paddleHeight / 2f, paddleHeight / 2f, paint);

        // HUD
        drawHud(canvas);

        // Overlays
        if (gameState == STATE_START) {
            drawOverlay(canvas, "STAR CATCHER",
                    "Slide finger to catch stars!\nAvoid red bombs.",
                    "TAP TO PLAY");
        } else if (gameState == STATE_GAME_OVER) {
            drawOverlay(canvas, "GAME OVER",
                    "Score: " + score + "   Best: " + highScore,
                    "TAP TO RETRY");
        }
    }

    private void drawHud(Canvas canvas) {
        float textSize = screenW * 0.048f;
        paint.setTextSize(textSize);
        paint.setColor(Color.WHITE);
        paint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("Score: " + score, screenW * 0.05f, screenH * 0.06f, paint);

        paint.setTextAlign(Paint.Align.CENTER);
        paint.setColor(Color.rgb(250, 204, 21));
        canvas.drawText("Lv " + level, screenW * 0.5f, screenH * 0.06f, paint);

        paint.setTextAlign(Paint.Align.RIGHT);
        paint.setColor(Color.rgb(248, 113, 113));
        StringBuilder hearts = new StringBuilder();
        for (int i = 0; i < Math.max(0, lives); i++) {
            hearts.append("♥ ");
        }
        canvas.drawText(hearts.toString().trim(), screenW * 0.95f, screenH * 0.06f, paint);
    }

    private void drawOverlay(Canvas canvas, String title, String subtitle, String buttonText) {
        paint.setColor(Color.argb(170, 5, 10, 25));
        canvas.drawRect(0, 0, screenW, screenH, paint);

        paint.setTextAlign(Paint.Align.CENTER);
        paint.setColor(Color.rgb(250, 204, 21));
        paint.setTextSize(screenW * 0.095f);
        paint.setFakeBoldText(true);
        canvas.drawText(title, screenW / 2f, screenH * 0.36f, paint);

        paint.setFakeBoldText(false);
        paint.setColor(Color.WHITE);
        paint.setTextSize(screenW * 0.045f);
        String[] lines = subtitle.split("\n");
        float y = screenH * 0.44f;
        for (String line : lines) {
            canvas.drawText(line, screenW / 2f, y, paint);
            y += screenW * 0.065f;
        }

        float btnW = screenW * 0.56f;
        float btnH = screenH * 0.075f;
        float btnTop = screenH * 0.58f;
        RectF btnRect = new RectF(
                screenW / 2f - btnW / 2f,
                btnTop,
                screenW / 2f + btnW / 2f,
                btnTop + btnH
        );
        paint.setColor(Color.rgb(14, 165, 233));
        canvas.drawRoundRect(btnRect, btnH / 2f, btnH / 2f, paint);

        paint.setColor(Color.WHITE);
        paint.setFakeBoldText(true);
        paint.setTextSize(screenW * 0.052f);
        canvas.drawText(buttonText, screenW / 2f, btnTop + btnH * 0.64f, paint);
        paint.setFakeBoldText(false);
    }

    private void drawStar(Canvas canvas, float cx, float cy, float r, float angleDeg, int color) {
        canvas.save();
        canvas.rotate(angleDeg, cx, cy);
        Path path = new Path();
        int points = 5;
        float innerR = r * 0.45f;
        for (int i = 0; i < points * 2; i++) {
            float rad = (i % 2 == 0) ? r : innerR;
            double theta = Math.toRadians(i * 36.0 - 90.0);
            float px = cx + (float) (Math.cos(theta) * rad);
            float py = cy + (float) (Math.sin(theta) * rad);
            if (i == 0) {
                path.moveTo(px, py);
            } else {
                path.lineTo(px, py);
            }
        }
        path.close();
        paint.setColor(color);
        canvas.drawPath(path, paint);
        canvas.restore();
    }

    private void drawBomb(Canvas canvas, FallingItem item) {
        paint.setColor(Color.rgb(239, 68, 68));
        canvas.drawCircle(item.x, item.y, item.radius * 0.85f, paint);

        paint.setColor(Color.rgb(127, 29, 29));
        canvas.drawCircle(item.x, item.y, item.radius * 0.55f, paint);

        paint.setColor(Color.WHITE);
        paint.setStrokeWidth(5f);
        float d = item.radius * 0.35f;
        canvas.drawLine(item.x - d, item.y - d, item.x + d, item.y + d, paint);
        canvas.drawLine(item.x + d, item.y - d, item.x - d, item.y + d, paint);
        paint.setStrokeWidth(0f);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (gameState == STATE_START || gameState == STATE_GAME_OVER) {
            if (action == MotionEvent.ACTION_DOWN) {
                resetGame();
            }
            return true;
        }

        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
            float x = event.getX();
            float halfW = paddleWidth / 2f;
            paddleX = Math.max(halfW, Math.min(screenW - halfW, x));
        }
        return true;
    }
}
