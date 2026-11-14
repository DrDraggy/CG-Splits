package com.cgsplits;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** What the player brought into the Hunllef room: corrupted armour (helm, body, legs; any tier) and weapons. */
@Getter
@RequiredArgsConstructor
public enum Armour
{
	FULL("T1 Armour", "T1", true),
	NONE("No Armour", "No Arm", true),
	/**
	 * Easter egg: entering with nothing but a basic (tier 1) weapon, or with a partial armour
	 * set. Still counts towards averages; only shown once you've actually done one.
	 */
	BURGER("Burger Enter", "Burger", false),
	/** Older versions saved partial sets as their own type; they load as BURGER. */
	PARTIAL("Partial Armour", "Partial", false);

	private final String displayName;
	/** Short label for the recent runs list. */
	private final String tag;
	/** Always listed in the UI, even with no enters yet. */
	private final boolean alwaysShown;

	/**
	 * @param pieces         armour pieces held (helm, body, legs)
	 * @param bestWeaponTier best weapon tier held: 0 none, 1 basic, 2 attuned, 3 perfected
	 */
	static Armour classify(int pieces, int bestWeaponTier)
	{
		if (pieces >= 3)
		{
			return FULL;
		}
		if (pieces > 0 || bestWeaponTier == 1)
		{
			return BURGER;
		}
		return NONE;
	}
}
