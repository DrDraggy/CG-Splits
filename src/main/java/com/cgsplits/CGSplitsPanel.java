package com.cgsplits;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.DynamicGridLayout;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * Sidebar tab: the last (or selected) run's splits, paceman-style stats across every run
 * including incomplete ones, and a clickable list of recent runs.
 */
class CGSplitsPanel extends PluginPanel
{
	private static final int RECENT_RUNS = 10;
	private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault());
	private static final Color MUTED = ColorScheme.LIGHT_GRAY_COLOR.darker();
	private static final Color FAILED = new Color(0xFF8C42);

	private final RunStore runStore;
	private final CGSplitsConfig config;
	private final CGSplitsPlugin plugin;
	private final SessionTracker session;
	private final JPanel content = new JPanel(new DynamicGridLayout(0, 1, 0, 4));

	// Stats card: Session and Overall share one card with a toggle; built once, updated every second
	private final JPanel statsCard = card(new DynamicGridLayout(0, 1, 0, 2));
	private final JLabel sessionTab;
	private final JLabel overallTab;
	private final JLabel statRuns;
	private final JLabel statEnters;
	private final JLabel statEph;
	private final JLabel statAvgEnter;
	private final JLabel statArmour;
	private final JLabel statBurger;
	private final JLabel statResets;
	private final JLabel statKills;
	private final JLabel statTimeName;
	private final JLabel statTime;
	private final JPanel resetHolder = new JPanel(new CardLayout());
	private final Timer sessionTimer;
	/** Which view of the stats card is showing. */
	private boolean showOverall;
	/** Runs changed while the tab was hidden, so it needs rebuilding when it's next opened. */
	private boolean stale;
	/** Which tab of the splits card is showing; kept when the panel is rebuilt. */
	private String splitsTab = "Main";

	/** Run shown in the detail section; follows the newest run unless the user picked another. */
	private Run selected;
	private long newestSeen;

	@Inject
	CGSplitsPanel(RunStore runStore, CGSplitsConfig config, CGSplitsPlugin plugin)
	{
		this.runStore = runStore;
		this.config = config;
		this.plugin = plugin;
		this.session = plugin.getSession();

		setBorder(new EmptyBorder(10, 10, 10, 10));
		setLayout(new BorderLayout());
		content.setBackground(ColorScheme.DARK_GRAY_COLOR);
		add(content, BorderLayout.NORTH);

		JPanel tabs = new JPanel(new GridLayout(1, 2));
		tabs.setOpaque(false);
		sessionTab = tab("Session", () -> showStats(false));
		overallTab = tab("Overall", () -> showStats(true));
		tabs.add(sessionTab);
		tabs.add(overallTab);
		statsCard.add(tabs);

		statRuns = statRow("Runs")[1];
		statEnters = statRow("Hunllef Enters")[1];
		statEph = statRow("Enters Per Hour")[1];
		statAvgEnter = statRow("Average Enter")[1];
		statArmour = statRow("T1/No Arm")[1];
		statBurger = statRow(Armour.BURGER.getDisplayName())[1];
		statResets = statRow("Resets")[1];
		statKills = statRow("Kills")[1];
		JLabel[] time = statRow("Session Time");
		statTimeName = time[0];
		statTime = time[1];
		((JPanel) statKills.getParent()).setToolTipText("Hunllef kills, and the share of enters that ended in one");

		// Reset only applies to the session; on Overall its space is kept so the card doesn't jump
		JButton reset = new JButton("Reset Session");
		reset.setFont(FontManager.getRunescapeSmallFont());
		reset.setFocusable(false);
		reset.addActionListener(e ->
		{
			session.reset(System.currentTimeMillis());
			updateStats();
		});
		JPanel noReset = new JPanel();
		noReset.setOpaque(false);
		resetHolder.setOpaque(false);
		resetHolder.add(reset, "session");
		resetHolder.add(noReset, "overall");
		statsCard.add(resetHolder);

		// Only tick while the tab is actually on screen; onActivate catches it up when opened
		sessionTimer = new Timer(1000, e ->
		{
			if (isShowing())
			{
				updateStats();
			}
		});
		sessionTimer.start();

		updateStats();
		refresh();
	}

	void shutDown()
	{
		sessionTimer.stop();
	}

	/**
	 * Runs were loaded or saved. Rebuilding the panel is the most work the plugin does, and a run
	 * is saved right as you leave the instance, so it's put off until the tab is opened.
	 */
	void runsChanged()
	{
		if (isShowing())
		{
			refresh();
		}
		else
		{
			stale = true;
		}
	}

	@Override
	public void onActivate()
	{
		if (stale)
		{
			refresh();
		}
		else
		{
			updateStats();
		}
	}

	private static String hex(Color color)
	{
		return String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
	}

	private void showStats(boolean overall)
	{
		showOverall = overall;
		updateStats();
	}

	/**
	 * A clickable tab label for the top of a card; style it with {@link #styleTab}. Switches on
	 * mouse press: Swing drops mouseClicked if the mouse moves even a pixel between press and
	 * release, which made quick clicks miss.
	 */
	private static JLabel tab(String text, Runnable onClick)
	{
		JLabel tab = new JLabel(text, SwingConstants.CENTER);
		tab.setFont(FontManager.getRunescapeSmallFont());
		tab.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		tab.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				if (SwingUtilities.isLeftMouseButton(e))
				{
					onClick.run();
				}
			}
		});
		return tab;
	}

	private static void styleTab(JLabel tab, boolean active)
	{
		tab.setForeground(active ? Color.WHITE : MUTED);
		tab.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 2, 0, active ? ColorScheme.BRAND_ORANGE : ColorScheme.MEDIUM_GRAY_COLOR),
			new EmptyBorder(2, 0, 3, 0)));
	}

	/** Adds a "name ... value" row to the stats card; returns {name, value}. */
	private JLabel[] statRow(String name)
	{
		JPanel row = row(name, MUTED, "", Color.WHITE, FontManager.getRunescapeSmallFont());
		statsCard.add(row);
		BorderLayout layout = (BorderLayout) row.getLayout();
		return new JLabel[]{
			(JLabel) layout.getLayoutComponent(BorderLayout.WEST),
			(JLabel) layout.getLayoutComponent(BorderLayout.EAST)};
	}

	private void updateStats()
	{
		styleTab(sessionTab, !showOverall);
		styleTab(overallTab, showOverall);
		((CardLayout) resetHolder.getLayout()).show(resetHolder, showOverall ? "overall" : "session");
		if (showOverall)
		{
			showOverallStats();
		}
		else
		{
			showSessionStats();
		}
	}

	private void showSessionStats()
	{
		SessionTracker.Snapshot s = session.snapshot(System.currentTimeMillis());
		statRuns.setText(String.valueOf(s.getRuns()));
		statEnters.setText(String.valueOf(s.getEnters()));
		statEph.setText(s.getEnters() > 0 ? TimeFormat.ephPrecise(s.getEntersPerHour()) : "-");
		((JPanel) statEph.getParent()).setToolTipText("Enters per hour of session time");
		statAvgEnter.setText(s.getEnters() > 0 ? TimeFormat.ticks(s.getAverageEnterTicks()) : "-");
		statArmour.setText(s.entersWith(Armour.FULL) + "/" + s.entersWith(Armour.NONE));
		statResets.setText(String.valueOf(s.getResets()));
		statKills.setText(kills(s.getKills(), s.getEnters()));

		// The burger row stays hidden until someone actually does one
		SessionTracker.EnterStats burger = s.getByArmour().get(Armour.BURGER);
		statBurger.setText(burger == null ? "-" : TimeFormat.ticks(burger.getAverageTicks()) + " (" + burger.getCount() + ")");
		statBurger.getParent().setVisible(burger != null);

		statTimeName.setText("Session Time");
		((JPanel) statTime.getParent()).setToolTipText("Counts between runs too, but pauses after "
			+ SessionTracker.PAUSE_AFTER_TICKS_OUTSIDE + " ticks outside the instance");
		if (!s.isActive())
		{
			statTime.setText("Not started");
		}
		else if (s.isPaused())
		{
			statTime.setText("<html>" + TimeFormat.duration(s.getElapsedMs())
				+ " <font color='" + hex(config.behindColor()) + "'>(paused)</font></html>");
		}
		else
		{
			statTime.setText(TimeFormat.duration(s.getElapsedMs()));
		}
	}

	/** Every saved run, plus play time counted across all sessions since it was first tracked. */
	private void showOverallStats()
	{
		RunStats stats = runStore.getStats();
		RunStats.MilestoneStats enters = stats.getMilestones().get(Milestone.BOSS_ROOM);
		int enterCount = enters == null ? 0 : enters.getCount();

		statRuns.setText(String.valueOf(stats.getRuns()));
		statEnters.setText(String.valueOf(enterCount));
		statAvgEnter.setText(enterCount > 0 ? TimeFormat.ticks(enters.getAverageTicks()) : "-");
		statArmour.setText(armourEnters(stats, Armour.FULL) + "/" + armourEnters(stats, Armour.NONE));
		statResets.setText(String.valueOf(stats.getResets()));
		statKills.setText(kills(stats.getCompletions(), enterCount));

		RunStats.MilestoneStats burger = stats.getEnters().get(Armour.BURGER);
		statBurger.setText(burger == null ? "-" : TimeFormat.ticks(burger.getAverageTicks()) + " (" + burger.getCount() + ")");
		statBurger.getParent().setVisible(burger != null);

		// EPH only counts enters made while play time was being tracked; older runs have no play time
		PlayTime playTime = session.overall(System.currentTimeMillis());
		double hours = playTime.getMs() / 3_600_000.0;
		statEph.setText(playTime.getEnters() > 0 && hours > 0 ? TimeFormat.ephPrecise(playTime.getEnters() / hours) : "-");
		String since = playTime.getSince() == 0 ? "" : " since " + DATE.format(Instant.ofEpochMilli(playTime.getSince()));
		((JPanel) statEph.getParent()).setToolTipText("Enters per hour of play time" + since);

		statTimeName.setText("Play Time");
		statTime.setText(playTime.getSince() == 0 ? "-" : TimeFormat.duration(playTime.getMs()));
		((JPanel) statTime.getParent()).setToolTipText("All session time" + since + ", including sessions you reset");
	}

	/** "6 (67%)": kills, and the share of enters that ended in one. */
	private static String kills(int kills, int enters)
	{
		return enters > 0 ? kills + " (" + Math.round(100.0 * kills / enters) + "%)" : String.valueOf(kills);
	}

	private static int armourEnters(RunStats stats, Armour armour)
	{
		RunStats.MilestoneStats e = stats.getEnters().get(armour);
		return e == null ? 0 : e.getCount();
	}

	/** Rebuild the panel from the store. Must be called on the Swing EDT. */
	void refresh()
	{
		stale = false;
		List<Run> runs = runStore.getRuns();
		if (runs.isEmpty())
		{
			selected = null;
			newestSeen = 0;
		}
		else
		{
			Run newest = runs.get(0);
			if (selected == null || newest.getStartedAt() > newestSeen)
			{
				selected = newest;
			}
			newestSeen = newest.getStartedAt();
		}

		content.removeAll();

		content.add(header("Top 3"));
		content.add(topThreeCard());

		// Order: Stats (Session/Overall), Splits (Main/Prep/Hunllef), Last Run, Recent Runs
		content.add(header("Stats"));
		content.add(statsCard);
		updateStats();

		content.add(header("Splits"));
		content.add(splitsCard(runStore.getStats()));

		content.add(header(runs.isEmpty() || selected == runs.get(0) ? "Last Run" : "Selected Run"));
		if (selected == null)
		{
			content.add(muted("No runs yet. Enter the Corrupted Gauntlet to start one."));
		}
		else
		{
			content.add(lastRunCard(selected));
		}

		// Only runs that made it into the Hunllef room; resets would just be noise here
		List<Run> entered = new ArrayList<>();
		for (Run run : runs)
		{
			if (run.hasSplit(Milestone.BOSS_ROOM))
			{
				entered.add(run);
				if (entered.size() == RECENT_RUNS)
				{
					break;
				}
			}
		}
		if (!entered.isEmpty())
		{
			content.add(header("Recent Runs"));
			for (Run run : entered)
			{
				content.add(historyRow(run));
			}
		}

		content.revalidate();
		content.repaint();
	}

	private static final Color[] PODIUM = {new Color(0xFFD700), new Color(0xC0C0C0), new Color(0xCD7F32)};

	/** The fastest three, in gold, silver and bronze; edit {@link WorldRecord#TOP_3} when it changes. */
	private JPanel topThreeCard()
	{
		JPanel card = card(new DynamicGridLayout(0, 1, 0, 3));
		for (int i = 0; i < WorldRecord.TOP_3.length && i < PODIUM.length; i++)
		{
			String name = WorldRecord.TOP_3[i][0];
			String time = WorldRecord.TOP_3[i][1];
			boolean empty = name == null || name.trim().isEmpty();
			// Empty places still show, as "2. -", so the podium is always complete
			if (empty)
			{
				name = "-";
				time = "";
			}
			// "1. rawr simon ... (T1 Prep) 3:57.0": name on the left in bold podium colour; the armour
			// tag sits small, plain and grey just before the time
			String tag = !empty && WorldRecord.TOP_3[i].length > 2 ? WorldRecord.TOP_3[i][2] : "";
			JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
			right.setOpaque(false);
			if (tag != null && !tag.trim().isEmpty())
			{
				JLabel tagLabel = label("(" + tag.trim() + ")", MUTED, FontManager.getRunescapeSmallFont());
				tagLabel.setBorder(new EmptyBorder(0, 0, 0, 6));
				right.add(tagLabel);
			}
			right.add(label(time, PODIUM[i], FontManager.getRunescapeBoldFont()));
			JPanel row = new JPanel(new BorderLayout(6, 0));
			row.setOpaque(false);
			row.add(label((i + 1) + ". " + name, PODIUM[i], FontManager.getRunescapeBoldFont()), BorderLayout.WEST);
			row.add(right, BorderLayout.EAST);
			// Hover any place for its splits, e.g. "Preparation 1:30.6, Hunllef 2:28.2, Finish 3:58.8"
			String[] entry = WorldRecord.TOP_3[i];
			String prep = entry.length > 3 ? entry[3] : "";
			String fight = entry.length > 4 ? entry[4] : "";
			if (!empty && !prep.isEmpty() && !fight.isEmpty())
			{
				row.setToolTipText("Preparation " + prep + ", Hunllef " + fight + ", Finish " + time);
			}
			card.add(row);
		}
		return card;
	}

	/** Result on the left, that run's splits on the right. */
	private JPanel lastRunCard(Run run)
	{
		JPanel card = card(new BorderLayout(10, 0));
		card.add(runSummary(run), BorderLayout.WEST);
		card.add(splitsTable(run), BorderLayout.CENTER);
		return card;
	}

	/**
	 * Stacked so it fits half the panel width: result, time, date, armour and perfected weapons.
	 * Always five lines, blank where there's nothing to show, so the card is the same height
	 * whichever run is selected.
	 */
	private JPanel runSummary(Run run)
	{
		JPanel summary = new JPanel(new DynamicGridLayout(0, 1, 0, 2));
		summary.setOpaque(false);
		summary.add(label(resultText(run), resultColor(run), FontManager.getRunescapeBoldFont()));
		summary.add(label(finalTime(run), Color.WHITE, FontManager.getRunescapeBoldFont()));
		summary.add(label(DATE.format(Instant.ofEpochMilli(run.getStartedAt())), MUTED, FontManager.getRunescapeSmallFont()));
		Armour armour = run.getArmourAtEnter();
		summary.add(label(armour != null ? armour.getDisplayName() : " ", Color.WHITE, FontManager.getRunescapeSmallFont()));
		summary.add(label(weaponsText(run), Color.WHITE, FontManager.getRunescapeSmallFont()));
		JPanel top = new JPanel(new BorderLayout());
		top.setOpaque(false);
		top.add(summary, BorderLayout.NORTH);
		return top;
	}

	/** "Perf Staff + Bow", "No Perfected", or blank for runs that didn't enter or predate this. */
	private static String weaponsText(Run run)
	{
		List<String> weapons = run.getPerfectedAtEnter();
		if (weapons == null || !run.hasSplit(Milestone.BOSS_ROOM))
		{
			return " ";
		}
		return weapons.isEmpty() ? "No Perfected" : "Perf " + String.join(" + ", weapons);
	}

	/** The splits shown for a single run, always all of them, with "-" for ones it didn't reach. */
	private static final Milestone[] RUN_SPLITS = {
		Milestone.BOSS_ROOM, Milestone.HUNLLEF_75, Milestone.HUNLLEF_50, Milestone.HUNLLEF_25, Milestone.HUNLLEF};
	private static final String[] RUN_SPLIT_NAMES = {"Prep", "75%", "50%", "25%", "End"};

	/**
	 * Prep, the Hunllef's 75/50/25% HP and End, all timed from the start of the run. The time is
	 * coloured against PB/best, with the exact gap on hover.
	 */
	private JPanel splitsTable(Run run)
	{
		JPanel table = new JPanel(new GridBagLayout());
		table.setOpaque(false);
		JPanel top = new JPanel(new BorderLayout());
		top.setOpaque(false);
		top.add(table, BorderLayout.NORTH);

		// Compare against your PB, or your previous PB if this run is the PB
		Run pb = runStore.getPersonalBestExcluding(run);
		Map<Milestone, Integer> best = runStore.getBestSplits();

		for (int i = 0; i < RUN_SPLITS.length; i++)
		{
			Milestone milestone = RUN_SPLITS[i];
			JLabel name = cell(RUN_SPLIT_NAMES[i], Color.WHITE, SwingConstants.LEFT);
			Integer tick = run.splitTime(milestone);
			if (tick == null)
			{
				addRow(table, i, name, cell("-", MUTED, SwingConstants.RIGHT));
				continue;
			}

			Integer pbTick = pb != null ? pb.splitTime(milestone) : null;
			Integer bestTick = best.get(milestone);
			Color color = Color.WHITE;
			if (pbTick != null)
			{
				color = tick <= pbTick ? config.aheadColor() : config.behindColor();
			}
			if (bestTick != null && tick <= bestTick)
			{
				color = config.bestColor();
			}

			JLabel value = cell(TimeFormat.ticks(tick), color, SwingConstants.RIGHT);
			if (pbTick != null)
			{
				value.setToolTipText(TimeFormat.delta(tick - pbTick) + " vs PB");
			}
			addRow(table, i, name, value);
		}
		return top;
	}

	/** Segments are shown as "+40.8" to read as time added after the split before them. */
	private static String formatSplit(Milestone milestone, int ticks)
	{
		return milestone.isRelative() ? "+" + TimeFormat.ticks(ticks) : TimeFormat.ticks(ticks);
	}

	/** Split name, indented for the HP quarters under the Hunllef fight. */
	private static JLabel splitName(Milestone milestone)
	{
		JLabel name = cell(milestone.getDisplayName(), milestone.isSubSplit() ? MUTED : Color.WHITE, SwingConstants.LEFT);
		if (milestone.isSubSplit())
		{
			name.setBorder(new EmptyBorder(0, 10, 0, 0));
			name.setToolTipText("Time to take the Hunllef from " + milestone.getDisplayName().replace("-", "% to ") + "% HP");
		}
		else if (milestone == Milestone.HUNLLEF_FIGHT)
		{
			name.setToolTipText("The whole fight, from entering the room to the kill");
		}
		return name;
	}

	private static JLabel label(String text, Color color, Font font)
	{
		JLabel label = new JLabel(text);
		label.setFont(font);
		label.setForeground(color);
		return label;
	}

	private static final String[] SPLIT_TABS = {"Main", "Prep", "Hunllef"};

	/**
	 * Every split's count, average and best in one card, a tab per group: Main (the Timer's
	 * splits), Prep (milestones and enters by armour) and Hunllef (the fight and its quarters).
	 * A CardLayout sizes the card to the tallest tab, so switching doesn't move what's below.
	 */
	private JPanel splitsCard(RunStats stats)
	{
		JPanel card = card(new BorderLayout(0, 6));
		CardLayout cards = new CardLayout();
		JPanel pages = new JPanel(cards);
		pages.setOpaque(false);
		pages.add(topAligned(splitsTable(Milestone.MAIN, stats)), SPLIT_TABS[0]);
		JPanel prep = splitsTable(Milestone.PREP, stats);
		addEnterRows(prep, Milestone.PREP.size() + 1, stats);
		pages.add(topAligned(prep), SPLIT_TABS[1]);
		pages.add(topAligned(splitsTable(Milestone.FIGHT, stats)), SPLIT_TABS[2]);

		JPanel tabs = new JPanel(new GridLayout(1, SPLIT_TABS.length));
		tabs.setOpaque(false);
		JLabel[] labels = new JLabel[SPLIT_TABS.length];
		for (int i = 0; i < SPLIT_TABS.length; i++)
		{
			String name = SPLIT_TABS[i];
			labels[i] = tab(name, () ->
			{
				splitsTab = name;
				cards.show(pages, name);
				for (JLabel l : labels)
				{
					styleTab(l, l.getText().equals(name));
				}
			});
			styleTab(labels[i], name.equals(splitsTab));
			tabs.add(labels[i]);
		}
		cards.show(pages, splitsTab);

		card.add(tabs, BorderLayout.NORTH);
		card.add(pages, BorderLayout.CENTER);
		return card;
	}

	private JPanel splitsTable(List<Milestone> milestones, RunStats stats)
	{
		JPanel table = new JPanel(new GridBagLayout());
		table.setOpaque(false);
		addRow(table, 0, headerCells("Split"));
		addMilestoneRows(table, 1, milestones, stats);
		return table;
	}

	private static JPanel topAligned(JPanel panel)
	{
		JPanel top = new JPanel(new BorderLayout());
		top.setOpaque(false);
		top.add(panel, BorderLayout.NORTH);
		return top;
	}

	/** Enters by armour: how many, average and best enter time, and kill rate on hover. */
	private void addEnterRows(JPanel table, int row, RunStats stats)
	{

		for (Armour armour : Armour.values())
		{
			// Burger enters only get a row once you've done one; old partial runs load as burgers
			if (armour == Armour.PARTIAL || (!armour.isAlwaysShown() && !stats.getEnters().containsKey(armour)))
			{
				continue;
			}
			RunStats.MilestoneStats e = stats.getEnters().get(armour);
			JLabel count = cell(e != null ? String.valueOf(e.getCount()) : "0", Color.WHITE, SwingConstants.RIGHT);
			int killRate = stats.completionPercent(armour);
			if (killRate >= 0)
			{
				count.setToolTipText(killRate + "% of these enters ended in a kill");
			}
			JLabel name = cell(armour.getDisplayName() + " Enter", Color.WHITE, SwingConstants.LEFT);
			name.setToolTipText("Preparation times of runs that entered with " + armour.getDisplayName());
			addRow(table, row++,
				name,
				count,
				cell(e != null ? TimeFormat.ticks(e.getAverageTicks()) : "-", Color.WHITE, SwingConstants.RIGHT),
				cell(e != null ? TimeFormat.ticks(e.getBestTicks()) : "-", config.bestColor(), SwingConstants.RIGHT));
		}
	}

	/** paceman-style rows: how many runs reached each split, and the average/best time. */
	private int addMilestoneRows(JPanel table, int row, List<Milestone> milestones, RunStats stats)
	{
		for (Milestone milestone : milestones)
		{
			RunStats.MilestoneStats m = stats.getMilestones().get(milestone);
			JLabel count = cell(m != null ? String.valueOf(m.getCount()) : "0", Color.WHITE, SwingConstants.RIGHT);
			count.setToolTipText(stats.percentReached(milestone) + "% of runs reached this split");
			addRow(table, row++,
				splitName(milestone),
				count,
				cell(m != null ? formatSplit(milestone, m.getAverageTicks()) : "-", Color.WHITE, SwingConstants.RIGHT),
				cell(m != null ? formatSplit(milestone, m.getBestTicks()) : "-", config.bestColor(), SwingConstants.RIGHT));
		}
		return row;
	}

	private static JLabel[] headerCells(String first)
	{
		return new JLabel[]{
			cell(first, MUTED, SwingConstants.LEFT),
			cell("Count", MUTED, SwingConstants.RIGHT),
			cell("Avg", MUTED, SwingConstants.RIGHT),
			cell("Best", MUTED, SwingConstants.RIGHT),
		};
	}

	/**
	 * Adds a table row where the first (name) column takes all spare width and the number
	 * columns are only as wide as their contents, so names like "1st weapon" aren't cut off.
	 */
	private static void addRow(JPanel table, int row, JLabel... cells)
	{
		for (int col = 0; col < cells.length; col++)
		{
			GridBagConstraints c = new GridBagConstraints();
			c.gridx = col;
			c.gridy = row;
			c.weightx = col == 0 ? 1 : 0;
			c.fill = GridBagConstraints.HORIZONTAL;
			c.anchor = col == 0 ? GridBagConstraints.WEST : GridBagConstraints.EAST;
			c.insets = new Insets(1, col == 0 ? 0 : 8, 1, 0);
			table.add(cells[col], c);
		}
	}

	private JPanel historyRow(Run run)
	{
		Color background = run == selected ? ColorScheme.DARKER_GRAY_HOVER_COLOR : ColorScheme.DARKER_GRAY_COLOR;
		// e.g. "2:18.6  T1" on the left, the finish time (or Died / Left) on the right
		String enter = TimeFormat.ticks(run.getSplits().get(Milestone.BOSS_ROOM));
		Armour armour = run.getArmourAtEnter();
		JPanel line = row(armour != null ? enter + "  " + armour.getTag() : enter, Color.WHITE,
			run.isCompleted() ? finalTime(run) : resultText(run), resultColor(run),
			FontManager.getRunescapeSmallFont());
		// When it happened underneath, e.g. "Today 14:32", so the order is easy to follow
		JPanel row = new JPanel(new DynamicGridLayout(0, 1, 0, 1));
		row.add(line);
		row.add(label(when(run.getStartedAt()), MUTED, FontManager.getRunescapeSmallFont()));
		row.setBackground(background);
		row.setBorder(new EmptyBorder(4, 6, 4, 6));
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		row.setToolTipText("Show this run's splits. Right-click to delete it.");

		// Right-click > Delete Run, e.g. for a test kill that shouldn't count towards averages
		JPopupMenu menu = new JPopupMenu();
		JMenuItem delete = new JMenuItem("Delete Run");
		delete.addActionListener(e -> confirmDelete(run));
		menu.add(delete);
		row.setComponentPopupMenu(menu);
		inheritPopupMenu(row);
		row.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				// On press, like the tabs, so a click with a little mouse movement still counts
				if (SwingUtilities.isLeftMouseButton(e))
				{
					selected = run;
					refresh();
				}
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				row.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				row.setBackground(background);
			}
		});
		return row;
	}

	/** Lets right-clicks anywhere on a row, including its labels, open the row's menu. */
	private static void inheritPopupMenu(Container container)
	{
		for (Component child : container.getComponents())
		{
			if (child instanceof JComponent)
			{
				((JComponent) child).setInheritsPopupMenu(true);
			}
			if (child instanceof Container)
			{
				inheritPopupMenu((Container) child);
			}
		}
	}

	/** Asks first, since a deleted run can't be brought back. */
	private void confirmDelete(Run run)
	{
		String what = run.isCompleted() ? "finished in " + finalTime(run) : resultText(run).toLowerCase();
		int choice = JOptionPane.showConfirmDialog(this,
			"<html>Delete this run (" + when(run.getStartedAt()) + ", " + what + ")?<br>"
				+ "It's removed from your PB, splits and averages for good.</html>",
			"Delete Run", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
		if (choice != JOptionPane.YES_OPTION)
		{
			return;
		}
		if (selected == run)
		{
			selected = null; // show the newest run instead
		}
		plugin.deleteRun(run);
		updateStats();
	}

	private static String resultText(Run run)
	{
		// Runs that got into the boss room ended at the Hunllef: "Died at Hunllef", "Reset at Hunllef"
		String where = run.hasSplit(Milestone.BOSS_ROOM) ? " at Hunllef" : "";
		switch (run.resolveEndReason())
		{
			case COMPLETED:
				return "Completed";
			case DIED:
				return "Died" + where;
			case ABANDONED:
				return "Abandoned" + where;
			default:
				return "Reset" + where;
		}
	}

	private static Color resultColor(Run run)
	{
		switch (run.resolveEndReason())
		{
			case COMPLETED:
				return ColorScheme.PROGRESS_COMPLETE_COLOR;
			case DIED:
				return ColorScheme.PROGRESS_ERROR_COLOR;
			default:
				return FAILED;
		}
	}

	private static String finalTime(Run run)
	{
		// Ticks are already snapped to the game's official time, so this matches it
		return TimeFormat.ticks(run.getTicks());
	}

	private static JLabel header(String text)
	{
		JLabel label = new JLabel(text);
		label.setFont(FontManager.getRunescapeBoldFont());
		label.setForeground(Color.WHITE);
		label.setBorder(new EmptyBorder(8, 0, 2, 0));
		return label;
	}

	private static JLabel muted(String text)
	{
		JLabel label = new JLabel("<html>" + text + "</html>");
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(MUTED);
		return label;
	}

	private static JPanel card(java.awt.LayoutManager layout)
	{
		JPanel card = new JPanel(layout);
		card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		card.setBorder(new EmptyBorder(6, 6, 6, 6));
		return card;
	}

	private static JPanel row(String left, Color leftColor, String right, Color rightColor, Font font)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setOpaque(false);
		JLabel l = new JLabel(left);
		l.setFont(font);
		l.setForeground(leftColor);
		JLabel r = new JLabel(right);
		r.setFont(font);
		r.setForeground(rightColor);
		row.add(l, BorderLayout.WEST);
		row.add(r, BorderLayout.EAST);
		return row;
	}

	/** "Today 14:32", "Yesterday 09:05", or "Nov 12, 21:40" for older runs. */
	private static String when(long epochMs)
	{
		ZonedDateTime time = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault());
		LocalDate today = LocalDate.now(ZoneId.systemDefault());
		if (time.toLocalDate().equals(today))
		{
			return "Today " + CLOCK.format(time);
		}
		if (time.toLocalDate().equals(today.minusDays(1)))
		{
			return "Yesterday " + CLOCK.format(time);
		}
		return DATE.format(time);
	}

	private static JLabel cell(String text, Color color, int alignment)
	{
		JLabel label = new JLabel(text, alignment);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(color);
		return label;
	}
}
