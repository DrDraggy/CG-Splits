package com.cgsplits;

/**
 * The current Corrupted Gauntlet world record. Shown at the top of the sidebar and used by the
 * "World Record" pace comparison.
 *
 * UPDATE HERE when a new world record is set: change the holder and the four times, rebuild,
 * and everything else follows. Times are "m:ss" (or "m:ss.s").
 */
public final class WorldRecord
{
	public static final String HOLDER = "rawr simon";

	/** Preparation: run start to entering the Hunllef room. */
	public static final String PREP = "1:42";
	/** Hunllef at 50% HP, from the start of the run. */
	public static final String HALF_HP = "2:53";
	/** The Hunllef fight on its own, Enter to kill. */
	public static final String FIGHT = "2:15";
	/** Overall time. */
	public static final String TOTAL = "3:57";

	/**
	 * The Top 3 shown at the top of the sidebar, fastest first:
	 * { name, overall time, armour tag, preparation, Hunllef fight }.
	 * UPDATE HERE when the leaderboard changes. A blank name shows as "-"; the tag and the two
	 * split times are optional (hover a place to see its splits).
	 */
	public static final String[][] TOP_3 = {
		{"rawr simon", "3:57.0", "T1 Prep", "1:42", "2:15"},
		{"Slinkey", "3:58.8", "No Arm", "1:30.6", "2:28.2"},
		// No full VOD, so the prep type isn't known
		{"Tate", "3:59.4", "", "1:27.6", "2:31.8"},
	};

	private WorldRecord()
	{
	}

	/** World record time for a timer split, in ticks, or null if there isn't one. */
	static Integer ticks(Milestone milestone)
	{
		switch (milestone)
		{
			case BOSS_ROOM:
				return TimeFormat.parseUserTime(PREP);
			case HUNLLEF_50:
				return TimeFormat.parseUserTime(HALF_HP);
			case HUNLLEF_FIGHT:
				return TimeFormat.parseUserTime(FIGHT);
			case HUNLLEF:
				return TimeFormat.parseUserTime(TOTAL);
			default:
				return null;
		}
	}
}
