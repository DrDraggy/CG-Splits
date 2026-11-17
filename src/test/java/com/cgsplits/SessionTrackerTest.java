package com.cgsplits;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class SessionTrackerTest
{
	private static final long MIN = 60_000L;

	@Test
	public void entersPerHourAverageEnterAndResets()
	{
		SessionTracker session = new SessionTracker();
		long t0 = 1_000_000L;

		session.onRunStarted(t0);
		session.onEnter(500, Armour.FULL);

		session.onRunStarted(t0 + 8 * MIN);
		session.onRunEnded(true, false); // reset in prep

		session.onRunStarted(t0 + 10 * MIN);
		session.onEnter(600, Armour.NONE);

		session.onRunStarted(t0 + 20 * MIN);
		session.onEnter(700, Armour.FULL);

		// Never left the instance, so all 30 minutes count
		SessionTracker.Snapshot s = session.snapshot(t0 + 30 * MIN);
		assertEquals(4, s.getRuns());
		assertEquals(1, s.getResets());
		assertEquals(3, s.getEnters());
		assertEquals(600, s.getAverageEnterTicks());
		assertEquals(6.0, s.getEntersPerHour(), 0.0001);
		assertEquals(30 * MIN, s.getElapsedMs());

		assertEquals(2, s.getByArmour().get(Armour.FULL).getCount());
		assertEquals(600, s.getByArmour().get(Armour.FULL).getAverageTicks());
		assertEquals(1, s.getByArmour().get(Armour.NONE).getCount());
		assertNull(s.getByArmour().get(Armour.PARTIAL));
	}

	@Test
	public void deletedRunTakenBackOut()
	{
		SessionTracker session = new SessionTracker();
		long t0 = 1_000_000L;

		session.onRunStarted(t0);
		session.onEnter(500, Armour.FULL);
		session.onRunEnded(false, true); // a real kill

		session.onRunStarted(t0 + 10 * MIN);
		session.onEnter(300, Armour.FULL);
		session.onRunEnded(false, true); // a test kill

		session.onRunStarted(t0 + 20 * MIN);
		session.onRunEnded(true, false); // a reset

		Run test = new Run();
		test.setStartedAt(t0 + 10 * MIN);
		test.getSplits().put(Milestone.BOSS_ROOM, 300);
		session.removeRun(test);

		SessionTracker.Snapshot s = session.snapshot(t0 + 30 * MIN);
		assertEquals(2, s.getRuns());
		assertEquals(1, s.getEnters());
		assertEquals(500, s.getAverageEnterTicks());
		assertEquals(1, s.getKills());
		assertEquals(1, s.getResets());
		assertEquals(1, s.getByArmour().get(Armour.FULL).getCount());
		assertEquals(500, s.getByArmour().get(Armour.FULL).getAverageTicks());
		assertEquals(1, session.overall(t0 + 30 * MIN).getEnters());

		// A run from before play time was tracked was never in the overall enters
		Run ancient = new Run();
		ancient.setStartedAt(t0 - 60 * MIN);
		ancient.getSplits().put(Milestone.BOSS_ROOM, 400);
		session.removeRun(ancient);
		assertEquals(1, session.snapshot(t0 + 30 * MIN).getEnters());
		assertEquals(1, session.overall(t0 + 30 * MIN).getEnters());

		// After a session reset, deleting an earlier run only takes it out of the overall enters
		session.reset(t0 + 30 * MIN);
		Run earlier = new Run();
		earlier.setStartedAt(t0);
		earlier.getSplits().put(Milestone.BOSS_ROOM, 500);
		session.removeRun(earlier);
		assertEquals(0, session.overall(t0 + 30 * MIN).getEnters());
		assertFalse(session.snapshot(t0 + 30 * MIN).isActive());
	}

	private static final long TICK = 600L;

	@Test
	public void shortTripsOutsideKeepCounting()
	{
		SessionTracker session = new SessionTracker();
		session.onRunStarted(0);
		session.onTick(true, 10 * MIN);

		// Reset: 5 ticks in the lobby before the next run, the clock keeps going
		for (int i = 1; i <= SessionTracker.PAUSE_AFTER_TICKS_OUTSIDE; i++)
		{
			session.onTick(false, 10 * MIN + i * TICK);
		}
		SessionTracker.Snapshot s = session.snapshot(10 * MIN + 5 * TICK);
		assertFalse(s.isPaused());
		assertEquals(10 * MIN + 5 * TICK, s.getElapsedMs());
	}

	@Test
	public void clockPausesAfterFiveTicksOutside()
	{
		SessionTracker session = new SessionTracker();
		session.onRunStarted(0);
		session.onEnter(500, Armour.FULL);
		session.onTick(true, 10 * MIN);

		// The 6th tick outside pauses it, counting the time up to then
		long t = 10 * MIN;
		for (int i = 1; i <= SessionTracker.PAUSE_AFTER_TICKS_OUTSIDE + 1; i++)
		{
			t = 10 * MIN + i * TICK;
			session.onTick(false, t);
		}
		long pausedAt = t;
		assertTrue(session.snapshot(pausedAt).isPaused());

		// AFK for an hour: the clock doesn't move
		assertEquals(pausedAt, session.snapshot(pausedAt + 60 * MIN).getElapsedMs());

		// Next run resumes it
		session.onRunStarted(pausedAt + 60 * MIN);
		assertFalse(session.snapshot(pausedAt + 60 * MIN).isPaused());
		assertEquals(pausedAt + MIN, session.snapshot(pausedAt + 61 * MIN).getElapsedMs());
	}

	@Test
	public void logoutPausesStraightAway()
	{
		SessionTracker session = new SessionTracker();
		session.onRunStarted(0);
		session.pause(5 * MIN);
		assertTrue(session.snapshot(30 * MIN).isPaused());
		assertEquals(5 * MIN, session.snapshot(30 * MIN).getElapsedMs());
	}

	@Test
	public void clockDoesNotStartBeforeFirstRun()
	{
		SessionTracker session = new SessionTracker();
		session.onTick(true, 0);
		assertFalse(session.snapshot(10 * MIN).isActive());
	}

	@Test
	public void armourEnterCounts()
	{
		SessionTracker session = new SessionTracker();
		session.onRunStarted(0);
		session.onEnter(500, Armour.FULL);
		session.onEnter(400, Armour.NONE);
		session.onEnter(450, Armour.NONE);
		SessionTracker.Snapshot s = session.snapshot(MIN);
		assertEquals(1, s.entersWith(Armour.FULL));
		assertEquals(2, s.entersWith(Armour.NONE));
		assertEquals(0, s.entersWith(Armour.BURGER));
	}

	@Test
	public void resetClearsSession()
	{
		SessionTracker session = new SessionTracker();
		session.onRunStarted(0);
		session.onEnter(500, Armour.FULL);
		session.onRunEnded(true, false);
		session.onRunEnded(false, true);
		assertEquals(1, session.snapshot(MIN).getKills());
		session.reset(MIN);
		assertFalse(session.snapshot(2 * MIN).isActive());
		session.onEnter(500, Armour.FULL); // enter without a run start is ignored
		assertEquals(0, session.snapshot(3 * MIN).getEnters());
		assertEquals(0, session.snapshot(3 * MIN).getResets());
		assertEquals(0, session.snapshot(3 * MIN).getKills());
	}

	@Test
	public void overallKeepsCountingThroughSessionResets()
	{
		SessionTracker session = new SessionTracker();
		session.setOverall(new PlayTime(60 * MIN, 5, 123L)); // saved last time

		session.onRunStarted(0);
		session.onEnter(500, Armour.FULL);
		session.reset(10 * MIN);

		session.onRunStarted(20 * MIN);
		session.onEnter(450, Armour.NONE);

		// 60 saved + 10 before the reset + 5 running now; AFK time between doesn't count
		PlayTime overall = session.overall(25 * MIN);
		assertEquals(75 * MIN, overall.getMs());
		assertEquals(7, overall.getEnters());
		assertEquals(123L, overall.getSince());
	}

	@Test
	public void overallStartsAtFirstRun()
	{
		SessionTracker session = new SessionTracker();
		assertEquals(0, session.overall(MIN).getSince());
		session.onRunStarted(5 * MIN);
		assertEquals(5 * MIN, session.overall(6 * MIN).getSince());
		assertEquals(MIN, session.overall(6 * MIN).getMs());
	}

	@Test
	public void olderSavedPlayTimeDoesNotOverwriteMemory()
	{
		SessionTracker session = new SessionTracker();
		session.onRunStarted(0);
		session.pause(30 * MIN);
		session.setOverall(new PlayTime(10 * MIN, 0, 1L));
		assertEquals(30 * MIN, session.overall(40 * MIN).getMs());
	}

	@Test
	public void parsesTypedTimesAndWorldRecord()
	{
		assertEquals(Integer.valueOf(170), TimeFormat.parseUserTime("1:42"));   // 102s
		assertEquals(Integer.valueOf(171), TimeFormat.parseUserTime(" 1:42.6 "));
		assertEquals(Integer.valueOf(97), TimeFormat.parseUserTime("58"));      // plain seconds
		assertNull(TimeFormat.parseUserTime(""));
		assertNull(TimeFormat.parseUserTime("fast"));

		assertEquals(Integer.valueOf(170), WorldRecord.ticks(Milestone.BOSS_ROOM)); // 1:42
		assertEquals(Integer.valueOf(395), WorldRecord.ticks(Milestone.HUNLLEF));   // 3:57
		// The WR's prep plus its fight time is its overall time
		assertEquals(WorldRecord.ticks(Milestone.HUNLLEF),
			Integer.valueOf(WorldRecord.ticks(Milestone.BOSS_ROOM) + WorldRecord.ticks(Milestone.HUNLLEF_FIGHT)));
		assertNull(WorldRecord.ticks(Milestone.DEMIS));
	}

	@Test
	public void ephDropsDecimalsWithoutRounding()
	{
		assertEquals("4", TimeFormat.eph(4.99));
		assertEquals("4", TimeFormat.eph(4.4));
		assertEquals("5", TimeFormat.eph(5.0));
		assertEquals("0", TimeFormat.eph(0.7));
		assertEquals("4.5", TimeFormat.ephPrecise(4.567));
		assertEquals("5.0", TimeFormat.ephPrecise(5.0));
		assertEquals("0.9", TimeFormat.ephPrecise(0.999));
	}
}
