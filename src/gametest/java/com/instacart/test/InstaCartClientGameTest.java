package com.instacart.test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Items;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;

/**
 * Boots a real client + integrated server, gives the player the gear, presses the Insta Cart key
 * and checks (server side) that an arrow was shot and the TNT minecart exploded.
 * Scenario 1: everything in the hotbar. Scenario 2: everything in the main inventory (must be moved to the hotbar).
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
			if (id.equals("minecraft:arrow") || id.equals("minecraft:tnt_minecart")) {
				EVENTS.add("load " + id);
			}
		});
		ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
			String id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
			if (id.equals("minecraft:arrow") || id.equals("minecraft:tnt_minecart")) {
				EVENTS.add("unload " + id);
			}
		});

		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			TestServerContext server = singleplayer.getServer();
			context.waitTicks(80);
			server.runCommand("gamemode survival @a");
			server.runCommand("effect give @a minecraft:resistance 1000 4 true");

			boolean hotbar = runScenario(context, server, "hotbar", "hotbar.0", "hotbar.1", "hotbar.2");
			boolean inventory = runScenario(context, server, "inventory", "inventory.5", "inventory.6", "inventory.7");
			log("FINAL hotbarScenario=" + hotbar + " inventoryScenario=" + inventory);
			if (!hotbar || !inventory) {
				throw new AssertionError("Insta Cart failed: hotbarScenario=" + hotbar + " inventoryScenario=" + inventory);
			}
		}
	}

	private static boolean runScenario(ClientGameTestContext context, TestServerContext server, String name, String bowSlot, String railSlot, String cartSlot) {
		server.runCommand("kill @e[type=!player]");
		server.runCommand("fill -6 99 -6 6 99 6 minecraft:stone");
		server.runCommand("fill -6 100 -6 6 108 6 minecraft:air");
		server.runCommand("tp @a 0.5 100 0.5 0 25");
		server.runCommand("clear @a");
		server.runCommand("item replace entity @a " + bowSlot + " with minecraft:bow[enchantments={\"minecraft:flame\":1}]");
		server.runCommand("item replace entity @a " + railSlot + " with minecraft:rail 4");
		server.runCommand("item replace entity @a " + cartSlot + " with minecraft:tnt_minecart");
		server.runCommand("item replace entity @a inventory.0 with minecraft:arrow 16");
		context.waitTicks(30);
		EVENTS.clear();

		context.getInput().pressKey(InputConstants.KEY_R);
		context.waitTicks(100);

		boolean arrow = EVENTS.stream().anyMatch(e -> e.equals("load minecraft:arrow"));
		boolean cart = EVENTS.stream().anyMatch(e -> e.equals("load minecraft:tnt_minecart"));
		boolean cartGone = EVENTS.stream().anyMatch(e -> e.equals("unload minecraft:tnt_minecart"));
		boolean railGone = server.computeOnServer(s -> s.overworld().getBlockState(new BlockPos(0, 100, 3)).isAir());
		int selected = server.computeOnServer(s -> s.getPlayerList().getPlayers().get(0).getInventory().getSelectedSlot());
		int arrows = server.computeOnServer(s -> {
			var inv = s.getPlayerList().getPlayers().get(0).getInventory();
			int n = 0;
			for (int i = 0; i < inv.getContainerSize(); i++) {
				if (inv.getItem(i).is(Items.ARROW)) {
					n += inv.getItem(i).getCount();
				}
			}
			return n;
		});
		boolean ok = arrow && cart && cartGone && railGone;
		log("SCENARIO " + name + ": arrowShot=" + arrow + " cartPlaced=" + cart + " cartGone=" + cartGone
				+ " railDestroyed=" + railGone + " selectedSlotAfter=" + selected + " arrowsLeft=" + arrows + " => " + (ok ? "PASS" : "FAIL"));
		context.takeScreenshot("instacart-" + name);
		return ok;
	}
}
