package com.cgsplits;

final class TimeFormat
{
	static final int MS_PER_TICK = 600;

	private TimeFormat()
	{
	}

	/** 700 ticks -> "7:00.0"; minutes are dropped under a minute, so 10 ticks -> "6.0" */
	static String ticks(int ticks)
	{
		long ms = (long) ticks * MS_PER_TICK;
		long minutes = ms / 60_000;
		long seconds = (ms / 1000) % 60;
		long tenths = (ms / 100) % 10;
		if (minutes == 0)
		{
			return String.format("%d.%d", seconds, tenths);
		}
		return String.format("%d:%02d.%d", minutes, seconds, tenths);
	}

	private static final java.util.regex.Pattern OFFICIAL = java.util.regex.Pattern.compile("(\\d+):(\\d{2})(?:\\.(\\d+))?");

	/** The game's chat-message time, e.g. "2:18.60", in ticks (231), or null if unparseable. */
	static Integer parseOfficial(String time)
	{
		if (time == null)
		{
			return null;
		}
		java.util.regex.Matcher m = OFFICIAL.matcher(time);
		if (!m.find())
		{
			return null;
		}
		double seconds = Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2));
		if (m.group(3) != null)
		{
			seconds += Double.parseDouble("0." + m.group(3));
		}
		return (int) Math.round(seconds * 1000 / MS_PER_TICK);
	}

	/**
	 * A time typed by a person: "1:42", "1:42.6" or plain seconds like "58". Returns ticks,
	 * or null if it's blank or can't be read.
	 */
	static Integer parseUserTime(String time)
	{
		if (time == null || time.trim().isEmpty())
		{
			return null;
		}
		String t = time.trim();
		if (t.matches("\\d+(\\.\\d+)?"))
		{
			return (int) Math.round(Double.parseDouble(t) * 1000 / MS_PER_TICK);
		}
		return parseOfficial(t);
	}

	/** Whole seconds, rounded down: 10 ticks (6.0s) -> "6", 11 ticks (6.6s) -> "6", 104 ticks -> "1:02" */
	static String wholeSeconds(int ticks)
	{
		long totalSeconds = (long) ticks * MS_PER_TICK / 1000;
		if (totalSeconds < 60)
		{
			return String.valueOf(totalSeconds);
		}
		return String.format("%d:%02d", totalSeconds / 60, totalSeconds % 60);
	}

	/** Wall-clock duration, 3_725_000 ms -> "1:02:05" */
	static String duration(long ms)
	{
		long totalSeconds = ms / 1000;
		return String.format("%d:%02d:%02d", totalSeconds / 3600, (totalSeconds / 60) % 60, totalSeconds % 60);
	}

	/** On-screen EPH: drops the decimals without rounding, 4.99 -> "4". */
	static String eph(double entersPerHour)
	{
		return String.valueOf((long) Math.floor(entersPerHour));
	}

	/** Sidebar EPH: one decimal, cut off rather than rounded, 4.567 -> "4.5". */
	static String ephPrecise(double entersPerHour)
	{
		// Locale.ROOT so it's always "4.5", never "4,5"
		return String.format(java.util.Locale.ROOT, "%.1f", Math.floor(entersPerHour * 10) / 10);
	}

	/** -5 ticks -> "-3.0", 105 ticks -> "+1:03.0" */
	static String delta(int ticks)
	{
		String sign = ticks < 0 ? "-" : "+";
		long ms = (long) Math.abs(ticks) * MS_PER_TICK;
		long minutes = ms / 60_000;
		long seconds = (ms / 1000) % 60;
		long tenths = (ms / 100) % 10;
		if (minutes > 0)
		{
			return String.format("%s%d:%02d.%d", sign, minutes, seconds, tenths);
		}
		return String.format("%s%d.%d", sign, seconds, tenths);
	}
}
