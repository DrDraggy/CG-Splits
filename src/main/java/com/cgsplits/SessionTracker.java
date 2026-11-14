package com.cgsplits;

import java.util.Collections;
import java.util.EnumMap;
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
	private int enters;
	private long totalEnterTicks;
	private final Map<Armour, int[]> armourEnters = new EnumMap<>(Armour.class); // {count, totalTicks}

	public synchronized void onRunStarted(long now)
	{
		active = true;
		runs++;
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
		totalEnterTicks += enterTicks;
		if (armour != null)
		{
			int[] stats = armourEnters.computeIfAbsent(armour, a -> new int[2]);
			stats[0]++;
			stats[1] += enterTicks;
		}
	}

	/** @param reset the run ended (died or left) before reaching the Hunllef room */
	public synchronized void onRunEnded(boolean reset)
	{
		if (active && reset)
		{
			resets++;
		}
	}

	public synchronized void reset()
	{
		active = false;
		accumulatedMs = 0;
		clockRunning = false;
		clockResumedAt = 0;
		ticksOutside = 0;
		runs = 0;
		resets = 0;
		enters = 0;
		totalEnterTicks = 0;
		armourEnters.clear();
	}

	public synchronized Snapshot snapshot(long now)
	{
		if (!active)
		{
			return new Snapshot(false, false, 0, 0, 0, 0, 0, 0, Collections.emptyMap());
		}
		long elapsed = accumulatedMs + (clockRunning ? Math.max(0, now - clockResumedAt) : 0);
		double hours = elapsed / 3_600_000.0;
		double eph = hours > 0 ? enters / hours : 0;

		Map<Armour, EnterStats> byArmour = new EnumMap<>(Armour.class);
		armourEnters.forEach((armour, s) -> byArmour.put(armour, new EnterStats(s[0], average(s[1], s[0]))));

		return new Snapshot(true, !clockRunning, elapsed, runs, resets, enters, average(totalEnterTicks, enters), eph,
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
			accumulatedMs += Math.max(0, now - clockResumedAt);
			clockRunning = false;
		}
	}

	private static int average(long total, int count)
	{
		return count == 0 ? 0 : (int) Math.round((double) total / count);
	}
}
