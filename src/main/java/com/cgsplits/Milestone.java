package com.cgsplits;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * A split point inside a Gauntlet run. Splits are milestone-based (like paceman.gg),
 * not order-based: prep routes differ, so each milestone is recorded the first time
 * it happens and compared against the tick the PB run reached the same milestone.
 *
 * Recorded splits are stored as ticks since the run started. Some milestones are segments
 * measured between two recorded splits (e.g. the Hunllef fight, from Enter to Finish); see
 * {@link #end()}, {@link #since()} and {@link Run#splitTime(Milestone)}.
 */
@Getter
@RequiredArgsConstructor
public enum Milestone
{
	FIRST_WEAPON("1st Weapon", true, false),
	/** First demi-boss (bear, dragon or dark beast) killed. */
	DEMIS("Demi Killed", true, false),
	FIRST_PERFECTED("Perfected Weapon", true, false),
	SECOND_PERFECTED("2nd Perfected", true, false),
	FULL_ARMOUR("T1 Armour", true, false),
	/** Entered the Hunllef room. */
	BOSS_ROOM("Preparation", true, false),

	/** The whole fight, Enter to Finish. */
	HUNLLEF_FIGHT("Hunllef", true, false),
	/** Each quarter of the Hunllef's HP on its own, to see where damage dropped off. */
	QUARTER_1("100-75", true, true),
	QUARTER_2("75-50", true, true),
	QUARTER_3("50-25", true, true),
	QUARTER_4("25-0", true, true),

	/** The Hunllef died: the run's total time. */
	HUNLLEF("Finish", true, false),

	/** Recorded HP thresholds that the quarters are built from. Shown via the quarters. */
	HUNLLEF_75("75% HP", false, false),
	/** Shown as the "50%" split, timed from the start of the run like on the Timer. */
	HUNLLEF_50("50%", true, false),
	HUNLLEF_25("25% HP", false, false),

	// Older versions split on each demi-boss. Kept only so those run files still load;
	// RunStore folds them into DEMIS.
	BEAR("Bear", false, false),
	DRAGON("Dragon", false, false),
	DARK_BEAST("Dark Beast", false, false);

	private final String displayName;
	private final boolean shown;
	/** Listed indented under the Hunllef fight. */
	private final boolean subSplit;

	/** Shorter name for the narrow Last Run card. */
	public String getShortName()
	{
		switch (this)
		{
			case FIRST_PERFECTED:
				return "Perf Wep";
			case SECOND_PERFECTED:
				return "2nd Perf Wep";
			case BOSS_ROOM:
				return "Prep";
			default:
				return displayName;
		}
	}

	/** The splits the in-raid Timer shows, listed under "Splits" in the sidebar. */
	public static final List<Milestone> MAIN = Collections.unmodifiableList(Arrays.asList(
		BOSS_ROOM, HUNLLEF_50, HUNLLEF));

	/** Everything else, listed under "Other Splits". */
	public static final List<Milestone> OTHER = Collections.unmodifiableList(Arrays.asList(
		FIRST_WEAPON, DEMIS, FIRST_PERFECTED, SECOND_PERFECTED, FULL_ARMOUR,
		HUNLLEF_FIGHT, QUARTER_1, QUARTER_2, QUARTER_3, QUARTER_4));

	/** Recorded HP thresholds: stored from the run start, moved with Enter when it's corrected. */
	static final List<Milestone> HP_THRESHOLDS = Collections.unmodifiableList(Arrays.asList(
		HUNLLEF_75, HUNLLEF_50, HUNLLEF_25));

	/** The recorded split this milestone ends at (itself, unless it's a segment). */
	public Milestone end()
	{
		switch (this)
		{
			case HUNLLEF_FIGHT:
			case QUARTER_4:
				return HUNLLEF;
			case QUARTER_1:
				return HUNLLEF_75;
			case QUARTER_2:
				return HUNLLEF_50;
			case QUARTER_3:
				return HUNLLEF_25;
			default:
				return this;
		}
	}

	/** The recorded split this milestone is timed from, or null for the start of the run. */
	public Milestone since()
	{
		switch (this)
		{
			case HUNLLEF_FIGHT:
			case QUARTER_1:
				return BOSS_ROOM;
			case QUARTER_2:
				return HUNLLEF_75;
			case QUARTER_3:
				return HUNLLEF_50;
			case QUARTER_4:
				return HUNLLEF_25;
			default:
				return null;
		}
	}

	/** Timed from another split rather than the run start; shown with a "+". */
	public boolean isRelative()
	{
		return since() != null;
	}

	/** Computed from other splits rather than recorded directly. */
	public boolean isSegment()
	{
		return end() != this;
	}
}
