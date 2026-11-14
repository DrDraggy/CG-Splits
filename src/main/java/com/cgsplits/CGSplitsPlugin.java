package com.cgsplits;

import com.google.inject.Injector;
import com.google.inject.Provides;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.WorldType;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.Text;

@Slf4j
@PluginDescriptor(
	name = "CG Splits",
	description = "LiveSplit-style auto-splits and personal bests for the Corrupted Gauntlet",
	tags = {"gauntlet", "corrupted", "cg", "hunllef", "splits", "timer", "speedrun", "pb"}
)
public class CGSplitsPlugin extends Plugin
{
	// TODO verify these IDs in a dev client (Varbit inspector / region debug overlay).
	static final int VARBIT_GAUNTLET_FINAL_ROOM_ENTERED = 9177;
	static final int VARBIT_GAUNTLET_ENTERED = 9178;
	static final int REGION_GAUNTLET = 7512;
	static final int REGION_CORRUPTED_GAUNTLET = 7768;
	/** The Gauntlet lobby building in Prifddinas. */
	static final int REGION_GAUNTLET_LOBBY = 12127;

	private static final int INVENTORY = 93;
	private static final int EQUIPMENT = 94;

	private static final Pattern DURATION = Pattern.compile("challenge duration: (\\d+:\\d{2}(?:\\.\\d+)?)", Pattern.CASE_INSENSITIVE);
	private static final Pattern PREP = Pattern.compile("preparation time: (\\d+:\\d{2}(?:\\.\\d+)?)", Pattern.CASE_INSENSITIVE);

	@Inject
	private Client client;

	@Inject
	private ItemManager itemManager;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private CGSplitsOverlay overlay;

	@Inject
	private CGSessionOverlay sessionOverlay;

	@Inject
	private CGSplitsConfig config;

	@Inject
	private RunStore runStore;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private Injector injector;

	@Getter
	private final RunTracker tracker = new RunTracker(RunTracker.GAME_TIMER_START_DELAY);

	@Getter
	private final SessionTracker session = new SessionTracker();

	/** Inside a Gauntlet instance or the Prifddinas Gauntlet lobby. */
	@Getter
	private volatile boolean inGauntletArea;

	/** False while the current run is on a Leagues, Deadman or other special world. */
	private boolean countThisRun = true;

	private CGSplitsPanel panel;
	private NavigationButton navButton;
	private Runnable storeListener;

	@Provides
	CGSplitsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(CGSplitsConfig.class);
	}

	@Override
	protected void startUp()
	{
		panel = injector.getInstance(CGSplitsPanel.class);
		storeListener = () -> SwingUtilities.invokeLater(panel::refresh);
		runStore.addListener(storeListener);
		runStore.load();

		navButton = NavigationButton.builder()
			.tooltip("CG Splits")
			.icon(createIcon())
			.priority(7)
			.panel(panel)
			.build();
		updateSidebar();
		overlayManager.add(overlay);
		overlayManager.add(sessionOverlay);
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		overlayManager.remove(sessionOverlay);
		clientToolbar.removeNavigation(navButton);

		// Keep the run in progress in the stats instead of silently dropping it
		Run unfinished = tracker.abandon();
		if (unfinished != null && unfinished.getTicks() > 0 && countThisRun)
		{
			onRunEnded(unfinished);
		}

		runStore.removeListener(storeListener);
		panel.shutDown();
		navButton = null;
		panel = null;
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		boolean inGauntlet = client.getVarbitValue(VARBIT_GAUNTLET_ENTERED) == 1;
		boolean inBossRoom = client.getVarbitValue(VARBIT_GAUNTLET_FINAL_ROOM_ENTERED) == 1;

		long now = System.currentTimeMillis();
		RunTracker.State before = tracker.getState();
		Run ended = tracker.onTick(inGauntlet, client.getTopLevelWorldView().isInstance(), inBossRoom, regionMode(), now);
		RunTracker.State after = tracker.getState();

		if (after == RunTracker.State.BOSS)
		{
			readHunllefHealth();
		}

		if (before == RunTracker.State.IDLE && after == RunTracker.State.PREP)
		{
			// Leagues, Deadman and other special worlds play differently, so their runs are
			// timed on screen but kept out of PBs, splits and the session
			countThisRun = isStandardWorld();
			if (countThisRun)
			{
				session.onRunStarted(now);
			}
			else
			{
				log.debug("Gauntlet run on a special world ({}): not counting it", client.getWorldType());
			}
		}
		else if (before == RunTracker.State.PREP && after == RunTracker.State.BOSS)
		{
			Run run = tracker.getCurrent();
			if (countThisRun && run.isCorrupted() && !run.isWeaponlessEnter())
			{
				session.onEnter(run.getSplits().get(Milestone.BOSS_ROOM), run.getArmourAtEnter());
				if (run.getArmourAtEnter() == Armour.BURGER)
				{
					client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "<col=ff9040>Burger Enter.</col>", null);
				}
			}
		}

		if (ended != null)
		{
			if (countThisRun)
			{
				session.onRunEnded(ended.isReset() && ended.isCorrupted());
				onRunEnded(ended);
			}
			else
			{
				log.debug("Not saving Gauntlet run from a special world");
			}
		}

		// Session clock keeps running between runs, but pauses after a few ticks outside the instance (AFK).
		// Runs on special worlds don't count, so neither does their time.
		session.onTick(inGauntlet && countThisRun, now);
		inGauntletArea = inGauntlet || worldRegion() == REGION_GAUNTLET_LOBBY;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGIN_SCREEN || event.getGameState() == GameState.HOPPING)
		{
			session.pause(System.currentTimeMillis());
			inGauntletArea = false;
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (CGSplitsConfig.GROUP.equals(event.getGroup()) && "showSidebar".equals(event.getKey()))
		{
			updateSidebar();
		}
	}

	private void updateSidebar()
	{
		if (config.showSidebar())
		{
			clientToolbar.addNavigation(navButton);
		}
		else
		{
			clientToolbar.removeNavigation(navButton);
		}
	}

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		if (event.getActor() instanceof NPC)
		{
			if (tracker.isRunning())
			{
				log.debug("CG NPC died: {}", event.getActor().getName());
			}
			tracker.onNpcDeath(event.getActor().getName());
		}
		else if (event.getActor() == client.getLocalPlayer())
		{
			tracker.onLocalPlayerDeath();
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		int id = event.getContainerId();
		if ((id != INVENTORY && id != EQUIPMENT) || !tracker.isRunning())
		{
			return;
		}

		List<String> names = new ArrayList<>();
		addItemNames(client.getItemContainer(INVENTORY), names);
		addItemNames(client.getItemContainer(EQUIPMENT), names);
		tracker.onItems(names);
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE)
		{
			return;
		}

		String message = Text.removeTags(event.getMessage());
		Matcher duration = DURATION.matcher(message);
		Matcher prep = PREP.matcher(message);
		tracker.onOfficialTimes(
			duration.find() ? duration.group(1) : null,
			prep.find() ? prep.group(1) : null);
	}

	private void onRunEnded(Run run)
	{
		if (!run.isCorrupted())
		{
			return;
		}
		if (run.isWeaponlessEnter())
		{
			log.debug("Not saving Gauntlet run: entered the Hunllef room without a weapon");
			return;
		}

		log.debug("Gauntlet run ended: completed={} ticks={} splits={}", run.isCompleted(), run.getTicks(), run.getSplits());
		log.debug("CG timer sync: arrival delay {} ticks, prep drift {} ticks, finish drift {} ticks (positive = our timer was ahead of the game's)",
			tracker.getArrivalDelayTicks(), run.getPrepDriftTicks(), run.getTimerDriftTicks());
		runStore.save(run);
	}

	private void addItemNames(ItemContainer container, List<String> names)
	{
		if (container == null)
		{
			return;
		}
		for (Item item : container.getItems())
		{
			if (item.getId() > 0)
			{
				names.add(itemManager.getItemComposition(item.getId()).getName());
			}
		}
	}

	/** A small red hourglass with gold sand, drawn in code so the plugin needs no image resources. */
	private static BufferedImage createIcon()
	{
		BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		// Top and bottom caps
		g.setColor(new Color(0x8E2A20));
		g.fillRect(2, 0, 12, 2);
		g.fillRect(2, 14, 12, 2);

		// Glass: two triangles meeting in the middle
		Path2D glass = new Path2D.Double();
		glass.moveTo(3, 2);
		glass.lineTo(13, 2);
		glass.lineTo(8, 8);
		glass.lineTo(13, 14);
		glass.lineTo(3, 14);
		glass.lineTo(8, 8);
		glass.closePath();
		g.setColor(new Color(0xC0392B));
		g.fill(glass);

		// Sand piled in the bottom
		Path2D sand = new Path2D.Double();
		sand.moveTo(5.5, 13);
		sand.lineTo(10.5, 13);
		sand.lineTo(8, 10);
		sand.closePath();
		g.setColor(new Color(0xF1C40F));
		g.fill(sand);

		g.dispose();
		return image;
	}

	/**
	 * Feeds the Hunllef's health bar to the tracker. The bar is only known once the Hunllef
	 * has been hit (ratio -1 before that), which is fine for the 75/50/25% thresholds.
	 */
	private void readHunllefHealth()
	{
		for (NPC npc : client.getTopLevelWorldView().npcs())
		{
			String name = npc.getName();
			if (name == null || !name.toLowerCase().contains("hunllef"))
			{
				continue;
			}
			int ratio = npc.getHealthRatio();
			int scale = npc.getHealthScale();
			if (ratio >= 0 && scale > 0)
			{
				tracker.onBossHealth((double) ratio / scale);
			}
			return;
		}
	}

	/** World types whose Gauntlet runs don't count: different rules, relics, or temporary characters. */
	private static final Set<WorldType> SPECIAL_WORLDS = EnumSet.of(
		WorldType.SEASONAL,           // Leagues
		WorldType.DEADMAN,
		WorldType.FRESH_START_WORLD,
		WorldType.BETA_WORLD,
		WorldType.TOURNAMENT_WORLD,
		WorldType.QUEST_SPEEDRUNNING,
		WorldType.NOSAVE_MODE,
		WorldType.PVP_ARENA,
		WorldType.LAST_MAN_STANDING);

	/** A normal (members) world, where runs count towards stats. */
	private boolean isStandardWorld()
	{
		for (WorldType type : client.getWorldType())
		{
			if (SPECIAL_WORLDS.contains(type))
			{
				return false;
			}
		}
		return true;
	}

	/** Region of the player's real (non-instance) location, or -1. */
	private int worldRegion()
	{
		Player player = client.getLocalPlayer();
		return player != null ? player.getWorldLocation().getRegionID() : -1;
	}

	private Run.Mode regionMode()
	{
		Player player = client.getLocalPlayer();
		if (player == null)
		{
			return Run.Mode.UNKNOWN;
		}

		LocalPoint local = player.getLocalLocation();
		WorldPoint point = WorldPoint.fromLocalInstance(client, local);
		if (point == null)
		{
			return Run.Mode.UNKNOWN;
		}

		switch (point.getRegionID())
		{
			case REGION_CORRUPTED_GAUNTLET:
				return Run.Mode.CORRUPTED;
			case REGION_GAUNTLET:
				return Run.Mode.NORMAL;
			default:
				return Run.Mode.UNKNOWN;
		}
	}
}
