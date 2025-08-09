package net.runelite.client;

import net.runelite.client.plugins.microbot.pestcontrol.PestControlPlugin;

public class Microbot
{
    public static void main(String[] args) throws Exception
    {
        RuneLiteDebug.pluginsToDebug.add(PestControlPlugin.class);
        RuneLiteDebug.main(args);
    }
}
