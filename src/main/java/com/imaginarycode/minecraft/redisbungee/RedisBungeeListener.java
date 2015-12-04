package com.imaginarycode.minecraft.redisbungee;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lombok.AllArgsConstructor;
import net.md_5.bungee.api.AbstractReconnectHandler;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.ServerPing;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.event.LoginEvent;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.event.ProxyPingEvent;
import net.md_5.bungee.api.event.ServerConnectedEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;

import com.google.common.base.Joiner;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Multimap;
import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.imaginarycode.minecraft.redisbungee.events.PubSubMessageEvent;
import com.imaginarycode.minecraft.redisbungee.util.RedisCallable;

@AllArgsConstructor
public class RedisBungeeListener implements Listener {
    private static final BaseComponent[] ALREADY_LOGGED_IN =
            new ComponentBuilder("You are already logged on to this server.").color(ChatColor.RED)
                    .append("\n\nIt may help to try logging in again in a few minutes.\nIf this does not resolve your issue, please contact staff.")
                    .color(ChatColor.GRAY)
                    .create();
    private final RedisBungee plugin;
    private final List<InetAddress> exemptAddresses;

    private static final List<String> ASYNC_PING_EVENT_HOSTILE = ImmutableList.of("ServerListPlus");

    @EventHandler(priority = EventPriority.LOWEST)
    public void onLogin(final LoginEvent event) {
        event.registerIntent(plugin);
        plugin.getProxy().getScheduler().runAsync(plugin, new RedisCallable<Void>(plugin) {
			@Override
            protected Void call(Jedis jedis) {
                if (event.isCancelled()) {
                    event.completeIntent(plugin);
                    return null;
                }

                for (String s : plugin.getServerIds()) {
                    if (jedis.sismember("proxy:" + s + ":usersOnline", event.getConnection().getName())) {
                        event.setCancelled(true);
                        // TODO: Make it accept a BaseComponent[] like everything else.
                        event.setCancelReason(TextComponent.toLegacyText(ALREADY_LOGGED_IN));
                        event.completeIntent(plugin);
                        return null;
                    }
                }

                Pipeline pipeline = jedis.pipelined();
                //plugin.getUuidTranslator().persistInfo(event.getConnection().getName(), event.getConnection().getUniqueId(), pipeline);
                RedisUtil.createPlayer(event.getConnection(), pipeline, false);
                // We're not publishing, the API says we only publish at PostLoginEvent time.
                pipeline.sync();

                event.completeIntent(plugin);
                return null;
            }
        });
    }

    @EventHandler
    public void onPostLogin(final PostLoginEvent event) {
        plugin.getProxy().getScheduler().runAsync(plugin, new RedisCallable<Void>(plugin) {
            @Override
            protected Void call(Jedis jedis) {
                jedis.publish("redisbungee-data", RedisBungee.getGson().toJson(new DataManager.DataManagerMessage<>(
                        event.getPlayer().getName(), DataManager.DataManagerMessage.Action.JOIN,
                        new DataManager.LoginPayload(event.getPlayer().getAddress().getAddress()))));
                return null;
            }
        });
    }

    @EventHandler
    public void onPlayerDisconnect(final PlayerDisconnectEvent event) {
        plugin.getProxy().getScheduler().runAsync(plugin, new RedisCallable<Void>(plugin) {
            @Override
            protected Void call(Jedis jedis) {
                Pipeline pipeline = jedis.pipelined();
                RedisUtil.cleanUpPlayer(event.getPlayer().getName(), pipeline);
                pipeline.sync();
                return null;
            }
        });
    }

    @EventHandler
    public void onServerChange(final ServerConnectedEvent event) {
        plugin.getProxy().getScheduler().runAsync(plugin, new RedisCallable<Void>(plugin) {
            @Override
            protected Void call(Jedis jedis) {
                jedis.hset("player:" + event.getPlayer().getName(), "server", event.getServer().getInfo().getName());
                jedis.publish("redisbungee-data", RedisBungee.getGson().toJson(new DataManager.DataManagerMessage<>(
                        event.getPlayer().getName(), DataManager.DataManagerMessage.Action.SERVER_CHANGE,
                        new DataManager.ServerChangePayload(event.getServer().getInfo().getName()))));
                return null;
            }
        });
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPing(final ProxyPingEvent event) {
        if (exemptAddresses.contains(event.getConnection().getAddress().getAddress())) {
            return;
        }

        ServerInfo forced = AbstractReconnectHandler.getForcedHost(event.getConnection());

        if (forced != null && event.getConnection().getListener().isPingPassthrough()) {
            return;
        }

        boolean runAsync = true;
        for (String s : ASYNC_PING_EVENT_HOSTILE) {
            if (ProxyServer.getInstance().getPluginManager().getPlugin(s) != null) {
                runAsync = false;
                break;
            }
        }

        if (runAsync) {
            event.registerIntent(plugin);
            plugin.getProxy().getScheduler().runAsync(plugin, new Runnable() {
                @Override
                public void run() {
                    ServerPing old = event.getResponse();
                    ServerPing reply = new ServerPing();
                    reply.setPlayers(new ServerPing.Players(old.getPlayers().getMax(), plugin.getCount(), old.getPlayers().getSample()));
                    reply.setDescription(old.getDescription());
                    reply.setFavicon(old.getFaviconObject());
                    reply.setVersion(old.getVersion());
                    event.setResponse(reply);
                    event.completeIntent(plugin);
                }
            });
        } else {
            // Async ping event will not work as an async-hostile plugin was found, so perform the ping modification synchronously.
            ServerPing old = event.getResponse();
            ServerPing reply = new ServerPing();
            reply.setPlayers(new ServerPing.Players(old.getPlayers().getMax(), plugin.getCount(), old.getPlayers().getSample()));
            reply.setDescription(old.getDescription());
            reply.setFavicon(old.getFaviconObject());
            reply.setVersion(old.getVersion());
            event.setResponse(reply);
        }
    }

    @EventHandler
    public void onPluginMessage(final PluginMessageEvent event) {
        if (event.getTag().equals("RedisBungee") && event.getSender() instanceof Server) {
            final byte[] data = Arrays.copyOf(event.getData(), event.getData().length);
            plugin.getProxy().getScheduler().runAsync(plugin, new Runnable() {
                @Override
                public void run() {
                    ByteArrayDataInput in = ByteStreams.newDataInput(data);

                    String subchannel = in.readUTF();
                    ByteArrayDataOutput out = ByteStreams.newDataOutput();
                    String type;

                    switch (subchannel) {
                        case "PlayerList":
                            out.writeUTF("PlayerList");
                            Set<String> original = Collections.emptySet();
                            type = in.readUTF();
                            if (type.equals("ALL")) {
                                out.writeUTF("ALL");
                                original = plugin.getPlayers();
                            } else {
                                try {
                                    original = plugin.getPlayersOnServer(type);
                                } catch (IllegalArgumentException ignored) {
                                }
                            }
                            Set<String> players = new HashSet<>();
                            for (String playerName : original)
                                players.add(playerName);
                            out.writeUTF(Joiner.on(',').join(players));
                            break;
                        case "PlayerCount":
                            out.writeUTF("PlayerCount");
                            type = in.readUTF();
                            if (type.equals("ALL")) {
                                out.writeUTF("ALL");
                                out.writeInt(plugin.getCount());
                            } else {
                                out.writeUTF(type);
                                try {
                                    out.writeInt(plugin.getPlayersOnServer(type).size());
                                } catch (IllegalArgumentException e) {
                                    out.writeInt(0);
                                }
                            }
                            break;
                        case "LastOnline":
                            String user = in.readUTF();
                            out.writeUTF("LastOnline");
                            out.writeUTF(user);
                            out.writeLong(RedisBungee.getApi().getLastOnline(user));
                            break;
                        case "ServerPlayers":
                            String type1 = in.readUTF();
                            out.writeUTF("ServerPlayers");
                            Multimap<String, String> multimap = RedisBungee.getApi().getServerToPlayers();

                            boolean includesUsers;

                            switch (type1) {
                                case "COUNT":
                                    includesUsers = false;
                                    break;
                                case "PLAYERS":
                                    includesUsers = true;
                                    break;
                                default:
                                    // TODO: Should I raise an error?
                                    return;
                            }

                            out.writeUTF(type1);

                            if (includesUsers) {
                                Multimap<String, String> human = HashMultimap.create();
                                for (Map.Entry<String, String> entry : multimap.entries()) {
                                    human.put(entry.getKey(), entry.getValue());
                                }
                                serializeMultimap(human, true, out);
                            } else {
                                // Due to Java generics, we are forced to coerce Strings into strings. This is less
                                // expensive than looking up names, since we just want counts.
                                Multimap<String, String> flunk = HashMultimap.create();
                                for (Map.Entry<String, String> entry : multimap.entries()) {
                                    flunk.put(entry.getKey(), entry.getValue().toString());
                                }
                                serializeMultimap(flunk, false, out);
                            }
                            break;
                        case "Proxy":
                            out.writeUTF("Proxy");
                            out.writeUTF(RedisBungee.getConfiguration().getServerId());
                            break;
                        default:
                            return;
                    }

                    ((Server) event.getSender()).sendData("RedisBungee", out.toByteArray());
                }
            });
        }
    }

    private void serializeMultimap(Multimap<String, String> collection, boolean includeNames, ByteArrayDataOutput output) {
        output.writeInt(collection.size());
        for (Map.Entry<String, Collection<String>> entry : collection.asMap().entrySet()) {
            output.writeUTF(entry.getKey());
            if (includeNames) {
                serializeCollection(entry.getValue(), output);
            } else {
                output.writeInt(entry.getValue().size());
            }
        }
    }

    private void serializeCollection(Collection<?> collection, ByteArrayDataOutput output) {
        output.writeInt(collection.size());
        for (Object o : collection) {
            output.writeUTF(o.toString());
        }
    }

    @EventHandler
    public void onPubSubMessage(PubSubMessageEvent event) {
        if (event.getChannel().equals("redisbungee-allservers") || event.getChannel().equals("redisbungee-" + RedisBungee.getApi().getServerId())) {
            String message = event.getMessage();
            if (message.startsWith("/"))
                message = message.substring(1);
            plugin.getLogger().info("Invoking command via PubSub: /" + message);
            plugin.getProxy().getPluginManager().dispatchCommand(RedisBungeeCommandSender.instance, message);
        }
    }
}
