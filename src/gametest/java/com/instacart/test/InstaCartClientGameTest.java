package com.instacart.test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.core.registries.BuiltInRegistries;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;

/**
 * Boots a real client + integrated server, gives the player the gear, presses the Insta Cart key
 * and checks (server side) that an arrow was shot and the TNT minecart exploded.
 */
@SuppressWarnings("UnstableApiUsage")
public class InstaCartClientGameTest implements FabricClientGameTest {
	private static final List<String> EVENTS = new CopyOnWriteArrayList<>();

	private static void log(String line) {
		System.out.println("[InstaCartTest] " + line);
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			String id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
			if (id.equals("minecraft:arrow") || id.equals("minecraft:tnt_minecart") || id.equals("minecraft:tnt")) {
				EVENTS.add("load " + id + " onFire=" + entity.isOnFire());
				log("load " + id + " onFire=" + entity.isOnFire());
			}
		});
		ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
			String id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
			if (id.equals("minecraft:arrow") || id.equals("minecraft:tnt_minecart")) {
				EVENTS.add("unload " + id);
				log("unload " + id);
			}
		});

		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			TestServerContext server = singleplayer.getServer();
			context.waitTicks(80);

			server.runCommand("gamemode survival @a");
			server.runCommand("effect give @a minecraft:resistance 1000 4 true");
			server.runCommand("fill -6 99 -6 6 99 6 minecraft:stone");
			server.runCommand("fill -6 100 -6 6 108 6 minecraft:air");
			server.runCommand("tp @a 0.5 100 0.5 0 25");
			server.runCommand("clear @a");
			server.runCommand("item replace entity @a hotbar.0 with minecraft:bow[enchantments={\"minecraft:flame\":1}]");
			server.runCommand("item replace entity @a hotbar.1 with minecraft:rail 4");
			server.runCommand("item replace entity @a hotbar.2 with minecraft:tnt_minecart 2");
			server.runCommand("item replace entity @a inventory.0 with minecraft:arrow 16");
			server.runCommand("kill @e[type=!player]");
			context.waitTicks(20);

			EVENTS.clear();
			log("hotbar before: " + server.computeOnServer(s -> s.getPlayerList().getPlayers().get(0).getInventory().getSelectedSlot()));

			context.getInput().pressKey(InputConstants.KEY_R);
			context.waitTicks(100);

			log("selected slot after: " + server.computeOnServer(s -> s.getPlayerList().getPlayers().get(0).getInventory().getSelectedSlot()));
			log("arrows left: " + server.computeOnServer(s -> {
				var inv = s.getPlayerList().getPlayers().get(0).getInventory();
				int n = 0;
				for (int i = 0; i < inv.getContainerSize(); i++) {
					if (inv.getItem(i).is(net.minecraft.world.item.Items.ARROW)) {
						n += inv.getItem(i).getCount();
					}
				}
				return n;
			}));
			log("events: " + EVENTS);
			context.takeScreenshot("instacart-after");

			boolean arrow = EVENTS.stream().anyMatch(e -> e.startsWith("load minecraft:arrow"));
			boolean cart = EVENTS.stream().anyMatch(e -> e.startsWith("load minecraft:tnt_minecart"));
			boolean exploded = EVENTS.stream().anyMatch(e -> e.startsWith("unload minecraft:tnt_minecart"));
			log("RESULT arrowShot=" + arrow + " cartPlaced=" + cart + " cartExploded=" + exploded);
			if (!arrow || !cart || !exploded) {
				throw new AssertionError("Insta Cart failed: arrowShot=" + arrow + " cartPlaced=" + cart + " cartExploded=" + exploded + " events=" + EVENTS);
			}
		}
	}
}
