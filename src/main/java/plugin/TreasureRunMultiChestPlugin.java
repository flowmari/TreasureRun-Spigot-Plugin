package plugin;

import plugin.RealtimeRankTicker;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.sql.*;
import java.util.*;

public class TreasureRunMultiChestPlugin extends JavaPlugin implements Listener, TabExecutor {

  // ================================
  // DB接続
  // ================================
  private Connection connection;

  // ================================
  // ゲーム状態
  // ================================
  private boolean isRunning = false;
  private long startTime;
  private String difficulty = "Normal";
  private BossBar bossBar;
  private int taskId = -1;

  // ✅ 終了Title維持タスク（DJ終点で止める）
  private int finishTitleTaskId = -1;

  private TreasureChestManager treasureChestManager;
  private TreasureRunGameEffectsPlugin treasureRunGameEffectsPlugin;
  private GameStageManager gameStageManager;
  private Location currentStageCenter = null;

  private TreasureItemFactory itemFactory;
  private RankRewardManager rankRewardManager;

  private int easyTimeLimit;
  private int normalTimeLimit;
  private int hardTimeLimit;

  private double easyMultiplier;
  private double normalMultiplier;
  private double hardMultiplier;

  private int easyPenalty;
  private int normalPenalty;
  private int hardPenalty;

  private final Map<String, Integer> treasureChestCounts = new HashMap<>();
  private final List<Material> treasurePool = new ArrayList<>();
  private int chestSpawnRadius;

  private RealtimeRankTicker rankTicker;
  public boolean rankDirty = true;

  private int totalChestsRemaining = 0;

  private final Map<Material, String> materialJapaneseNames = new HashMap<>() {{
    put(Material.DIAMOND, "ダイヤモンド");
    put(Material.GOLD_INGOT, "金インゴット");
    put(Material.IRON_INGOT, "鉄インゴット");
    put(Material.EMERALD, "エメラルド");
    put(Material.APPLE, "リンゴ");
    put(Material.NETHERITE_INGOT, "ネザライトのインゴット");
    put(Material.LAPIS_LAZULI, "ラピスラズリ");
    put(Material.REDSTONE, "レッドストーン");
    put(Material.COAL, "石炭");
    put(Material.ENCHANTED_GOLDEN_APPLE, "エンチャント金リンゴ");
    put(Material.TNT, "TNT");
    put(Material.DIAMOND_BLOCK, "ダイヤモンドブロック");
    put(Material.GOLD_BLOCK, "金ブロック");
    put(Material.EMERALD_BLOCK, "エメラルドブロック");
    put(Material.IRON_BLOCK, "鉄ブロック");
  }};

  private final Map<UUID, Integer> playerScores = new HashMap<>();

  private final Map<UUID, Location> originalLocations = new HashMap<>();
  private long previousWorldTime = -1;
  private boolean previousStorm = false;
  private boolean previousThundering = false;

  // =======================================================
  // ✅ StartThemePlayer（ゲーム開始テーマ）
  // =======================================================
  private StartThemePlayer startThemePlayer;

  public StartThemePlayer getStartThemePlayer() {
    return startThemePlayer;
  }

  @Override
  public void onEnable() {
    getLogger().info("🌈 TreasureRunMultiChestPlugin: 起動 🌈");

    saveDefaultConfig();
    Bukkit.getPluginManager().registerEvents(this, this);

    // ✅ StartThemePlayer を生成して保持
    startThemePlayer = new StartThemePlayer(this);

    // ✅ ログアウトでBGM停止（事故防止）
    Bukkit.getPluginManager().registerEvents(new StartThemeStopListener(this), this);

    Bukkit.getPluginManager().registerEvents(new StageMobControlListener(this), this);

    TreasureRunGameEffectsPlugin effects = new TreasureRunGameEffectsPlugin(this);
    Bukkit.getPluginManager().registerEvents(effects, this);
    this.treasureRunGameEffectsPlugin = effects;

    if (getCommand("gameStart") != null) {
      getCommand("gameStart").setExecutor(this);
      getCommand("gameStart").setTabCompleter(this);
    }
    if (getCommand("gameRank") != null) {
      getCommand("gameRank").setExecutor(this);
    }
    if (getCommand("craftspecialemerald") != null) {
      getCommand("craftspecialemerald").setExecutor(new CraftSpecialEmeraldCommand());
    }
    if (getCommand("checktreasureemerald") != null) {
      getCommand("checktreasureemerald").setExecutor(new CheckTreasureEmeraldCommand(this));
    }
    if (getCommand("gameMenu") != null) {
      getCommand("gameMenu").setExecutor(this);
    }
    if (getCommand("clearStageBlocks") != null) {
      getCommand("clearStageBlocks").setExecutor(new StageCleanupCommand(this));
    }

    setupDatabase();
    loadConfigValues();

    this.treasureChestManager =
        new TreasureChestManager(this, treasureChestCounts, treasurePool, chestSpawnRadius);

    // ★追加：宝箱取得演出（開けた瞬間に、取得アイテムが宝箱から出る）
    Bukkit.getPluginManager().registerEvents(new TreasureChestPickupListener(this), this);

    this.itemFactory = new TreasureItemFactory(this);

    this.gameStageManager = new GameStageManager(this);
    Bukkit.getPluginManager().registerEvents(this.gameStageManager, this);
    getLogger().info("[TreasureRun] GameStageManager event registered!");

    this.rankRewardManager = new RankRewardManager(this);

    // =======================================================
    // ✅ 追加：debug=true のときだけ /rank コマンドを有効化（撮影・動作確認用）
    // - OP限定は RankDebugCommand 側でチェック
    // - debug=false のときはコマンドを登録しない（本番で事故りにくい）
    // =======================================================
    if (getConfig().getBoolean("debug")) {
      getLogger().warning("⚠ DEBUG MODE ON: /rank デバッグコマンドを有効化します");
      if (getCommand("rank") != null) {
        getCommand("rank").setExecutor(new RankDebugCommand(this));
      } else {
        getLogger().warning("⚠ /rank が plugin.yml に見つかりません（commands: rank を追加済みか確認してください）");
      }
    }
    // =======================================================

    CustomRecipeLoader recipeLoader = new CustomRecipeLoader(this);
    recipeLoader.registerRecipes();

    int rtInterval = getConfig().getInt("rankTicker.intervalSec", 10);
    int rtTopN = getConfig().getInt("rankTicker.topN", 10);
    int rtWidth = getConfig().getInt("rankTicker.tickerWidth", 32);
    rankTicker = new RealtimeRankTicker(this, rtInterval, rtTopN, rtWidth);
    rankTicker.start();

    getLogger().info("✅ TreasureRunMultiChestPlugin が正常に起動しました！");
  }

  @Override
  public void onDisable() {
    // ✅ 停止（鳴りっぱなし防止）
    if (startThemePlayer != null) startThemePlayer.stopAll();

    if (bossBar != null) bossBar.removeAll();

    if (finishTitleTaskId != -1) {
      Bukkit.getScheduler().cancelTask(finishTitleTaskId);
      finishTitleTaskId = -1;
    }

    try {
      if (connection != null) connection.close();
    } catch (SQLException e) {
      e.printStackTrace();
    }
    if (rankTicker != null) rankTicker.stop();
    if (treasureChestManager != null) treasureChestManager.removeAllChests();

    if (gameStageManager != null) {
      gameStageManager.clearDifficultyBlocks();
      gameStageManager.clearShopEntities();
    }

    if (taskId != -1) {
      Bukkit.getScheduler().cancelTask(taskId);
      taskId = -1;
    }

    getLogger().info("🔻 TreasureRunMultiChestPlugin: 無効化");
  }

  // ✅ 追加：ゲーム中にプレイヤーが抜けても残骸が残らないように掃除
  @EventHandler
  public void onPlayerQuit(PlayerQuitEvent event) {
    Player player = event.getPlayer();
    if (!isRunning) return;

    // ✅ 退出時も停止（事故防止）
    if (startThemePlayer != null) startThemePlayer.stop(player);

    if (taskId != -1) {
      Bukkit.getScheduler().cancelTask(taskId);
      taskId = -1;
    }

    if (finishTitleTaskId != -1) {
      Bukkit.getScheduler().cancelTask(finishTitleTaskId);
      finishTitleTaskId = -1;
    }

    if (bossBar != null) bossBar.removeAll();
    if (treasureChestManager != null) treasureChestManager.removeAllChests();

    if (gameStageManager != null) {
      gameStageManager.clearDifficultyBlocks();
      gameStageManager.clearShopEntities();
    }

    isRunning = false;
    playerScores.remove(player.getUniqueId());
    originalLocations.remove(player.getUniqueId());

    // ワールド状態だけ戻す（Quit中にテレポはしない）
    if (previousWorldTime >= 0) {
      World w = player.getWorld();
      w.setTime(previousWorldTime);
      w.setStorm(previousStorm);
      w.setThundering(previousThundering);
      previousWorldTime = -1;
    }
  }

  // =======================================================
  // MySQL 接続（自動再接続）
  // =======================================================
  public Connection getConnection() {
    try {
      if (connection == null || connection.isClosed() || !connection.isValid(1)) {
        getLogger().warning("⚠ MySQL 再接続を試みます…");
        reconnect();
      }
    } catch (SQLException e) {
      getLogger().warning("⚠ MySQL 接続チェック失敗: " + e.getMessage());
      reconnect();
    }
    return connection;
  }

  private void reconnect() {
    try {
      if (connection != null) {
        try { connection.close(); } catch (SQLException ignored) {}
      }

      String host = getConfig().getString("database.host", "localhost");
      String port = getConfig().getString("database.port", "3306");
      String database = getConfig().getString("database.database", "treasureDB");
      String username = getConfig().getString("database.user", "root");
      String password = getConfig().getString("database.password", "");

      connection = DriverManager.getConnection(
          "jdbc:mysql://" + host + ":" + port + "/" + database +
              "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
          username, password
      );

      getLogger().info("✅ MySQL 再接続成功！");
    } catch (SQLException e) {
      getLogger().severe("❌ MySQL 再接続失敗: " + e.getMessage());
    }
  }

  private void setupDatabase() {
    String host = getConfig().getString("database.host", "localhost");
    String port = getConfig().getString("database.port", "3306");
    String database = getConfig().getString("database.database", "treasureDB");
    String username = getConfig().getString("database.user", "root");
    String password = getConfig().getString("database.password", "");

    try {
      Class.forName("com.mysql.cj.jdbc.Driver");
      connection = DriverManager.getConnection(
          "jdbc:mysql://" + host + ":" + port + "/" + database +
              "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
          username, password
      );

      Statement stmt = connection.createStatement();
      stmt.executeUpdate("CREATE TABLE IF NOT EXISTS scores (" +
          "id INT AUTO_INCREMENT PRIMARY KEY," +
          "player_name VARCHAR(50)," +
          "score INT," +
          "time BIGINT," +
          "difficulty VARCHAR(10)" +
          ");");
      stmt.close();

      getLogger().info("✅ scores テーブル準備完了");

    } catch (ClassNotFoundException | SQLException e) {
      getLogger().severe("❌ DB 初期化失敗");
      e.printStackTrace();
    }
  }

  private void saveScore(String playerName, int score, long timeSec, String difficulty) {
    try (PreparedStatement ps = getConnection().prepareStatement(
        "INSERT INTO scores (player_name, score, time, difficulty) VALUES (?, ?, ?, ?)")) {

      ps.setString(1, playerName);
      ps.setInt(2, score);
      ps.setLong(3, timeSec);
      ps.setString(4, difficulty);
      ps.executeUpdate();

      rankDirty = true;

    } catch (SQLException e) {
      e.printStackTrace();
    }
  }

  private int getRunRank(String playerName, int score, long timeSec, String difficulty) {
    Connection conn = getConnection();
    if (conn == null) {
      getLogger().severe("❌ getRunRank: DB接続が null です");
      return -1;
    }

    String sql =
        "SELECT player_name, score, time, difficulty " +
            "FROM scores " +
            "WHERE UPPER(difficulty) = UPPER(?) " +
            "ORDER BY time ASC, score DESC";

    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, difficulty);

      try (ResultSet rs = ps.executeQuery()) {
        int rank = 0;
        while (rs.next()) {
          rank++;

          String name = rs.getString("player_name");
          int s       = rs.getInt("score");
          long t      = rs.getLong("time");
          String diff = rs.getString("difficulty");

          if (s == score &&
              t == timeSec &&
              diff != null && diff.equalsIgnoreCase(difficulty) &&
              name != null && name.equalsIgnoreCase(playerName)) {
            return rank;
          }
        }
      }

      getLogger().warning("⚠ getRunRank: 該当行が見つかりませんでした " +
          "(player=" + playerName +
          ", score=" + score +
          ", time=" + timeSec +
          ", difficulty=" + difficulty + ")");
    } catch (SQLException e) {
      getLogger().warning("⚠ ランキング計算中にエラー: " + e.getMessage());
      e.printStackTrace();
    }

    return -1;
  }

  private void showRanking(Player player) {
    try (PreparedStatement ps = getConnection().prepareStatement(
        "SELECT player_name, score, time, difficulty " +
            "FROM scores " +
            "ORDER BY time ASC, score DESC " +
            "LIMIT 10");
        ResultSet rs = ps.executeQuery()) {

      player.sendMessage(ChatColor.GOLD + "=== 🌟 TreasureRun ランキング TOP10（タイム優先）🌟 ===");

      int rank = 1;
      while (rs.next()) {
        String name = rs.getString("player_name");
        int score = rs.getInt("score");
        long time = rs.getLong("time");
        String diff = rs.getString("difficulty");

        ChatColor diffColor = switch (diff) {
          case "Easy" -> ChatColor.GREEN;
          case "Normal" -> ChatColor.YELLOW;
          case "Hard" -> ChatColor.RED;
          default -> ChatColor.WHITE;
        };

        player.sendMessage(
            ChatColor.AQUA + "" + rank + "位 " +
                ChatColor.WHITE + name + "  " +
                ChatColor.YELLOW + time + "秒 " +
                ChatColor.GOLD + "" + score + "点  " +
                diffColor + "難易度: " + diff
        );

        rank++;
      }

      if (rank == 1) {
        player.sendMessage(ChatColor.GRAY + "まだスコアがありません。");
      }

    } catch (SQLException e) {
      e.printStackTrace();
      player.sendMessage(ChatColor.RED + "ランキング取得中にエラーが発生しました");
    }
  }

  private void loadConfigValues() {
    easyTimeLimit   = getConfig().getInt("difficultySettings.Easy.timeLimit", 300);
    normalTimeLimit = getConfig().getInt("difficultySettings.Normal.timeLimit", 180);
    hardTimeLimit   = getConfig().getInt("difficultySettings.Hard.timeLimit", 120);

    chestSpawnRadius = getConfig().getInt("chestSpawnRadius", 20);

    easyMultiplier   = getConfig().getDouble("difficultySettings.Easy.multiplier", 0.5);
    normalMultiplier = getConfig().getDouble("difficultySettings.Normal.multiplier", 1.0);
    hardMultiplier   = getConfig().getDouble("difficultySettings.Hard.multiplier", 1.5);

    easyPenalty   = getConfig().getInt("difficultySettings.Easy.penalty", 5);
    normalPenalty = getConfig().getInt("difficultySettings.Normal.penalty", 10);
    hardPenalty   = getConfig().getInt("difficultySettings.Hard.penalty", 15);

    treasureChestCounts.put("Easy",   getConfig().getInt("treasureChestCount.Easy", 3));
    treasureChestCounts.put("Normal", getConfig().getInt("treasureChestCount.Normal", 2));
    treasureChestCounts.put("Hard",   getConfig().getInt("treasureChestCount.Hard", 1));

    treasurePool.clear();
    for (String name : getConfig().getStringList("treasureItems")) {
      Material m = Material.matchMaterial(name);
      if (m == null) {
        try { m = Material.matchMaterial(name.toUpperCase(Locale.ROOT)); } catch (Exception ignored) {}
      }
      if (m != null) treasurePool.add(m);
      else getLogger().warning("❌ 無効な Material: " + name);
    }

    if (treasurePool.isEmpty()) treasurePool.add(Material.DIAMOND);

    getLogger().info("✅ config.yml を読み込みました");
  }

  // =======================================================
  // Commands
  // =======================================================
  @Override
  public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
    if (!(sender instanceof Player player)) {
      sender.sendMessage("プレイヤーのみ実行できます");
      return true;
    }

    if (cmd.getName().equalsIgnoreCase("gameRank")) {
      showRanking(player);
      rankDirty = true;
      return true;
    }

    if (cmd.getName().equalsIgnoreCase("gameMenu")) {
      GameMenu.showGameMenu(player, difficulty);
      GameMenu.openRuleBook(player, difficulty);
      return true;
    }

    if (cmd.getName().equalsIgnoreCase("gameStart")) {

      if (isRunning) {
        player.sendMessage(ChatColor.RED + "ゲームは既に実行中です。");
        return true;
      }

      // ✅ 1) gameStart の最初に「前回の残骸を必ず掃除してから生成」
      if (gameStageManager != null) {
        gameStageManager.clearDifficultyBlocks();
        gameStageManager.clearShopEntities();
      }
      treasureChestManager.removeAllChests();

      // ✅ 念のため開始前に開始テーマの残タスクを止める（事故防止）
      if (startThemePlayer != null) startThemePlayer.stop(player);

      playerScores.put(player.getUniqueId(), 0);

      if (treasureRunGameEffectsPlugin != null) {
        for (Player p : Bukkit.getOnlinePlayers()) {
          treasureRunGameEffectsPlugin.resetPlayerTreasureCount(p);
        }
      }

      if (args.length >= 1) {
        String diff = args[0].toLowerCase();
        if (diff.equals("easy") || diff.equals("normal") || diff.equals("hard")) {
          difficulty = diff.substring(0, 1).toUpperCase() + diff.substring(1);
        } else {
          player.sendMessage(ChatColor.RED + "難易度は Easy / Normal / Hard です。");
          return true;
        }
      } else {
        difficulty = "Normal";
      }

      originalLocations.put(player.getUniqueId(), player.getLocation().clone());

      Location stage = gameStageManager.buildSeasideStageAndTeleport(player);
      currentStageCenter = stage;

      int currentTotalChests = 10;
      treasureChestManager.spawnChests(player, difficulty, currentTotalChests);
      totalChestsRemaining = currentTotalChests;

      player.sendMessage(ChatColor.GREEN + "宝箱 " + currentTotalChests + " 個を配置しました！");

      GameMenu.showGameMenu(player, difficulty);
      GameMenu.openRuleBook(player, difficulty);

      new BukkitRunnable() {
        int count = 3;

        @Override
        public void run() {
          if (count > 0) {
            player.sendTitle(ChatColor.GREEN + "スタートまで…",
                ChatColor.YELLOW + "" + count, 10, 20, 10);
            count--;
          } else {
            player.sendTitle(ChatColor.GREEN + "スタート！", "", 10, 20, 10);
            TreasureRunMultiChestPlugin.this.startGame(player);
            this.cancel();
          }
        }
      }.runTaskTimer(TreasureRunMultiChestPlugin.this, 0L, 20L);

      return true;
    }

    if (cmd.getName().equalsIgnoreCase("gameEnd")) {
      UUID uuid = player.getUniqueId();
      int score = playerScores.getOrDefault(uuid, 0);
      long elapsedSec = Math.max(0, (System.currentTimeMillis() - startTime) / 1000L);

      player.sendMessage(ChatColor.GOLD + "ゲーム終了！合計スコア: " + ChatColor.YELLOW + score);

      saveScore(player.getName(), score, elapsedSec, difficulty);

      // ✅ 手動終了でも必ず停止
      if (startThemePlayer != null) startThemePlayer.stop(player);

      if (taskId != -1) {
        Bukkit.getScheduler().cancelTask(taskId);
        taskId = -1;
      }

      if (gameStageManager != null) {
        gameStageManager.clearDifficultyBlocks();
        gameStageManager.clearShopEntities();
      }

      isRunning = false;
      if (bossBar != null) bossBar.removeAll();
      treasureChestManager.removeAllChests();
      playerScores.remove(uuid);

      restoreWorldAndPlayer(player);
      return true;
    }

    return false;
  }

  @Override
  public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
    if (cmd.getName().equalsIgnoreCase("gameStart")) {
      if (args.length == 1) {
        return Arrays.asList("easy", "normal", "hard");
      }
    }
    return Collections.emptyList();
  }

  @EventHandler
  public void onGamestartStyleCommand(PlayerCommandPreprocessEvent event) {
    String msg = event.getMessage().trim().toLowerCase(Locale.ROOT);

    if (!msg.startsWith("/gamestart:")) return;

    String mode = msg.substring("/gamestart:".length()).trim();
    if (!(mode.equals("easy") || mode.equals("normal") || mode.equals("hard"))) return;

    event.setCancelled(true);
    Player player = event.getPlayer();

    if (isRunning) {
      player.sendMessage(ChatColor.RED + "ゲームは既に実行中です。");
      return;
    }

    // ✅ 1) gamestart: の最初に「前回の残骸を必ず掃除してから生成」
    if (gameStageManager != null) {
      gameStageManager.clearDifficultyBlocks();
      gameStageManager.clearShopEntities();
    }
    treasureChestManager.removeAllChests();

    // ✅ 念のため開始前に開始テーマの残タスクを止める（事故防止）
    if (startThemePlayer != null) startThemePlayer.stop(player);

    playerScores.put(player.getUniqueId(), 0);

    if (treasureRunGameEffectsPlugin != null) {
      for (Player p : Bukkit.getOnlinePlayers()) {
        treasureRunGameEffectsPlugin.resetPlayerTreasureCount(p);
      }
    }

    difficulty = mode.substring(0, 1).toUpperCase() + mode.substring(1);
    originalLocations.put(player.getUniqueId(), player.getLocation().clone());

    Location stage = gameStageManager.buildSeasideStageAndTeleport(player);
    currentStageCenter = stage;

    int currentTotalChests = 10;
    treasureChestManager.spawnChests(player, difficulty, currentTotalChests);
    totalChestsRemaining = currentTotalChests;

    player.sendMessage(ChatColor.GREEN + "宝箱 " + currentTotalChests + " 個を配置しました！");

    GameMenu.showGameMenu(player, difficulty);
    GameMenu.openRuleBook(player, difficulty);

    new BukkitRunnable() {
      int count = 3;

      @Override
      public void run() {
        if (count > 0) {
          player.sendTitle(ChatColor.GREEN + "スタートまで…",
              ChatColor.YELLOW + "" + count, 10, 20, 10);
          count--;
        } else {
          player.sendTitle(ChatColor.GREEN + "スタート！", "", 10, 20, 10);
          TreasureRunMultiChestPlugin.this.startGame(player);
          this.cancel();
        }
      }
    }.runTaskTimer(TreasureRunMultiChestPlugin.this, 0L, 20L);
  }

  // =======================================================
  // ✅ 終了判定（最後の宝箱を開けた時）
  // =======================================================
  @EventHandler
  public void onInventoryOpen(InventoryOpenEvent event) {
    if (!isRunning) return;
    if (!(event.getPlayer() instanceof Player player)) return;

    Inventory inv = event.getInventory();
    Object holder = inv.getHolder();
    if (!(holder instanceof Chest) && !(holder instanceof DoubleChest)) return;

    Location chestLoc = inv.getLocation();
    if (chestLoc == null) return;
    Block chestBlock = chestLoc.getBlock();

    if (!treasureChestManager.isOurChest(chestBlock)) return;
    if (chestBlock.getType() != Material.CHEST) return;
    if (!isRunning) return;

    boolean hadAnyItem = false;
    boolean jackpot = false;

    if (chestBlock.getState() instanceof Chest chestState) {
      ItemStack[] contents = chestState.getBlockInventory().getContents();
      for (ItemStack item : contents) {
        if (item == null || item.getType() == Material.AIR) continue;

        hadAnyItem = true;

        String nameJP = materialJapaneseNames.getOrDefault(item.getType(), item.getType().name());
        player.sendMessage(ChatColor.GOLD + " 宝物: " +
            ChatColor.AQUA + nameJP + ChatColor.WHITE + " ×" + item.getAmount());

        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item.clone());
        leftovers.values().forEach(remain ->
            player.getWorld().dropItemNaturally(player.getLocation(), remain)
        );

        switch (item.getType()) {
          case NETHERITE_INGOT, ENCHANTED_GOLDEN_APPLE, DIAMOND_BLOCK,
               EMERALD_BLOCK, GOLD_BLOCK, IRON_BLOCK -> jackpot = true;
          default -> {}
        }
      }

      chestState.getBlockInventory().clear();
      chestState.update();
    }

    int add = 0;
    if (hadAnyItem) add += 100;
    if (jackpot) add += 200;

    if (add > 0) {
      int newScore = playerScores.getOrDefault(player.getUniqueId(), 0) + add;
      playerScores.put(player.getUniqueId(), newScore);
      player.sendMessage(ChatColor.GREEN + "スコア +" + add + "点（累計: " + newScore + "点）");
    }

    chestBlock.setType(Material.AIR);

    Location effectLoc = chestLoc.clone().add(0.5, 0.8, 0.5);
    World w = effectLoc.getWorld();

    w.spawnParticle(Particle.END_ROD, effectLoc, 40, 0.6, 0.6, 0.6, 0.02);
    spawnRisingPillars(effectLoc, Particle.END_ROD);
    burstStars(effectLoc);

    totalChestsRemaining = Math.max(0, totalChestsRemaining - 1);

    if (totalChestsRemaining > 0) {
      player.sendMessage(ChatColor.AQUA + "残りの宝箱: " +
          ChatColor.YELLOW + totalChestsRemaining + ChatColor.AQUA + " 個");
      return;
    }

    // =========================================================
    // ✅ ここから「ゲーム終了演出」
    // =========================================================
    if (!isRunning) return;
    isRunning = false;

    // ✅ 最後の宝箱で終了が確定したら、開始テーマはここで止める（DJと競合させない）
    if (startThemePlayer != null) startThemePlayer.stop(player);

    // ✅ 2) 最後の宝箱で終了が確定した時点で、finishDelayまで待たずに難易度ブロックを先に片付ける
    if (gameStageManager != null) {
      gameStageManager.clearDifficultyBlocks();
    }

    if (taskId != -1) {
      Bukkit.getScheduler().cancelTask(taskId);
      taskId = -1;
    }

    // 念のため（前回の終了Title維持が残っていたら止める）
    if (finishTitleTaskId != -1) {
      Bukkit.getScheduler().cancelTask(finishTitleTaskId);
      finishTitleTaskId = -1;
    }

    final int finalScore = playerScores.getOrDefault(player.getUniqueId(), 0);

    long elapsedMs = System.currentTimeMillis() - startTime;
    long totalSeconds = elapsedMs / 1000;
    long minutes = totalSeconds / 60;
    long seconds = totalSeconds % 60;
    long hundredths = (elapsedMs % 1000) / 10;

    final String timeText = String.format("%d:%02d.%02d", minutes, seconds, hundredths);
    final long elapsedSec = totalSeconds;

    saveScore(player.getName(), finalScore, elapsedSec, difficulty);

    final int rank = getRunRank(player.getName(), finalScore, elapsedSec, difficulty);
    final String rankLabel = (rank > 0) ? ("#" + rank) : "-";

    // =========================================================
    // ✅ DJ総Tickを基準に「Title維持」「順位演出」「restore」を同じ終点へ揃える（完全版・互換あり）
    // =========================================================
    long djTotalTicksWork = (treasureRunGameEffectsPlugin != null)
        ? treasureRunGameEffectsPlugin.getDjTotalTicks()
        : 0L;

    // 互換：もしDJ総Tickが取れない環境なら、旧見積もりへフォールバック
    if (djTotalTicksWork <= 0L) {
      djTotalTicksWork = getTotalEffectTicksForRank(rank);
    }

    // ✅ ラムダ内で使えるように final 化
    final long djTotalTicksFinal = Math.max(0L, djTotalTicksWork);

    final long rewardStartDelay = 65L; // DJ/順位演出スタート
    final long finishDelay = rewardStartDelay + djTotalTicksFinal;

    // ✅ DJ + 順位演出を「同じ開始点」で開始し、どちらもDJ終点まで動かす
    Bukkit.getScheduler().runTaskLater(this, () -> {
      if (!player.isOnline()) return;

      // 1) 順位報酬＋演出（DJ終点まで動く版）
      if (rankRewardManager != null && rank > 0) {
        rankRewardManager.giveRankRewardWithEffect(player, rank, djTotalTicksFinal);
      }

      // 2) DJ花火イベント（Titleは出さない：Score/Time/RankのTitle維持と競合させない）
      if (treasureRunGameEffectsPlugin != null) {
        treasureRunGameEffectsPlugin.triggerUltimateDJEvent(player, false);

        // 互換で必要なら（通常は不要。DJ側がロックしてる前提）
        // treasureRunGameEffectsPlugin.onAllTreasuresCollected(player);
      }
    }, rewardStartDelay);

    // ✅ Title を DJが終わるまで「消えない + 上書きされても戻す」…0.5秒ごとに打ち直す
    final long titleStartDelay = rewardStartDelay + 1;

    Bukkit.getScheduler().runTaskLater(this, () -> {
      if (!player.isOnline()) return;

      // finishDelay まで維持したい（titleStartDelay からの残り）
      final long keepDuration = Math.max(0L, finishDelay - titleStartDelay);

      // 10tickごとに送る（sendTitleのstay=20tickなので常に見える）
      final long period = 10L;

      // 必要回数（切り上げ）。0なら1回だけ送ってすぐ終わり
      final long maxRuns = (keepDuration <= 0) ? 1 : (long) Math.ceil((double) keepDuration / period);

      finishTitleTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, new Runnable() {
        long runs = 0;

        @Override
        public void run() {
          if (!player.isOnline()) {
            if (finishTitleTaskId != -1) {
              Bukkit.getScheduler().cancelTask(finishTitleTaskId);
              finishTitleTaskId = -1;
            }
            return;
          }

          // 送信
          player.sendTitle(
              ChatColor.AQUA + "TreasureRun Complete!",
              ChatColor.GOLD + "Score: " + finalScore +
                  ChatColor.YELLOW + "  Time: " + timeText +
                  ChatColor.AQUA + "  Rank: " + ChatColor.LIGHT_PURPLE + rankLabel,
              0,
              20,
              0
          );

          runs++;
          if (runs >= maxRuns) {
            if (finishTitleTaskId != -1) {
              Bukkit.getScheduler().cancelTask(finishTitleTaskId);
              finishTitleTaskId = -1;
            }
          }
        }
      }, 0L, period);

    }, titleStartDelay);

    // ✅ restoreWorldAndPlayer はDJ終点の後（全部見せ切ってから）
    final String playerName = player.getName();
    final UUID playerUuid = player.getUniqueId();

    Bukkit.getScheduler().runTaskLater(this, () -> {

      // 念のためTitle維持タスクが残っていたら止める
      if (finishTitleTaskId != -1) {
        Bukkit.getScheduler().cancelTask(finishTitleTaskId);
        finishTitleTaskId = -1;
      }

      if (player.isOnline()) {
        player.sendMessage(ChatColor.GOLD + "すべての宝箱を開けました！ゲーム終了！");
        player.sendMessage(ChatColor.AQUA + "タイム: " + ChatColor.YELLOW + timeText +
            ChatColor.GOLD + "  スコア: " + finalScore +
            ChatColor.LIGHT_PURPLE + "  ランク: " + rankLabel);
      }

      if (gameStageManager != null) {
        gameStageManager.clearDifficultyBlocks();
        gameStageManager.clearShopEntities();
      }

      if (bossBar != null) bossBar.removeAll();
      treasureChestManager.removeAllChests();
      playerScores.remove(playerUuid);

      Bukkit.broadcastMessage(ChatColor.AQUA + playerName +
          " が全ての宝箱を開けました！最終スコア: " + finalScore);

      restoreWorldAndPlayer(player);

    }, finishDelay);
  }

  private void startGame(Player player) {
    getLogger().info("[StartTheme] startGame called by " + player.getName()); // ★追加

    isRunning = true;
    startTime = System.currentTimeMillis();

    // ✅ ゲーム開始時に開始テーマを流す（ここが本命）
    if (startThemePlayer != null) startThemePlayer.play(player);

    if (gameStageManager != null && currentStageCenter != null) {
      gameStageManager.startLoopEffects(currentStageCenter);
    }

    World w = player.getWorld();
    previousWorldTime = w.getTime();
    previousStorm = w.hasStorm();
    previousThundering = w.isThundering();

    w.setTime(1000L);
    w.setStorm(false);
    w.setThundering(false);

    bossBar = Bukkit.createBossBar(ChatColor.GREEN + "残り時間", BarColor.GREEN, BarStyle.SOLID);
    bossBar.addPlayer(player);

    taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
      long elapsed = (System.currentTimeMillis() - startTime) / 1000;
      int timeLimit = switch (difficulty) {
        case "Easy" -> easyTimeLimit;
        case "Normal" -> normalTimeLimit;
        case "Hard"  -> hardTimeLimit;
        default      -> 180;
      };

      long remaining = Math.max(0, timeLimit - elapsed);
      bossBar.setProgress((double) remaining / timeLimit);

      player.spigot().sendMessage(ChatMessageType.ACTION_BAR,
          new TextComponent(ChatColor.YELLOW + "残り時間: " + remaining + "秒"));

      if (remaining <= 0) {
        if (taskId != -1) {
          Bukkit.getScheduler().cancelTask(taskId);
          taskId = -1;
        }

        if (gameStageManager != null) {
          gameStageManager.clearDifficultyBlocks();
          gameStageManager.clearShopEntities();
        }

        player.sendMessage(ChatColor.GOLD + "時間切れ！ゲーム終了！");

        int finalScore = playerScores.getOrDefault(player.getUniqueId(), 0);
        long elapsedSec = (System.currentTimeMillis() - startTime) / 1000L;
        saveScore(player.getName(), finalScore, elapsedSec, difficulty);

        // ✅ 時間切れでも必ず停止
        if (startThemePlayer != null) startThemePlayer.stop(player);

        isRunning = false;
        if (bossBar != null) bossBar.removeAll();
        treasureChestManager.removeAllChests();
        playerScores.remove(player.getUniqueId());

        restoreWorldAndPlayer(player);
      }

    }, 0L, 20L);
  }

  public boolean isGameRunning() {
    return isRunning;
  }

  private void restoreWorldAndPlayer(Player player) {
    UUID uuid = player.getUniqueId();

    Location original = originalLocations.remove(uuid);
    if (original != null) {
      player.teleport(original);
    }

    if (previousWorldTime >= 0) {
      World w = player.getWorld();
      w.setTime(previousWorldTime);
      w.setStorm(previousStorm);
      w.setThundering(previousThundering);
      previousWorldTime = -1;
    }
  }

  private void spawnRisingPillars(Location center, Particle particle) {
    World w = center.getWorld();

    new BukkitRunnable() {
      double yOff = 0;

      @Override
      public void run() {
        for (int i = -1; i <= 1; i++) {
          for (int j = -1; j <= 1; j++) {
            Location loc = center.clone().add(i * 0.5, yOff, j * 0.5);
            w.spawnParticle(particle, loc, 3, 0.05, 0.1, 0.05, 0.01);
          }
        }
        yOff += 0.25;
        if (yOff > 3.5) cancel();
      }
    }.runTaskTimer(this, 0L, 2L);
  }

  public void burstStars(Location center) {
    World w = center.getWorld();
    for (int i = 0; i < 60; i++) {
      double angle = Math.random() * Math.PI * 2;
      double speed = 0.2 + Math.random() * 0.3;
      double vx = Math.cos(angle) * speed;
      double vz = Math.sin(angle) * speed;
      double vy = 0.2 + Math.random() * 0.3;

      w.spawnParticle(Particle.END_ROD,
          center.clone().add(0, 1.2, 0),
          0, vx, vy, vz, 1);
    }
  }

  public GameStageManager getGameStageManager() {
    return gameStageManager;
  }

  public TreasureItemFactory getItemFactory() {
    return itemFactory;
  }

  // ★追加（ここだけ追加）：
  public TreasureChestManager getTreasureChestManager() {
    return treasureChestManager;
  }

  // =======================================================
  // ✅ 追加：/rank デバッグコマンドから呼ぶための getter
  // （DB/ランキングを触らず、演出だけ発火するために RankRewardManager を渡す）
  // =======================================================
  public RankRewardManager getRankRewardManager() {
    return rankRewardManager;
  }

  // ★追加（ここだけ追加）：effects の getter
  public TreasureRunGameEffectsPlugin getTreasureRunGameEffectsPlugin() {
    return treasureRunGameEffectsPlugin;
  }

  // ✅ 互換：残しておく（DJ総Tickが取れない場合のフォールバックにも使える）
  private int getTotalEffectTicksForRank(int rank) {
    return switch (rank) {
      case 1 -> 180;
      case 2 -> 140;
      case 3 -> 50;
      default -> 0;
    };
  }
}