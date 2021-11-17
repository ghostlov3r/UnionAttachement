package dev.ghostlov3r.unionatt;

import dev.ghostlov3r.beengine.Server;
import dev.ghostlov3r.beengine.event.EventListener;
import dev.ghostlov3r.beengine.event.EventManager;
import dev.ghostlov3r.beengine.event.player.PlayerPreLoginEvent;
import dev.ghostlov3r.beengine.permission.BanEntry;
import dev.ghostlov3r.beengine.plugin.AbstractPlugin;
import dev.ghostlov3r.beengine.scheduler.AsyncTask;
import dev.ghostlov3r.beengine.utils.config.Config;
import dev.ghostlov3r.minecraft.LoginSuccessor;
import dev.ghostlov3r.minecraft.MinecraftSession;
import dev.ghostlov3r.nbt.NbtMap;
import lord.core.Lord;
import lord.core.gamer.Gamer;
import lord.core.union.UnionDataProvider;
import lord.core.union.UnionServer;
import lord.core.union.packet.GamerDataRequest;
import lord.core.union.packet.GamerDataResponse;
import lord.core.union.packet.GamerDataSave;
import lord.core.union.packet.UnionPacketHandler;

import java.text.DateFormat;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class UnionAttachment extends AbstractPlugin<Config> implements EventListener<Gamer> {

	DateFormat banFormat = DateFormat.getDateTimeInstance();
	Map<Long, MinecraftSession> sentRequests = new ConcurrentHashMap<>();
	Map<String, NbtMap> receivedData = new ConcurrentHashMap<>();

	// TODO чистка мапок выше

	@Override
	protected void onLoad() {
		Lord.unionHandler.servers().forEach(server -> {
			server.handler = new PacketHandler();
		});
		Lord.unionHandler.setProvider(new DataProvider());
	}

	@Override
	protected void onEnable() {
		EventManager.get().register(this, this);
	}

	public class DataProvider extends UnionDataProvider {

		@Override
		public NbtMap readData(String name) {
			return receivedData.remove(name);
		}

		@Override
		public void writeData(String name, NbtMap data) {
			Server.asyncPool().execute(new AsyncTask() {
				@Override
				public void run() {
					Lord.unionHandler.servers().forEach(server -> {
						if (server.isOnline && server.name.equals(HARDCODED_LOBBY_NAME)) {
							GamerDataSave response = new GamerDataSave();
							response.name = name;
							response.data = data;
							server.sendPacket(response);
						}
					});
				}
			});
		}
	}

	public class PacketHandler extends UnionPacketHandler {
		@Override
		public boolean handle(GamerDataResponse packet, UnionServer server) {
			MinecraftSession session = sentRequests.remove(packet.requestId);
			if (session != null && session.isConnected()) {

				Runnable task = null;

				switch (packet.status) {
					case ALLOW -> {
						receivedData.put(session.playerInfo().username(), packet.gamerData);
						task = () -> {
							session.onServerLoginSuccess();
							session.worker().localFlush(session);
						};
					}
					case DUPLICATE -> {
						task = () -> session.disconnect("Вы уже играете");
					}
					case BANNED -> {
						String message = packet.bannedUntil == BanEntry.NEVER_EXPIRES
								? "Вы забанены"
								: "Вы забанены до " + banFormat.format(new Date(packet.bannedUntil));
						task = () -> session.disconnect(message);
					}
				}
				if (task != null) {
					session.worker().scheduleTask(task);
				}
				return true;
			}
			return false;
		}
	}

	static String HARDCODED_LOBBY_NAME = "lobby";

	@Override
	public void onPlayerPreLogin(PlayerPreLoginEvent event) {
		event.setSuccessor(new LoginSuccessor(event.session()) {
			@Override
			public void onLoginSuccess() {
				boolean sent = false;
				for (UnionServer server : Lord.unionHandler.servers()) {
					if (server.isOnline && server.name.equals(HARDCODED_LOBBY_NAME)) {
						GamerDataRequest request = new GamerDataRequest();
						request.requestId = ThreadLocalRandom.current().nextLong();
						request.name = event.session().playerInfo().username();
						request.address = event.session().address();
						sentRequests.put(request.requestId, event.session());
						server.sendPacket(request);
						sent = true;
						break;
					}
				}
				if (!sent) {
					event.setKickReason(PlayerPreLoginEvent.KickReason.PLUGIN, "Мы не можем проверить статус вашей авторизации");
				}
			}
		});
	}
}
