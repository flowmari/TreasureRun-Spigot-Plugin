package plugin;

import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * TreasureRun BGM（統合版）
 *
 * • /start〜開始30秒：DnB（テンション上げる）
 * • 探索のメイン：DnB（音量控えめ + ハット間引き版）
 * • 宝箱開けた瞬間：sparkle強調（単発）※今鳴ってるBGMに重ねる
 * • 終了〜ランキング演出：West Coast（HIPHOP/ブーンバップ）
 *
 * ✅ プレイヤーごとに1タスク
 * ✅ フェーズ切り替え（INTRO→EXPLORE→END）
 *
 * ✅ END（HIPHOP）は「厳密に97 BPM（長時間平均で完全一致）」に固定：
 *    Spigotは1tick=1/20秒の整数なので、各ステップを3tick/4tickで割り当てる。
 *    97BPMに必要な「合計tick」を、ループ全体でピッタリ一致させる（誤差ゼロ）。
 *
 *    - 1分=1200tick
 *    - 97BPM → 1分で97拍
 *    - 1拍=16分×4 → 1分で388ステップ
 *    - 1200tickを388ステップに割り当てるため、基本3tick + 36ステップだけ4tick（合計ぴったり1200）
 *    - これを4倍して「1552ステップ=97小節」のループにすると、ループ全体が厳密に240秒になり、97BPM固定になる
 */
public class StartThemePlayer {
  private final JavaPlugin plugin;

  // プレイヤーごとに1セッション
  private final Map<UUID, Session> running = new HashMap<>();

  // =========================
  // フェーズ設定
  // =========================
  private static final int INTRO_SECONDS = 30;
  private static final int INTRO_TICKS = 20 * INTRO_SECONDS;

  private enum Phase { INTRO_DNB, EXPLORE_DNB, END_WESTCOAST }

  // =========================
  // DnB 音量ノブ（MUSICカテゴリ）
  // =========================
  private final float dnbIntroDrumVol = 0.95f;
  private final float dnbIntroBassVol = 1.10f;
  private final float dnbIntroSubVol  = 0.95f;
  private final float dnbIntroBiteVol = 0.55f;
  private final float dnbIntroPadVol  = 0.28f;

  // 探索メイン（控えめ）
  private final float dnbExploreDrumVol = 0.78f;
  private final float dnbExploreBassVol = 1.00f;
  private final float dnbExploreSubVol  = 0.88f;
  private final float dnbExploreBiteVol = 0.45f;
  private final float dnbExplorePadVol  = 0.22f;

  // =========================
  // West Coast 音量ノブ
  // =========================
  private final float westDrumVol = 0.95f;
  private final float westBassVol = 1.00f;
  private final float westPadVol  = 0.28f;
  private final float westSubVol  = 0.90f;

  // =========================
  // sparkle（宝箱っぽい高音キラキラ）
  // =========================
  private final float sparkleVol   = 0.65f; // 宝箱単発は強め＆少し明るめ
  private final float sparklePitch = 1.30f;

  // West Coast側の“時々sparkle”
  private final float westSparkleVol   = 0.42f;
  private final float westSparklePitch = 1.18f;
  private static final Sound WEST_SPARKLE_SOUND = Sound.BLOCK_AMETHYST_BLOCK_CHIME;

  public StartThemePlayer(JavaPlugin plugin) {
    this.plugin = plugin;
  }

  // ==========================================================
  // 外部から呼ぶAPI
  // ==========================================================

  /** 互換用：旧呼び出し startThemePlayer.play(player) を生かす */
  public void play(Player p) {
    startGameBgm(p);
  }

  /** /start〜開始30秒：DnB → 探索：DnB弱め（ハット間引き）へ自動遷移 */
  public void startGameBgm(Player p) {
    if (p == null || !p.isOnline()) return;

    stop(p);

    Session s = new Session();
    s.playerId = p.getUniqueId();
    s.phase = Phase.INTRO_DNB;
    s.phaseTicks = 0;

    s.frames = buildDnB2StepAmenChopLowWithSparkle(false); // intro: 通常
    s.idx = 0;
    s.wait = 0;

    s.task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> tick(p), 0L, 1L);
    running.put(p.getUniqueId(), s);
  }

  /** 宝箱を開けた瞬間に「キラキラ」を単発で鳴らす（今のBGMに重ねる） */
  public void playTreasureSparkle(Player p) {
    if (p == null || !p.isOnline()) return;
    p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.MUSIC, sparkleVol, sparklePitch);
  }

  /** 終了〜ランキング演出：West Coast（HIPHOP/ブーンバップ）に切り替える */
  public void switchToRankingBgm(Player p) {
    if (p == null) return;
    Session s = running.get(p.getUniqueId());
    if (s == null) return;

    s.phase = Phase.END_WESTCOAST;
    s.phaseTicks = 0;

    // ✅ ここが差分：パターン印象は保ちつつ「厳密97BPM」へ
    s.frames = buildHipHopBoomBapExact97BpmWithSparkle();

    s.idx = 0;
    s.wait = 0;
  }

  public void stop(Player p) {
    if (p == null) return;
    Session s = running.remove(p.getUniqueId());
    if (s != null && s.task != null) s.task.cancel();
  }

  public void stopAll() {
    for (Session s : running.values()) {
      if (s != null && s.task != null) s.task.cancel();
    }
    running.clear();
  }

  // ==========================================================
  // ループ実行（共通）
  // ==========================================================
  private void tick(Player p) {
    Session s = running.get(p.getUniqueId());
    if (s == null) return;

    try {
      if (!p.isOnline()) { stop(p); return; }

      s.phaseTicks++;

      // INTRO → EXPLORE（開始30秒）
      if (s.phase == Phase.INTRO_DNB && s.phaseTicks >= INTRO_TICKS) {
        s.phase = Phase.EXPLORE_DNB;
        s.phaseTicks = 0;
        s.frames = buildDnB2StepAmenChopLowWithSparkle(true); // explore: ハット間引き
        s.idx = 0;
        s.wait = 0;
      }

      if (s.wait > 0) { s.wait--; return; }

      if (s.idx >= s.frames.size()) s.idx = 0;
      Frame f = s.frames.get(s.idx++);

      Vol v = volumesFor(s.phase);

      // =========================
      // DRUMS
      // =========================
      if (f.kick) {
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASEDRUM, SoundCategory.MUSIC, v.drumVol, 0.82f);
      }

      if (f.ghostSnare) {
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_SNARE, SoundCategory.MUSIC, v.drumVol * 0.35f, 1.12f);
      }

      if (f.snare) {
        float snarePitch = (s.phase == Phase.END_WESTCOAST) ? 1.04f : 1.08f;
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_SNARE, SoundCategory.MUSIC, v.drumVol * 0.92f, snarePitch);
      }

      if (f.hat) {
        if (s.phase == Phase.END_WESTCOAST) {
          // West Coast：openHatで“抜け”
          float hatPitch = f.openHat ? 0.92f : 1.55f;
          float hatVol = f.openHat ? (v.drumVol * 0.55f) : (v.drumVol * (f.hatAccent ? 0.50f : 0.36f));
          p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, SoundCategory.MUSIC, hatVol, hatPitch);
        } else {
          // DnB：アクセントで切り刻み + 微揺れ
          float vol = f.hatAccent ? v.drumVol * 0.48f : v.drumVol * 0.26f;
          float pit = f.hatAccent ? 1.70f : 1.55f;
          if (f.hatAlt) pit *= 0.97f;
          p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, SoundCategory.MUSIC, vol, pit);
        }
      }

      // =========================
      // sparkle（フレーム内の“時々”）
      // =========================
      if (f.sparkle) {
        if (s.phase == Phase.END_WESTCOAST) {
          p.playSound(p.getLocation(), WEST_SPARKLE_SOUND, SoundCategory.MUSIC, westSparkleVol, westSparklePitch);
        } else {
          // DnB内の時々sparkle（控えめ）
          p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.MUSIC, sparkleVol * 0.55f, sparklePitch);
        }
      }

      // =========================
      // BASS
      // =========================
      if (f.bassSemi != null) {
        float pitch = clampPitch(semitoneToPitch(f.bassSemi));
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, SoundCategory.MUSIC, v.bassVol, pitch);

        if (s.phase == Phase.END_WESTCOAST) {
          // West Coast：BASS + DIDGERIDOO
          p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO, SoundCategory.MUSIC, v.subVol, 0.50f);
        } else {
          // DnB：BASS + DIDGERIDOO + BIT
          p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO, SoundCategory.MUSIC, v.subVol, 0.50f);
          p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, SoundCategory.MUSIC, v.biteVol, clampPitch(pitch));
        }
      }

      // =========================
      // PAD
      // =========================
      if (f.padSemi != null) {
        float pitch = clampPitch(semitoneToPitch(f.padSemi));
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, SoundCategory.MUSIC, v.padVol, pitch);
      }

      s.wait = Math.max(1, f.durationTicks);

    } catch (Throwable t) {
      plugin.getLogger().severe("[StartTheme] runtime error: " + t.getMessage());
      t.printStackTrace();
      stop(p);
    }
  }

  private Vol volumesFor(Phase phase) {
    Vol v = new Vol();
    switch (phase) {
      case INTRO_DNB -> {
        v.drumVol = dnbIntroDrumVol;
        v.bassVol = dnbIntroBassVol;
        v.subVol  = dnbIntroSubVol;
        v.biteVol = dnbIntroBiteVol;
        v.padVol  = dnbIntroPadVol;
      }
      case EXPLORE_DNB -> {
        v.drumVol = dnbExploreDrumVol;
        v.bassVol = dnbExploreBassVol;
        v.subVol  = dnbExploreSubVol;
        v.biteVol = dnbExploreBiteVol;
        v.padVol  = dnbExplorePadVol;
      }
      case END_WESTCOAST -> {
        v.drumVol = westDrumVol;
        v.bassVol = westBassVol;
        v.subVol  = westSubVol;
        v.biteVol = 0.0f;
        v.padVol  = westPadVol;
      }
    }
    return v;
  }

  // ==========================================================
  // DnB + sparkle（そのまま）
  // ==========================================================
  private static List<Frame> buildDnB2StepAmenChopLowWithSparkle(boolean thinHats) {
    List<Frame> out = new ArrayList<>();

    final int STEP = 1;
    final int LEN  = 64;

    Integer[] bass = new Integer[LEN];
    Arrays.fill(bass, null);

    for (int i = 0; i < 16; i++) bass[i]      = (i == 0 || i == 8)  ? -12 : null; // E
    for (int i = 16; i < 32; i++) bass[i]     = (i == 16 || i == 24) ? -10 : null; // G
    for (int i = 32; i < 48; i++) bass[i]     = (i == 32 || i == 40) ? -14 : null; // D
    for (int i = 48; i < 64; i++) bass[i]     = (i == 48 || i == 56) ? -15 : null; // C

    Integer[] pad = new Integer[LEN];
    Arrays.fill(pad, null);
    pad[0]  = -7;
    pad[16] = -7;
    pad[32] = -8;
    pad[48] = -10;

    boolean[] kick = new boolean[LEN];
    boolean[] snare = new boolean[LEN];
    boolean[] ghostSnare = new boolean[LEN];
    boolean[] hat = new boolean[LEN];
    boolean[] hatAccent = new boolean[LEN];
    boolean[] hatAlt = new boolean[LEN];
    boolean[] sparkle = new boolean[LEN];

    for (int i = 0; i < LEN; i++) {
      hat[i] = thinHats ? (i % 2 == 0) : true;
      hatAlt[i] = (i % 4 == 1);
    }

    for (int i = 0; i < LEN; i++) {
      int pos = i % 16;
      snare[i] = (pos == 4 || pos == 12);
      ghostSnare[i] = (pos == 3 || pos == 11);

      int bar = (i / 16) % 4;
      if (bar == 1 && pos == 14) ghostSnare[i] = true;
      if (bar == 3 && pos == 1)  ghostSnare[i] = true;
    }

    for (int i = 0; i < LEN; i++) {
      int pos = i % 16;
      int bar = (i / 16) % 4;

      boolean base =
          (pos == 0) ||
              (pos == 3) ||
              (pos == 10) ||
              (pos == 15);

      if (bar == 1) base = base || (pos == 6);
      if (bar == 2) base = base || (pos == 8);
      if (bar == 3) base = base || (pos == 1);

      if (pos == 4 || pos == 12) base = false;
      kick[i] = base;
    }

    for (int i = 0; i < LEN; i++) {
      int pos = i % 16;
      int bar = (i / 16) % 4;

      boolean acc =
          (pos == 0) || (pos == 2) || (pos == 3) ||
              (pos == 5) || (pos == 6) || (pos == 7) ||
              (pos == 8) || (pos == 10) ||
              (pos == 14) || (pos == 15);

      if (bar == 1) acc = acc || (pos == 12);
      if (bar == 2) acc = acc && (pos != 6);
      if (bar == 3) acc = acc || (pos == 1);

      if (thinHats) {
        if (pos == 2 || pos == 7 || pos == 14) acc = false;
      }

      hatAccent[i] = acc;
    }

    Random rng = new Random(20251210L);
    for (int i = 0; i < LEN; i++) {
      int pos = i % 16;
      int bar = (i / 16) % 4;

      if (pos == 4 || pos == 12) continue;
      if (pos == 3 || pos == 11) continue;

      boolean planned =
          (bar == 0 && (pos == 14)) ||
              (bar == 1 && (pos == 7))  ||
              (bar == 2 && (pos == 15)) ||
              (bar == 3 && (pos == 9));

      int rate = thinHats ? 3 : 6;
      boolean randomHit = hatAccent[i] && (rng.nextInt(100) < rate);

      sparkle[i] = planned || randomHit;
    }

    for (int i = 0; i < LEN; i++) {
      Frame f = new Frame();
      f.durationTicks = STEP;

      f.bassSemi = bass[i];
      f.padSemi  = pad[i];

      f.kick = kick[i];
      f.snare = snare[i];
      f.ghostSnare = ghostSnare[i];

      f.hat = hat[i];
      f.hatAccent = hatAccent[i];
      f.hatAlt = hatAlt[i];

      f.sparkle = sparkle[i];

      f.openHat = false;

      out.add(f);
    }

    return out;
  }

  // ==========================================================
  // West Coast（印象はそのまま） + 厳密97BPM
  //
  // 16ステップ = 1小節（4/4）
  // 各ステップのdurationTicksを 3 or 4 に割り当てて、ループ全体の合計tickが厳密に97BPMになるようにする。
  //
  // ループ長：97小節（=1552ステップ）
  // 合計tick：240秒=4800tick（厳密）
  // ==========================================================
  private static List<Frame> buildHipHopBoomBapExact97BpmWithSparkle() {
    List<Frame> out = new ArrayList<>();
    Random rng = new Random(19961101L);

    // ===== 厳密97BPM用の「3/4tick割り当て」 =====
    // 1552ステップのうち、4tickにする回数は144回（+144tick）にすると合計4800tickになる
    final int TOTAL_BARS = 97;
    final int STEPS_PER_BAR = 16;
    final int TOTAL_STEPS = TOTAL_BARS * STEPS_PER_BAR; // 1552

    final int BASE_TICKS = 3;           // 基本は3tick
    final int EXTRA_FOUR_TICKS = 144;   // 4tickにするステップ数（合計tickを厳密一致させる）

    int err = 0; // 誤差拡散（均等に4tickを散らす）

    for (int bar = 0; bar < TOTAL_BARS; bar++) {
      // あなたの元の「2小節バリエ」を保つため、barParityで分岐
      int barParity = bar % 2;

      Integer bassRoot = (barParity == 0) ? -12 : -10;
      Integer padRoot  = (barParity == 0) ? -7  : -5;

      for (int i = 0; i < STEPS_PER_BAR; i++) {
        Frame f = new Frame();

        // ===== durationTicks（厳密97BPM）=====
        // 3tickが基本、EXTRA_FOUR_TICKS回だけ4tickを混ぜる（合計tickが必ず一致）
        err += EXTRA_FOUR_TICKS;
        int dur = BASE_TICKS;
        if (err >= TOTAL_STEPS) {
          dur = BASE_TICKS + 1; // 4tick
          err -= TOTAL_STEPS;
        }
        f.durationTicks = dur;

        // ===== ここから「元の印象（配置）」を維持 =====

        // スネア：2&4（i==4,12）
        f.snare = (i == 4 || i == 12);

        // ゴースト：スネア直前 + 終わり（i==3,11,15）
        f.ghostSnare = (i == 3 || i == 11 || i == 15);

        // キック：前ノリ多め（元の2小節分岐のまま）
        if (barParity == 0) {
          f.kick = (i == 0 || i == 6 || i == 7 || i == 8 || i == 14 || i == 15);
        } else {
          f.kick = (i == 0 || i == 5 || i == 7 || i == 8 || i == 13 || i == 15);
        }

        // ハット：跳ね（常時）/ アクセントは偶数ステップ
        f.hat = true;
        f.hatAccent = (i % 2 == 0);

        // openHat：抜け（i==6,14）
        f.openHat = (i == 6 || i == 14);

        // ベース：1拍目/3拍目（i==0,8）
        f.bassSemi = (i == 0 || i == 8) ? bassRoot : null;
        if (i == 7 && barParity == 1) f.bassSemi = bassRoot + 2;

        // パッド：小節頭だけ
        f.padSemi = (i == 0) ? padRoot : null;

        // sparkle：時々（安全地帯のみ）
        boolean safe = (i != 4 && i != 12 && i != 3 && i != 11);
        boolean planned = (barParity == 0 && (i == 10)) || (barParity == 1 && (i == 14));
        boolean randomHit = (safe && (i == 2 || i == 7 || i == 10 || i == 14 || i == 15) && (rng.nextInt(100) < 8));
        f.sparkle = safe && (planned || randomHit);

        // DnB専用
        f.hatAlt = false;

        out.add(f);
      }
    }

    // このoutは「97小節ループ」なので、ループ全体で厳密に97BPMになる
    return out;
  }

  // ==========================================================
  // utils / data
  // ==========================================================
  private static float semitoneToPitch(int semi) {
    return (float) Math.pow(2.0, semi / 12.0);
  }

  private static float clampPitch(float p) {
    if (p < 0.5f) return 0.5f;
    if (p > 2.0f) return 2.0f;
    return p;
  }

  private static class Frame {
    int durationTicks;

    Integer bassSemi;
    Integer padSemi;

    boolean kick;
    boolean snare;
    boolean ghostSnare;

    boolean hat;
    boolean hatAccent;

    // DnB用
    boolean hatAlt;

    // West用
    boolean openHat;

    boolean sparkle;
  }

  private static class Session {
    UUID playerId;
    BukkitTask task;

    Phase phase;
    int phaseTicks;

    List<Frame> frames;
    int idx;
    int wait;
  }

  private static class Vol {
    float drumVol;
    float bassVol;
    float subVol;
    float biteVol;
    float padVol;
  }
}