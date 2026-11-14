package com.cgsplits;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
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
	private static final int RECENT_RUNS = 20;
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault());
	private static final Color MUTED = ColorScheme.LIGHT_GRAY_COLOR.darker();
	private static final Color FAILED = new Color(0xFF8C42);

	private final RunStore runStore;
	private final CGSplitsConfig config;
	private final SessionTracker session;
	private final JPanel content = new JPanel(new DynamicGridLayout(0, 1, 0, 4));

	// Session card is built once and updated in place every second
	private final JPanel sessionCard = card(new DynamicGridLayout(0, 1, 0, 2));
	private final JLabel sessionEnters;
	private final JLabel sessionEph;
	private final JLabel sessionAvgEnter;
	private final JLabel sessionResets;
	private final JLabel sessionArmour;
	private final JLabel sessionBurger;
	private final JLabel sessionTime;
	private final Timer sessionTimer;

	/** Run shown in the detail section; follows the newest run unless the user picked another. */
	private Run selected;
	private long newestSeen;

	@Inject
	CGSplitsPanel(RunStore runStore, CGSplitsConfig config, CGSplitsPlugin plugin)
	{
		this.runStore = runStore;
		this.config = config;
		this.session = plugin.getSession();

		setBorder(new EmptyBorder(10, 10, 10, 10));
		setLayout(new BorderLayout());
		content.setBackground(ColorScheme.DARK_GRAY_COLOR);
		add(content, BorderLayout.NORTH);

		sessionEnters = valueRow(sessionCard, "Hunllef Enters");
		sessionEph = valueRow(sessionCard, "Enters Per Hour");
		sessionAvgEnter = valueRow(sessionCard, "Average Enter");
		sessionArmour = valueRow(sessionCard, "T1/No Arm");
		sessionBurger = valueRow(sessionCard, Armour.BURGER.getDisplayName());
		sessionResets = valueRow(sessionCard, "Resets");
		sessionTime = valueRow(sessionCard, "Session Time");
		((JPanel) sessionTime.getParent()).setToolTipText("Counts between runs too, but pauses after "
			+ SessionTracker.PAUSE_AFTER_TICKS_OUTSIDE + " ticks outside the instance");
		JButton reset = new JButton("Reset Session");
		reset.setFont(FontManager.getRunescapeSmallFont());
		reset.setFocusable(false);
		reset.addActionListener(e ->
		{
			session.reset();
			updateSession();
		});
		sessionCard.add(reset);

		sessionTimer = new Timer(1000, e -> updateSession());
		sessionTimer.start();

		updateSession();
		refresh();
	}

	void shutDown()
	{
		sessionTimer.stop();
	}

	private static String hex(Color color)
	{
		return String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
	}

	private void updateSession()
	{
		SessionTracker.Snapshot s = session.snapshot(System.currentTimeMillis());
		sessionEnters.setText(String.valueOf(s.getEnters()));
		sessionEph.setText(s.getEnters() > 0 ? TimeFormat.ephPrecise(s.getEntersPerHour()) : "-");
		sessionAvgEnter.setText(s.getEnters() > 0 ? TimeFormat.ticks(s.getAverageEnterTicks()) : "-");
		sessionResets.setText(String.valueOf(s.getResets()));
		sessionArmour.setText(s.entersWith(Armour.FULL) + "/" + s.entersWith(Armour.NONE));

		// The burger row stays hidden until someone actually does one
		SessionTracker.EnterStats burger = s.getByArmour().get(Armour.BURGER);
		sessionBurger.setText(burger == null ? "-" : TimeFormat.ticks(burger.getAverageTicks()) + " (" + burger.getCount() + ")");
		sessionBurger.getParent().setVisible(burger != null);

		if (!s.isActive())
		{
			sessionTime.setText("Not started");
		}
		else if (s.isPaused())
		{
			sessionTime.setText("<html>" + TimeFormat.duration(s.getElapsedMs())
				+ " <font color='" + hex(config.behindColor()) + "'>(paused)</font></html>");
		}
		else
		{
			sessionTime.setText(TimeFormat.duration(s.getElapsedMs()));
		}
	}

	/** Rebuild the panel from the store. Must be called on the Swing EDT. */
	void refresh()
	{
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

		// Order: Session, Splits, Enters by Armour, Last Run, Recent Runs
		content.add(header("Session"));
		content.add(sessionCard);

		content.add(header("Splits"));
		content.add(mainSplitsTable(runStore.getStats()));

		content.add(header("Other Splits"));
		content.add(otherSplitsTable(runStore.getStats()));

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

	/** Stacked so it fits half the panel width: result, time, date, armour. */
	private JPanel runSummary(Run run)
	{
		JPanel summary = new JPanel(new DynamicGridLayout(0, 1, 0, 2));
		summary.setOpaque(false);
		summary.add(label(resultText(run), resultColor(run), FontManager.getRunescapeBoldFont()));
		summary.add(label(finalTime(run), Color.WHITE, FontManager.getRunescapeBoldFont()));
		summary.add(label(DATE.format(Instant.ofEpochMilli(run.getStartedAt())), MUTED, FontManager.getRunescapeSmallFont()));
		if (run.getArmourAtEnter() != null)
		{
			summary.add(label(run.getArmourAtEnter().getDisplayName(), Color.WHITE, FontManager.getRunescapeSmallFont()));
		}
		// Keep the summary pinned to the top when the splits list is taller
		JPanel top = new JPanel(new BorderLayout());
		top.setOpaque(false);
		top.add(summary, BorderLayout.NORTH);
		return top;
	}

	/** Split name and time; the time is coloured against PB/best, with the exact gap on hover. */
	private JPanel splitsTable(Run run)
	{
		JPanel table = new JPanel(new GridBagLayout());
		table.setOpaque(false);
		JPanel top = new JPanel(new BorderLayout());
		top.setOpaque(false);
		top.add(table, BorderLayout.NORTH);
		if (run.getSplits().isEmpty())
		{
			addRow(table, 0, cell("No Splits", MUTED, SwingConstants.LEFT));
			return top;
		}

		// Compare against your PB, or your previous PB if this run is the PB
		Run pb = runStore.getPersonalBestExcluding(run);
		Map<Milestone, Integer> best = runStore.getBestSplits();

		int row = 0;
		for (Milestone milestone : displayOrder(run))
		{
			int tick = run.splitTime(milestone);
			Integer pbTick = pb != null ? pb.splitTime(milestone) : null;
			Integer bestTick = best.get(milestone);

			String delta = "";
			Color color = Color.WHITE;
			if (pbTick != null)
			{
				delta = TimeFormat.delta(tick - pbTick);
				color = tick <= pbTick ? config.aheadColor() : config.behindColor();
			}
			if (bestTick != null && tick <= bestTick)
			{
				color = config.bestColor();
			}

			JLabel value = cell(formatSplit(milestone, tick), color, SwingConstants.RIGHT);
			if (!delta.isEmpty())
			{
				value.setToolTipText(delta + " vs PB");
			}
			addRow(table, row++, splitName(milestone, true), value);
		}
		return top;
	}

	/**
	 * Prep splits in the order this run reached them, then Enter, the Hunllef fight with its
	 * quarters underneath, and Finish.
	 */
	private static List<Milestone> displayOrder(Run run)
	{
		List<Milestone> prep = new ArrayList<>();
		List<Milestone> fight = new ArrayList<>();
		for (Milestone m : Milestone.values())
		{
			if (!m.isShown() || run.splitTime(m) == null || m == Milestone.HUNLLEF)
			{
				continue;
			}
			if (m.isRelative())
			{
				fight.add(m);
			}
			else
			{
				prep.add(m);
			}
		}
		prep.sort((a, b) -> Integer.compare(run.getSplits().get(a), run.getSplits().get(b)));

		List<Milestone> order = new ArrayList<>(prep);
		order.addAll(fight);
		if (run.splitTime(Milestone.HUNLLEF) != null)
		{
			order.add(Milestone.HUNLLEF);
		}
		return order;
	}

	/** Segments are shown as "+40.8" to read as time added after the split before them. */
	private static String formatSplit(Milestone milestone, int ticks)
	{
		return milestone.isRelative() ? "+" + TimeFormat.ticks(ticks) : TimeFormat.ticks(ticks);
	}

	/** Split name, indented for the HP quarters under the Hunllef fight. */
	private static JLabel splitName(Milestone milestone, boolean compact)
	{
		JLabel name = cell(compact ? milestone.getShortName() : milestone.getDisplayName(), milestone.isSubSplit() ? MUTED : Color.WHITE, SwingConstants.LEFT);
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

	/** The Timer's splits: Preparation, 50% and Finish, all from the start of the run. */
	private JPanel mainSplitsTable(RunStats stats)
	{
		JPanel table = card(new GridBagLayout());
		int row = 0;
		addRow(table, row++, headerCells("Split"));
		row = addMilestoneRows(table, row, Milestone.MAIN, stats);
		return table;
	}

	/** Everything else: prep milestones, the Hunllef fight and its quarters, then enters by armour. */
	private JPanel otherSplitsTable(RunStats stats)
	{
		JPanel table = card(new GridBagLayout());
		int row = 0;
		addRow(table, row++, headerCells("Split"));
		row = addMilestoneRows(table, row, Milestone.OTHER, stats);

		// Enters by armour: how many, average and best enter time, and kill rate on hover
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
		return table;
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
				splitName(milestone, false),
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
		JPanel row = row(armour != null ? enter + "  " + armour.getTag() : enter, Color.WHITE,
			run.isCompleted() ? finalTime(run) : resultText(run), resultColor(run),
			FontManager.getRunescapeSmallFont());
		row.setBackground(background);
		row.setBorder(new EmptyBorder(4, 6, 4, 6));
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		row.setToolTipText("Show this run's splits");
		row.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				selected = run;
				refresh();
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

	/** Adds a "name ... value" row to the card and returns the value label for later updates. */
	private static JLabel valueRow(JPanel card, String name)
	{
		JPanel row = row(name, MUTED, "", Color.WHITE, FontManager.getRunescapeSmallFont());
		card.add(row);
		return (JLabel) ((BorderLayout) row.getLayout()).getLayoutComponent(BorderLayout.EAST);
	}

	private static JLabel cell(String text, Color color, int alignment)
	{
		JLabel label = new JLabel(text, alignment);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(color);
		return label;
	}
}
