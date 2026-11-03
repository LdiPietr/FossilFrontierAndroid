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
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.view.HapticFeedbackConstants;
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

    @Override protected void onDestroy() {
        if (game != null) game.close();
        super.onDestroy();
    }

    static final class Game extends View {
        static final int W = 1080, H = 1920, N = 9, CELL = 102, GX = 81, GY = 375;
        static final int SOIL = 0, ROCK = 1, BONE = 2, CRYSTAL = 3, CHEST = 4;
        static final String[] SPECIES = {"TRICERA", "STEGOSA", "RAPTOR", "BRONTON", "CERATON"};
        static final String[] BIOMES = {"DUNE DORATE", "CANYON ROSSO", "GIUNGLA PERDUTA", "TUNDRA", "LAVA ANTICA"};

        static final int[] NEED = {4, 5, 6, 7, 8};
        static final int[] ASSEMBLE = {200, 700, 1900, 5500, 14000};
        static final int[] RENT = {2, 5, 12, 28, 65};
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
        private final int[] exhibitLevel = new int[SPECIES.length];
        private long coins = 30, dug = 0, carriedValue = 0, lifetimeSales = 0;
        private int depth = 1, pick = 1, drill = 0, radar = 0, cart = 0, pack = 0, amber = 0;
        private int page = 0; // SCAVA / BASE / UPGRADE / MUSEO
        private int previousCell = -1, combo = 0;
        private long prestigeConfirmUntil = 0;
        private long lastDigAt = 0;
        private ToneGenerator tone;
        private long frameTime = SystemClock.uptimeMillis(), bannerUntil = frameTime + 3500;
        private double autoTimer = 0, rentTimer = 0;
        private String banner = "SCAVA, RIEMPI LO ZAINO, VENDI ALLA BASE";
        private final float[] particlesX = new float[22], particlesY = new float[22];
        private final long[] particlesUntil = new long[22];
        private int particleIndex = 0;

        Game(Context context) {
            super(context);
            prefs = context.getSharedPreferences("fossil_frontier_v1", Context.MODE_PRIVATE);
            try { tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 22); }
            catch (Exception ignored) { tone = null; }
            restore();
        }

        void close() {
            if (tone != null) { tone.release(); tone = null; }
        }

        private void beep(int type) {
            try { if (tone != null) tone.startTone(type, 90); }
            catch (Exception ignored) { }
        }

        private int biome() { return ((depth - 1) / 5) % BIOMES.length; }

        private int difficulty() {
            return Math.min(40, (depth - 1) / 3);
        }

        private int capacity() { return Math.min(160, 12 + cart * 7); }

        private boolean full() { return pack >= capacity(); }

        private static long safe(double value) {
            if (Double.isNaN(value) || value <= 0) return 0;
            return value >= 1000000000000000d ? 1000000000000000L : (long)Math.ceil(value);
        }

        private static long plus(long a, long b) {
            return a > 1000000000000000L - b ? 1000000000000000L : a + b;
        }

        private double amberBoost() { return 1d + amber * 0.3d; }

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
            for (int i = 0; i < exhibits.length; i++) {
                if (exhibits[i]) {
                    total = plus(total, safe(RENT[i] * Math.pow(1.7, Math.max(0, exhibitLevel[i] - 1)) * amberBoost()));
                }
            }
            return total;
        }

        private void restore() {
            String content = prefs.getString("state", null);
            if (content == null) { nextLayer(); return; }
            try {
                JSONObject j = new JSONObject(content);
                coins = Math.max(0, j.optLong("coins", 30));
                dug = Math.max(0, j.optLong("dug", 0));
                depth = Math.max(1, j.optInt("depth", 1));
                pick = Math.max(1, j.optInt("pick", 1));
                drill = Math.max(0, j.optInt("drill", 0));
                radar = Math.max(0, j.optInt("radar", 0));
                cart = Math.max(0, j.optInt("cart", 0));
                pack = Math.max(0, j.optInt("pack", 0));
                carriedValue = Math.max(0, j.optLong("carry", 0));
                lifetimeSales = Math.max(0, j.optLong("sales", 0));
                amber = Math.max(0, j.optInt("amber", 0));
                JSONArray bones = j.getJSONArray("bones");
                JSONArray display = j.getJSONArray("exhibits");
                JSONArray levels = j.optJSONArray("museumLevels");
                for (int i = 0; i < SPECIES.length; i++) {
                    pieces[i] = Math.max(0, bones.optInt(i));
                    exhibits[i] = display.optBoolean(i, false);
                    exhibitLevel[i] = exhibits[i] ? Math.max(1, levels == null ? 1 : levels.optInt(i, 1)) : 0;
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
                long bonus = safe(Math.min(away, 6 * 3600) * (double)rentPerSecond());
                coins = plus(coins, bonus);
                if (bonus > 0) notice("MUSEO OFFLINE: +" + money(bonus));
            } catch (Exception badSave) {
                coins = 30; dug = 0; depth = 1; pick = 1; drill = radar = cart = pack = amber = 0;
                carriedValue = lifetimeSales = 0;
                for (int i = 0; i < SPECIES.length; i++) {
                    pieces[i] = 0; exhibits[i] = false; exhibitLevel[i] = 0;
                }
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
                j.put("pack", pack);
                j.put("carry", carriedValue);
                j.put("sales", lifetimeSales);
                j.put("amber", amber);
                j.put("time", System.currentTimeMillis());
                JSONArray bones = new JSONArray(), displays = new JSONArray();
                JSONArray levels = new JSONArray(), cells = new JSONArray();
                for (int i = 0; i < SPECIES.length; i++) {
                    bones.put(pieces[i]);
                    displays.put(exhibits[i]);
                    levels.put(exhibitLevel[i]);
                }
                for (int r = 0; r < N; r++) for (int c = 0; c < N; c++) {
                    JSONArray cell = new JSONArray();
                    cell.put(type[r][c]); cell.put(hp[r][c]); cell.put(max[r][c]);
                    cells.put(cell);
                }
                j.put("bones", bones); j.put("exhibits", displays);
                j.put("museumLevels", levels); j.put("cells", cells);
                prefs.edit().putString("state", j.toString()).apply();
            } catch (Exception ignored) {
                notice("ERRORE DI SALVATAGGIO");
            }
        }

        private static String money(long amount) {
            if (amount >= 1000000000000L) return String.format(java.util.Locale.ROOT, "%.1fT", amount / 1000000000000d);
            if (amount >= 1000000000L) return String.format(java.util.Locale.ROOT, "%.1fB", amount / 1000000000d);
            if (amount >= 1000000L) return String.format(java.util.Locale.ROOT, "%.1fM", amount / 1000000d);
            if (amount >= 10000L) return String.format(java.util.Locale.ROOT, "%.1fK", amount / 1000d);
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

        private void awardBone(int i) {
            if (exhibits[i]) {
                carriedValue = plus(carriedValue, (i + 1) * 18L);
                notice("FOSSILE DOPPIO: VALORE EXTRA NELLO ZAINO");
            } else {
                pieces[i]++;
                notice(pieces[i] >= NEED[i] ? SPECIES[i] + " PRONTO PER IL MUSEO!" :
                        "OSSO " + SPECIES[i] + ": " + pieces[i] + "/" + NEED[i]);
            }
            beep(ToneGenerator.TONE_PROP_ACK);
        }

        private void foundFossil() {
            int available = Math.min(SPECIES.length, 1 + (depth - 1) / 3);
            awardBone(rng.nextInt(Math.max(1, available)));
        }

        private long rewardFor(int tile, boolean manual) {
            double value = (3d + depth * 2d) * Math.pow(1.12, Math.min(100, depth - 1));
            if (tile == ROCK) value *= 1.4;
            if (tile == CHEST) value *= 3.5;
            if (tile == CRYSTAL) value *= 2.8;
            if (tile == BONE) value *= 1.2;
            value *= amberBoost();
            if (manual) value *= 1d + Math.min(8, combo) * 0.04d;
            return safe(value);
        }

        private void hit(int r, int c, boolean manual) {
            if (r < 0 || c < 0 || r >= N || c >= N || hp[r][c] == 0) return;
            if (full()) {
                if (manual) notice("ZAINO PIENO! VENDI I REPERTI ALLA BASE");
                return;
            }
            hp[r][c] = Math.max(0, hp[r][c] - (manual ? pick : Math.max(1, (pick + 1) / 2)));
            if (manual) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            int index = particleIndex++ % particlesX.length;
            particlesX[index] = GX + c * CELL + 51;
            particlesY[index] = GY + r * CELL + 51;
            particlesUntil[index] = SystemClock.uptimeMillis() + 400;
            if (hp[r][c] > 0) return;
            dug++;
            if (manual) {
                long now = SystemClock.uptimeMillis();
                combo = now - lastDigAt < 1800 ? Math.min(12, combo + 1) : 1;
                lastDigAt = now;
            }
            pack++;
            carriedValue = plus(carriedValue, rewardFor(type[r][c], manual));
            if (type[r][c] == BONE) foundFossil();
            if (remaining() == 0) {
                depth++;
                carriedValue = plus(carriedValue, safe((25 + depth * 8d) * amberBoost()));
                nextLayer();
                notice("NUOVO STRATO! PROFONDITA' " + depth);
                beep(ToneGenerator.TONE_PROP_BEEP);
            }
            if (full()) {
                notice("ZAINO PIENO! TORNA ALLA BASE");
                beep(ToneGenerator.TONE_PROP_BEEP2);
            }
            if (dug % 7 == 0 || full()) save();
        }

        private long price(int upgrade) {
            int tier = upgrade == 0 ? pick - 1 : upgrade == 1 ? drill : upgrade == 2 ? radar : cart;
            double base = upgrade == 0 ? 75d : upgrade == 1 ? 480d : upgrade == 2 ? 280d : 165d;
            double growth = upgrade == 0 ? 1.82d : upgrade == 1 ? 1.95d : upgrade == 2 ? 1.93d : 1.85d;
            return safe(base * Math.pow(growth, Math.max(0, tier)));
        }

        private void upgrade(int i) {
            if (i == 2 && radar >= 6) { notice("RADAR AL MASSIMO"); return; }
            if (i == 3 && cart >= 22) { notice("ZAINO AL MASSIMO"); return; }
            long cost = price(i);
            if (coins < cost) { notice("MONETE INSUFFICIENTI"); return; }
            coins -= cost;
            if (i == 0) pick++;
            if (i == 1) drill++;
            if (i == 2) radar++;
            if (i == 3) cart++;
            notice(i == 0 ? "PICCONE PIU' FORTE!" : i == 1 ? "TRIVELLA MIGLIORATA!" :
                    i == 2 ? "RADAR PIU' PRECISO!" : "ZAINO AMPLIATO!");
            beep(ToneGenerator.TONE_PROP_ACK);
            save();
        }

        private long museumPrice(int i) {
            return safe(RENT[i] * 115d * Math.pow(1.75, Math.max(0, exhibitLevel[i] - 1)));
        }

        private void assemble(int i) {
            if (exhibits[i]) {
                long cost = museumPrice(i);
                if (coins < cost) { notice("MONETE INSUFFICIENTI"); return; }
                coins -= cost;
                exhibitLevel[i]++;
                notice(SPECIES[i] + " MIGLIORATO! +" + money(rentPerSecond()) + "/SEC TOTALI");
            } else {
                if (pieces[i] < NEED[i]) { notice("SERVONO ALTRI FOSSILI"); return; }
                if (coins < ASSEMBLE[i]) { notice("MONETE INSUFFICIENTI"); return; }
                coins -= ASSEMBLE[i];
                exhibits[i] = true;
                exhibitLevel[i] = 1;
                notice(SPECIES[i] + " ESPOSTO! ENTRATE AUTOMATICHE");
            }
            beep(ToneGenerator.TONE_PROP_BEEP);
            save();
        }

        private void sell() {
            if (pack == 0) { notice("LO ZAINO E' VUOTO! VAI A SCAVARE"); page = 0; return; }
            long value = carriedValue;
            coins = plus(coins, value);
            lifetimeSales = plus(lifetimeSales, value);
            pack = 0; carriedValue = 0;
            combo = 0; autoTimer = 0;
            page = 0;
            notice("VENDUTO! +" + money(value) + " MONETE");
            beep(ToneGenerator.TONE_PROP_ACK);
            save();
        }

        private void ascend() {
            if (depth < 12) { notice("RAGGIUNGI LO STRATO 12 PER RINASCERE"); return; }
            long now = SystemClock.uptimeMillis();
            if (now > prestigeConfirmUntil) {
                prestigeConfirmUntil = now + 5000;
                notice("ATTENZIONE: TOCCA ANCORA PER AZZERARE LA SPEDIZIONE");
                return;
            }
            int gained = Math.max(1, 1 + (depth - 12) / 4);
            amber = Math.min(100000, amber + gained);
            coins = 30; dug = 0; depth = pick = 1;
            drill = radar = cart = pack = 0;
            carriedValue = 0; combo = 0; autoTimer = rentTimer = 0;
            for (int i = 0; i < SPECIES.length; i++) {
                pieces[i] = 0; exhibits[i] = false; exhibitLevel[i] = 0;
            }
            prestigeConfirmUntil = 0;
            nextLayer();
            page = 0;
            notice("RINASCITA! +" + gained + " AMBRA, BONUS PERMANENTE");
            beep(ToneGenerator.TONE_PROP_BEEP);
            save();
        }

        private void tick(float dt) {
            rentTimer += dt;
            if (rentTimer >= 1) {
                int seconds = (int) rentTimer;
                rentTimer -= seconds;
                if (rentPerSecond() > 0) coins = plus(coins, safe(seconds * (double)rentPerSecond()));
            }
            if (drill <= 0 || full()) return;
            autoTimer += dt;
            double interval = Math.max(0.7, 4.0 * Math.pow(0.87, drill - 1));
            if (autoTimer < interval) return;
            autoTimer -= interval;
            int index = rng.nextInt(N * N);
            if (hp[index / N][index % N] > 0) {
                hit(index / N, index % N, false);
                return;
            }
            for (int r = 0; r < N; r++) for (int c = 0; c < N; c++) {
                if (hp[r][c] > 0) { hit(r, c, false); return; }
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
            text(c, "+" + money(rentPerSecond()) + "/SEC", 786, 223, 30, GOLD, false);
            if (page == 0) drawDig(c);
            if (page == 1) drawBase(c);
            if (page == 2) drawShop(c);
            if (page == 3) drawMuseum(c);
            rectangle(c, 0, 1728, W, H, DARK);
            String[] names = {"SCAVA", "BASE", "UPGRADE", "MUSEO"};
            for (int i = 0; i < 4; i++) {
                int x = 24 + i * 264;
                rounded(c, x, 1757, x + 240, 1879, 18,
                        i == page ? GOLD : Color.rgb(45, 86, 83));
                text(c, names[i], x + 120, 1832, 35, i == page ? DARK : CREAM, true);
            }
            if (now < bannerUntil) {
                rounded(c, 75, 1634, 1005, 1708, 18, DARK);
                text(c, banner, 540, 1683, 30, GOLD, true);
            }
            for (int i = 0; i < particlesX.length; i++) {
                if (particlesUntil[i] > now) {
                    float age = 1f - (particlesUntil[i] - now) / 400f;
                    int alpha = (int) (220f * (1f - age));
                    float px = particlesX[i] + (i % 2 == 0 ? -1 : 1) * age * 60f;
                    float py = particlesY[i] - age * age * 92f;
                    rectangle(c, px - 9, py - 9, px + 9, py + 9,
                            Color.argb(Math.max(0, Math.min(255, alpha)), 255, 207, 103));
                }
            }
            c.restore();
            postInvalidateDelayed(40);
        }

        private void drawLandscape(Canvas c) {
            int b = biome();
            int[] sky = {0xFF9EE6DB, 0xFFF7B37B, 0xFFA6DAC5, 0xFFC4EAF7, 0xFFF48E7B};
            int[] ridge = {0xFFD3AF73, 0xFFB86452, 0xFF5D9E74, 0xFF86AEC5, 0xFF773D50};
            rectangle(c, 0, 288, W, 372, sky[b]);
            rectangle(c, 0, 355, 265, 371, ridge[b]);
            rectangle(c, 750, 344, 1080, 371, ridge[b]);
            rectangle(c, 65, 330, 115, 353, ridge[b]);
            rectangle(c, 135, 316, 164, 352, ridge[b]);
            rectangle(c, 926, 315, 957, 345, ridge[b]);
            if (b == 2) {
                rectangle(c, 196, 319, 210, 361, DARK);
                rectangle(c, 175, 304, 237, 330, Color.rgb(38, 126, 82));
            } else if (b == 3) {
                rectangle(c, 205, 299, 265, 312, CREAM);
                rectangle(c, 270, 306, 320, 317, CREAM);
            } else {
                rectangle(c, 201, 307, 239, 342, GOLD);
            }
            text(c, BIOMES[b] + "  •  STRATO " + depth, 540, 341, 35, DARK, true);
        }

        private void drawBase(Canvas c) {
            rectangle(c, 0, 288, W, 1695, Color.rgb(230, 230, 206));
            text(c, "CAMPO BASE", 540, 360, 64, DARK, true);
            rounded(c, 58, 400, 1022, 790, 32, DARK);
            rounded(c, 77, 418, 1003, 774, 24, Color.rgb(45, 91, 86));
            text(c, "IL TUO ZAINO", 110, 484, 44, CREAM, false);
            text(c, pack + " / " + capacity() + " REPERTI", 110, 551, 61, GOLD, false);
            text(c, "VALORE ALLA VENDITA", 110, 635, 34, CREAM, false);
            text(c, money(carriedValue) + " MONETE", 112, 706, 59, GOLD, false);
            rounded(c, 95, 736, 984, 760, 9, Color.rgb(93, 125, 114));
            if (pack > 0) rounded(c, 98, 739, 98 + (float)pack / capacity() * 884, 757, 8, ORANGE);
            rounded(c, 85, 829, 995, 1018, 27, pack > 0 ? GOLD : Color.rgb(154, 158, 142));
            text(c, pack > 0 ? "VENDI IL CARICO" : "ZAINO VUOTO", 540, 902, 57, DARK, true);
            text(c, pack > 0 ? "+" + money(carriedValue) + " MONETE" : "TORNA A SCAVARE", 540, 963, 41, DARK, true);
            rounded(c, 59, 1060, 1021, 1295, 25, Color.rgb(202, 218, 197));
            text(c, "COME SI AVANZA", 93, 1121, 41, DARK, false);
            text(c, "1. SCAVA FINO A RIEMPIRE LO ZAINO", 93, 1177, 34, DARK, false);
            text(c, "2. VENDI, POTENZIA E TORNA PIU' IN PROFONDITA'", 93, 1230, 28, DARK, false);
            rounded(c, 59, 1330, 1021, 1615, 25, DARK);
            text(c, "AMBRA PERMANENTE: " + amber, 92, 1405, 43, GOLD, false);
            text(c, "OGNI AMBRA = +30% VALORE E VISITATORI", 92, 1460, 29, CREAM, false);
            boolean unlock = depth >= 12;
            rounded(c, 93, 1490, 985, 1585, 18, unlock ? ORANGE : Color.rgb(92, 114, 110));
            text(c, unlock ? "RINASCI (AZZERA LA SPEDIZIONE)" :
                    "RINASCITA DISPONIBILE ALLO STRATO 12", 540, 1551,
                    unlock ? 37 : 31, unlock ? DARK : CREAM, true);
        }

        private void drawVisitors(Canvas c, int exhibit, int top) {
            for (int j = 0; j < 3; j++) {
                float bob = (float) Math.sin(frameTime * 0.003 + j + exhibit) * 4f;
                int x = 712 + j * 72, y = top + 140 + (int) bob;
                int jacket = (j + exhibit) % 2 == 0 ? ORANGE : TEAL;
                rectangle(c, x - 11, y - 30, x + 12, y - 7, CREAM);
                rectangle(c, x - 16, y - 5, x + 17, y + 34, jacket);
                rectangle(c, x - 13, y + 34, x - 2, y + 52, DARK);
                rectangle(c, x + 4, y + 34, x + 15, y + 52, DARK);
            }
        }

        private void drawDig(Canvas c) {
            drawLandscape(c);
            rectangle(c, 53, 369, 1026, 1302, Color.rgb(45, 51, 52));
            for (int row = 0; row < N; row++) for (int col = 0; col < N; col++)
                drawCell(c, row, col);
            rounded(c, 67, 1352, 1014, 1550, 28, DARK);
            text(c, "ZAINO: " + pack + "/" + capacity(), 102, 1418, 43, full() ? ORANGE : CREAM, false);
            if (combo > 1 && SystemClock.uptimeMillis() - lastDigAt < 1900)
                text(c, "COMBO +" + Math.min(32, combo * 4) + "%",
                        891, 1418, 30, ORANGE, true);
            text(c, "REPERTI " + money(carriedValue), 103, 1495, 41, GOLD, false);
            text(c, remaining() + "/81", 912, 1495, 38, CREAM, true);
            rounded(c, 78, 1581, 1002, 1619, 8, Color.rgb(196, 213, 181));
            if (pack > 0) rounded(c, 80, 1583, 80 + 920f * pack / capacity(), 1617, 8, TEAL);
            if (full()) {
                rectangle(c, 55, 500, 1025, 1200, Color.argb(225, 20, 46, 46));
                rounded(c, 103, 700, 977, 1039, 28, DARK);
                text(c, "ZAINO PIENO!", 540, 791, 70, ORANGE, true);
                text(c, "NON PUOI SCAVARE ALTRO", 540, 856, 41, CREAM, true);
                rounded(c, 178, 896, 901, 1000, 16, GOLD);
                text(c, "VAI ALLA BASE", 540, 965, 48, DARK, true);
            }
        }

        private void drawCell(Canvas c, int row, int col) {
            int x = GX + col * CELL, y = GY + row * CELL;
            if (hp[row][col] <= 0) {
                rectangle(c, x + 2, y + 2, x + CELL - 2, y + CELL - 2,
                        Color.rgb(47, 43 + row % 3 * 3, 48));
                rectangle(c, x + 9, y + 9, x + 22, y + 17, Color.rgb(76, 64, 61));
                return;
            }
            int[][] palettes = {{205, 161, 100}, {195, 114, 83},
                    {125, 155, 99}, {170, 199, 213}, {112, 78, 91}};
            int[] shade = palettes[biome()];
            int fade = difficulty() * 4 + row * 2;
            int soil = Color.rgb(Math.max(50, shade[0] - fade),
                    Math.max(48, shade[1] - fade), Math.max(42, shade[2] - fade));
            int stone = biome() == 3 ? Color.rgb(110, 150, 167) :
                    biome() == 4 ? Color.rgb(84, 69, 88) : Color.rgb(124, 123, 114);
            rectangle(c, x + 2, y + 2, x + CELL - 2, y + CELL - 2,
                    type[row][col] == ROCK ? stone : soil);
            rectangle(c, x + 10, y + 12, x + 32, y + 22, Color.argb(100, 95, 80, 65));
            rectangle(c, x + 69, y + 56, x + 82, y + 65, Color.argb(100, 95, 80, 65));
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
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(5);
                p.setColor(Color.argb(165, 46, 41, 40));
                c.drawLine(x + 28, y + 20, x + 49, y + 51, p);
                c.drawLine(x + 49, y + 51, x + 66, y + 66, p);
                p.setStyle(Paint.Style.FILL);
                rectangle(c, x + 11, y + CELL - 16, x + 91, y + CELL - 9, DARK);
                rectangle(c, x + 11, y + CELL - 16,
                        x + 11 + 80f * hp[row][col] / max[row][col],
                        y + CELL - 9, ORANGE);
            }
        }

        private void drawShop(Canvas c) {
            text(c, "POTENZIAMENTI", 540, 356, 62, DARK, true);
            String[] names = {"PICCONE", "TRIVELLA AUTOMATICA", "RADAR FOSSILI", "ZAINO PIU' GRANDE"};
            String[] desc = {"Rompi piu' velocemente i blocchi", "Scava da sola, ma si ferma a zaino pieno",
                    "Piu' ossa negli strati", "Fai spedizioni piu' lunghe"};
            String[] effect = {"FORZA " + pick + " > " + (pick + 1),
                    "AUTO LIV. " + drill + " > " + (drill + 1),
                    "CHANCE OSSA +" + radar * 2 + "%",
                    "CAPACITA' " + capacity() + " > " + Math.min(160, 19 + cart * 7)};
            int[] levels = {pick, drill, radar, cart};
            for (int i = 0; i < 4; i++) {
                int y = 400 + i * 300;
                rounded(c, 60, y, 1020, y + 257, 27, DARK);
                rounded(c, 77, y + 15, 1003, y + 244, 20, Color.rgb(42, 83, 79));
                rounded(c, 110, y + 55, 247, y + 189, 12, i % 2 == 0 ? GOLD : TEAL);
                icon(c, i, 178, y + 126);
                text(c, names[i], 281, y + 83, 38, CREAM, false);
                text(c, desc[i], 281, y + 126, 25, Color.rgb(161, 213, 201), false);
                text(c, "LIV. " + levels[i] + "  " + effect[i], 281, y + 166, 27, GOLD, false);
                boolean maxed = (i == 2 && radar >= 6) || (i == 3 && cart >= 22);
                boolean enough = !maxed && coins >= price(i);
                rounded(c, 671, y + 178, 968, y + 230, 15,
                        enough ? GOLD : Color.rgb(104, 119, 113));
                text(c, maxed ? "MAX" : "COMPRA " + money(price(i)), 819, y + 214, 29,
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
                if (exhibits[i]) {
                    long income = safe(RENT[i] * Math.pow(1.7, exhibitLevel[i] - 1) * amberBoost());
                    text(c, "LIVELLO " + exhibitLevel[i] + "  +" + money(income) + "/SEC", 313, y + 113, 31, GOLD, false);
                    text(c, "PIU' VISITATORI = PIU' INCASSI", 312, y + 159, 26, CREAM, false);
                    boolean enough = coins >= museumPrice(i);
                    rounded(c, 657, y + 143, 979, y + 195, 12,
                            enough ? GOLD : Color.rgb(112, 124, 120));
                    text(c, "MIGLIORA " + money(museumPrice(i)), 818, y + 179, 28,
                            enough ? DARK : CREAM, true);
                } else {
                    text(c, pieces[i] + "/" + NEED[i] + " OSSA", 313, y + 113, 31,
                            Color.rgb(175, 226, 206), false);
                    boolean enough = pieces[i] >= NEED[i] && coins >= ASSEMBLE[i];
                    rounded(c, 657, y + 143, 979, y + 195, 12,
                            enough ? GOLD : Color.rgb(112, 124, 120));
                    text(c, "ASSEMBLA " + money(ASSEMBLE[i]), 818, y + 179, 29,
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
                page = Math.min(3, Math.max(0, (int)(x / 270f)));
                notice(page == 0 ? "SCAVA FINO A RIEMPIRE LO ZAINO" :
                        page == 1 ? "VENDI I REPERTI AL CAMPO BASE" :
                        page == 2 ? "INVESTI IN ATTREZZATURA" : "ESPONI FOSSILI E GUADAGNA OFFLINE");
                invalidate();
                return true;
            }
            if (page == 0 && full() && action == MotionEvent.ACTION_DOWN &&
                    y >= 650 && y <= 1100) {
                page = 1;
                notice("IL CAMPO BASE COMPRA I TUOI REPERTI");
                invalidate();
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
                if (y >= 829 && y <= 1018) sell();
                else if (y >= 1490 && y <= 1585) ascend();
            } else if (action == MotionEvent.ACTION_DOWN && page == 2) {
                for (int i = 0; i < 4; i++) {
                    int top = 400 + i * 300;
                    if (y >= top && y < top + 257) { upgrade(i); break; }
                }
            } else if (action == MotionEvent.ACTION_DOWN && page == 3) {
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
