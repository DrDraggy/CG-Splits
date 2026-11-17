package com.cgsplits;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.Getter;

/**
 * Run state machine and split detection. Deliberately has no RuneLite dependencies:
 * the plugin translates client events into these calls, which keeps this unit-testable.
 */
public class RunTracker
{
	public enum State
	{
		IDLE,
		PREP,
		BOSS,
		FINISHED
	}

	/** Ticks to wait after the Hunllef dies for the completion chat messages before saving. */
	static final int SAVE_DELAY_TICKS = 10;

	/**
	 * Ticks from the Hunllef's HP hitting 0 until the game's own timer stops and the kill counts.
	 * Measured on real kills: the official "Challenge duration" was 6 ticks after the death on 5
	 * of 6 runs (5 on the other). The timer keeps running for these ticks, and normally the
	 * completion message arrives within them and sets the exact time.
	 */
	static final int KILL_COUNT_DELAY_TICKS = 6;

	/**
	 * The game's own Gauntlet timer starts 4 ticks after the "entered Gauntlet" varbit flips.
	 * Measured against the official "Preparation time" message on real runs: starting on the
	 * flip put us 4 ticks ahead, and starting on arrival (1 tick later) put us 3 ticks ahead.
	 */
	static final int GAME_TIMER_START_DELAY = 4;

	private static final Pattern WEAPON = Pattern.compile("^(corrupted|crystal) (staff|bow|halberd) \\((basic|attuned|perfected)\\)$");
	private static final Pattern ARMOUR = Pattern.compile("^(corrupted|crystal) (helm|body|legs) \\((basic|attuned|perfected)\\)$");

	@Getter
	private State state = State.IDLE;
	@Getter
	private Run current;
	/** The most recently ended run, kept so the overlay can keep showing it. */
	@Getter
	private Run lastRun;

	private boolean primed;
	private boolean wasInGauntlet;
	private boolean diedThisRun;
	private boolean awaitingArrival;
	private int ticksSinceEntered;
	/** See {@link #GAME_TIMER_START_DELAY}. */
	private final int startDelayTicks;
	/** Ticks between the varbit flipping and arriving in the instance, for timer diagnostics. */
	@Getter
	private int arrivalDelayTicks;
	private int ticksSinceFinish;
	private final Set<String> perfectedTypes = new HashSet<>();
	/** 0 none, 1 basic, 2 attuned, 3 perfected. */
	private int bestWeaponTier;
	private final Set<String> armourSlots = new HashSet<>();

	/** Starts the timer the same tick the "entered Gauntlet" varbit flips (used by tests). */
	public RunTracker()
	{
		this(0);
	}

	/**
	 * @param startDelayTicks ticks after the "entered Gauntlet" varbit flips before the timer
	 *                        starts, to line up with the game's own timer
	 */
	public RunTracker(int startDelayTicks)
	{
		this.startDelayTicks = startDelayTicks;
	}

	public boolean isRunning()
	{
		return state == State.PREP || state == State.BOSS;
	}

	/**
	 * Called once per game tick.
	 *
	 * @param inGauntlet the "entered Gauntlet" varbit is set
	 * @param inInstance the player has actually arrived in the instanced maze. The varbit flips a
	 *                   few ticks before the teleport finishes, and the game's own timer only
	 *                   starts on arrival, so the run starts on the first tick both are true.
	 * @return a run that has ended and should be saved, or null
	 */
	public Run onTick(boolean inGauntlet, boolean inInstance, boolean inBossRoom, Run.Mode regionMode, long now)
	{
		// The first tick after (re)starting only records state, so loading the plugin
		// mid-run does not start a run with a wrong timer.
		if (!primed)
		{
			primed = true;
			wasInGauntlet = inGauntlet;
			return null;
		}

		boolean entered = inGauntlet && !wasInGauntlet;
		wasInGauntlet = inGauntlet;

		switch (state)
		{
			case IDLE:
				if (entered)
				{
					awaitingArrival = true;
					arrivalDelayTicks = 0;
					ticksSinceEntered = 0;
				}
				if (awaitingArrival)
				{
					if (!inGauntlet)
					{
						awaitingArrival = false;
					}
					else if (inInstance && ticksSinceEntered >= startDelayTicks)
					{
						awaitingArrival = false;
						start(now);
						applyMode(regionMode);
					}
					else
					{
						if (!inInstance)
						{
							arrivalDelayTicks++;
						}
						ticksSinceEntered++;
					}
				}
				return null;

			case PREP:
			case BOSS:
				if (!inGauntlet)
				{
					// Died or left before the Hunllef died
					return end(false);
				}
				current.setTicks(current.getTicks() + 1);
				applyMode(regionMode);
				if (state == State.PREP && inBossRoom)
				{
					current.setArmourAtEnter(Armour.classify(armourSlots.size(), bestWeaponTier));
					current.setPerfectedAtEnter(perfectedWeapons());
					split(Milestone.BOSS_ROOM);
					state = State.BOSS;
				}
				return null;

			case FINISHED:
				ticksSinceFinish++;
				if (!inGauntlet)
				{
					return end(true);
				}
				// The game's timer runs on through the death animation: keep counting until the
				// completion message gives the official time, or estimate it if none comes
				if (!current.hasSplit(Milestone.HUNLLEF))
				{
					if (ticksSinceFinish <= KILL_COUNT_DELAY_TICKS)
					{
						current.setTicks(current.getTicks() + 1);
					}
					if (ticksSinceFinish >= KILL_COUNT_DELAY_TICKS)
					{
						split(Milestone.HUNLLEF);
					}
				}
				if (ticksSinceFinish >= SAVE_DELAY_TICKS)
				{
					return end(true);
				}
				return null;
		}
		return null;
	}

	public void onNpcDeath(String npcName)
	{
		if (!isRunning() || npcName == null)
		{
			return;
		}

		String name = npcName.toLowerCase(Locale.ROOT);
		applyMode(modeFromName(name));

		if (name.contains("hunllef"))
		{
			finish();
		}
		else if (name.endsWith("bear") || name.endsWith("dark beast") || name.endsWith("dragon"))
		{
			// Most routes only kill the one or two demis their weapons need, so split on the first
			split(Milestone.DEMIS);
		}
	}

	/** Called with the names of every item currently in the inventory and equipment. */
	public void onItems(Collection<String> itemNames)
	{
		if (!isRunning())
		{
			return;
		}

		for (String raw : itemNames)
		{
			String name = raw.toLowerCase(Locale.ROOT);

			Matcher weapon = WEAPON.matcher(name);
			if (weapon.matches())
			{
				applyMode(modeFromName(name));
				split(Milestone.FIRST_WEAPON);
				bestWeaponTier = Math.max(bestWeaponTier, weaponTier(weapon.group(3)));
				if ("perfected".equals(weapon.group(3)))
				{
					perfectedTypes.add(weapon.group(2));
				}
				continue;
			}

			Matcher armour = ARMOUR.matcher(name);
			if (armour.matches())
			{
				applyMode(modeFromName(name));
				armourSlots.add(armour.group(2));
			}
		}

		if (!perfectedTypes.isEmpty())
		{
			split(Milestone.FIRST_PERFECTED);
		}
		if (perfectedTypes.size() >= 2)
		{
			split(Milestone.SECOND_PERFECTED);
		}
	}

	/** The game's own completion messages. Also acts as a fallback finish trigger. */
	public void onOfficialTimes(String duration, String prep)
	{
		Run run = state == State.FINISHED || isRunning() ? current : null;
		if (run == null)
		{
			return;
		}
		if (duration != null)
		{
			run.setOfficialDuration(duration);
			if (isRunning())
			{
				finish();
			}
			// The game's clock is the source of truth: snap the finish to it
			Integer ticks = TimeFormat.parseOfficial(duration);
			if (ticks != null)
			{
				run.setTimerDriftTicks(run.getSplits().getOrDefault(Milestone.HUNLLEF, run.getTicks()) - ticks);
				run.setTicks(ticks);
				run.getSplits().put(Milestone.HUNLLEF, ticks);
			}
		}
		if (prep != null)
		{
			run.setOfficialPrep(prep);
			Integer ticks = TimeFormat.parseOfficial(prep);
			if (ticks != null)
			{
				Integer ours = run.getSplits().get(Milestone.BOSS_ROOM);
				if (ours != null)
				{
					run.setPrepDriftTicks(ours - ticks);
					// Move the HP splits with the entry so their time since entering stays the same
					for (Milestone m : Milestone.values())
					{
						if (Milestone.HP_THRESHOLDS.contains(m))
						{
							run.getSplits().computeIfPresent(m, (k, v) -> v - (ours - ticks));
						}
					}
				}
				run.getSplits().put(Milestone.BOSS_ROOM, ticks);
			}
		}
	}

	/**
	 * The Hunllef's HP, as a fraction from 0 to 1. Records the 75/50/25% splits the first tick
	 * its HP is below each threshold, so exactly 50% doesn't count yet but 49.9% does.
	 */
	public void onBossHealth(double fraction)
	{
		if (state != State.BOSS)
		{
			return;
		}
		if (fraction < 0.75)
		{
			split(Milestone.HUNLLEF_75);
		}
		if (fraction < 0.50)
		{
			split(Milestone.HUNLLEF_50);
		}
		if (fraction < 0.25)
		{
			split(Milestone.HUNLLEF_25);
		}
	}

	/**
	 * The Hunllef's HP as a fraction from 0 to 1, or -1 while it isn't known yet.
	 * <p>
	 * The health bar over its head is coarse: the game sends {@code ratio = 1 + (scale - 1) * hp
	 * / maxHp} (0 once dead), so one bar step spans several percent and a bar at exactly half can
	 * still be 51.7% HP. The boss HP HUD has the exact HP, so that is used whenever it matches the
	 * bar. Otherwise (HUD off, or a stale value from another boss) this returns the highest HP the
	 * bar allows, so a threshold is only passed once the Hunllef is certainly below it.
	 */
	static double bossHealthFraction(int ratio, int scale, int hudHp, int hudMaxHp)
	{
		if (ratio < 0 || scale <= 0)
		{
			return -1;
		}
		if (ratio == 0)
		{
			return 0;
		}
		if (scale == 1)
		{
			return 1; // the bar only says it's alive
		}
		if (hudHp > 0 && hudMaxHp > 0 && 1 + (scale - 1) * hudHp / hudMaxHp == ratio)
		{
			return (double) hudHp / hudMaxHp;
		}
		// HP on this bar step is below ratio / (scale - 1), never equal to it
		return Math.min(1, Math.nextDown((double) ratio / (scale - 1)));
	}

	public void onLocalPlayerDeath()
	{
		if (isRunning())
		{
			diedThisRun = true;
		}
	}

	/**
	 * Stop tracking (plugin turned off). Returns the run that was in progress so it can still
	 * be saved: marked ABANDONED if mid-run, or as completed if the Hunllef was already dead.
	 */
	public Run abandon()
	{
		Run run = null;
		if (isRunning())
		{
			current.setEndReason(Run.EndReason.ABANDONED);
			run = end(false);
		}
		else if (state == State.FINISHED)
		{
			run = end(true);
		}
		state = State.IDLE;
		current = null;
		primed = false;
		return run;
	}

	private void start(long now)
	{
		current = new Run();
		current.setStartedAt(now);
		perfectedTypes.clear();
		bestWeaponTier = 0;
		armourSlots.clear();
		ticksSinceFinish = 0;
		diedThisRun = false;
		state = State.PREP;
	}

	/**
	 * The Hunllef died. The Finish split isn't set yet: the game's clock stops a few ticks later
	 * (see {@link #KILL_COUNT_DELAY_TICKS}), so the FINISHED state keeps the timer running.
	 */
	private void finish()
	{
		current.setCompleted(true);
		current.setEndReason(Run.EndReason.COMPLETED);
		ticksSinceFinish = 0;
		state = State.FINISHED;
	}

	private Run end(boolean completed)
	{
		Run run = current;
		if (completed)
		{
			// Left before the estimate or the official time came in
			split(Milestone.HUNLLEF);
		}
		run.setCompleted(completed);
		if (run.getEndReason() == null)
		{
			run.setEndReason(completed ? Run.EndReason.COMPLETED
				: diedThisRun ? Run.EndReason.DIED : Run.EndReason.LEFT);
		}
		lastRun = run;
		current = null;
		state = State.IDLE;
		return run;
	}

	private void split(Milestone milestone)
	{
		current.getSplits().putIfAbsent(milestone, current.getTicks());
	}

	private void applyMode(Run.Mode mode)
	{
		if (current != null && current.getMode() == Run.Mode.UNKNOWN && mode != Run.Mode.UNKNOWN)
		{
			current.setMode(mode);
		}
	}

	/** Perfected weapons made so far, in a fixed order: "Staff", "Bow", "Halberd". */
	private List<String> perfectedWeapons()
	{
		List<String> weapons = new ArrayList<>();
		for (String type : new String[]{"staff", "bow", "halberd"})
		{
			if (perfectedTypes.contains(type))
			{
				weapons.add(Character.toUpperCase(type.charAt(0)) + type.substring(1));
			}
		}
		return weapons;
	}

	private static int weaponTier(String tier)
	{
		switch (tier)
		{
			case "perfected":
				return 3;
			case "attuned":
				return 2;
			default:
				return 1;
		}
	}

	private static Run.Mode modeFromName(String lowerName)
	{
		if (lowerName.startsWith("corrupted"))
		{
			return Run.Mode.CORRUPTED;
		}
		if (lowerName.startsWith("crystal"))
		{
			return Run.Mode.NORMAL;
		}
		return Run.Mode.UNKNOWN;
	}

}
