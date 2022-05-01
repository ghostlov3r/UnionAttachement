package dev.ghostlov3r.unionatt;

import beengine.Server;
import beengine.event.EventListener;
import beengine.event.EventManager;
import beengine.event.player.PlayerPreLoginEvent;
import beengine.minecraft.LoginSuccessor;
import beengine.minecraft.MinecraftSession;
import beengine.nbt.NbtMap;
import beengine.permission.BanEntry;
import beengine.plugin.AbstractPlugin;
import beengine.scheduler.AsyncTask;
import beengine.scheduler.Scheduler;
import beengine.util.config.Config;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import lord.core.Lord;
import lord.core.gamer.Gamer;
import lord.core.union.UnionDataProvider;
import lord.core.union.UnionServer;
import lord.core.union.packet.*;

import java.text.DateFormat;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public class UnionAttachment extends AbstractPlugin<Config> implements EventListener<Gamer> {

	static String MAIN_SERVER_ID = "lobby";
	static long MAX_TIME = TimeUnit.SECONDS.toMillis(30);
	static long SAVE_MAX_TIME = TimeUnit.SECONDS.toMillis(15);

	@RequiredArgsConstructor
	static class SentRequest {
		final MinecraftSession session;
		long time = System.currentTimeMillis();
	}

	@Accessors(fluent = true)
	@Getter
	@RequiredArgsConstructor
	static class ReceivedData {
		final NbtMap nbt;
		long time = System.currentTimeMillis();
	}

	@RequiredArgsConstructor
	static class DataSave {
		final NbtMap data;
		long time = System.currentTimeMillis();
	}

	DateFormat banFormat = DateFormat.getDateTimeInstance();
	Map<Long, SentRequest> sentRequests = new ConcurrentHashMap<>();
	Map<String, ReceivedData> receivedData = new ConcurrentHashMap<>();
	Map<String, DataSave> dataSave = new ConcurrentHashMap<>();

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
		Scheduler.delayedRepeat(20, 200, () -> Server.asyncPool().execute(new AsyncTask() {
			@Override
			public void run() {
				UnionAttachment.this.doChecks();
			}

			@Override
			public String name() {
				return "UnionAttChecks";
			}
		}));
	}

	void doChecks () {
		long now = System.currentTimeMillis();
		sentRequests.forEach((id, request) -> {
			if (request.time + MAX_TIME < now) {
				sentRequests.compute(id, (__, req) -> {
					if (req == request)
						req = null;
					return req;
				});
			}
		});
		receivedData.forEach((name, data) -> {
			if (data.time + MAX_TIME < now) {
				receivedData.compute(name, (__, d) -> {
					if (d == data)
						d = null;
					return d;
				});
			}
		});
		dataSave.forEach((name, data) -> {
			if (data.time + SAVE_MAX_TIME < now) {
				dataSave.compute(name, (__, d) -> {
					if (d == data) {
						doDataSave(name, d.data);
						d.time = now;
					}
					return d;
				});
			}
		});
	}

	public class DataProvider extends UnionDataProvider {

		@Override
		public NbtMap readData(String name) {
			return Optional.ofNullable(receivedData.remove(name)).map(ReceivedData::nbt).orElse(null);
		}

		@Override
		public void writeData(String name, NbtMap data) {
			Server.asyncPool().execute(new AsyncTask() {
				@Override
				public void run() {
					dataSave.put(name, new DataSave(data));
					doDataSave(name, data);
				}

				@Override
				public String name() {
					return "UnionAttDataSend";
				}
			});
		}
	}

	void doDataSave (String name, NbtMap data) {
		UnionServer server = Lord.unionHandler.getServer(MAIN_SERVER_ID);
		if (server != null && server.isOnline) {
			GamerDataSave response = new GamerDataSave();
			response.name = name;
			response.data = data;
			server.sendPacket(response);
		}
	}

	public class PacketHandler extends UnionPacketHandler {
		@Override
		public boolean handle(GamerDataResponse packet, UnionServer server) {
			SentRequest request = sentRequests.remove(packet.requestId);
			if (request == null) {
				return false;
			}
			MinecraftSession session = request.session;
			if (session.isConnected()) {

				Runnable task = null;

				switch (packet.status) {
					case ALLOW -> {
						receivedData.put(session.playerInfo().username(), new ReceivedData(packet.gamerData));
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

		@Override
		public boolean handle(GamerDataSaved packet, UnionServer server) {
			return dataSave.remove(packet.name) != null;
		}
	}

	@Override
	public void onPlayerPreLogin(PlayerPreLoginEvent event) {
		event.setSuccessor(new LoginSuccessor(event.session()) {
			@Override
			public void onLoginSuccess() {
				UnionServer server = Lord.unionHandler.getServer(MAIN_SERVER_ID);
				if (server == null || !server.isOnline) {
					event.session().disconnect("Мы не можем проверить статус вашей авторизации\n Попробуйте снова");
				} else {
					GamerDataRequest request = new GamerDataRequest();
					request.requestId = ThreadLocalRandom.current().nextLong();
					request.name = event.session().playerInfo().username();
					request.address = event.session().address();
					sentRequests.put(request.requestId, new SentRequest(event.session()));
					server.sendPacket(request);
				}
			}
		});
	}
}
