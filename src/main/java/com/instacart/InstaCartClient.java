package com.instacart;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.function.Predicate;

import com.mojang.blaze3d.platform.InputConstants;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Insta Cart: one key press places a rail (or reuses the rail you are looking at), puts a TNT minecart on it,
 * then draws a Flame bow at the cart. A flaming arrow detonates a TNT minecart instantly.
 *
 * Everything runs client side as a small tick state machine, using only normal player actions
 * (hotbar select, use item on block, use item, release item).
 */
public class InstaCartClient implements ClientModInitializer {
	public static final String MOD_ID = "instacart";
	private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private enum Phase { IDLE, PLACE_RAIL, PLACE_CART, START_DRAW, DRAWING, RESTORE }

	/** Everything validated at key press time. */
	private record Plan(BlockPos railPos, BlockHitResult railHit, boolean placeRail, int bowSlot, int railSlot, int cartSlot) {}

	// Config (config/instacart.properties)
	private int drawTicks = 20;
	private boolean autoAim = true;

	private KeyMapping triggerKey;

	private Phase phase = Phase.IDLE;
	private Plan plan;
	private int originalSlot;
	private int drawCounter;
	private boolean holdingUseKey;

	@Override
	public void onInitializeClient() {
		loadConfig();

		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
		this.triggerKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.instacart.trigger",
				InputConstants.Type.KEYSYM,
				InputConstants.KEY_R,
				category
		));

		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
	}

	private void onTick(Minecraft mc) {
		boolean pressed = false;
		while (this.triggerKey.consumeClick()) {
			pressed = true;
		}

		LocalPlayer player = mc.player;
		if (player == null || mc.level == null || mc.gameMode == null) {
			this.reset(mc);
			return;
		}

		if (this.phase != Phase.IDLE) {
			if (mc.gui.screen() != null || player.isSpectator()) {
				this.abort(mc, "Cancelled.");
				return;
			}
			this.step(mc, player);
			return;
		}

		if (pressed && mc.gui.screen() == null) {
			this.start(mc, player);
		}
	}

	// ---------------------------------------------------------------- checks

	private void start(Minecraft mc, LocalPlayer player) {
		if (player.isSpectator() || player.isUsingItem()) {
			return;
		}

		Inventory inv = player.getInventory();

		// Search the whole inventory (0-8 hotbar, 9-35 main); items found outside the hotbar are swapped in below.
		int bowSlot = findSlot(inv, stack -> stack.getItem() instanceof BowItem && hasFlame(stack));
		int railSlot = findSlot(inv, stack -> stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof BaseRailBlock);
		int cartSlot = findSlot(inv, stack -> stack.is(Items.TNT_MINECART));

		if (bowSlot < 0) {
			say(player, "You need a bow with Flame in your inventory.");
			return;
		}
		if (player.getProjectile(inv.getItem(bowSlot)).isEmpty()) {
			say(player, "You have no arrows.");
			return;
		}
		if (cartSlot < 0) {
			say(player, "You need a TNT minecart in your inventory.");
			return;
		}

		// The crosshair target is already limited to the block interaction range.
		HitResult hit = mc.hitResult;
		if (!(hit instanceof BlockHitResult blockHit) || blockHit.getType() != HitResult.Type.BLOCK) {
			say(player, "No block in range - aim at a block you can reach.");
			return;
		}

		ClientLevel level = mc.level;
		BlockState targeted = level.getBlockState(blockHit.getBlockPos());

		BlockPos railPos;
		boolean placeRail;
		if (targeted.getBlock() instanceof BaseRailBlock) {
			// Already looking at a rail: just use it.
			railPos = blockHit.getBlockPos();
			placeRail = false;
		} else {
			if (railSlot < 0) {
				say(player, "You need a rail in your inventory.");
				return;
			}
			ItemStack railStack = inv.getItem(railSlot);
			BlockItem railItem = (BlockItem) railStack.getItem();
			BlockPlaceContext context = new BlockPlaceContext(player, InteractionHand.MAIN_HAND, railStack, blockHit);
			if (!context.canPlace()) {
				say(player, "No room to place a rail there.");
				return;
			}
			railPos = context.getClickedPos();
			BlockState railState = railItem.getBlock().getStateForPlacement(context);
			if (railState == null || !railState.canSurvive(level, railPos)) {
				say(player, "A rail can't be placed there (no solid block below).");
				return;
			}
			placeRail = true;
		}

		// Bring everything we need into the hotbar (a swap puts the displaced hotbar item where the item came from).
		int[] slots = { bowSlot, placeRail ? railSlot : -1, cartSlot };
		boolean[] reserved = new boolean[9];
		for (int slot : slots) {
			if (slot >= 0 && slot < 9) {
				reserved[slot] = true;
			}
		}
		for (int i = 0; i < slots.length; i++) {
			if (slots[i] < 9) {
				continue;
			}
			int target = freeHotbarSlot(inv, reserved);
			if (target < 0) {
				say(player, "No free hotbar slot to bring your items into.");
				return;
			}
			LOGGER.info("[InstaCart] moving inventory slot {} to hotbar slot {}", slots[i], target);
			// InventoryMenu: main inventory slots 9-35 have the same index as in Inventory.
			mc.gameMode.handleInventoryMouseClick(player.inventoryMenu.containerId, slots[i], target, ContainerInput.SWAP, player);
			slots[i] = target;
			reserved[target] = true;
		}
		bowSlot = slots[0];
		railSlot = slots[1];
		cartSlot = slots[2];

		LOGGER.info("[InstaCart] start: railPos={} placeRail={} slots(bow={}, rail={}, cart={}) drawTicks={}", railPos, placeRail, bowSlot, railSlot, cartSlot, this.drawTicks);
		this.plan = new Plan(railPos, blockHit, placeRail, bowSlot, railSlot, cartSlot);
		this.originalSlot = inv.getSelectedSlot();
		this.drawCounter = 0;
		this.phase = placeRail ? Phase.PLACE_RAIL : Phase.PLACE_CART;
		this.step(mc, player);
	}

	// ---------------------------------------------------------------- sequence

	private void step(Minecraft mc, LocalPlayer player) {
		Inventory inv = player.getInventory();
		Plan p = this.plan;
		LOGGER.info("[InstaCart] phase {} (using={}, slot={})", this.phase, player.isUsingItem(), inv.getSelectedSlot());

		switch (this.phase) {
			case PLACE_RAIL -> {
				inv.setSelectedSlot(p.railSlot());
				InteractionResult result = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, p.railHit());
				if (!result.consumesAction()) {
					this.abort(mc, "Could not place the rail.");
					return;
				}
				player.swing(InteractionHand.MAIN_HAND);
				this.phase = Phase.PLACE_CART;
			}
			case PLACE_CART -> {
				if (!(mc.level.getBlockState(p.railPos()).getBlock() instanceof BaseRailBlock)) {
					this.abort(mc, "The rail was not placed.");
					return;
				}
				inv.setSelectedSlot(p.cartSlot());
				BlockHitResult onRail = new BlockHitResult(Vec3.atCenterOf(p.railPos()), Direction.UP, p.railPos(), false);
				InteractionResult result = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, onRail);
				if (!result.consumesAction()) {
					this.abort(mc, "Could not place the TNT minecart.");
					return;
				}
				player.swing(InteractionHand.MAIN_HAND);
				this.phase = Phase.START_DRAW;
			}
			case START_DRAW -> {
				inv.setSelectedSlot(p.bowSlot());
				this.aim(player);
				InteractionResult result = mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
				if (!result.consumesAction() || !player.isUsingItem()) {
					this.abort(mc, "Could not draw the bow.");
					return;
				}
				// Vanilla releases the bow on the next tick if the use key is not held, so hold it for the draw.
				mc.options.keyUse.setDown(true);
				this.holdingUseKey = true;
				this.drawCounter = 0;
				this.phase = Phase.DRAWING;
			}
			case DRAWING -> {
				if (!player.isUsingItem() || inv.getSelectedSlot() != p.bowSlot()) {
					this.abort(mc, "Bow draw was interrupted.");
					return;
				}
				this.aim(player);
				mc.options.keyUse.setDown(true);
				this.drawCounter++;
				if (this.drawCounter >= this.drawTicks) {
					LOGGER.info("[InstaCart] releasing bow after {} ticks (ticksUsingItem={})", this.drawCounter, player.getTicksUsingItem());
					mc.gameMode.releaseUsingItem(player);
					mc.options.keyUse.setDown(false);
					this.holdingUseKey = false;
					this.phase = Phase.RESTORE;
				}
			}
			case RESTORE -> {
				inv.setSelectedSlot(this.originalSlot);
				this.plan = null;
				this.phase = Phase.IDLE;
			}
			default -> {
			}
		}
	}

	/** Look at the middle of the minecart so the arrow flies straight into it. */
	private void aim(LocalPlayer player) {
		if (!this.autoAim) {
			return;
		}
		BlockPos pos = this.plan.railPos();
		Vec3 target = new Vec3(pos.getX() + 0.5, pos.getY() + 0.4, pos.getZ() + 0.5);
		Vec3 eye = player.getEyePosition();
		double dx = target.x - eye.x;
		double dy = target.y - eye.y;
		double dz = target.z - eye.z;
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		player.setYRot((float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F);
		player.setXRot((float) -(Mth.atan2(dy, horizontal) * (180.0 / Math.PI)));
	}

	private void abort(Minecraft mc, String message) {
		LocalPlayer player = mc.player;
		if (player != null) {
			if (this.phase == Phase.DRAWING && player.isUsingItem()) {
				player.stopUsingItem();
			}
			player.getInventory().setSelectedSlot(this.originalSlot);
			say(player, message);
		}
		this.reset(mc);
	}

	private void reset(Minecraft mc) {
		if (this.holdingUseKey && mc.options != null) {
			mc.options.keyUse.setDown(false);
		}
		this.holdingUseKey = false;
		this.plan = null;
		this.phase = Phase.IDLE;
	}

	// ---------------------------------------------------------------- helpers

	/** First slot (hotbar first, then main inventory) whose stack matches, or -1. */
	private static int findSlot(Inventory inv, Predicate<ItemStack> test) {
		for (int i = 0; i < 36; i++) {
			ItemStack stack = inv.getItem(i);
			if (!stack.isEmpty() && test.test(stack)) {
				return i;
			}
		}
		return -1;
	}

	/** An unreserved hotbar slot: empty ones first, starting from the selected slot; otherwise any unreserved one. */
	private static int freeHotbarSlot(Inventory inv, boolean[] reserved) {
		int selected = inv.getSelectedSlot();
		for (int k = 0; k < 9; k++) {
			int i = (selected + k) % 9;
			if (!reserved[i] && inv.getItem(i).isEmpty()) {
				return i;
			}
		}
		for (int k = 0; k < 9; k++) {
			int i = (selected + k) % 9;
			if (!reserved[i]) {
				return i;
			}
		}
		return -1;
	}

	private static boolean hasFlame(ItemStack stack) {
		ItemEnchantments enchantments = stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
		for (Holder<Enchantment> enchantment : enchantments.keySet()) {
			if (enchantment.is(Enchantments.FLAME)) {
				return true;
			}
		}
		return false;
	}

	private static void say(LocalPlayer player, String message) {
		LOGGER.info("[InstaCart] {}", message);
		player.sendSystemMessage(Component.literal("[Insta Cart] " + message));
	}

	// ---------------------------------------------------------------- config

	private void loadConfig() {
		Path file = FabricLoader.getInstance().getConfigDir().resolve("instacart.properties");
		Properties props = new Properties();

		if (Files.exists(file)) {
			try (InputStream in = Files.newInputStream(file)) {
				props.load(in);
			} catch (IOException e) {
				LOGGER.warn("Could not read {}", file, e);
			}
		} else {
			props.setProperty("draw_ticks", "20");
			props.setProperty("auto_aim", "true");
			try (OutputStream out = Files.newOutputStream(file)) {
				props.store(out, """
						Insta Cart
						draw_ticks: how many ticks the bow is drawn before release (3-20). 20 = full power. Lower = faster but weaker arrow.
						auto_aim: turn the camera towards the minecart while drawing (true/false).""");
			} catch (IOException e) {
				LOGGER.warn("Could not write {}", file, e);
			}
		}

		try {
			this.drawTicks = Mth.clamp(Integer.parseInt(props.getProperty("draw_ticks", "20").trim()), 3, 20);
		} catch (NumberFormatException e) {
			this.drawTicks = 20;
		}
		this.autoAim = Boolean.parseBoolean(props.getProperty("auto_aim", "true").trim());
	}
}
