package com.cgsplits;

import java.awt.Color;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup(CGSplitsConfig.GROUP)
public interface CGSplitsConfig extends Config
{
	String GROUP = "cgsplits";

	@ConfigItem(
		keyName = "showTimer",
		name = "Show Timer",
		description = "Show the on-screen Prep / Finish timer while inside the Gauntlet. Runs are still tracked when it's off",
		position = 0
	)
	default boolean showTimer()
	{
		return true;
	}

	@ConfigItem(
		keyName = "timerPrecision",
		name = "Timer Precision",
		description = "Ticks: the timer updates every game tick (6.0, 6.6, 7.2). Seconds: no decimals (6, 7, 8)",
		position = 1
	)
	default TimerPrecision timerPrecision()
	{
		return TimerPrecision.TICKS;
	}

	@ConfigItem(
		keyName = "showHalfHpSplit",
		name = "Show 50% HP Split",
		description = "Add a line to the timer with the time from the start of the run until the Hunllef reached 50% HP",
		position = 2
	)
	default boolean showHalfHpSplit()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showSessionOverlay",
		name = "Show Session Overlay",
		description = "Show a separate on-screen box with this session's stats while in the Gauntlet area; pick its lines under Session Overlay",
		position = 3
	)
	default boolean showSessionOverlay()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showSidebar",
		name = "Show Sidebar Tab",
		description = "Show the CG Splits tab (last run, stats, session) in the sidebar",
		position = 4
	)
	default boolean showSidebar()
	{
		return true;
	}

	@ConfigSection(
		name = "Session Overlay",
		description = "Which lines the on-screen session box shows",
		position = 5
	)
	String sessionSection = "session";

	@ConfigItem(
		keyName = "sessionShowEph",
		name = "Enters Per Hour",
		description = "Show EPH in the session box",
		position = 6,
		section = sessionSection
	)
	default boolean sessionShowEph()
	{
		return true;
	}

	@ConfigItem(
		keyName = "sessionShowAvg",
		name = "Average Enter",
		description = "Show the average enter time in the session box",
		position = 7,
		section = sessionSection
	)
	default boolean sessionShowAvg()
	{
		return true;
	}

	@ConfigItem(
		keyName = "sessionShowEnters",
		name = "Hunllef Enters",
		description = "Show how many times you've entered the Hunllef room this session",
		position = 8,
		section = sessionSection
	)
	default boolean sessionShowEnters()
	{
		return false;
	}

	@ConfigItem(
		keyName = "sessionShowArmour",
		name = "T1/No Arm",
		description = "Show this session's T1 armour and no armour enters, e.g. 5/12",
		position = 9,
		section = sessionSection
	)
	default boolean sessionShowArmour()
	{
		return false;
	}

	@ConfigItem(
		keyName = "sessionShowResets",
		name = "Resets",
		description = "Show how many runs you reset before the Hunllef this session",
		position = 10,
		section = sessionSection
	)
	default boolean sessionShowResets()
	{
		return false;
	}

	@ConfigItem(
		keyName = "sessionShowTime",
		name = "Session Time",
		description = "Show the session time that EPH is based on",
		position = 11,
		section = sessionSection
	)
	default boolean sessionShowTime()
	{
		return false;
	}

	@ConfigSection(
		name = "Set Threshold",
		description = "What the on-screen timer's colours compare your Preparation, 50% and Finish against",
		position = 12
	)
	String paceSection = "pace";

	@ConfigItem(
		keyName = "paceComparison",
		name = "Compare Against",
		description = "Personal: your PB's splits. Custom: the times below. World Record: the current WR",
		position = 13,
		section = paceSection
	)
	default PaceComparison paceComparison()
	{
		return PaceComparison.PERSONAL;
	}

	@ConfigItem(
		keyName = "customPrep",
		name = "Custom Preparation",
		description = "Target preparation time for Custom, e.g. 1:50",
		position = 14,
		section = paceSection
	)
	default String customPrep()
	{
		return "";
	}

	@ConfigItem(
		keyName = "customHalf",
		name = "Custom 50%",
		description = "Target time from the start of the run until the Hunllef is at 50% HP, for Custom, e.g. 3:05",
		position = 15,
		section = paceSection
	)
	default String customHalf()
	{
		return "";
	}

	@ConfigItem(
		keyName = "showGap",
		name = "Show Gap",
		description = "Show how far ahead or behind the target each timer split is, e.g. 4:41.4 (-3.0)",
		position = 16,
		section = paceSection
	)
	default boolean showGap()
	{
		return false;
	}

	@ConfigItem(
		keyName = "aheadColor",
		name = "Ahead Colour",
		description = "Colour for a split that beat the target",
		position = 17,
		section = paceSection
	)
	default Color aheadColor()
	{
		return new Color(0x2ECC40);
	}

	@ConfigItem(
		keyName = "behindColor",
		name = "Behind Colour",
		description = "Colour for a split slower than the target, and for the live timer once it passes the target",
		position = 18,
		section = paceSection
	)
	default Color behindColor()
	{
		return new Color(0xFF4136);
	}

	@ConfigItem(
		keyName = "bestColor",
		name = "Best Colour",
		description = "Personal only: colour for your best ever time at that split",
		position = 19,
		section = paceSection
	)
	default Color bestColor()
	{
		return new Color(0xFFD700);
	}
}
