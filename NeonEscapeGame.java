import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.util.*;
import java.util.List;

/**
 * NEON ESCAPE - a single-file Java Swing cyberpunk endless runner.
 * Compile: javac NeonEscape.java
 * Run:     java NeonEscape
 *
 * Controls:
 *   SPACE / W / UP : jump (press twice for double jump)
 *   SHIFT / X      : dash
 *   DOWN / S       : slide
 *   P              : pause
 *   R              : restart after game over
 *   ESC            : return to menu
 */
public class NeonEscapeGame extends JPanel implements ActionListener {
    private static final int W = 1000, H = 600;
    private final javax.swing.Timer timer = new javax.swing.Timer(16, this);
    private final Random random = new Random(7);

    private enum Screen { MENU, PLAYING, PAUSED, GAME_OVER }
    private Screen screen = Screen.MENU;

    private Player player;
    private final ArrayList<Obstacle> obstacles = new ArrayList<>();
    private final ArrayList<Coin> coins = new ArrayList<>();
    private final ArrayList<Platform> platforms = new ArrayList<>();
    private final ArrayList<Particle> particles = new ArrayList<>();
    private final ArrayList<Star> stars = new ArrayList<>();

    private int distance, coinCount, bestScore;
    private double worldSpeed = 4.2, spawnTimer, coinTimer, platformTimer, shake;
    private boolean downHeld;
    private long lastTime = System.nanoTime();

    private final Color cyan = new Color(0, 245, 255);
    private final Color pink = new Color(255, 35, 180);
    private final Color purple = new Color(120, 70, 255);
    private final Font titleFont = new Font("SansSerif", Font.BOLD, 52);
    private final Font hudFont = new Font("Monospaced", Font.BOLD, 17);
    private final Font smallFont = new Font("Monospaced", Font.PLAIN, 13);

    public NeonEscapeGame() {
        setPreferredSize(new Dimension(W, H));
        setFocusable(true);
        setBackground(new Color(5, 7, 24));
        for (int i = 0; i < 90; i++) {
            stars.add(new Star(random.nextInt(W), random.nextInt(330),
                    0.25 + random.nextDouble() * 1.2, 1 + random.nextInt(2)));
        }
        installKeys();
        resetGame();
        timer.start();
    }

    private void installKeys() {
        bind("pressed SPACE", "jump1", () -> {
            if (screen == Screen.MENU) startGame();
            else if (screen == Screen.PLAYING) player.jump();
            else if (screen == Screen.GAME_OVER) startGame();
            else if (screen == Screen.PAUSED) screen = Screen.PLAYING;
        });
        bind("pressed W", "jump2", () -> { if (screen == Screen.PLAYING) player.jump(); });
        bind("pressed UP", "jump3", () -> { if (screen == Screen.PLAYING) player.jump(); });
        bind("pressed X", "dash1", () -> { if (screen == Screen.PLAYING) player.dash(); });
        bind("pressed SHIFT", "dash2", () -> { if (screen == Screen.PLAYING) player.dash(); });
        bind("pressed DOWN", "slideStart", () -> {
            downHeld = true;
            if (screen == Screen.PLAYING) player.sliding = true;
        });
        bind("released DOWN", "slideEnd", () -> { downHeld = false; if (player != null) player.sliding = false; });
        bind("pressed S", "slideStartS", () -> {
            downHeld = true;
            if (screen == Screen.PLAYING) player.sliding = true;
        });
        bind("released S", "slideEndS", () -> { downHeld = false; if (player != null) player.sliding = false; });
        bind("pressed P", "pause", () -> {
            if (screen == Screen.PLAYING) screen = Screen.PAUSED;
            else if (screen == Screen.PAUSED) screen = Screen.PLAYING;
        });
        bind("pressed R", "restart", () -> { if (screen == Screen.GAME_OVER) startGame(); });
        bind("pressed ESCAPE", "escape", () -> screen = Screen.MENU);
        bind("pressed ENTER", "enter", () -> {
            if (screen == Screen.MENU || screen == Screen.GAME_OVER) startGame();
            else if (screen == Screen.PAUSED) screen = Screen.PLAYING;
        });
    }

    private void bind(String stroke, String name, Runnable action) {
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(stroke), name);
        getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { action.run(); }
        });
    }

    private void resetGame() {
        player = new Player(155, 435);
        obstacles.clear(); coins.clear(); platforms.clear(); particles.clear();
        distance = 0; coinCount = 0; worldSpeed = 4.2;
        spawnTimer = 70; coinTimer = 45; platformTimer = 130; shake = 0;
        downHeld = false;
    }

    private void startGame() {
        resetGame();
        screen = Screen.PLAYING;
        lastTime = System.nanoTime();
    }

    @Override public void actionPerformed(ActionEvent e) {
        long now = System.nanoTime();
        double dt = Math.min(2.2, (now - lastTime) / 16_666_667.0);
        lastTime = now;
        if (screen == Screen.PLAYING) updateGame(dt);
        repaint();
    }

    private void updateGame(double dt) {
        distance += Math.max(1, (int)(worldSpeed * dt));
        worldSpeed = Math.min(9.0, 4.2 + distance / 5000.0);
        player.update(dt);

        spawnTimer -= dt;
        if (spawnTimer <= 0) {
            spawnObstacle();
            spawnTimer = Math.max(42, 90 - worldSpeed * 4) + random.nextInt(35);
        }
        coinTimer -= dt;
        if (coinTimer <= 0) {
            spawnCoinLine();
            coinTimer = 85 + random.nextInt(65);
        }
        platformTimer -= dt;
        if (platformTimer <= 0) {
            platforms.add(new Platform(W + 30, 355 + random.nextInt(65), 150 + random.nextInt(100)));
            platformTimer = 200 + random.nextInt(120);
        }

        Rectangle2D.Double pr = player.bounds();
        Iterator<Obstacle> oi = obstacles.iterator();
        while (oi.hasNext()) {
            Obstacle o = oi.next();
            o.x -= worldSpeed * dt;
            o.phase += 0.08 * dt;
            if (o.x + o.w < -30) oi.remove();
            else if (pr.intersects(o.bounds())) {
                if (o.type == Obstacle.LASER && player.dashTime > 0) continue;
                if (o.type == Obstacle.BARRIER && player.sliding) continue;
                hitPlayer();
                break;
            }
        }

        Iterator<Coin> ci = coins.iterator();
        while (ci.hasNext()) {
            Coin c = ci.next();
            c.x -= worldSpeed * dt;
            c.phase += 0.12 * dt;
            if (c.x < -30) ci.remove();
            else if (pr.intersects(new Rectangle2D.Double(c.x - 11, c.y - 11, 22, 22))) {
                coinCount++;
                burst(c.x, c.y, cyan, 9);
                ci.remove();
            }
        }

        Iterator<Platform> pi = platforms.iterator();
        while (pi.hasNext()) {
            Platform p = pi.next();
            p.x -= worldSpeed * dt;
            p.phase += 0.06 * dt;
            if (p.x + p.w < -20) pi.remove();
        }

        for (Particle p : particles) p.update(dt);
        particles.removeIf(p -> p.life <= 0);
        shake = Math.max(0, shake - 0.7 * dt);
    }

    private void spawnObstacle() {
        int type = random.nextInt(3);
        if (type == 0) obstacles.add(new Obstacle(W + 20, 457, 38, 83, Obstacle.BARRIER));
        else if (type == 1) obstacles.add(new Obstacle(W + 20, 420, 58, 120, Obstacle.LASER));
        else obstacles.add(new Obstacle(W + 20, 472, 45, 68, Obstacle.DRONE));
    }

    private void spawnCoinLine() {
        int y = 340 + random.nextInt(115);
        int count = 4 + random.nextInt(3);
        for (int i = 0; i < count; i++) coins.add(new Coin(W + 35 + i * 38, y - (i % 2) * 18));
    }

    private void hitPlayer() {
        burst(player.x + 20, player.y + 30, pink, 25);
        shake = 8;
        screen = Screen.GAME_OVER;
        bestScore = Math.max(bestScore, score());
    }

    private int score() { return distance / 5 + coinCount * 25; }

    private void burst(double x, double y, Color color, int count) {
        for (int i = 0; i < count; i++) {
            double a = random.nextDouble() * Math.PI * 2;
            double s = 1 + random.nextDouble() * 4;
            particles.add(new Particle(x, y, Math.cos(a) * s, Math.sin(a) * s, color));
        }
    }

    @Override protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        double sx = shake > 0 ? (random.nextDouble() - .5) * shake : 0;
        double sy = shake > 0 ? (random.nextDouble() - .5) * shake : 0;
        g2.translate(sx, sy);
        drawBackground(g2);
        drawPlatforms(g2);
        drawCoins(g2);
        drawObstacles(g2);
        if (screen != Screen.MENU) drawPlayer(g2);
        drawParticles(g2);
        if (screen == Screen.PLAYING || screen == Screen.PAUSED || screen == Screen.GAME_OVER) drawHud(g2);
        if (screen == Screen.MENU) drawMenu(g2);
        else if (screen == Screen.PAUSED) drawOverlay(g2, "SYSTEM PAUSED", "Press P or ENTER to resume");
        else if (screen == Screen.GAME_OVER) drawGameOver(g2);
        g2.dispose();
    }

    private void drawBackground(Graphics2D g) {
        GradientPaint sky = new GradientPaint(0, 0, new Color(5, 7, 25), 0, H, new Color(23, 8, 45));
        g.setPaint(sky); g.fillRect(0, 0, W, H);

        // Moon / planet glow
        glow(g, 780, 120, 95, new Color(130, 35, 220, 50));
        g.setColor(new Color(205, 180, 255, 190)); g.fillOval(748, 88, 64, 64);
        g.setColor(new Color(125, 85, 180, 110)); g.fillOval(766, 99, 17, 12); g.fillOval(783, 120, 12, 17);

        for (Star s : stars) {
            s.x -= s.speed * (screen == Screen.PLAYING ? worldSpeed / 4 : .35);
            if (s.x < 0) s.x = W;
            g.setColor(new Color(130, 220, 255, 90 + (int)(Math.abs(Math.sin(System.nanoTime() / 5e8 + s.x)) * 150)));
            g.fillOval((int)s.x, s.y, s.size, s.size);
        }

        // Distant skyline
        drawCityLayer(g, 0.18, 300, new Color(17, 20, 54), new Color(50, 40, 120, 110), 0);
        drawCityLayer(g, 0.34, 365, new Color(15, 15, 43), new Color(0, 220, 255, 95), 1);
        drawCityLayer(g, 0.55, 425, new Color(10, 12, 32), new Color(255, 30, 170, 90), 2);

        // Horizon haze
        GradientPaint haze = new GradientPaint(0, 340, new Color(255, 25, 175, 0), 0, 480, new Color(100, 20, 255, 45));
        g.setPaint(haze); g.fillRect(0, 330, W, 150);

        // Ground / rooftop
        g.setColor(new Color(8, 10, 26)); g.fillRect(0, 520, W, 80);
        g.setColor(cyan); g.setStroke(new BasicStroke(2)); g.drawLine(0, 520, W, 520);
        glow(g, 470, 520, 38, new Color(0, 245, 255, 65));
        for (int x = 0; x < W; x += 65) {
            int xx = (int)(x - (distance * 2 % 65));
            g.setColor(new Color(0, 220, 255, 55));
            g.drawLine(xx, 521, xx - 22, 600);
        }
        g.setColor(new Color(255, 30, 170, 75));
        for (int x = 15; x < W; x += 160) {
            int xx = (int)(x - (distance % 160));
            g.drawLine(xx, 536, xx + 55, 536);
        }
    }

    private void drawCityLayer(Graphics2D g, double factor, int baseY, Color building, Color light, int layer) {
        int shift = (int)((distance * factor) % 170);
        for (int i = -1; i < 8; i++) {
            int x = i * 170 - shift;
            int bw = 105 + ((i * 37 + layer * 19) & 55);
            int bh = 90 + ((i * 53 + layer * 41) & 130);
            int y = baseY - bh;
            g.setColor(building); g.fillRect(x, y, bw, bh + 80);
            g.setColor(new Color(light.getRed(), light.getGreen(), light.getBlue(), 45));
            g.drawRect(x, y, bw, bh + 80);
            for (int wx = x + 12; wx < x + bw - 6; wx += 19) {
                for (int wy = y + 12; wy < baseY - 5; wy += 24) {
                    if (((wx + wy + layer * 7) / 3) % 4 != 0) {
                        g.setColor(new Color(light.getRed(), light.getGreen(), light.getBlue(), 65 + Math.floorMod(wx + wy, 90)));
                        g.fillRoundRect(wx, wy, 5, 10, 2, 2);
                    }
                }
            }
            if (i % 3 == 0) {
                g.setColor(light); g.setStroke(new BasicStroke(2));
                g.drawLine(x + bw / 2, y - 18, x + bw / 2, y);
                g.drawLine(x + bw / 2 - 10, y - 12, x + bw / 2 + 10, y - 12);
            }
        }
    }

    private void drawPlatforms(Graphics2D g) {
        for (Platform p : platforms) {
            glow(g, p.x + p.w / 2, p.y, 22, new Color(0, 240, 255, 45));
            g.setColor(new Color(13, 28, 55)); g.fillRoundRect((int)p.x, p.y, p.w, 13, 4, 4);
            g.setColor(cyan); g.setStroke(new BasicStroke(2)); g.drawLine((int)p.x, p.y, (int)(p.x + p.w), p.y);
            g.setColor(new Color(0, 245, 255, 70));
            g.drawLine((int)p.x + 10, p.y + 8, (int)(p.x + p.w - 10), p.y + 8);
        }
    }

    private void drawCoins(Graphics2D g) {
        for (Coin c : coins) {
            double bob = Math.sin(c.phase) * 4;
            glow(g, c.x, c.y + bob, 18, new Color(255, 210, 30, 75));
            g.setColor(new Color(255, 185, 20)); g.fillOval((int)c.x - 9, (int)(c.y + bob) - 9, 18, 18);
            g.setColor(new Color(255, 245, 145)); g.setStroke(new BasicStroke(2));
            g.drawOval((int)c.x - 6, (int)(c.y + bob) - 6, 12, 12);
            g.drawLine((int)c.x, (int)(c.y + bob) - 5, (int)c.x, (int)(c.y + bob) + 5);
        }
    }

    private void drawObstacles(Graphics2D g) {
        for (Obstacle o : obstacles) {
            if (o.type == Obstacle.BARRIER) {
                glow(g, o.x + o.w / 2, o.y + 40, 28, new Color(255, 20, 160, 55));
                g.setColor(new Color(32, 17, 48)); g.fillRoundRect((int)o.x, o.y, o.w, o.h, 8, 8);
                g.setColor(pink); g.setStroke(new BasicStroke(3)); g.drawRoundRect((int)o.x, o.y, o.w, o.h, 8, 8);
                g.setColor(new Color(255, 80, 190)); g.fillRect((int)o.x + 7, o.y + 18, o.w - 14, 7);
                g.fillRect((int)o.x + 7, o.y + 43, o.w - 14, 7);
            } else if (o.type == Obstacle.LASER) {
                int pulse = (int)(Math.sin(o.phase) * 30);
                glow(g, o.x + o.w / 2, o.y + 55, 42, new Color(255, 30, 80, 65));
                g.setColor(new Color(40, 20, 50)); g.fillRoundRect((int)o.x, o.y, 9, o.h, 5, 5);
                g.fillRoundRect((int)(o.x + o.w - 9), o.y, 9, o.h, 5, 5);
                g.setColor(new Color(255, 20, 70, 180)); g.setStroke(new BasicStroke(7));
                g.drawLine((int)o.x + 5, o.y + 14, (int)(o.x + o.w - 5), o.y + 14);
                g.setColor(new Color(255, 105 + pulse, 150, 230)); g.setStroke(new BasicStroke(2));
                g.drawLine((int)o.x + 5, o.y + 14, (int)(o.x + o.w - 5), o.y + 14);
                g.setColor(pink); g.fillOval((int)o.x - 3, o.y + 4, 15, 15);
                g.fillOval((int)(o.x + o.w - 12), o.y + 4, 15, 15);
            } else {
                double bob = Math.sin(o.phase) * 8;
                glow(g, o.x + 20, o.y + bob, 25, new Color(255, 40, 150, 75));
                g.setColor(new Color(34, 26, 58)); g.fillRoundRect((int)o.x, (int)(o.y + bob), o.w, o.h, 12, 12);
                g.setColor(purple); g.setStroke(new BasicStroke(2)); g.drawRoundRect((int)o.x, (int)(o.y + bob), o.w, o.h, 12, 12);
                g.setColor(cyan); g.fillOval((int)o.x + 8, (int)(o.y + bob) + 15, 8, 8);
                g.fillOval((int)o.x + 27, (int)(o.y + bob) + 15, 8, 8);
                g.setColor(pink); g.drawLine((int)o.x + 9, (int)(o.y + bob) + 38, (int)o.x + 34, (int)(o.y + bob) + 38);
            }
        }
    }

    private void drawPlayer(Graphics2D g) {
        Player p = player;
        int px = (int)p.x, py = (int)p.y;
        if (p.dashTime > 0) {
            for (int i = 4; i >= 1; i--) {
                g.setColor(new Color(0, 240, 255, 18 * i));
                g.fillRoundRect(px - i * 16, py + 7, 34, p.sliding ? 28 : 48, 16, 16);
            }
            glow(g, px + 18, py + 25, 42, new Color(0, 245, 255, 80));
        }
        int bodyH = p.sliding ? 25 : 43;
        int bodyY = p.sliding ? py + 20 : py + 12;
        // shadow
        g.setColor(new Color(0, 0, 0, 90)); g.fillOval(px - 7, 510, 48, 9);
        // legs / animated running pose
        int stride = p.onGround ? (int)(Math.sin(p.runPhase) * 10) : 4;
        g.setStroke(new BasicStroke(7, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(20, 30, 65));
        if (!p.sliding) {
            g.drawLine(px + 17, py + 39, px + 12 + stride, py + 55);
            g.drawLine(px + 23, py + 39, px + 28 - stride, py + 54);
            g.setColor(cyan);
            g.drawLine(px + 12 + stride, py + 55, px + 5 + stride, py + 57);
            g.drawLine(px + 28 - stride, py + 54, px + 36 - stride, py + 56);
        } else {
            g.drawLine(px + 10, py + 39, px + 2, py + 47);
            g.drawLine(px + 22, py + 39, px + 36, py + 43);
        }
        // torso
        glow(g, px + 20, bodyY + bodyH / 2, 24, new Color(0, 240, 255, 35));
        g.setColor(new Color(15, 24, 54));
        g.fillRoundRect(px + 10, bodyY, 21, bodyH, 9, 9);
        g.setColor(cyan); g.setStroke(new BasicStroke(2));
        g.drawRoundRect(px + 10, bodyY, 21, bodyH, 9, 9);
        g.setColor(pink); g.drawLine(px + 14, bodyY + 13, px + 27, bodyY + 13);
        // head and visor
        g.setColor(new Color(22, 28, 58)); g.fillOval(px + 11, py - 1, 23, 23);
        g.setColor(cyan); g.setStroke(new BasicStroke(2)); g.drawOval(px + 11, py - 1, 23, 23);
        g.setColor(new Color(255, 35, 180)); g.fillRoundRect(px + 25, py + 7, 10, 5, 3, 3);
        // scarf
        g.setColor(pink);
        Polygon scarf = new Polygon(new int[]{px + 12, px + 2, px - 8}, new int[]{py + 15, py + 20, py + 10}, 3);
        g.fillPolygon(scarf);
        if (p.dashTime > 0) {
            g.setColor(new Color(0, 255, 255, 210)); g.setStroke(new BasicStroke(2));
            g.drawRoundRect(px - 5, py - 7, 47, 70, 15, 15);
        }
    }

    private void drawParticles(Graphics2D g) {
        for (Particle p : particles) {
            g.setColor(new Color(p.color.getRed(), p.color.getGreen(), p.color.getBlue(),
                    Math.max(0, Math.min(255, (int)(255 * p.life / 30)))));
            g.fillOval((int)p.x, (int)p.y, 4, 4);
        }
    }

    private void drawHud(Graphics2D g) {
        g.setFont(hudFont);
        g.setColor(new Color(0, 0, 0, 125)); g.fillRoundRect(18, 17, 280, 82, 14, 14);
        g.setColor(cyan); g.drawRoundRect(18, 17, 280, 82, 14, 14);
        g.setColor(Color.WHITE); g.drawString("DISTANCE  " + (distance / 10) + " m", 34, 44);
        g.setColor(new Color(255, 205, 45)); g.drawString("COINS     " + coinCount, 34, 70);
        g.setColor(new Color(210, 190, 255)); g.drawString("SCORE     " + score(), 34, 91);

        g.setFont(smallFont); g.setColor(new Color(180, 200, 230));
        g.drawString("SPACE: JUMP x2     SHIFT/X: DASH     DOWN: SLIDE     P: PAUSE", 285, 579);
        // dash meter
        g.setColor(new Color(0, 0, 0, 140)); g.fillRoundRect(815, 22, 160, 34, 10, 10);
        g.setColor(new Color(130, 70, 255)); g.drawRoundRect(815, 22, 160, 34, 10, 10);
        g.setColor(Color.WHITE); g.drawString("DASH", 827, 43);
        g.setColor(new Color(30, 35, 65)); g.fillRoundRect(878, 33, 85, 11, 5, 5);
        g.setColor(cyan);
        int meter = (int)(85 * Math.max(0, Math.min(1, player.dashCooldown / 80.0)));
        g.fillRoundRect(878, 33, meter, 11, 5, 5);
    }

    private void drawMenu(Graphics2D g) {
        // dark overlay for cinematic title screen
        g.setColor(new Color(2, 3, 18, 105)); g.fillRect(0, 0, W, H);
        glow(g, 500, 205, 200, new Color(0, 230, 255, 40));
        g.setFont(new Font("SansSerif", Font.BOLD, 15)); g.setColor(cyan);
        centered(g, "A CYBERPUNK PARKOUR RUNNER", 150);
        g.setFont(titleFont);
        glow(g, 500, 218, 110, new Color(255, 20, 180, 65));
        g.setColor(Color.WHITE); centered(g, "NEON", 218);
        g.setColor(cyan); centered(g, "ESCAPE", 273);
        g.setColor(new Color(255, 40, 180)); g.setStroke(new BasicStroke(3)); g.drawLine(365, 292, 635, 292);
        g.setFont(new Font("SansSerif", Font.PLAIN, 17)); g.setColor(new Color(215, 225, 255));
        centered(g, "RUN THE ROOFTOPS. OUTRUN THE MACHINES.", 330);
        g.setFont(hudFont); g.setColor(Color.WHITE); centered(g, "[ PRESS ENTER OR SPACE TO START ]", 405);
        g.setFont(smallFont); g.setColor(new Color(160, 180, 220));
        centered(g, "DOUBLE JUMP  /  DASH THROUGH LASERS  /  COLLECT ENERGY COINS", 445);
        centered(g, "A JAVA SWING MINI PROJECT", 565);
    }

    private void drawOverlay(Graphics2D g, String heading, String subtitle) {
        g.setColor(new Color(3, 4, 20, 205)); g.fillRect(0, 0, W, H);
        g.setFont(new Font("SansSerif", Font.BOLD, 36)); g.setColor(cyan); centered(g, heading, 260);
        g.setFont(hudFont); g.setColor(Color.WHITE); centered(g, subtitle, 310);
    }

    private void drawGameOver(Graphics2D g) {
        g.setColor(new Color(3, 4, 20, 215)); g.fillRect(0, 0, W, H);
        glow(g, 500, 215, 150, new Color(255, 20, 120, 55));
        g.setFont(new Font("SansSerif", Font.BOLD, 43)); g.setColor(pink); centered(g, "RUN TERMINATED", 220);
        g.setFont(hudFont); g.setColor(Color.WHITE);
        centered(g, "SCORE  " + score() + "     COINS  " + coinCount, 275);
        centered(g, "DISTANCE  " + (distance / 10) + " m", 309);
        centered(g, "BEST THIS SESSION  " + bestScore, 343);
        g.setColor(cyan); centered(g, "[ PRESS R / ENTER TO RETRY ]", 405);
        g.setFont(smallFont); g.setColor(new Color(165, 185, 220)); centered(g, "PRESS ESC TO RETURN TO TITLE", 438);
    }

    private void centered(Graphics2D g, String text, int y) {
        FontMetrics fm = g.getFontMetrics();
        g.drawString(text, (W - fm.stringWidth(text)) / 2, y);
    }

    private void glow(Graphics2D g, double cx, double cy, double radius, Color color) {
        for (int i = 5; i >= 1; i--) {
            int r = (int)(radius * i / 2.5);
            int alpha = Math.max(1, color.getAlpha() / (i + 1));
            g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha));
            g.fillOval((int)cx - r, (int)cy - r, r * 2, r * 2);
        }
    }

    private class Player {
        double x, y, vy, runPhase, dashTime, dashCooldown;
        boolean onGround = true, sliding;
        int jumps;
        Player(double x, double y) { this.x = x; this.y = y; }
        void jump() {
            if (sliding) sliding = false;
            if (jumps < 2) {
                vy = jumps == 0 ? -12.8 : -11.0;
                jumps++;
                onGround = false;
                burst(x + 18, y + 53, jumps == 1 ? cyan : pink, 7);
            }
        }
        void dash() {
            if (dashCooldown <= 0) {
                dashTime = 12;
                dashCooldown = 80;
                burst(x + 10, y + 25, cyan, 12);
            }
        }
        void update(double dt) {
            runPhase += 0.28 * dt;
            if (dashTime > 0) dashTime -= dt;
            if (dashCooldown > 0) dashCooldown -= dt;
            if (!onGround) {
                vy += 0.58 * dt;
                y += vy * dt;
                if (y >= 435) {
                    y = 435; vy = 0; onGround = true; jumps = 0;
                }
            }
            if (sliding && onGround) { /* slide pose is drawn separately */ }
        }
        Rectangle2D.Double bounds() {
            if (sliding && onGround) return new Rectangle2D.Double(x + 4, y + 23, 37, 28);
            return new Rectangle2D.Double(x + 7, y + 1, 30, 55);
        }
    }

    private static class Obstacle {
        static final int BARRIER = 0, LASER = 1, DRONE = 2;
        double x, phase;
        int y, w, h, type;
        Obstacle(double x, int y, int w, int h, int type) {
            this.x = x; this.y = y; this.w = w; this.h = h; this.type = type;
        }
        Rectangle2D.Double bounds() {
            if (type == LASER) return new Rectangle2D.Double(x, y + 5, w, 20);
            if (type == DRONE) return new Rectangle2D.Double(x, y + 8 + Math.sin(phase) * 8, w, h - 15);
            return new Rectangle2D.Double(x, y, w, h);
        }
    }
    private static class Coin {
        double x, phase; int y;
        Coin(double x, int y) { this.x = x; this.y = y; }
    }
    private static class Platform {
        double x, phase; int y, w;
        Platform(double x, int y, int w) { this.x = x; this.y = y; this.w = w; }
    }
    private static class Particle {
        double x, y, vx, vy, life = 30; Color color;
        Particle(double x, double y, double vx, double vy, Color color) {
            this.x = x; this.y = y; this.vx = vx; this.vy = vy; this.color = color;
        }
        void update(double dt) { x += vx * dt; y += vy * dt; vy += .08 * dt; life -= dt; }
    }
    private static class Star {
        double x, speed; int y, size;
        Star(double x, int y, double speed, int size) { this.x = x; this.y = y; this.speed = speed; this.size = size; }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("NEON ESCAPE | Cyberpunk Runner");
            NeonEscapeGame game = new NeonEscapeGame();
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setContentPane(game);
            frame.pack();
            frame.setResizable(false);
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
            game.requestFocusInWindow();
        });
    }
}
