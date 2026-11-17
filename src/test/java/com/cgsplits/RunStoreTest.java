package com.cgsplits;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.After;
import org.junit.Test;

public class RunStoreTest
{
	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

	@After
	public void tearDown()
	{
		executor.shutdownNow();
	}

	private static Run completed(long startedAt, int ticks, int enter)
	{
		Run run = new Run();
		run.setStartedAt(startedAt);
		run.setMode(Run.Mode.CORRUPTED);
		run.setCompleted(true);
		run.setEndReason(Run.EndReason.COMPLETED);
		run.setTicks(ticks);
		run.getSplits().put(Milestone.BOSS_ROOM, enter);
		run.getSplits().put(Milestone.HUNLLEF, ticks);
		return run;
	}

	@Test
	public void deletedRunNoLongerCounts()
	{
		RunStore store = new RunStore(new Gson(), executor);
		Run real = completed(1000, 450, 180);
		Run test = completed(2000, 400, 100); // a faster test kill
		store.save(real);
		store.save(test);
		assertEquals(test, store.getPersonalBest());
		assertEquals(Integer.valueOf(100), store.bestTick(Milestone.BOSS_ROOM));

		int[] changes = {0};
		store.addListener(() -> changes[0]++);
		assertTrue(store.delete(test));

		assertEquals(1, store.getRuns().size());
		assertEquals(real, store.getPersonalBest());
		assertEquals(Integer.valueOf(180), store.bestTick(Milestone.BOSS_ROOM));
		assertEquals(1, store.getStats().getRuns());
		assertEquals(1, changes[0]);

		// Deleting a run that isn't there does nothing, so the session isn't changed twice
		assertFalse(store.delete(test));
		assertEquals(1, changes[0]);

		store.delete(real);
		assertNull(store.getPersonalBest());
		assertEquals(0, store.getStats().getRuns());
	}
}
