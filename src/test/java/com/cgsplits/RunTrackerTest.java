package com.cgsplits;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Before;
import org.junit.Test;

public class RunTrackerTest
{
	private RunTracker tracker;

	@Before
	public void setUp()
	{
		tracker = new RunTracker();
		tick(false, false); // prime
	}

	private Run tick(boolean inGauntlet, boolean inBossRoom)
	{
		return tracker.onTick(inGauntlet, true, inBossRoom, Run.Mode.UNKNOWN, 0L);
	}

	private void ticks(int n)
	{
		for (int i = 0; i < n; i++)
		{
			assertNull(tick(true, false));
		}
	}

	@Test
	public void fullRun()
	{
		tick(true, false);
		assertEquals(RunTracker.State.PREP, tracker.getState());

		ticks(60);
		tracker.onNpcDeath("Corrupted Bear"); // first demi: the Demi split
		ticks(20);
		tracker.onNpcDeath("Corrupted Dragon"); // later demis don't move it
		ticks(20);
		tracker.onNpcDeath("Corrupted Dark Beast");
		ticks(50);
		tracker.onItems(Arrays.asList("Corrupted shards", "Corrupted staff (attuned)"));
		ticks(50);
		tracker.onItems(Arrays.asList("Corrupted staff (perfected)", "Corrupted bow (perfected)",
			"Corrupted helm (basic)", "Corrupted body (basic)", "Corrupted legs (basic)"));
		ticks(100);
		assertNull(tick(true, true));
		assertEquals(RunTracker.State.BOSS, tracker.getState());
		ticks(99);
		tracker.onNpcDeath("Corrupted Hunllef");
		assertEquals(RunTracker.State.FINISHED, tracker.getState());

		// The game's official times win: prep 3:00.00 = 300 ticks, total 4:01.20 = 402 ticks
		tracker.onOfficialTimes(null, "3:00.00");
		tracker.onOfficialTimes("4:01.20", null);

		Run run = tick(false, false);
		assertNotNull(run);
		assertTrue(run.isCompleted());
		assertEquals(Run.Mode.CORRUPTED, run.getMode());
		assertEquals(402, run.getTicks());
		assertEquals(Integer.valueOf(1), run.getPrepDriftTicks());
		assertEquals(Integer.valueOf(-2), run.getTimerDriftTicks());
		assertEquals(Integer.valueOf(60), run.getSplits().get(Milestone.DEMIS));
		assertFalse(run.hasSplit(Milestone.BEAR));
		assertEquals(Integer.valueOf(150), run.getSplits().get(Milestone.FIRST_WEAPON));
		assertEquals(Integer.valueOf(200), run.getSplits().get(Milestone.FIRST_PERFECTED));
		assertEquals(Integer.valueOf(200), run.getSplits().get(Milestone.SECOND_PERFECTED));
		assertFalse(run.hasSplit(Milestone.FULL_ARMOUR));
		assertEquals(Integer.valueOf(300), run.getSplits().get(Milestone.BOSS_ROOM));
		assertEquals(Armour.FULL, run.getArmourAtEnter());
		assertEquals(Arrays.asList("Staff", "Bow"), run.getPerfectedAtEnter());
		assertFalse(run.isReset());
		assertEquals(Integer.valueOf(402), run.getSplits().get(Milestone.HUNLLEF));
		assertEquals("4:01.20", run.getOfficialDuration());
		assertEquals("3:00.00", run.getOfficialPrep());
		assertEquals(RunTracker.State.IDLE, tracker.getState());
	}

	@Test
	public void deathFailsRun()
	{
		tick(true, false);
		ticks(10);
		Run run = tick(false, false);
		assertNotNull(run);
		assertFalse(run.isCompleted());
		assertFalse(run.hasSplit(Milestone.HUNLLEF));
	}

	@Test
	public void endReasons()
	{
		// Died
		tick(true, false);
		ticks(10);
		tracker.onItems(Collections.singletonList("Corrupted bow (basic)"));
		tracker.onNpcDeath("Corrupted Bear");
		tracker.onLocalPlayerDeath();
		Run died = tick(false, false);
		assertEquals(Run.EndReason.DIED, died.getEndReason());
		assertEquals(Milestone.DEMIS, died.furthestSplit()); // the bear kill, after the weapon

		assertTrue(died.isReset());

		// Left without dying
		tick(true, false);
		ticks(10);
		Run left = tick(false, false);
		assertEquals(Run.EndReason.LEFT, left.getEndReason());
		assertTrue(left.isReset());

		// Entered with no armour, then died to the Hunllef: not a reset
		tick(true, false);
		ticks(10);
		tick(true, true);
		tracker.onLocalPlayerDeath();
		Run bossDeath = tick(false, false);
		assertEquals(Armour.NONE, bossDeath.getArmourAtEnter());
		assertFalse(bossDeath.isReset());

		// Plugin turned off mid-run
		tick(true, false);
		ticks(10);
		Run abandoned = tracker.abandon();
		assertEquals(Run.EndReason.ABANDONED, abandoned.getEndReason());
		assertFalse(abandoned.isCompleted());
		assertNull(tracker.abandon());
	}

	@Test
	public void statsCountIncompleteRuns()
	{
		Run completed = new Run();
		completed.setCompleted(true);
		completed.setEndReason(Run.EndReason.COMPLETED);
		completed.getSplits().put(Milestone.DEMIS, 100);
		completed.getSplits().put(Milestone.HUNLLEF, 500);

		Run died = new Run();
		died.setEndReason(Run.EndReason.DIED);
		died.getSplits().put(Milestone.DEMIS, 200);

		Run legacy = new Run(); // saved before endReason existed

		RunStats stats = RunStats.of(Arrays.asList(completed, died, legacy));
		assertEquals(3, stats.getRuns());
		assertEquals(1, stats.getCompletions());
		assertEquals(1, stats.getDeaths());
		assertEquals(2, stats.getMilestones().get(Milestone.DEMIS).getCount());
		assertEquals(150, stats.getMilestones().get(Milestone.DEMIS).getAverageTicks());
		assertEquals(100, stats.getMilestones().get(Milestone.DEMIS).getBestTicks());
		assertEquals(67, stats.percentReached(Milestone.DEMIS));
		assertEquals(Run.EndReason.LEFT, legacy.resolveEndReason());
	}

	@Test
	public void finishedRunSavedAfterDelayWhileStillInside()
	{
		tick(true, false);
		ticks(5);
		tracker.onNpcDeath("Corrupted Hunllef");
		for (int i = 1; i < RunTracker.SAVE_DELAY_TICKS; i++)
		{
			assertNull(tick(true, true));
		}
		assertNotNull(tick(true, true));
		// Still inside: must not start a new run until we leave and re-enter
		tick(true, true);
		assertEquals(RunTracker.State.IDLE, tracker.getState());
	}

	@Test
	public void timerRunsUntilTheKillCounts()
	{
		tick(true, false);
		ticks(99);
		tick(true, true);
		ticks(100); // 200 ticks in
		tracker.onNpcDeath("Corrupted Hunllef");
		Run run = tracker.getCurrent();
		assertFalse(run.hasSplit(Milestone.HUNLLEF)); // HP is 0, but the game's clock still runs

		// It keeps counting through the death animation, still on screen
		for (int i = 0; i < 4; i++)
		{
			assertNull(tick(true, true));
		}
		assertEquals(204, run.getTicks());
		assertFalse(run.hasSplit(Milestone.HUNLLEF));
		assertEquals(run, tracker.getCurrent());

		// The completion message sets the exact time and freezes it
		tick(true, true);
		tracker.onOfficialTimes("2:03.60", null); // 206 ticks
		assertEquals(Integer.valueOf(206), run.getSplits().get(Milestone.HUNLLEF));
		assertEquals(Integer.valueOf(-1), run.getTimerDriftTicks());
		tick(true, true);
		tick(true, true);
		assertEquals(206, run.getTicks());
		assertEquals(Integer.valueOf(206), run.getSplits().get(Milestone.HUNLLEF));

		Run saved = tick(false, false);
		assertEquals(206, saved.getTicks());
		assertTrue(saved.isCompleted());
	}

	@Test
	public void killTimeEstimatedWithoutCompletionMessage()
	{
		tick(true, false);
		ticks(9);
		tick(true, true); // 10 ticks in
		tracker.onNpcDeath("Corrupted Hunllef");
		for (int i = 1; i < RunTracker.SAVE_DELAY_TICKS; i++)
		{
			assertNull(tick(true, true));
		}
		Run run = tick(true, true);
		int expected = 10 + RunTracker.KILL_COUNT_DELAY_TICKS;
		assertEquals(Integer.valueOf(expected), run.getSplits().get(Milestone.HUNLLEF));
		assertEquals(expected, run.getTicks());

		// Teleported out before the estimate: stops where it got to
		tick(false, false);
		tick(true, false);
		ticks(9);
		tick(true, true);
		tracker.onNpcDeath("Corrupted Hunllef");
		tick(true, true);
		tick(true, true);
		Run left = tick(false, false);
		assertTrue(left.isCompleted());
		assertEquals(Integer.valueOf(12), left.getSplits().get(Milestone.HUNLLEF));
	}

	@Test
	public void pluginStartedMidRunDoesNotStartRun()
	{
		RunTracker fresh = new RunTracker();
		fresh.onTick(true, true, false, Run.Mode.UNKNOWN, 0L);
		fresh.onTick(true, true, false, Run.Mode.UNKNOWN, 0L);
		assertEquals(RunTracker.State.IDLE, fresh.getState());
	}

	@Test
	public void splitsOnlyRecordedOnce()
	{
		tick(true, false);
		ticks(10);
		tracker.onItems(Collections.singletonList("Corrupted halberd (basic)"));
		ticks(10);
		tracker.onItems(Collections.singletonList("Corrupted halberd (basic)"));
		assertEquals(Integer.valueOf(10), tracker.getCurrent().getSplits().get(Milestone.FIRST_WEAPON));
	}

	@Test
	public void timeFormat()
	{
		assertEquals("7:00.0", TimeFormat.ticks(700));
		assertEquals("0.6", TimeFormat.ticks(1));
		assertEquals("59.4", TimeFormat.ticks(99));
		assertEquals("1:00.0", TimeFormat.ticks(100));
		assertEquals("-3.0", TimeFormat.delta(-5));
		assertEquals("+1:03.0", TimeFormat.delta(105));

		assertEquals("6", TimerPrecision.SECONDS.format(10));
		assertEquals("6", TimerPrecision.SECONDS.format(11));
		assertEquals("0", TimerPrecision.SECONDS.format(1));
		assertEquals("1:02", TimerPrecision.SECONDS.format(104));
		assertEquals("6.6", TimerPrecision.TICKS.format(11));
	}

	@Test
	public void timerStartsOnArrivalNotVarbitFlip()
	{
		RunTracker t = new RunTracker();
		t.onTick(false, false, false, Run.Mode.UNKNOWN, 0L); // prime

		// Varbit flips, but the teleport into the instance takes 4 ticks
		for (int i = 0; i < 4; i++)
		{
			t.onTick(true, false, false, Run.Mode.UNKNOWN, 0L);
			assertEquals(RunTracker.State.IDLE, t.getState());
		}
		t.onTick(true, true, false, Run.Mode.UNKNOWN, 0L);
		assertEquals(RunTracker.State.PREP, t.getState());
		assertEquals(0, t.getCurrent().getTicks());
		assertEquals(4, t.getArrivalDelayTicks());

		t.onTick(true, true, false, Run.Mode.UNKNOWN, 0L);
		assertEquals(1, t.getCurrent().getTicks());
	}

	@Test
	public void timerStartsWithTheGameTimer()
	{
		RunTracker t = new RunTracker(RunTracker.GAME_TIMER_START_DELAY);
		t.onTick(false, false, false, Run.Mode.UNKNOWN, 0L); // prime

		// Varbit flips and the player arrives 1 tick later, like the real run in the log
		t.onTick(true, false, false, Run.Mode.UNKNOWN, 0L);
		for (int i = 1; i < RunTracker.GAME_TIMER_START_DELAY; i++)
		{
			t.onTick(true, true, false, Run.Mode.UNKNOWN, 0L);
			assertEquals(RunTracker.State.IDLE, t.getState());
		}
		t.onTick(true, true, false, Run.Mode.UNKNOWN, 0L); // 4 ticks after the flip
		assertEquals(RunTracker.State.PREP, t.getState());
		assertEquals(1, t.getArrivalDelayTicks());
	}

	@Test
	public void cancelledEntryDoesNotStartRun()
	{
		RunTracker t = new RunTracker();
		t.onTick(false, false, false, Run.Mode.UNKNOWN, 0L);
		t.onTick(true, false, false, Run.Mode.UNKNOWN, 0L);
		t.onTick(false, false, false, Run.Mode.UNKNOWN, 0L); // varbit dropped before arriving
		t.onTick(false, true, false, Run.Mode.UNKNOWN, 0L); // some other instance
		assertEquals(RunTracker.State.IDLE, t.getState());
	}

	@Test
	public void parsesOfficialTimes()
	{
		assertEquals(Integer.valueOf(231), TimeFormat.parseOfficial("2:18.60"));
		assertEquals(Integer.valueOf(571), TimeFormat.parseOfficial("5:42.60."));
		assertEquals(Integer.valueOf(100), TimeFormat.parseOfficial("1:00"));
		assertNull(TimeFormat.parseOfficial("soon"));
	}

	@Test
	public void hunllefThresholdNeedsHpBelowIt()
	{
		tick(true, false);
		tick(true, true);
		tracker.onBossHealth(0.509);
		tracker.onBossHealth(0.50); // exactly half isn't below half yet
		assertFalse(tracker.getCurrent().hasSplit(Milestone.HUNLLEF_50));
		tracker.onBossHealth(0.499);
		assertTrue(tracker.getCurrent().hasSplit(Milestone.HUNLLEF_50));
		assertFalse(tracker.getCurrent().hasSplit(Milestone.HUNLLEF_25));
	}

	@Test
	public void bossHealthFromBarAndHud()
	{
		// Bar of 30 with 1000 max HP: ratio 15 covers 483-516 HP
		assertEquals(0.509, RunTracker.bossHealthFraction(15, 30, 509, 1000), 1e-9);
		assertEquals(0.499, RunTracker.bossHealthFraction(15, 30, 499, 1000), 1e-9);
		// HUD off or stale (doesn't match the bar): highest HP the bar allows, never below 50%
		double bar15 = RunTracker.bossHealthFraction(15, 30, 0, 0);
		assertTrue(bar15 >= 0.5 && bar15 < 15 / 29.0);
		assertEquals(bar15, RunTracker.bossHealthFraction(15, 30, 200, 1000), 0);
		assertTrue(RunTracker.bossHealthFraction(14, 30, 0, 0) < 0.5);
		// Unknown before the first hit, 0 once dead, never above full
		assertEquals(-1, RunTracker.bossHealthFraction(-1, -1, 509, 1000), 0);
		assertEquals(0, RunTracker.bossHealthFraction(0, 30, 0, 1000), 0);
		assertTrue(RunTracker.bossHealthFraction(30, 30, 0, 0) <= 1);
	}

	@Test
	public void hunllefHpThresholdsTimedFromEntry()
	{
		tick(true, false);
		ticks(100);
		tracker.onBossHealth(0.5); // ignored during prep
		assertFalse(tracker.getCurrent().hasSplit(Milestone.HUNLLEF_50));

		tick(true, true); // enter at tick 101
		ticks(20);
		tracker.onBossHealth(0.80);
		ticks(10);
		tracker.onBossHealth(0.74); // tick 131: 30 ticks after entry
		ticks(30);
		tracker.onBossHealth(0.20); // one big drop passes both 50% and 25%

		Run run = tracker.getCurrent();
		assertEquals(Integer.valueOf(30), run.splitTime(Milestone.QUARTER_1)); // 100-75
		assertEquals(Integer.valueOf(30), run.splitTime(Milestone.QUARTER_2)); // 75-50
		assertEquals(Integer.valueOf(0), run.splitTime(Milestone.QUARTER_3));  // 50-25, same hit
		// 50% is a full-run time, like Preparation and Finish
		assertEquals(Integer.valueOf(161), run.splitTime(Milestone.HUNLLEF_50));

		tracker.onBossHealth(0.10); // later readings don't move a split
		assertEquals(Integer.valueOf(0), run.splitTime(Milestone.QUARTER_3));

		// Official prep 1 tick earlier than ours: the whole fight moves with Enter, so the
		// quarters keep their lengths and 50% moves 1 tick earlier too
		tracker.onNpcDeath("Corrupted Hunllef");
		tracker.onOfficialTimes(null, "1:00.00"); // 100 ticks
		assertEquals(Integer.valueOf(100), run.getSplits().get(Milestone.BOSS_ROOM));
		assertEquals(Integer.valueOf(30), run.splitTime(Milestone.QUARTER_1));
		assertEquals(Integer.valueOf(30), run.splitTime(Milestone.QUARTER_2));
		assertEquals(Integer.valueOf(160), run.splitTime(Milestone.HUNLLEF_50));
	}

	@Test
	public void burgerEnters()
	{
		// Basic weapon and nothing else
		assertEquals(Armour.BURGER, Armour.classify(0, 1));
		// Partial armour, whatever the weapon
		assertEquals(Armour.BURGER, Armour.classify(1, 3));
		assertEquals(Armour.BURGER, Armour.classify(2, 1));
		// Real enters
		assertEquals(Armour.NONE, Armour.classify(0, 2));
		assertEquals(Armour.NONE, Armour.classify(0, 3));
		assertEquals(Armour.FULL, Armour.classify(3, 1));

		// Through the tracker: basic staff only
		tick(true, false);
		ticks(10);
		tracker.onItems(Collections.singletonList("Corrupted staff (basic)"));
		tick(true, true);
		assertEquals(Armour.BURGER, tracker.getCurrent().getArmourAtEnter());
		tick(false, false);

		// Upgrading a basic weapon to attuned is no longer a burger
		tick(true, false);
		tracker.onItems(Collections.singletonList("Corrupted bow (basic)"));
		ticks(10);
		tracker.onItems(Collections.singletonList("Corrupted bow (attuned)"));
		tick(true, true);
		assertEquals(Armour.NONE, tracker.getCurrent().getArmourAtEnter());

		// Old saved partial-armour enters load as burgers
		Run old = new Run();
		old.setArmourAtEnter(Armour.PARTIAL);
		old.migrateLegacySplits();
		assertEquals(Armour.BURGER, old.getArmourAtEnter());
	}

	@Test
	public void weaponlessEnterIsNotARealEnter()
	{
		tick(true, false);
		ticks(10);
		tick(true, true); // ran straight into the boss room
		tracker.onLocalPlayerDeath();
		Run thrown = tick(false, false);
		assertTrue(thrown.isWeaponlessEnter());
		assertFalse(thrown.isReset());

		tick(true, false);
		ticks(10);
		tracker.onItems(Collections.singletonList("Corrupted staff (basic)"));
		tick(true, true);
		Run real = tracker.getCurrent();
		assertFalse(real.isWeaponlessEnter());

		Run reset = new Run(); // never entered: not a weaponless enter
		assertFalse(reset.isWeaponlessEnter());
	}

	@Test
	public void hunllefFightSplitsIntoIndependentQuarters()
	{
		// Your 5:19.8 run from the log
		Run run = new Run();
		run.getSplits().put(Milestone.BOSS_ROOM, 259);
		run.getSplits().put(Milestone.HUNLLEF_75, 327);
		run.getSplits().put(Milestone.HUNLLEF_50, 416);
		run.getSplits().put(Milestone.HUNLLEF_25, 480);
		run.getSplits().put(Milestone.HUNLLEF, 533);

		assertEquals(Integer.valueOf(274), run.splitTime(Milestone.HUNLLEF_FIGHT)); // 2:44.4
		assertEquals(Integer.valueOf(68), run.splitTime(Milestone.QUARTER_1));      // 40.8
		assertEquals(Integer.valueOf(89), run.splitTime(Milestone.QUARTER_2));      // 53.4
		assertEquals(Integer.valueOf(64), run.splitTime(Milestone.QUARTER_3));      // 38.4
		assertEquals(Integer.valueOf(53), run.splitTime(Milestone.QUARTER_4));      // 31.8
		assertEquals(Integer.valueOf(533), run.splitTime(Milestone.HUNLLEF));       // total, from run start

		// Quarters add up to the whole fight
		int sum = run.splitTime(Milestone.QUARTER_1) + run.splitTime(Milestone.QUARTER_2)
			+ run.splitTime(Milestone.QUARTER_3) + run.splitTime(Milestone.QUARTER_4);
		assertEquals(274, sum);

		// A run that died mid-fight has the quarters it finished, but no fight or last quarter
		Run died = new Run();
		died.getSplits().put(Milestone.BOSS_ROOM, 259);
		died.getSplits().put(Milestone.HUNLLEF_75, 327);
		assertEquals(Integer.valueOf(68), died.splitTime(Milestone.QUARTER_1));
		assertNull(died.splitTime(Milestone.QUARTER_2));
		assertNull(died.splitTime(Milestone.HUNLLEF_FIGHT));
	}

	@Test
	public void oldRunFilesFoldDemisIntoOneSplit()
	{
		Run old = new Run();
		old.getSplits().put(Milestone.BEAR, 100);
		old.getSplits().put(Milestone.DARK_BEAST, 250);
		old.getSplits().put(Milestone.DRAGON, 180);
		old.getSplits().put(Milestone.BOSS_ROOM, 300);
		old.migrateLegacySplits();
		assertEquals(Integer.valueOf(100), old.getSplits().get(Milestone.DEMIS)); // first demi
		assertFalse(old.hasSplit(Milestone.BEAR));
		assertEquals(2, old.getSplits().size());

		// A single demi kill still gives a Demi split
		Run one = new Run();
		one.getSplits().put(Milestone.DRAGON, 180);
		one.migrateLegacySplits();
		assertEquals(Integer.valueOf(180), one.getSplits().get(Milestone.DEMIS));
		assertEquals(1, one.getSplits().size());
	}
}
