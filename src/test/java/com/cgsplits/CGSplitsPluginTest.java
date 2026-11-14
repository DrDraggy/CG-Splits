package com.cgsplits;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/** Launches a dev RuneLite client with this plugin loaded. Run with: gradle run */
public class CGSplitsPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(CGSplitsPlugin.class);
		RuneLite.main(args);
	}
}
