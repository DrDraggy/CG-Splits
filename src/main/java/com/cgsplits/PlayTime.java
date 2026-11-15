package com.cgsplits;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Play time across every session, counted the same way as the session clock, and the Hunllef
 * enters made during it. Saved to playtime.json so it survives restarts and session resets.
 * Runs saved before this existed have no play time, so enters per hour only uses
 * {@link #enters}, not every enter on record.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlayTime
{
	private long ms;
	private int enters;
	/** When counting started (epoch ms), or 0 if it hasn't yet. */
	private long since;
}
