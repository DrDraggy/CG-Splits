package com.cgsplits;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.Filepath;

/**
 * Stores every run (completed or not) as its own JSON file in the plugin's data folder,
 * .runelite/plugin-data/cg-splits/runs/, and derives the PB, best splits and aggregate stats
 * from them. All file access goes through RuneLite's {@link Filepath}.
 */
@Slf4j
@Singleton
public class RunStore
{
	/** Where runs are saved; set by {@link #load(Filepath)} when the plugin starts. */
	private volatile Filepath runsDir;

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

	/**
	 * Loads every saved run from the plugin's data folder.
	 *
	 * @param pluginDirectory the folder RuneLite gives this plugin (see {@code Plugin#getPluginDirectory})
	 */
	public synchronized void load(Filepath pluginDirectory)
	{
		runsDir = pluginDirectory.joinSegment("runs");

		List<Run> loaded = new ArrayList<>();
		if (runsDir.isDirectory())
		{
			List<Filepath> files;
			try (Stream<Filepath> walk = runsDir.walk(1))
			{
				files = walk
					.filter(f -> f.isFile() && f.getFileName().endsWith(".json"))
					.collect(Collectors.toList());
			}
			catch (IOException e)
			{
				log.warn("Couldn't list saved Gauntlet runs in {}", runsDir, e);
				files = Collections.emptyList();
			}

			for (Filepath file : files)
			{
				try (Reader reader = file.openBufferedReader())
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
		Filepath dir = runsDir;
		if (dir == null)
		{
			log.warn("Not saving Gauntlet run: data folder not set up");
			return;
		}
		Filepath file = dir.joinSegment(run.getStartedAt() + ".json");
		try
		{
			dir.createDirectories();
			try (Writer writer = file.openBufferedWriter())
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
