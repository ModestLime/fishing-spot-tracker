/*
 * Copyright (c) 2026, SpockNinja
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.fishingspottracker;

import com.google.inject.Provides;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import javax.inject.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.client.Notifier;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@Slf4j
@PluginDescriptor(
	name = "Fishing Spot Tracker",
	description = "Displays a pie-timer circle over fishing spots that depletes and shifts color as the spot ages",
	tags = {"fishing", "overlay", "timer", "skilling", "spots"}
)
public class FishingSpotTrackerPlugin extends Plugin
{
	/**
	 * How long (in ticks) to remember a spot after it despawns (e.g. walked out of range).
	 * 15 minutes = 1500 ticks. If you come back within this window, the timer resumes.
	 */
	private static final int LOCATION_CACHE_EXPIRY_TICKS = 1500;

	/**
	 * Set of animation IDs that indicate the player is actively fishing.
	 */
	private static final java.util.Set<Integer> FISHING_ANIMATIONS = java.util.Set.of(
		621, 622, 623, 619, 620, 624, 625, 626, 627, 628, 629,
		632, 633, 5108, 6703, 6704, 6706, 6707, 6708, 6709, 6710,
		7401, 8336, 9350, 9353, 9354, 9355, 9356, 9357, 9358, 9359,
		9360, 9361, 9362
	);

	@Getter
	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private FishingSpotTrackerConfig config;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private FishingSpotTrackerOverlay overlay;

	@Inject
	private FishingSpotMinimapOverlay minimapOverlay;

	@Inject
	private Notifier notifier;

	/**
	 * Maps each tracked fishing spot NPC to its tracking data.
	 */
	@Getter
	private final Map<NPC, TrackedSpot> trackedSpots = new HashMap<>();

	/**
	 * Cache of recently-despawned spots keyed by world location + NPC ID.
	 * Used to restore timers when walking back into range of a spot.
	 */
	private final Map<LocationKey, CachedSpot> locationCache = new HashMap<>();

	/**
	 * Whether the player was fishing on the previous tick (for idle detection).
	 */
	private boolean wasFishing;

	/**
	 * Cached newest spot — sticky to avoid bouncing between tied spots.
	 */
	private NPC cachedNewestSpot;

	@Override
	protected void startUp()
	{
		overlayManager.add(overlay);
		overlayManager.add(minimapOverlay);
		clientThread.invoke(this::scanExistingSpots);
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		overlayManager.remove(minimapOverlay);
		trackedSpots.clear();
		locationCache.clear();
		wasFishing = false;
		cachedNewestSpot = null;
	}

	/**
	 * Scans all NPCs currently in the scene and tracks any fishing spots.
	 * Handles the case where the plugin is enabled while spots are already visible.
	 */
	private void scanExistingSpots()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		int currentTick = client.getTickCount();
		for (NPC npc : client.getNpcs())
		{
			if (npc != null && FishingSpotData.findSpot(npc.getId()) != null)
			{
				WorldPoint wp = npc.getWorldLocation();
				trackedSpots.putIfAbsent(npc, new TrackedSpot(currentTick, wp));
			}
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();
		if (state == GameState.LOGIN_SCREEN || state == GameState.HOPPING)
		{
			trackedSpots.clear();
			locationCache.clear();
			wasFishing = false;
			cachedNewestSpot = null;
		}
	}

	@Subscribe
	public void onNpcSpawned(NpcSpawned event)
	{
		NPC npc = event.getNpc();
		FishingSpotData spotData = FishingSpotData.findSpot(npc.getId());
		if (spotData == null)
		{
			return;
		}

		WorldPoint wp = npc.getWorldLocation();
		LocationKey key = new LocationKey(wp, npc.getId());
		int currentTick = client.getTickCount();

		CachedSpot cached = locationCache.remove(key);

		if (cached != null)
		{
			int totalElapsed = currentTick - cached.spawnTick;
			int maxTicks = spotData.getMaxTicks();

			if (totalElapsed <= maxTicks)
			{
				trackedSpots.put(npc, new TrackedSpot(cached.spawnTick, wp));
				return;
			}
		}

		trackedSpots.put(npc, new TrackedSpot(currentTick, wp));
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned event)
	{
		NPC npc = event.getNpc();
		TrackedSpot tracked = trackedSpots.remove(npc);
		if (tracked == null)
		{
			return;
		}

		LocationKey key = new LocationKey(tracked.worldPoint, npc.getId());
		locationCache.put(key, new CachedSpot(tracked.spawnTick, client.getTickCount()));
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		int currentTick = client.getTickCount();

		// Detect NPCs that have teleported to a new tile (spot "moved")
		// or exceeded their max tick lifetime (reset timer)
		for (Map.Entry<NPC, TrackedSpot> entry : trackedSpots.entrySet())
		{
			NPC npc = entry.getKey();
			TrackedSpot tracked = entry.getValue();

			if (npc.getId() == -1)
			{
				continue;
			}

			WorldPoint currentPos = npc.getWorldLocation();
			if (!currentPos.equals(tracked.worldPoint))
			{
				entry.setValue(new TrackedSpot(currentTick, currentPos));
			}
			else
			{
				FishingSpotData spotData = FishingSpotData.findSpot(npc.getId());
				if (spotData != null && !spotData.isUnpredictable()
					&& currentTick - tracked.spawnTick > spotData.getMaxTicks())
				{
					entry.setValue(new TrackedSpot(currentTick, currentPos));
				}
			}
		}

		// Clean up invalid NPCs
		trackedSpots.keySet().removeIf(npc -> npc.getId() == -1);

		// Expire old entries from the location cache
		locationCache.entrySet().removeIf(e ->
			currentTick - e.getValue().despawnTick > LOCATION_CACHE_EXPIRY_TICKS);

		// Update newest spot (sticky)
		updateNewestSpot();

		// Idle detection
		updateIdleState();
	}

	private void updateIdleState()
	{
		Player localPlayer = client.getLocalPlayer();
		if (localPlayer == null)
		{
			wasFishing = false;
			return;
		}

		boolean isFishing = FISHING_ANIMATIONS.contains(localPlayer.getAnimation());

		if (wasFishing && !isFishing && config.idleNotification())
		{
			notifier.notify("You have stopped fishing!");
		}

		wasFishing = isFishing;
	}

	/**
	 * Returns a value between 0.0 and 1.0 representing how far through
	 * its estimated lifetime the spot is. 0.0 = just appeared, 1.0 = at max expected duration.
	 */
	public double getSpotProgress(NPC npc)
	{
		TrackedSpot tracked = trackedSpots.get(npc);
		if (tracked == null)
		{
			return 0.0;
		}

		int elapsed = client.getTickCount() - tracked.spawnTick;
		FishingSpotData spotData = FishingSpotData.findSpot(npc.getId());
		int maxTicks = (spotData != null) ? spotData.getMaxTicks() : FishingSpotData.DEFAULT_MAX_TICKS;

		return Math.min(1.0, (double) elapsed / maxTicks);
	}

	/**
	 * Gets the spawn tick for a tracked NPC (used by the overlay for timer text).
	 */
	public Integer getSpawnTick(NPC npc)
	{
		TrackedSpot tracked = trackedSpots.get(npc);
		return tracked != null ? tracked.spawnTick : null;
	}

	/**
	 * Returns the cached newest spot. Updated each game tick to avoid
	 * bouncing between spots that share the same spawn tick.
	 */
	public NPC getNewestSpot()
	{
		return cachedNewestSpot;
	}

	/**
	 * Recomputes the newest spot. Sticky: keeps the current pick unless
	 * a strictly newer spot appears or the current pick is no longer valid.
	 */
	private void updateNewestSpot()
	{
		// Check if the current pick is still valid
		if (cachedNewestSpot != null)
		{
			TrackedSpot tracked = trackedSpots.get(cachedNewestSpot);
			if (tracked == null || cachedNewestSpot.getId() == -1)
			{
				cachedNewestSpot = null;
			}
		}

		NPC bestCandidate = null;
		int lowestElapsed = Integer.MAX_VALUE;
		int currentTick = client.getTickCount();

		for (Map.Entry<NPC, TrackedSpot> entry : trackedSpots.entrySet())
		{
			NPC npc = entry.getKey();
			if (npc.getId() == -1)
			{
				continue;
			}

			FishingSpotData spotData = FishingSpotData.findSpot(npc.getId());
			if (spotData != null && spotData.isStatic())
			{
				continue;
			}

			int elapsed = currentTick - entry.getValue().spawnTick;
			if (elapsed < lowestElapsed)
			{
				lowestElapsed = elapsed;
				bestCandidate = npc;
			}
		}

		if (cachedNewestSpot == null)
		{
			// No current pick — use whatever we found
			cachedNewestSpot = bestCandidate;
		}
		else if (bestCandidate != null)
		{
			// Only switch if the new candidate is strictly newer
			TrackedSpot currentTracked = trackedSpots.get(cachedNewestSpot);
			TrackedSpot candidateTracked = trackedSpots.get(bestCandidate);
			if (currentTracked != null && candidateTracked != null
				&& candidateTracked.spawnTick > currentTracked.spawnTick)
			{
				cachedNewestSpot = bestCandidate;
			}
		}
	}

	@Provides
	FishingSpotTrackerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(FishingSpotTrackerConfig.class);
	}

	/**
	 * Tracks a fishing spot NPC with its original spawn tick and last known position.
	 */
	static class TrackedSpot
	{
		final int spawnTick;
		final WorldPoint worldPoint;

		TrackedSpot(int spawnTick, WorldPoint worldPoint)
		{
			this.spawnTick = spawnTick;
			this.worldPoint = worldPoint;
		}
	}

	/**
	 * A despawned spot saved by location so the timer can be restored
	 * if the player walks back into range.
	 */
	private static class CachedSpot
	{
		final int spawnTick;
		final int despawnTick;

		CachedSpot(int spawnTick, int despawnTick)
		{
			this.spawnTick = spawnTick;
			this.despawnTick = despawnTick;
		}
	}

	/**
	 * Composite key: world location + NPC ID, so we only restore timers
	 * for the same spot type at the same tile.
	 */
	private static class LocationKey
	{
		final WorldPoint worldPoint;
		final int npcId;

		LocationKey(WorldPoint worldPoint, int npcId)
		{
			this.worldPoint = worldPoint;
			this.npcId = npcId;
		}

		@Override
		public boolean equals(Object o)
		{
			if (this == o) return true;
			if (!(o instanceof LocationKey)) return false;
			LocationKey that = (LocationKey) o;
			return npcId == that.npcId && worldPoint.equals(that.worldPoint);
		}

		@Override
		public int hashCode()
		{
			return 31 * worldPoint.hashCode() + npcId;
		}
	}
}
