package com.cgsplits;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;

/** Small on-screen box for the current session: EPH and AVG by default, more lines optional. */
class CGSessionOverlay extends OverlayPanel
{
	private final CGSplitsPlugin plugin;
	private final CGSplitsConfig config;

	@Inject
	CGSessionOverlay(CGSplitsPlugin plugin, CGSplitsConfig config)
	{
		super(plugin);
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.TOP_LEFT);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		// Visible inside the instance and in the Prifddinas Gauntlet lobby, hidden elsewhere
		if (!config.showSessionOverlay() || !plugin.isInGauntletArea())
		{
			return null;
		}

		SessionTracker.Snapshot s = plugin.getSession().snapshot(System.currentTimeMillis());
		if (!s.isActive())
		{
			return null;
		}

		// Each line can be switched on or off under Session Overlay; EPH and AVG are on by default
		if (config.sessionShowEph())
		{
			addLine("EPH:", s.getEnters() > 0 ? TimeFormat.eph(s.getEntersPerHour()) : "-", Color.WHITE);
		}
		if (config.sessionShowAvg())
		{
			addLine("AVG:", s.getEnters() > 0 ? TimeFormat.wholeSeconds(s.getAverageEnterTicks()) : "-", Color.WHITE);
		}
		if (config.sessionShowEnters())
		{
			addLine("Enters:", String.valueOf(s.getEnters()), Color.WHITE);
		}
		if (config.sessionShowArmour())
		{
			addLine("T1/No Arm:", s.entersWith(Armour.FULL) + "/" + s.entersWith(Armour.NONE), Color.WHITE);
		}
		if (config.sessionShowResets())
		{
			addLine("Resets:", String.valueOf(s.getResets()), Color.WHITE);
		}
		if (config.sessionShowTime())
		{
			// Red while paused (out of the instance for more than a few ticks)
			addLine("Session:", TimeFormat.duration(s.getElapsedMs()), s.isPaused() ? config.behindColor() : Color.WHITE);
		}

		return super.render(graphics);
	}

	private void addLine(String left, String right, Color rightColor)
	{
		panelComponent.getChildren().add(LineComponent.builder()
			.left(left)
			.right(right)
			.rightColor(rightColor)
			.build());
	}
}
