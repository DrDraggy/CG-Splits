package com.cgsplits;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import lombok.Value;

/**
 * Tracks the current play session: runs started, resets, Hunllef room enters, enters per hour
 * (EPH) and average enter time, overall and split by armour worn on entry.
 *
 * The session clock runs all session long, including the short trips through the lobby between
 * runs, so EPH reflects real play time. If the player stays outside the instance for more than
 * {@link #PAUSE_AFTER_TICKS_OUTSIDE} ticks (AFK, banking, logged out) the clock pauses until
 * they're back in a run. A session starts with its first run and lasts until reset manually or
 * the client restarts.
 *
 * Written from the client thread and read from the Swing/overlay threads, hence synchronized.
 */
public class SessionTracker
{
	/** Ticks outside the instance before the session clock pauses. */
	static final int PAUSE_AFTER_TICKS_OUTSIDE = 5;

	@Value
	public static class EnterStats
	{
		int count;
		int averageTicks;
	}

	@Value
	public static class Snapshot
	{
		boolean active;
		/** The clock is paused because the player has been out of the instance for a while. */
		boolean paused;
		/** Session time that counts towards EPH. */
		long elapsedMs;
		int runs;
		int resets;
		int kills;
		int enters;
		int averageEnterTicks;
		double entersPerHour;
		/** Only armour types with at least one enter are present. */
		Map<Armour, EnterStats> byArmour;

		/** Enters with this armour so far this session. */
		public int entersWith(Armour armour)
		{
			EnterStats stats = byArmour.get(armour);
			return stats == null ? 0 : stats.getCount();
		}
	}

	private boolean active;
	private long accumulatedMs;
	private boolean clockRunning;
	private long clockResumedAt;
	private int ticksOutside;
	private int runs;
	private int resets;
	private int kills;
	private int enters;
	private long totalEnterTicks;
	private final Map<Armour, int[]> armourEnters = new EnumMap<>(Armour.class); // {count, totalTicks}

	/** What a run added to this session's totals, so a deleted run can be taken back out. */
	private static class Counted
	{
		boolean entered;
		int enterTicks;
		Armour armour;
		boolean reset;
		boolean killed;
	}

	/** This session's runs by start time ({@link Run#getStartedAt()}). */
	private final Map<Long, Counted> counted = new HashMap<>();
	private Counted currentRun;

	// Overall play time and enters across every session: not cleared by reset(), saved between restarts
	private long overallMs;
	private int overallEnters;
	private long overallSince;

	/** Restores the overall totals saved last time the plugin ran. */
	public synchronized void setOverall(PlayTime saved)
	{
		// The plugin object outlives being turned off and on, so what's in memory may be newer
		// than the file (which is written in the background)
		if (saved.getMs() < overallMs)
		{
			return;
		}
		overallMs = Math.max(0, saved.getMs());
		overallEnters = Math.max(0, saved.getEnters());
		overallSince = saved.getSince();
	}

	/** Overall play time and enters, including the stretch the clock is running right now. */
	public synchronized PlayTime overall(long now)
	{
		long running = clockRunning ? Math.max(0, now - clockResumedAt) : 0;
		return new PlayTime(overallMs + running, overallEnters, overallSince);
	}

	/** @param now when the run started, the same time it's saved with as {@link Run#getStartedAt()} */
	public synchronized void onRunStarted(long now)
	{
		if (overallSince == 0)
		{
			overallSince = now;
		}
		active = true;
		runs++;
		currentRun = new Counted();
		counted.put(now, currentRun);
		ticksOutside = 0;
		resumeClock(now);
	}

	/**
	 * Called every game tick. Inside the instance the clock runs; outside it keeps running for
	 * up to {@link #PAUSE_AFTER_TICKS_OUTSIDE} ticks (time between runs counts) and then pauses.
	 */
	public synchronized void onTick(boolean inInstance, long now)
	{
		if (!active)
		{
			return;
		}
		if (inInstance)
		{
			ticksOutside = 0;
			resumeClock(now);
		}
		else if (++ticksOutside > PAUSE_AFTER_TICKS_OUTSIDE)
		{
			pauseClock(now);
		}
	}

	/** Pause straight away, e.g. on logout or world hop. */
	public synchronized void pause(long now)
	{
		if (active)
		{
			ticksOutside = PAUSE_AFTER_TICKS_OUTSIDE + 1;
			pauseClock(now);
		}
	}

	/** The player walked into the Hunllef room, {@code enterTicks} ticks into the run. */
	public synchronized void onEnter(int enterTicks, Armour armour)
	{
		if (!active)
		{
			return;
		}
		enters++;
		overallEnters++;
		totalEnterTicks += enterTicks;
		if (armour != null)
		{
			int[] stats = armourEnters.computeIfAbsent(armour, a -> new int[2]);
			stats[0]++;
			stats[1] += enterTicks;
		}
		if (currentRun != null)
		{
			currentRun.entered = true;
			currentRun.enterTicks = enterTicks;
			currentRun.armour = armour;
		}
	}

	/**
	 * @param reset  the run ended (died or left) before reaching the Hunllef room
	 * @param killed the run ended with the Hunllef dead
	 */
	public synchronized void onRunEnded(boolean reset, boolean killed)
	{
		if (!active)
		{
			return;
		}
		if (reset)
		{
			resets++;
		}
		if (killed)
		{
			kills++;
		}
		if (currentRun != null)
		{
			currentRun.reset = reset;
			currentRun.killed = killed;
			currentRun = null;
		}
	}

	/**
	 * A saved run was deleted: take it back out of the session (if it was played this session)
	 * and out of the overall enters used for overall EPH. The time played still counts.
	 */
	public synchronized void removeRun(Run run)
	{
		Counted c = counted.remove(run.getStartedAt());
		if (c == currentRun)
		{
			currentRun = null;
		}
		if (c != null && active)
		{
			runs = Math.max(0, runs - 1);
			if (c.reset)
			{
				resets = Math.max(0, resets - 1);
			}
			if (c.killed)
			{
				kills = Math.max(0, kills - 1);
			}
			if (c.entered)
			{
				enters = Math.max(0, enters - 1);
				totalEnterTicks = Math.max(0, totalEnterTicks - c.enterTicks);
				int[] stats = c.armour == null ? null : armourEnters.get(c.armour);
				if (stats != null)
				{
					stats[0]--;
					stats[1] -= c.enterTicks;
					if (stats[0] <= 0)
					{
						armourEnters.remove(c.armour);
					}
				}
			}
		}

		// Overall enters count every enter since play time was first tracked, across sessions
		boolean entered = c != null ? c.entered : run.hasSplit(Milestone.BOSS_ROOM);
		boolean tracked = c != null || (overallSince != 0 && run.getStartedAt() >= overallSince);
		if (entered && tracked)
		{
			overallEnters = Math.max(0, overallEnters - 1);
		}
	}

	/** Starts a fresh session; time played so far still counts towards the overall total. */
	public synchronized void reset(long now)
	{
		pauseClock(now);
		active = false;
		accumulatedMs = 0;
		clockRunning = false;
		clockResumedAt = 0;
		ticksOutside = 0;
		runs = 0;
		resets = 0;
		kills = 0;
		enters = 0;
		totalEnterTicks = 0;
		armourEnters.clear();
		counted.clear();
		currentRun = null;
	}

	public synchronized Snapshot snapshot(long now)
	{
		if (!active)
		{
			return new Snapshot(false, false, 0, 0, 0, 0, 0, 0, 0, Collections.emptyMap());
		}
		long elapsed = accumulatedMs + (clockRunning ? Math.max(0, now - clockResumedAt) : 0);
		double hours = elapsed / 3_600_000.0;
		double eph = hours > 0 ? enters / hours : 0;

		Map<Armour, EnterStats> byArmour = new EnumMap<>(Armour.class);
		armourEnters.forEach((armour, s) -> byArmour.put(armour, new EnterStats(s[0], average(s[1], s[0]))));

		return new Snapshot(true, !clockRunning, elapsed, runs, resets, kills, enters, average(totalEnterTicks, enters), eph,
			Collections.unmodifiableMap(byArmour));
	}

	private void resumeClock(long now)
	{
		if (!clockRunning)
		{
			clockRunning = true;
			clockResumedAt = now;
		}
	}

	private void pauseClock(long now)
	{
		if (clockRunning)
		{
			long segment = Math.max(0, now - clockResumedAt);
			accumulatedMs += segment;
			overallMs += segment;
			clockRunning = false;
		}
	}

	private static int average(long total, int count)
	{
		return count == 0 ? 0 : (int) Math.round((double) total / count);
	}
}
