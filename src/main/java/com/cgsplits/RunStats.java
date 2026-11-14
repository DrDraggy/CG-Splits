package com.cgsplits;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import lombok.Getter;

/**
 * Aggregate stats across runs, including incomplete ones, like paceman.gg's stats page:
 * how many runs reached each split, and the average and best time to get there.
 */
@Getter
public class RunStats
{
	@Getter
	public static class MilestoneStats
	{
		private int count;
		private long totalTicks;
		private int bestTicks = Integer.MAX_VALUE;

		void add(int ticks)
		{
			count++;
			totalTicks += ticks;
			bestTicks = Math.min(bestTicks, ticks);
		}

		public int getAverageTicks()
		{
			return count == 0 ? 0 : (int) Math.round((double) totalTicks / count);
		}
	}

	private int runs;
	private int completions;
	private int deaths;
	private int resets;
	private final Map<Milestone, MilestoneStats> milestones = new EnumMap<>(Milestone.class);
	/** Enter times (prep) grouped by armour worn on entry. */
	private final Map<Armour, MilestoneStats> enters = new EnumMap<>(Armour.class);
	/** Completed runs grouped by armour worn on entry. */
	private final Map<Armour, Integer> completionsByArmour = new EnumMap<>(Armour.class);

	static RunStats of(Iterable<Run> runs)
	{
		RunStats stats = new RunStats();
		for (Run run : runs)
		{
			stats.runs++;
			switch (run.resolveEndReason())
			{
				case COMPLETED:
					stats.completions++;
					break;
				case DIED:
					stats.deaths++;
					break;
				default:
					break;
			}
			if (run.isReset())
			{
				stats.resets++;
			}
			for (Milestone milestone : Milestone.values())
			{
				Integer time = run.splitTime(milestone);
				if (time != null)
				{
					stats.milestones.computeIfAbsent(milestone, m -> new MilestoneStats()).add(time);
				}
			}

			Armour armour = run.getArmourAtEnter();
			Integer enter = run.getSplits().get(Milestone.BOSS_ROOM);
			if (armour != null && enter != null)
			{
				stats.enters.computeIfAbsent(armour, a -> new MilestoneStats()).add(enter);
				if (run.isCompleted())
				{
					stats.completionsByArmour.merge(armour, 1, Integer::sum);
				}
			}
		}
		return stats;
	}

	public Map<Milestone, MilestoneStats> getMilestones()
	{
		return Collections.unmodifiableMap(milestones);
	}

	public Map<Armour, MilestoneStats> getEnters()
	{
		return Collections.unmodifiableMap(enters);
	}

	/** Share of enters with this armour that ended in a kill, 0-100, or -1 with no enters. */
	public int completionPercent(Armour armour)
	{
		MilestoneStats e = enters.get(armour);
		if (e == null || e.getCount() == 0)
		{
			return -1;
		}
		return (int) Math.round(100.0 * completionsByArmour.getOrDefault(armour, 0) / e.getCount());
	}

	/** Share of all runs that reached this milestone, 0-100. */
	public int percentReached(Milestone milestone)
	{
		MilestoneStats m = milestones.get(milestone);
		return m == null || runs == 0 ? 0 : (int) Math.round(100.0 * m.getCount() / runs);
	}
}
