package com.cgsplits;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/**
 * The on-screen timer: Preparation, optionally 50%, and Finish, all timed from the start of the
 * run and shown only while inside the instance.
 *
 * Colours compare each split against a target picked in the settings (your PB, your own custom
 * times, or the world record): green once reached under the target, red once over it. A live
 * time turns red as soon as it passes the target, so you see you're behind before the split.
 * With Personal, gold marks your best ever time at that split.
 */
class CGSplitsOverlay extends OverlayPanel
{
	private final CGSplitsPlugin plugin;
	private final CGSplitsConfig config;
	private final RunStore runStore;

	@Inject
	CGSplitsOverlay(CGSplitsPlugin plugin, CGSplitsConfig config, RunStore runStore)
	{
		super(plugin);
		this.plugin = plugin;
		this.config = config;
		this.runStore = runStore;
		setPosition(OverlayPosition.TOP_LEFT);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		// The tracker drops the run as soon as the player leaves the instance
		Run run = plugin.getTracker().getCurrent();
		if (run == null || !config.showTimer())
		{
			return null;
		}

		panelComponent.getChildren().add(TitleComponent.builder()
			.text("Timer")
			.color(Color.WHITE)
			.build());

		// Preparation: counts up until the Hunllef room, then freezes
		Integer prep = run.getSplits().get(Milestone.BOSS_ROOM);
		addSplit("Preparation:", Milestone.BOSS_ROOM, prep, run.getTicks());

		// 50% (optional): appears once the Hunllef is at half health
		Integer half = run.getSplits().get(Milestone.HUNLLEF_50);
		if (config.showHalfHpSplit() && half != null)
		{
			addSplit("50%:", Milestone.HUNLLEF_50, half, run.getTicks());
		}

		// Finish: appears once in the boss room, freezes when the kill counts (the game's own
		// time, a few ticks after the Hunllef's HP hits 0)
		if (prep != null)
		{
			addSplit("Finish:", Milestone.HUNLLEF, run.getSplits().get(Milestone.HUNLLEF), run.getTicks());
		}

		return super.render(graphics);
	}

	/**
	 * @param reached the split's tick if reached (frozen), otherwise null and the live tick is shown
	 */
	private void addSplit(String label, Milestone milestone, Integer reached, int live)
	{
		Integer target = target(milestone);
		Color color = Color.WHITE;
		if (reached != null)
		{
			if (target != null)
			{
				color = reached <= target ? config.aheadColor() : config.behindColor();
			}
			if (config.paceComparison() == PaceComparison.PERSONAL)
			{
				Integer best = runStore.bestTick(milestone);
				if (best == null || reached <= best)
				{
					color = config.bestColor();
				}
			}
		}
		else if (target != null && live > target)
		{
			// Still going, but already slower than the target
			color = config.behindColor();
		}

		String time = config.timerPrecision().format(reached != null ? reached : live);
		// Optional gap to the target: once the split is reached, or live once it's already behind
		if (config.showGap() && target != null && (reached != null || live > target))
		{
			time += " (" + TimeFormat.delta((reached != null ? reached : live) - target) + ")";
		}

		panelComponent.getChildren().add(LineComponent.builder()
			.left(label)
			.right(time)
			.rightColor(color)
			.build());
	}

	/** The target tick for a split, from run start, for the chosen comparison; null if none. */
	private Integer target(Milestone milestone)
	{
		switch (config.paceComparison())
		{
			case CUSTOM:
				switch (milestone)
				{
					case BOSS_ROOM:
						return TimeFormat.parseUserTime(config.customPrep());
					case HUNLLEF_50:
						return TimeFormat.parseUserTime(config.customHalf());
					default:
						return null;
				}
			case WORLD_RECORD:
				return WorldRecord.ticks(milestone);
			default:
				Run pb = runStore.getPersonalBest();
				return pb != null ? pb.getSplits().get(milestone) : null;
		}
	}
}
