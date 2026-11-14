package com.cgsplits;

import lombok.RequiredArgsConstructor;

/** What the on-screen timer's colours compare your splits against. */
@RequiredArgsConstructor
public enum PaceComparison
{
	/** Your personal best run's splits; gold marks your best ever. */
	PERSONAL("Personal"),
	/** Times you type into the settings. */
	CUSTOM("Custom"),
	/** The current world record, see {@link WorldRecord}. */
	WORLD_RECORD("World Record");

	private final String name;

	@Override
	public String toString()
	{
		return name;
	}
}
