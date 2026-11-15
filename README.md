# CG Splits

A RuneLite plugin with LiveSplit-style auto-splits and personal bests for the Corrupted Gauntlet.

Times are counted in game ticks (0.6s each) from entering the instance. When a run finishes, the game's own "Challenge duration" and "Preparation time" chat messages are stored as the official times.

## Splits

Splits are milestones, like on paceman.gg. Each milestone is recorded the first time it happens, in whatever order your prep route reaches it, and compared with the tick your PB reached the same milestone.

| Milestone | Trigger |
|---|---|
| 1st Weapon | Any corrupted staff, bow or halberd in your inventory or equipment |
| Demi Killed | First demi-boss (bear, dragon or dark beast) killed |
| Perfected Weapon / 2nd Perfected | First and second distinct perfected weapon types |
| Preparation | Entering the Hunllef room |
| 75% / 50% / 25% | The Hunllef first at or below that much health, timed from the start of the run |
| Hunllef | The whole fight, from Enter to the kill. Listed under it, each quarter of the Hunllef's HP on its own: **100-75**, **75-50**, **50-25** and **25-0**, so you can see where your damage dropped off |
| Finish | Hunllef death, or the completion chat message as a fallback |

Each run is saved as JSON to `~/.runelite/plugin-data/cg-splits/runs/` (RuneLite moves runs from the older `~/.runelite/cg-splits/` folder there automatically), completed or not. It records how the run ended (completed, died, left or abandoned), whether you entered the Hunllef room with **T1 armour** (helm, body and legs, any tier) or **No Armour**, and which perfected weapons you'd made by then. Partial sets (one or two pieces) count as a **Burger Enter**.

Overall play time (session time added up across every session, including ones you reset) is saved to `~/.runelite/plugin-data/cg-splits/playtime.json`. A **reset** is a run that ended before reaching the Hunllef room.

Entering the Hunllef room without ever holding a corrupted weapon isn't treated as a real enter: the run isn't saved and doesn't count towards EPH or average enter.

Runs on special worlds (Leagues, Deadman, Fresh Start, beta, tournament, quest speedrunning and similar) are timed on screen but never saved, and don't count towards your PB, splits or session.

Times under a minute are shown without minutes (`6.0`, not `0:06.0`).

## On-screen boxes

- **Timer:** **Preparation:**, optionally **50%:**, and **Finish:**, all timed from the start of the run and shown only while you're inside the instance. The colours follow **Set Threshold** (below). Turn it off with **Show Timer**. **Show 50% HP Split** adds the time from entering the room to the Hunllef reaching half health. **Timer Precision** switches between live ticks (`6.0`, `6.6`, `7.2`) and seconds (`6`, `7`, `8`). Prep counts up until you enter the Hunllef room; Finish appears in the boss room and stops when the Hunllef dies.
- **Session:** EPH (shown without decimals, not rounded) and AVG by default. Under the **Session Overlay** settings you can also turn on Hunllef Enters, T1/No Arm (e.g. `5/12`), Resets and Session Time. It shows while you're in the instance or the Prifddinas Gauntlet lobby. Turn it off with **Show Session Overlay**. Hold Alt and drag either box to move it.

The session clock that EPH is based on keeps running between runs, so quick resets through the lobby count as play time. If you stay outside the instance for more than 5 ticks (AFK, banking, logged out) it pauses, and the sidebar shows **(paused)** in red until your next run.

## Sidebar

The hourglass icon in the sidebar opens the tab below. You can hide it with the **Show Sidebar Tab** option.
- **Stats**, with a **Session** / **Overall** toggle:
  - Runs, Hunllef Enters, Enters Per Hour (one decimal) and Average Enter
  - T1/No Arm: how many enters had T1 armour vs no armour, e.g. `5/12`
  - Resets, and Kills with the share of enters that ended in one, e.g. `6 (67%)`
  - **Session:** Session Time, with **(paused)** in red while the clock is paused, and a **Reset Session** button
  - **Overall:** every saved run, and Play Time added up across all sessions. Overall Enters Per Hour only counts enters made since play time started being tracked, since older runs have no play time.
- **Splits**, with **Main** / **Prep** / **Hunllef** tabs. Like paceman.gg's stats page, these count incomplete runs too. For each split: how many runs reached it (hover for %), with the average and best time.
  - **Main** holds what the Timer shows: Preparation, 50% and Finish, all from the start of the run.
  - **Prep** holds 1st Weapon, Demi Killed and the perfected weapons, then enters by armour (T1 Armour Enter, No Armour Enter; hover the count for the kill rate).
  - **Hunllef** holds the whole fight and its four quarters.
- **Last Run:** result, final time, date, armour on entry and perfected weapons (e.g. `Perf Staff + Bow`), next to Prep, 75%, 50%, 25% and End coloured against your PB (hover for the gap). Splits the run didn't reach show `-`, so the box stays the same size whichever run is selected.
- **Recent Runs:** the last 10 runs that reached the Hunllef room, each showing its enter time and armour (`2:18.6  T1` or `1:19.2  No Arm`) on the left, its finish time (or Died at Hunllef / Reset at Hunllef) on the right, and when it happened underneath (`Today 14:32`). Resets are left out of this list but still count in the split stats. Click a run to show it above.

## Development

You need **JDK 11–21** to run Gradle. Gradle 8.x can't run on JDK 26. RuneLite plugins compile to Java 11 either way.

1. Open this folder in IntelliJ IDEA and import it as a Gradle project.
2. In Settings → Build Tools → Gradle, set **Gradle JVM** to `jdk-21`. Leave "Use Gradle from" on `gradle-wrapper.properties`.
3. Run `CGSplitsPluginTest.main`, or the Gradle `run` task, to start a dev client with the plugin loaded.
4. Run the tests with the Gradle `test` task. `RunTrackerTest` covers the split logic without a game client.

## TODO before first real use

These IDs are from memory and need checking in a dev client, using the Varbit inspector and the developer tools' location overlay:

- `CGSplitsPlugin.VARBIT_GAUNTLET_ENTERED` (9178) and `VARBIT_GAUNTLET_FINAL_ROOM_ENTERED` (9177)
- `REGION_CORRUPTED_GAUNTLET` (7768) and `REGION_GAUNTLET` (7512)
- `REGION_GAUNTLET_LOBBY` (12127), the Prifddinas Gauntlet lobby. If it's wrong, the Session box will disappear as soon as you leave the instance.
- The exact item names for corrupted weapons and armour, which `RunTracker` matches with regexes
- The completion chat message wording, which `CGSplitsPlugin.DURATION` and `PREP` match

## Set Threshold

The **Set Threshold** settings section picks what the Timer's colours compare against:

| Compare Against | Targets |
|---|---|
| **Personal** (default) | Your PB run's splits. Gold marks your best-ever time at that split. |
| **Custom** | The times you type into **Custom Preparation** and **Custom 50%** (`1:50`, `1:50.4` or plain seconds like `58`). A blank box means no target for that split. Finish has no custom target. |
| **World Record** | The current world record. |

A reached split turns green if it beat the target and red if it didn't. While a split is still counting, it turns red as soon as it passes the target, so you can see you're behind before you get there.

Turn on **Show Gap** (off by default) to also show how far ahead or behind the target each split is, e.g. `4:41.4 (-3.0)`. While a split is still counting, the gap appears once you're behind.

## Top 3 and World Record

The sidebar opens with a **Top 3** list in gold, silver and bronze; hover a place for its splits. Both live in `src/main/java/com/cgsplits/WorldRecord.java`: edit `TOP_3` when the leaderboard changes (name, overall time, an optional armour tag like `T1 Prep`, and optional preparation and Hunllef times shown when you hover the place; a blank name shows as `-`), and the WR times when there's a new record. The **World Record** threshold uses the same file. Rebuild after editing.
