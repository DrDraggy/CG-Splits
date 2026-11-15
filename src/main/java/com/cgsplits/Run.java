package com.cgsplits;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * One Gauntlet attempt. Serialized to JSON as-is, so keep fields simple.
 * All times are in game ticks (1 tick = 600ms), counted from entering the instance.
 */
@Data
public class Run
{
	public enum Mode
	{
		UNKNOWN,
		NORMAL,
		CORRUPTED
	}

	public enum EndReason
	{
		COMPLETED,
		DIED,
		/** Left the instance (teleported out, logged out, disconnected). */
		LEFT,
		/** Plugin was turned off mid-run. */
		ABANDONED
	}

	private long startedAt;
	private Mode mode = Mode.UNKNOWN;
	private boolean completed;
	private EndReason endReason;
	/** Armour held when entering the Hunllef room; null if the run never got there. */
	private Armour armourAtEnter;
	/**
	 * Perfected weapons made before entering the Hunllef room, e.g. ["Staff", "Bow"]; empty if
	 * none, null for runs saved before this was recorded.
	 */
	private List<String> perfectedAtEnter;
	private int ticks;
	private Map<Milestone, Integer> splits = new LinkedHashMap<>();

	/** Times the game prints in chat on completion, e.g. "6:42.60". Ground truth for validation. */
	private String officialDuration;
	private String officialPrep;

	/**
	 * How many ticks our live timer was off from the game's official times before being
	 * corrected (positive = we were ahead). Kept to check the timer stays in sync.
	 */
	private Integer prepDriftTicks;
	private Integer timerDriftTicks;

	public boolean hasSplit(Milestone milestone)
	{
		return splits.containsKey(milestone);
	}

	/**
	 * The milestone's display time in ticks: since the run started for most milestones, or the
	 * length of the segment for ones timed from another split (the Hunllef fight, each HP
	 * quarter). Null if a split it needs is missing.
	 */
	public Integer splitTime(Milestone milestone)
	{
		Integer end = splits.get(milestone.end());
		if (end == null || milestone.since() == null)
		{
			return end;
		}
		Integer start = splits.get(milestone.since());
		return start == null ? null : end - start;
	}

	/**
	 * Runs saved by older versions have a split per demi-boss (and may contain null keys
	 * for milestones that no longer exist). Fold all three demis into one DEMIS split,
	 * reached when the last of them died, and drop the old keys.
	 */
	public void migrateLegacySplits()
	{
		splits.remove(null);
		// The T1 armour split was dropped; the T1 Enter row covers it
		splits.remove(Milestone.FULL_ARMOUR);
		Integer bear = splits.remove(Milestone.BEAR);
		Integer dragon = splits.remove(Milestone.DRAGON);
		Integer darkBeast = splits.remove(Milestone.DARK_BEAST);
		// The Demi split is the first demi killed
		Integer firstDemi = null;
		for (Integer demi : new Integer[]{bear, dragon, darkBeast})
		{
			if (demi != null && (firstDemi == null || demi < firstDemi))
			{
				firstDemi = demi;
			}
		}
		if (firstDemi != null && !splits.containsKey(Milestone.DEMIS))
		{
			splits.put(Milestone.DEMIS, firstDemi);
		}

		// Partial armour enters are burger enters now
		if (armourAtEnter == Armour.PARTIAL)
		{
			armourAtEnter = Armour.BURGER;
		}

		// Older versions kept the sentence's full stop ("5:42.60.") and didn't snap to the
		// game's official times; fix both now
		if (officialPrep != null && officialPrep.endsWith("."))
		{
			officialPrep = officialPrep.substring(0, officialPrep.length() - 1);
		}
		if (officialDuration != null && officialDuration.endsWith("."))
		{
			officialDuration = officialDuration.substring(0, officialDuration.length() - 1);
		}
		Integer officialPrepTicks = TimeFormat.parseOfficial(officialPrep);
		if (officialPrepTicks != null && splits.containsKey(Milestone.BOSS_ROOM))
		{
			splits.put(Milestone.BOSS_ROOM, officialPrepTicks);
		}
		Integer officialTicks = TimeFormat.parseOfficial(officialDuration);
		if (officialTicks != null && completed)
		{
			ticks = officialTicks;
			splits.put(Milestone.HUNLLEF, officialTicks);
		}
	}

	/** End reason, with a fallback for runs saved before it was recorded. */
	public EndReason resolveEndReason()
	{
		if (endReason != null)
		{
			return endReason;
		}
		return completed ? EndReason.COMPLETED : EndReason.LEFT;
	}

	/** The latest milestone this run reached, or null if it reached none. */
	public Milestone furthestSplit()
	{
		Milestone furthest = null;
		int furthestTick = -1;
		for (Map.Entry<Milestone, Integer> split : splits.entrySet())
		{
			if (split.getValue() >= furthestTick)
			{
				furthest = split.getKey();
				furthestTick = split.getValue();
			}
		}
		return furthest;
	}

	/**
	 * Walked into the Hunllef room without ever holding a corrupted weapon (e.g. testing or
	 * throwing a run). These aren't real enters, so they're not saved or counted.
	 */
	public boolean isWeaponlessEnter()
	{
		return hasSplit(Milestone.BOSS_ROOM) && !hasSplit(Milestone.FIRST_WEAPON);
	}

	/** A run that ended (died or left) before reaching the Hunllef room. */
	public boolean isReset()
	{
		EndReason reason = resolveEndReason();
		return !hasSplit(Milestone.BOSS_ROOM) && (reason == EndReason.DIED || reason == EndReason.LEFT);
	}

	/** Runs whose mode wasn't detected are assumed to be corrupted; normal runs are only saved when tracked. */
	public boolean isCorrupted()
	{
		return mode != Mode.NORMAL;
	}
}
