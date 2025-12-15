package plugin;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

public class StartThemeStopListener implements Listener {
  private final TreasureRunMultiChestPlugin plugin;

  public StartThemeStopListener(TreasureRunMultiChestPlugin plugin) {
    this.plugin = plugin;
  }

  @EventHandler
  public void onQuit(PlayerQuitEvent e) {
    plugin.getStartThemePlayer().stop(e.getPlayer());
  }
}