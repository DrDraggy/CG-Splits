package com.cgsplits;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** How the on-screen timer displays time. */
@Getter
@RequiredArgsConstructor
public enum TimerPrecision
{
	/** Every game tick: 6.0, 6.6, 7.2 ... */
	TICKS("Ticks (0.6s)"),
	/** Whole seconds only: 6, 7, 8 ... */
	SECONDS("Seconds");

	private final String name;

	String format(int ticks)
	{
		return this == SECONDS ? TimeFormat.wholeSeconds(ticks) : TimeFormat.ticks(ticks);
	}

	@Override
	public String toString()
	{
		return name;
	}
}
