package com.orca.bloodmoonhorde;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

public class BloodMoonHorde implements ModInitializer {
    public static final String MOD_ID = "blood-moon-horde";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static final Item BLOOD_MOON_TROPHY = new Item(new Item.Settings().maxCount(1));

    private static boolean isBloodMoonActive = false;
    private static boolean bossSpawned = false;
    private static boolean warningShown = false;
    private static boolean rewardsGiven = false;
    private static long lastBloodMoonDay = -7;
    private static final int BLOOD_MOON_INTERVAL = 7;
    private static final Random random = new Random();

    private static int spawnCooldown = 0;
    private static final Map<Integer, Long> playerBloodMoonStart = new HashMap<>();

    @Override
    public void onInitialize() {
        LOGGER.info("Blood Moon Horde mod initialized!");

        Registry.register(Registries.ITEM, Identifier.of(MOD_ID, "blood_moon_trophy"), BLOOD_MOON_TROPHY);

        ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);
    }

    private void onServerTick(MinecraftServer server) {
        ServerWorld overworld = server.getOverworld();
        if (overworld == null) return;

        long timeOfDay = overworld.getTimeOfDay() % 24000;
        long currentDay = overworld.getTimeOfDay() / 24000;

        boolean shouldBeBloodMoon = (currentDay % BLOOD_MOON_INTERVAL == 0) && currentDay > 0;
        boolean isNight = timeOfDay >= 13000 && timeOfDay < 23000;
        boolean isDusk = timeOfDay >= 12000 && timeOfDay < 13000;
        boolean isMidnight = timeOfDay >= 18000 && timeOfDay < 18100;

        if (shouldBeBloodMoon && isDusk && !warningShown) {
            broadcastMessage(server, "A Blood Moon is rising tonight! Prepare yourself!", Formatting.DARK_RED);
            warningShown = true;
        }

        if (shouldBeBloodMoon && isNight) {
            if (!isBloodMoonActive) {
                startBloodMoon(server);
            }

            handleBloodMoonEffects(server, overworld, isMidnight);

        } else if (isBloodMoonActive && !isNight) {
            endBloodMoon(server);
        }

        if (!shouldBeBloodMoon && currentDay > lastBloodMoonDay) {
            warningShown = false;
        }
    }

    private void startBloodMoon(MinecraftServer server) {
        isBloodMoonActive = true;
        bossSpawned = false;
        rewardsGiven = false;
        lastBloodMoonDay = server.getOverworld().getTimeOfDay() / 24000;

        broadcastMessage(server, "The Blood Moon has begun! Monsters grow stronger!", Formatting.RED);

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            playerBloodMoonStart.put(player.getId(), server.getOverworld().getTime());
        }

        LOGGER.info("Blood Moon event started!");
    }

    private void endBloodMoon(MinecraftServer server) {
        isBloodMoonActive = false;

        broadcastMessage(server, "The Blood Moon fades... You have survived!", Formatting.GOLD);

        if (!rewardsGiven) {
            giveRewards(server);
            rewardsGiven = true;
        }

        LOGGER.info("Blood Moon event ended!");
    }

    private void handleBloodMoonEffects(MinecraftServer server, ServerWorld world, boolean isMidnight) {
        spawnCooldown--;

        if (spawnCooldown <= 0) {
            spawnExtraMobs(world);
            spawnCooldown = 20;
        }

        applyMobBuffs(world);

        if (isMidnight && !bossSpawned) {
            spawnBloodMoonBoss(server, world);
            bossSpawned = true;
        }

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (player.getWorld() == world) {
                player.addStatusEffect(new StatusEffectInstance(StatusEffects.NIGHT_VISION, 400, 0, false, false));
            }
        }
    }

    private void spawnExtraMobs(ServerWorld world) {
        for (ServerPlayerEntity player : world.getPlayers()) {
            for (int i = 0; i < 10; i++) {
                int offsetX = random.nextInt(64) - 32;
                int offsetZ = random.nextInt(64) - 32;

                if (Math.abs(offsetX) < 8 && Math.abs(offsetZ) < 8) continue;

                BlockPos spawnPos = player.getBlockPos().add(offsetX, 0, offsetZ);
                spawnPos = world.getTopPosition(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, spawnPos);

                if (!world.getBlockState(spawnPos.down()).isAir() &&
                    world.getBlockState(spawnPos).isAir() &&
                    world.getBlockState(spawnPos.up()).isAir()) {

                    HostileEntity mob = selectRandomMob(world, spawnPos);
                    if (mob != null) {
                        mob.refreshPositionAndAngles(spawnPos, random.nextFloat() * 360, 0);
                        world.spawnEntity(mob);
                    }
                }
            }
        }
    }

    private HostileEntity selectRandomMob(ServerWorld world, BlockPos pos) {
        int choice = random.nextInt(5);

        return switch (choice) {
            case 0 -> EntityType.ZOMBIE.create(world);
            case 1 -> EntityType.SKELETON.create(world);
            case 2 -> EntityType.SPIDER.create(world);
            case 3 -> EntityType.CREEPER.create(world);
            case 4 -> EntityType.WITCH.create(world);
            default -> EntityType.ZOMBIE.create(world);
        };
    }

    private void applyMobBuffs(ServerWorld world) {
        for (HostileEntity mob : world.getEntitiesByClass(HostileEntity.class,
                world.getPlayers().isEmpty() ? null : world.getPlayers().get(0).getBoundingBox().expand(128),
                e -> true)) {

            if (!mob.hasStatusEffect(StatusEffects.SPEED)) {
                mob.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 600, 1, false, false));
            }
            if (!mob.hasStatusEffect(StatusEffects.STRENGTH)) {
                mob.addStatusEffect(new StatusEffectInstance(StatusEffects.STRENGTH, 600, 1, false, false));
            }
            if (!mob.hasStatusEffect(StatusEffects.GLOWING)) {
                mob.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, 600, 0, false, false));
            }
        }
    }

    private void spawnBloodMoonBoss(MinecraftServer server, ServerWorld world) {
        for (ServerPlayerEntity player : world.getPlayers()) {
            int offsetX = random.nextInt(20) - 10;
            int offsetZ = random.nextInt(20) - 10;
            if (offsetX == 0) offsetX = 5;
            if (offsetZ == 0) offsetZ = 5;

            BlockPos spawnPos = player.getBlockPos().add(offsetX, 0, offsetZ);
            spawnPos = world.getTopPosition(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, spawnPos);

            ZombieEntity boss = EntityType.ZOMBIE.create(world);
            if (boss != null) {
                boss.refreshPositionAndAngles(spawnPos, 0, 0);
                boss.setCustomName(Text.literal("Blood Moon Champion").formatted(Formatting.DARK_RED, Formatting.BOLD));
                boss.setCustomNameVisible(true);

                boss.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH).setBaseValue(100.0);
                boss.setHealth(100.0f);
                boss.getAttributeInstance(EntityAttributes.GENERIC_ATTACK_DAMAGE).setBaseValue(10.0);
                boss.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED).setBaseValue(0.35);
                boss.getAttributeInstance(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE).setBaseValue(1.0);

                boss.addStatusEffect(new StatusEffectInstance(StatusEffects.FIRE_RESISTANCE, 999999, 0, false, false));
                boss.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, 999999, 0, false, true));

                world.spawnEntity(boss);

                broadcastMessage(server, "The Blood Moon Champion has arrived!", Formatting.DARK_RED);

                LOGGER.info("Blood Moon Boss spawned at {}", spawnPos);
                break;
            }
        }
    }

    private void giveRewards(MinecraftServer server) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            player.getInventory().insertStack(new ItemStack(BLOOD_MOON_TROPHY, 1));

            int lootRoll = random.nextInt(100);
            if (lootRoll < 30) {
                player.getInventory().insertStack(new ItemStack(net.minecraft.item.Items.DIAMOND, random.nextInt(3) + 1));
            } else if (lootRoll < 60) {
                player.getInventory().insertStack(new ItemStack(net.minecraft.item.Items.EMERALD, random.nextInt(5) + 2));
            } else if (lootRoll < 80) {
                player.getInventory().insertStack(new ItemStack(net.minecraft.item.Items.GOLDEN_APPLE, 1));
            } else {
                player.getInventory().insertStack(new ItemStack(net.minecraft.item.Items.ENCHANTED_GOLDEN_APPLE, 1));
            }

            player.sendMessage(Text.literal("You received a Blood Moon Trophy and rare loot for surviving!").formatted(Formatting.GOLD), false);
        }
    }

    private void broadcastMessage(MinecraftServer server, String message, Formatting color) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            player.sendMessage(Text.literal(message).formatted(color), false);
        }
    }

    public static boolean isBloodMoonActive() {
        return isBloodMoonActive;
    }
}
