package com.imaginarycode.minecraft.redisbungee.events;

import lombok.ToString;
import net.md_5.bungee.api.plugin.Event;

/**
 * This event is sent when a player disconnects. RedisBungee sends the event
 * only when
 * the proxy the player has been connected to is different than the local proxy.
 * <p>
 * This event corresponds to
 * {@link net.md_5.bungee.api.event.PlayerDisconnectEvent}, and is fired
 * asynchronously.
 *
 * @since 0.3.4
 */
@ToString
public class PlayerLeftNetworkEvent extends Event {
	private final String playerName;

	public PlayerLeftNetworkEvent(String playerName) {
		this.playerName = playerName;
	}

	public String getUuid() {
		return playerName;
	}
}
