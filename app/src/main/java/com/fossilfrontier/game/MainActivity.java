package com.fossilfrontier.game;

import android.app.Activity;
import android.os.Bundle;
import android.os.SystemClock;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Random;

/**
 * Fossil Frontier: an original, self-contained Android fossil excavation game.
 * Offline, no permissions, no ads, no proprietary assets.
 */
public final class MainActivity extends Activity {
    private Game game;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        game = new Game(this);
        setContentView(game);
    }

    @Override protected void onPause() {
        if (game != null) game.save();
        super.onPause();
    }

    static final class Game extends View {
        static final int W = 1080, H = 1920, N = 9, CELL = 102, GX = 81, GY = 375;
        static final int SOIL = 0, ROCK = 1, BONE = 2, CRYSTAL = 3, CHEST = 4;
        static final String[] SPECIES = {"TRICERA", "STEGOSA", "RAPTOR", "BRONTON", "CERATON"};
        static final int[] NEED = {4, 5, 6, 7, 8};
        static final int[] ASSEMBLE = {150, 370, 900, 2300, 5600};
        static final int[] RENT = {2, 6, 14, 36, 90};
        static final int DARK = Color.rgb(20, 47, 50);
        static final int CREAM = Color.rgb(255, 247, 220);
        static final int GOLD = Color.rgb(255, 203, 90);
        static final int TEAL = Color.rgb(52, 156, 142);
        static final int ORANGE = Color.rgb(250, 145, 85);
        private final Random rng = new Random();
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final SharedPreferences prefs;
        private final int[][] type = new int[N][N];
        private final int[][] hp = new int[N][N];
        private final int[][] max = new int[N][N];
        private final int[] pieces = new int[SPECIES.length];
        private final boolean[] exhibits = new boolean[SPECIES.length];
        private long coins = 120, dug = 0;
        private int depth = 1, pick = 1, drill = 0, radar = 0, cart = 0;
        private int page = 0; // DIG / SHOP / MUSEUM
        private int previousCell = -1;
        private long frameTime = SystemClock.uptimeMillis(), bannerUntil = frameTime + 3500;
        private double autoTimer = 0, rentTimer = 0;
        private String banner = "TOCCA O TRASCINA PER SCAVARE";
        private final float[] particlesX = new float[22], particlesY = new float[22];
        private final long[] particlesUntil = new long[22];
        private int particleIndex = 0;

        Game(Context context) {
            super(context);
            prefs = context.getSharedPreferences("fossil_frontier_v1", Context.MODE_PRIVATE);
            restore();
        }

        private int difficulty() {
            return Math.min(12, (depth - 1) / 4);
        }

        private void nextLayer() {
            int hard = difficulty();
            for (int r = 0; r < N; r++) for (int c = 0; c < N; c++) {
                int roll = rng.nextInt(100);
                int boneChance = Math.min(25, 13 + radar * 2);
                type[r][c] = roll < boneChance ? BONE : roll < boneChance + 7 ? CRYSTAL :
                        roll < boneChance + 17 ? CHEST : roll < 52 ? ROCK : SOIL;
                max[r][c] = 1 + hard + (type[r][c] == ROCK ? 2 + hard / 2 : 0);
                if (type[r][c] == BONE || type[r][c] == CRYSTAL) max[r][c]++;
                if (depth == 1 && r < 2) max[r][c] = Math.max(1, max[r][c] - 1);
                hp[r][c] = max[r][c];
            }
        }

        private long rentPerSecond() {
            long total = 0;
            for (int i = 0; i < exhibits.length; i++) if (exhibits[i]) total += RENT[i];
            return total;
        }

        private void restore() {
            String content = prefs.getString("state", null);
            if (content == null) { nextLayer(); return; }
            try {
                JSONObject j = new JSONObject(content);
                coins = j.optLong("coins", 120);
                dug = j.optLong("dug", 0);
                depth = Math.max(1, j.optInt("depth", 1));
                pick = Math.max(1, j.optInt("pick", 1));
                drill = Math.max(0, j.optInt("drill", 0));
                radar = Math.max(0, j.optInt("radar", 0));
                cart = Math.max(0, j.optInt("cart", 0));
                JSONArray bones = j.getJSONArray("bones");
                JSONArray display = j.getJSONArray("exhibits");
                for (int i = 0; i < SPECIES.length; i++) {
                    pieces[i] = Math.max(0, bones.optInt(i));
                    exhibits[i] = display.optBoolean(i, false);
                }
                JSONArray cells = j.getJSONArray("cells");
                if (cells.length() != N * N) throw new IllegalStateException("Invalid save map");
                for (int r = 0; r < N; r++) for (int c = 0; c < N; c++) {
                    JSONArray cell = cells.getJSONArray(r * N + c);
                    type[r][c] = cell.getInt(0);
                    hp[r][c] = cell.getInt(1);
                    max[r][c] = cell.getInt(2);
                }
                long away = Math.max(0, (System.currentTimeMillis() - j.optLong("time", System.currentTimeMillis())) / 1000);
                long bonus = Math.min(away, 8 * 3600) * rentPerSecond();
                coins += bonus;
                if (bonus > 0) notice("MUSEO OFFLINE: +" + money(bonus));
            } catch (Exception badSave) {
                coins = 120; dug = 0; depth = 1; pick = 1; drill = radar = cart = 0;
                for (int i = 0; i < SPECIES.length; i++) { pieces[i] = 0; exhibits[i] = false; }
                nextLayer();
            }
        }

        void save() {
            try {
                JSONObject j = new JSONObject();
                j.put("coins", coins);
                j.put("dug", dug);
                j.put("depth", depth);
                j.put("pick", pick);
                j.put("drill", drill);
                j.put("radar", radar);
                j.put("cart", cart);
                j.put("time", System.currentTimeMillis());
                JSONArray bones = new JSONArray(), displays = new JSONArray(), cells = new JSONArray();
                for (int i = 0; i < SPECIES.length; i++) {
                    bones.put(pieces[i]);
                    displays.put(exhibits[i]);
                }
                for (int r = 0; r < N; r++) for (int c = 0; c < N; c++) {
                    JSONArray cell = new JSONArray();
                    cell.put(type[r][c]); cell.put(hp[r][c]); cell.put(max[r][c]);
                    cells.put(cell);
                }
                j.put("bones", bones);
                j.put("exhibits", displays);
                j.put("cells", cells);
                prefs.edit().putString("state", j.toString()).apply();
            } catch (Exception ignored) {
                notice("ERRORE DI SALVATAGGIO");
            }
        }

        private static String money(long amount) {
            if (amount >= 1000000000L) return amount / 1000000000L + "B";
            if (amount >= 1000000L) return amount / 1000000L + "M";
            if (amount >= 10000L) return amount / 1000L + "K";
            return Long.toString(amount);
        }

        private void notice(String text) {
            banner = text;
            bannerUntil = SystemClock.uptimeMillis() + 3000;
        }

        private int remaining() {
            int left = 0;
            for (int[] row : hp) for (int cell : row) if (cell > 0) left++;
            return left;
        }

        private void foundFossil() {
            int available = Math.min(SPECIES.length, 1 + (depth - 1) / 3);
            int i = rng.nextInt(Math.max(1, available));
            if (exhibits[i]) {
                coins += (i + 1) * 25L;
                notice("FOSSILE DOPPIO: BONUS MONETE!");
            } else {
                pieces[i]++;
                notice(pieces[i] >= NEED[i] ? SPECIES[i] + " PRONTO PER IL MUSEO!" :
                        "OSSO " + SPECIES[i] + ": " + pieces[i] + "/" + NEED[i]);
            }
        }

        private void hit(int r, int c, boolean manual) {
            if (r < 0 || c < 0 || r >= N || c >= N || hp[r][c] == 0) return;
            hp[r][c] = Math.max(0, hp[r][c] - (manual ? pick : Math.max(1, pick / 2)));
            int index = particleIndex++ % particlesX.length;
            particlesX[index] = GX + c * CELL + 51;
            particlesY[index] = GY + r * CELL + 51;
            particlesUntil[index] = SystemClock.uptimeMillis() + 400;
            if (hp[r][c] > 0) return;
            dug++;
            long reward = (4L + depth) * (1L + cart);
            if (type[r][c] == ROCK) reward += 8 + depth * 2L;
            if (type[r][c] == CHEST) reward += 27 + depth * 4L;
            if (type[r][c] == CRYSTAL) reward += 55 + depth * 7L;
            coins += reward;
            if (type[r][c] == BONE) foundFossil();
            if (remaining() == 0) {
                depth++;
                coins += 70L + depth * 20L;
                nextLayer();
                notice("NUOVO STRATO! PROFONDITA' " + depth);
            }
            if (dug % 9 == 0) save();
        }

        private long price(int upgrade) {
            int current = upgrade == 0 ? pick : upgrade == 1 ? drill : upgrade == 2 ? radar : cart;
            long base = upgrade == 0 ? 75 : upgrade == 1 ? 220 : upgrade == 2 ? 170 : 130;
            return Math.max(30, base * (current + 1L) * (current + 1L) / (upgrade == 0 ? 4 : 1));
        }

        private void upgrade(int i) {
            long cost = price(i);
            if (coins < cost) { notice("MONETE INSUFFICIENTI"); return; }
            coins -= cost;
            if (i == 0) pick++;
            if (i == 1) drill++;
            if (i == 2) radar++;
            if (i == 3) cart++;
            notice("POTENZIAMENTO ACQUISTATO!");
            save();
        }

        private void assemble(int i) {
            if (exhibits[i]) { notice("GIA' IN ESPOSIZIONE"); return; }
            if (pieces[i] < NEED[i]) { notice("SERVONO ALTRI FOSSILI"); return; }
            if (coins < ASSEMBLE[i]) { notice("MONETE INSUFFICIENTI"); return; }
            coins -= ASSEMBLE[i];
            exhibits[i] = true;
            notice(SPECIES[i] + " ESPOSTO! +" + RENT[i] + "/S");
            save();
        }

        private void tick(float dt) {
            if (rentPerSecond() > 0) {
                rentTimer += dt;
                while (rentTimer >= 1) { rentTimer--; coins += rentPerSecond(); }
            }
            if (drill > 0) {
                autoTimer += dt;
                double interval = Math.max(0.35, 3.2 / (1 + drill * 0.52));
                if (autoTimer >= interval) {
                    autoTimer = 0;
                    for (int tries = 0; tries < N * N; tries++) {
                        int index = rng.nextInt(N * N);
                        if (hp[index / N][index % N] > 0) {
                            hit(index / N, index % N, false);
                            break;
                        }
                    }
                }
            }
        }

        private void rectangle(Canvas c, float x1, float y1, float x2, float y2, int fill) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(fill);
            c.drawRect(x1, y1, x2, y2, p);
        }

        private void rounded(Canvas c, float x1, float y1, float x2, float y2, float radius, int fill) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(fill);
            c.drawRoundRect(new RectF(x1, y1, x2, y2), radius, radius, p);
        }

        private void text(Canvas c, String content, float x, float baseline, float size, int fill, boolean center) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(fill);
            p.setTypeface(android.graphics.Typeface.create("sans-serif-condensed",
                    android.graphics.Typeface.BOLD));
            p.setTextSize(size);
            p.setTextAlign(center ? Paint.Align.CENTER : Paint.Align.LEFT);
            c.drawText(content, x, baseline, p);
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            long now = SystemClock.uptimeMillis();
            float dt = Math.min(0.15f, Math.max(0f, (now - frameTime) / 1000f));
            frameTime = now;
            tick(dt);
            c.save();
            c.scale(getWidth() / (float) W, getHeight() / (float) H);
            rectangle(c, 0, 0, W, H, CREAM);
            rectangle(c, 0, 0, W, 272, DARK);
            rectangle(c, 0, 272, W, 288, TEAL);
            text(c, "FOSSIL", 55, 87, 68, GOLD, false);
            text(c, "FRONTIER", 55, 147, 60, CREAM, false);
            rounded(c, 570, 44, 1022, 132, 18, Color.rgb(38, 87, 87));
            text(c, "MONETE", 594, 77, 26, TEAL, false);
            text(c, money(coins), 593, 123, 44, GOLD, false);
            rounded(c, 54, 174, 1025, 249, 13, Color.rgb(42, 79, 78));
            text(c, "PROF. " + depth, 75, 223, 36, CREAM, false);
            text(c, "SCAVI " + money(dug), 350, 223, 32, CREAM, false);
            text(c, "+" + rentPerSecond() + "/SEC", 786, 223, 30, GOLD, false);
            if (page == 0) drawDig(c);
            if (page == 1) drawShop(c);
            if (page == 2) drawMuseum(c);
            rectangle(c, 0, 1728, W, H, DARK);
            String[] names = {"SCAVA", "UPGRADE", "MUSEO"};
            for (int i = 0; i < 3; i++) {
                int x = 42 + i * 345;
                rounded(c, x, 1757, x + 315, 1879, 22,
                        i == page ? GOLD : Color.rgb(45, 86, 83));
                text(c, names[i], x + 158, 1833, 42, i == page ? DARK : CREAM, true);
            }
            if (now < bannerUntil) {
                rounded(c, 75, 1634, 1005, 1708, 18, DARK);
                text(c, banner, 540, 1683, 30, GOLD, true);
            }
            for (int i = 0; i < particlesX.length; i++) {
                if (particlesUntil[i] > now) {
                    int alpha = (int) (220f * (particlesUntil[i] - now) / 400f);
                    rectangle(c, particlesX[i] - 8, particlesY[i] - 8,
                            particlesX[i] + 8, particlesY[i] + 8,
                            Color.argb(Math.max(0, Math.min(255, alpha)), 255, 207, 103));
                }
            }
            c.restore();
            postInvalidateDelayed(40);
        }

        private void drawDig(Canvas c) {
            rectangle(c, 0, 288, W, 372, Color.rgb(116, 202, 186));
            text(c, "SITO DI SCAVO " + (1 + (depth - 1) / 5), 540, 345, 46, DARK, true);
            rectangle(c, 53, 369, 1026, 1302, Color.rgb(64, 55, 55));
            for (int row = 0; row < N; row++) for (int col = 0; col < N; col++)
                drawCell(c, row, col);
            rounded(c, 67, 1352, 1014, 1550, 28, DARK);
            text(c, "TERRA DA RIMUOVERE", 105, 1416, 39, CREAM, false);
            text(c, remaining() + " / 81 BLOCCHI", 540, 1502, 60, GOLD, true);
            rounded(c, 78, 1581, 1002, 1619, 8, Color.rgb(196, 213, 181));
            float completed = (81 - remaining()) / 81f;
            rounded(c, 80, 1583, 80 + 920 * completed, 1617, 8, TEAL);
        }

        private void drawCell(Canvas c, int row, int col) {
            int x = GX + col * CELL, y = GY + row * CELL;
            if (hp[row][col] <= 0) {
                rectangle(c, x + 2, y + 2, x + CELL - 2, y + CELL - 2,
                        Color.rgb(47, 43 + row % 3 * 3, 48));
                rectangle(c, x + 9, y + 9, x + 22, y + 17, Color.rgb(76, 64, 61));
                return;
            }
            int base = Math.max(92, 201 - difficulty() * 11 - row * 3);
            int soil = Color.rgb(base, Math.max(65, base - 48), Math.max(45, base - 89));
            rectangle(c, x + 2, y + 2, x + CELL - 2, y + CELL - 2,
                    type[row][col] == ROCK ? Color.rgb(124, 123, 114) : soil);
            rectangle(c, x + 10, y + 12, x + 32, y + 22, Color.rgb(152, 97, 65));
            rectangle(c, x + 69, y + 56, x + 82, y + 65, Color.rgb(155, 98, 66));
            if (type[row][col] == ROCK) {
                rectangle(c, x + 25, y + 31, x + 73, y + 72, Color.rgb(89, 91, 87));
                rectangle(c, x + 31, y + 34, x + 57, y + 43, Color.rgb(181, 171, 153));
            }
            boolean exposed = hp[row][col] < max[row][col] ||
                    (radar > 0 && (row + col + depth) % Math.max(2, 7 - radar) == 0);
            if (exposed && type[row][col] == BONE) {
                rectangle(c, x + 42, y + 20, x + 55, y + 80, CREAM);
                rectangle(c, x + 25, y + 40, x + 73, y + 54, CREAM);
            }
            if (exposed && type[row][col] == CRYSTAL) {
                rectangle(c, x + 35, y + 34, x + 67, y + 69, Color.rgb(90, 227, 218));
                rectangle(c, x + 40, y + 30, x + 60, y + 44, CREAM);
            }
            if (exposed && type[row][col] == CHEST) {
                rectangle(c, x + 29, y + 38, x + 73, y + 71, GOLD);
                rectangle(c, x + 46, y + 38, x + 57, y + 71, DARK);
            }
            if (hp[row][col] < max[row][col]) {
                rectangle(c, x + 11, y + CELL - 16, x + 91, y + CELL - 9, DARK);
                rectangle(c, x + 11, y + CELL - 16,
                        x + 11 + 80f * hp[row][col] / max[row][col],
                        y + CELL - 9, ORANGE);
            }
        }

        private void drawShop(Canvas c) {
            text(c, "LABORATORIO", 540, 356, 62, DARK, true);
            String[] names = {"PICCONE POTENZIATO", "TRIVELLA AUTOMATICA",
                    "RADAR FOSSILI", "CARRELLO DA MINIERA"};
            String[] desc = {"Piu' danni per tocco", "Scava anche da solo",
                    "Piu' fossili negli strati", "Piu' monete dagli scavi"};
            int[] levels = {pick, drill, radar, cart};
            for (int i = 0; i < 4; i++) {
                int y = 400 + i * 300;
                rounded(c, 60, y, 1020, y + 257, 27, DARK);
                rounded(c, 77, y + 15, 1003, y + 244, 20, Color.rgb(42, 83, 79));
                rounded(c, 110, y + 55, 247, y + 189, 12, i % 2 == 0 ? GOLD : TEAL);
                icon(c, i, 178, y + 126);
                text(c, names[i], 281, y + 83, 36, CREAM, false);
                text(c, desc[i], 281, y + 128, 28, Color.rgb(161, 213, 201), false);
                text(c, "LIVELLO " + levels[i], 281, y + 182, 35, GOLD, false);
                boolean enough = coins >= price(i);
                rounded(c, 671, y + 170, 968, y + 226, 15,
                        enough ? GOLD : Color.rgb(104, 119, 113));
                text(c, money(price(i)) + " MONETE", 819, y + 209, 29,
                        enough ? DARK : CREAM, true);
            }
        }

        private void icon(Canvas c, int i, int x, int y) {
            if (i == 0) {
                rectangle(c, x - 8, y - 54, x + 8, y + 51, DARK);
                rectangle(c, x - 50, y - 60, x + 52, y - 37, CREAM);
            } else if (i == 1) {
                rectangle(c, x - 27, y - 52, x + 27, y + 35, DARK);
                rectangle(c, x - 50, y + 27, x + 51, y + 50, CREAM);
            } else if (i == 2) {
                p.setColor(DARK); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(11);
                c.drawCircle(x, y - 10, 37, p);
                p.setStyle(Paint.Style.FILL);
                rectangle(c, x + 20, y + 22, x + 48, y + 52, DARK);
            } else {
                rectangle(c, x - 51, y - 37, x + 51, y + 18, DARK);
                rectangle(c, x - 42, y + 18, x - 20, y + 46, CREAM);
                rectangle(c, x + 20, y + 18, x + 42, y + 46, CREAM);
            }
        }

        private void drawMuseum(Canvas c) {
            text(c, "MUSEO PREISTORICO", 540, 354, 54, DARK, true);
            for (int i = 0; i < SPECIES.length; i++) {
                int y = 383 + i * 253;
                rounded(c, 55, y, 1025, y + 226, 24, DARK);
                rounded(c, 70, y + 12, 1010, y + 212, 17,
                        exhibits[i] ? Color.rgb(49, 125, 103) : Color.rgb(55, 81, 78));
                rounded(c, 90, y + 29, 287, y + 196, 12, Color.rgb(26, 55, 58));
                dino(c, i, 186, y + 115, exhibits[i] ? GOLD : Color.rgb(104, 124, 116));
                text(c, SPECIES[i], 313, y + 65, 40, CREAM, false);
                text(c, exhibits[i] ? "ESPOSTO: +" + RENT[i] + "/SEC" :
                        pieces[i] + "/" + NEED[i] + " OSSA", 313, y + 113, 31,
                        exhibits[i] ? GOLD : Color.rgb(175, 226, 206), false);
                if (exhibits[i]) {
                    text(c, "COLLEZIONE COMPLETA", 312, y + 165, 28, GOLD, false);
                } else {
                    boolean enough = pieces[i] >= NEED[i] && coins >= ASSEMBLE[i];
                    rounded(c, 657, y + 143, 979, y + 195, 12,
                            enough ? GOLD : Color.rgb(112, 124, 120));
                    text(c, money(ASSEMBLE[i]) + " ASSEMBLA", 818, y + 179, 29,
                            enough ? DARK : CREAM, true);
                }
            }
        }

        private void dino(Canvas c, int species, int x, int y, int fill) {
            rectangle(c, x - 61, y - 6, x + 34, y + 29, fill);
            rectangle(c, x - 38, y - 29, x + 26, y + 7, fill);
            rectangle(c, x + 20, y - 49, x + 47, y + 5, fill);
            rectangle(c, x + 34, y - 50, x + 67, y - 23, fill);
            rectangle(c, x - 80, y - 15, x - 50, y - 3, fill);
            rectangle(c, x - 41, y + 27, x - 24, y + 53, fill);
            rectangle(c, x + 4, y + 27, x + 21, y + 53, fill);
            if (species == 0 || species == 4) {
                rectangle(c, x + 56, y - 53, x + 65, y - 34, CREAM);
                rectangle(c, x + 20, y - 54, x + 30, y - 36, CREAM);
            }
            if (species == 1) for (int j = 0; j < 4; j++)
                rectangle(c, x - 35 + j * 21, y - 43, x - 23 + j * 21, y - 22, CREAM);
            if (species == 3) {
                rectangle(c, x + 15, y - 73, x + 28, y - 29, fill);
                rectangle(c, x + 20, y - 89, x + 63, y - 72, fill);
            }
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            float x = event.getX() * W / Math.max(1f, getWidth());
            float y = event.getY() * H / Math.max(1f, getHeight());
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                previousCell = -1;
                return true;
            }
            if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_MOVE) return true;
            if (action == MotionEvent.ACTION_DOWN && y >= 1728) {
                int target = x < 365 ? 0 : x < 716 ? 1 : 2;
                page = target;
                notice(target == 0 ? "BUONA CACCIA AI FOSSILI!" :
                        target == 1 ? "POTENZIA GLI ATTREZZI!" : "RICOSTRUISCI I DINOSAURI!");
                return true;
            }
            if (page == 0 && y >= GY && y < GY + N * CELL &&
                    x >= GX && x < GX + N * CELL) {
                int row = (int) ((y - GY) / CELL), col = (int) ((x - GX) / CELL);
                int index = row * N + col;
                if (previousCell != index || action == MotionEvent.ACTION_DOWN) {
                    hit(row, col, true);
                    previousCell = index;
                }
            } else if (action == MotionEvent.ACTION_DOWN && page == 1) {
                for (int i = 0; i < 4; i++) {
                    int top = 400 + i * 300;
                    if (y >= top && y < top + 257) { upgrade(i); break; }
                }
            } else if (action == MotionEvent.ACTION_DOWN && page == 2) {
                for (int i = 0; i < SPECIES.length; i++) {
                    int top = 383 + i * 253;
                    if (y >= top && y < top + 226) { assemble(i); break; }
                }
            }
            invalidate();
            return true;
        }
    }
}
