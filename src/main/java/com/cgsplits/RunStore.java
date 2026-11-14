package com.cgsplits;

import com.google.gson.Gson;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

/**
 * Stores every run (completed or not) as its own JSON file in ~/.runelite/cg-splits/runs/
 * and derives the PB, best splits and aggregate stats from them.
 */
@Slf4j
@Singleton
public class RunStore
{
	private static final File RUNS_DIR = new File(new File(RuneLite.RUNELITE_DIR, "cg-splits"), "runs");

	private final Gson gson;
	private final ScheduledExecutorService executor;
	private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

	/** Corrupted runs only, newest first. Replaced wholesale, never mutated, so reads need no lock. */
	private volatile List<Run> runs = new ArrayList<>();
	private volatile Run personalBest;
	private volatile Map<Milestone, Integer> bestSplits = new EnumMap<>(Milestone.class);
	private volatile RunStats stats = RunStats.of(new ArrayList<>());

	@Inject
	RunStore(Gson gson, ScheduledExecutorService executor)
	{
		this.gson = gson.newBuilder().setPrettyPrinting().create();
		this.executor = executor;
	}

	/** Called (on the thread that changed the data) whenever runs are loaded or saved. */
	public void addListener(Runnable listener)
	{
		listeners.add(listener);
	}

	public void removeListener(Runnable listener)
	{
		listeners.remove(listener);
	}

	public synchronized void load()
	{
		List<Run> loaded = new ArrayList<>();
		File[] files = RUNS_DIR.listFiles((dir, name) -> name.endsWith(".json"));
		if (files != null)
		{
			for (File file : files)
			{
				try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8))
				{
					Run run = gson.fromJson(reader, Run.class);
					// Runs saved before weaponless enters were ignored are skipped here too
					if (run != null && run.isCorrupted() && !run.isWeaponlessEnter())
					{
						run.migrateLegacySplits();
						loaded.add(run);
					}
				}
				catch (Exception e)
				{
					log.warn("Skipping unreadable run file {}", file, e);
				}
			}
		}
		update(loaded);
		log.debug("Loaded {} Gauntlet runs", loaded.size());
	}

	public synchronized void save(Run run)
	{
		executor.execute(() -> write(run));
		if (run.isCorrupted())
		{
			List<Run> updated = new ArrayList<>(runs);
			updated.add(run);
			update(updated);
		}
	}

	/** Corrupted runs, newest first. */
	public List<Run> getRuns()
	{
		return runs;
	}

	/**
	 * Fastest completed corrupted run other than {@code run}, or null. Used to colour a run's
	 * splits: a new PB is compared against the PB it beat, not against itself.
	 */
	public Run getPersonalBestExcluding(Run run)
	{
		Run best = null;
		for (Run other : runs)
		{
			if (other != run && other.isCompleted() && (best == null || other.getTicks() < best.getTicks()))
			{
				best = other;
			}
		}
		return best;
	}

	/** Best (lowest) tick a recorded split was ever reached at, counted from the run start. */
	public Integer bestTick(Milestone milestone)
	{
		Integer best = null;
		for (Run run : runs)
		{
			Integer tick = run.getSplits().get(milestone);
			if (tick != null && (best == null || tick < best))
			{
				best = tick;
			}
		}
		return best;
	}

	/** Fastest completed corrupted run, or null. */
	public Run getPersonalBest()
	{
		return personalBest;
	}

	/** Best-ever tick each milestone was reached at, across all corrupted runs. */
	public Map<Milestone, Integer> getBestSplits()
	{
		return bestSplits;
	}

	public RunStats getStats()
	{
		return stats;
	}

	private void update(List<Run> newRuns)
	{
		newRuns.sort(Comparator.comparingLong(Run::getStartedAt).reversed());

		Run pb = null;
		Map<Milestone, Integer> best = new EnumMap<>(Milestone.class);
		for (Run run : newRuns)
		{
			if (run.isCompleted() && (pb == null || run.getTicks() < pb.getTicks()))
			{
				pb = run;
			}
			for (Milestone milestone : Milestone.values())
			{
				Integer time = run.splitTime(milestone);
				if (time != null)
				{
					best.merge(milestone, time, Math::min);
				}
			}
		}

		runs = Collections.unmodifiableList(newRuns);
		personalBest = pb;
		bestSplits = best;
		stats = RunStats.of(newRuns);

		for (Runnable listener : listeners)
		{
			listener.run();
		}
	}

	private void write(Run run)
	{
		File file = new File(RUNS_DIR, run.getStartedAt() + ".json");
		try
		{
			Files.createDirectories(RUNS_DIR.toPath());
			try (Writer writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8))
			{
				gson.toJson(run, writer);
			}
		}
		catch (IOException e)
		{
			log.warn("Failed to save Gauntlet run to {}", file, e);
		}
	}
}
