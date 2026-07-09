// get out little bro
    //
package com.pvpbot.stabshot.logic;

import com.pvpbot.stabshot.config.StabConfig;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.ArrayList;
import java.util.List;

public class StabLogic {

    private static final int   WEMMBU_STOP_ABOVE_BOTTOM = 6;
    private static final float UNBREAKABLE_RESISTANCE   = 1_000.0f;

    // -------------------------------------------------------------------------
    // Tick queues
    // -------------------------------------------------------------------------

    private record PendingStrike(ServerWorld world, int x, int y, int z, long fireAtTick) {}
    private record PendingParticles(ServerWorld world, int cx, int topY, int bottomY,
                                    int cz, int radius, int phase, long fireAtTick) {}

    private static final List<PendingStrike>    PENDING         = new ArrayList<>();
    private static final List<PendingParticles> PARTICLE_PHASES = new ArrayList<>();

    public static void onServerTick(net.minecraft.server.MinecraftServer server) {
        if (PENDING.isEmpty() && PARTICLE_PHASES.isEmpty()) return;
        long now = server.getTicks();

        PENDING.removeIf(s -> {
            if (now >= s.fireAtTick()) {
                executeStrike(s.world(), s.x(), s.y(), s.z());
                return true;
            }
            return false;
        });

        PARTICLE_PHASES.removeIf(pp -> {
            if (now >= pp.fireAtTick()) {
                spawnColumnPhase(pp.world(), pp.cx(), pp.topY(), pp.bottomY(),
                        pp.cz(), pp.radius(), pp.phase());
                return true;
            }
            return false;
        });
    }

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------

    public static void summonStab(ServerWorld world, int x, int y, int z) {
        int delay = Math.max(0, StabConfig.fireDelayTicks);
        if (delay <= 0) {
            executeStrike(world, x, y, z);
        } else {
            PENDING.add(new PendingStrike(world, x, y, z,
                    world.getServer().getTicks() + delay));
        }
    }

    private static void executeStrike(ServerWorld world, int x, int y, int z) {
        if (StabConfig.isWemmbuMode()) summonWemmbu(world, x, z);
        else                           summonLegacy(world, x, y, z);
    }

    // -------------------------------------------------------------------------
    // WEMMBU mode
    // -------------------------------------------------------------------------

    private static void summonWemmbu(ServerWorld world, int cx, int cz) {
        int radius  = Math.max(0, StabConfig.wemmbuRadius);
        int bottomY = world.getBottomY() + WEMMBU_STOP_ABOVE_BOTTOM;
        int topY    = Math.max(findHighestSurfaceInFootprint(world, cx, cz, radius), bottomY);

        playSounds(world, cx, topY, cz);

        // Single particle phase only — fires with the strike, no follow-up bursts
        spawnColumnPhase(world, cx, topY, bottomY, cz, radius, 0);

        if (StabConfig.destroyTerrain) carveShaft(world, cx, cz, radius, topY, bottomY);
        damageEntities(world, cx, bottomY, topY, cz, radius, 1.0f);
    }

    // -------------------------------------------------------------------------
    // LEGACY mode
    // -------------------------------------------------------------------------

    private static void summonLegacy(ServerWorld world, int x, int y, int z) {
        int radius  = Math.max(0, StabConfig.strikeRadius);
        int strikeY = y + StabConfig.columnStartAbove;
        int bottomY = y - Math.max(1, StabConfig.blastDepth);

        playSounds(world, x, strikeY, z);

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int colX = x + dx, colZ = z + dz;
                int surfaceY = findColumnSurface(world, colX, y, colZ);
                if (StabConfig.destroyTerrain) carveLegacyColumn(world, colX, surfaceY, colZ);
            }
        }

        // Single particle phase only
        spawnColumnPhase(world, x, strikeY, bottomY, z, radius, 0);
        damageEntities(world, x, bottomY, strikeY + 2, z, radius, 1.0f);
    }

    // -------------------------------------------------------------------------
    // Particles — single phase, fires with the strike
    // -------------------------------------------------------------------------

    private static void spawnColumnPhase(ServerWorld world,
                                          int cx, int topY, int bottomY,
                                          int cz, int radius, int phase) {
        if (topY < bottomY) return;

        double xzSpread = Math.max(0.4, radius * 0.5);

        int yStep;
        switch (phase) {
            case 0  -> yStep = 3;
            case 1  -> yStep = 5;
            default -> yStep = 8;
        }

        List<ServerPlayerEntity> players = world.getPlayers();
        for (int y = topY; y >= bottomY; y -= yStep) {
            for (ServerPlayerEntity player : players) {
                world.spawnParticles(player, ParticleTypes.EXPLOSION_EMITTER, true,
                        cx + 0.5, y + 0.5, cz + 0.5,
                        1, xzSpread, 0.3, xzSpread, 0.0);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Terrain carving
    // -------------------------------------------------------------------------

    private static void carveShaft(ServerWorld world, int cx, int cz,
                                    int radius, int topY, int bottomY) {
        for (int y = topY; y >= bottomY; y--) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int cheb = Math.max(Math.abs(dx), Math.abs(dz));
                    if (cheb == radius
                            && stableChance(cx + dx, y, cz + dz, StabConfig.ledgeBlockChance))
                        continue;
                    breakIfPossible(world, cx + dx, y, cz + dz);
                }
            }
        }
    }

    private static void breakIfPossible(ServerWorld world, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        BlockState state = world.getBlockState(pos);
        if (canAffect(state)) world.breakBlock(pos, false);
    }

    private static void carveLegacyColumn(ServerWorld world, int x, int surfaceY, int z) {
        int impactY  = surfaceY + StabConfig.columnStartAbove;
        int maxDepth = Math.max(1, StabConfig.blastDepth);
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int y = impactY; y >= surfaceY - maxDepth; y--) {
            pos.set(x, y, z);
            BlockState state = world.getBlockState(pos);
            if (!canAffect(state)) continue;
            double progress = (impactY - y) / (double)(maxDepth + StabConfig.columnStartAbove + 1);
            if (state.getBlock().getBlastResistance() <= 60.0 * (1.0 - progress * 0.6))
                world.breakBlock(pos, false);
        }
    }

    // -------------------------------------------------------------------------
    // Entity damage — shield blocking, armor/shield durability, living only
    // -------------------------------------------------------------------------

    private static void damageEntities(ServerWorld world, int cx, int minY, int maxY,
                                        int cz, int radius, float mult) {
        double reach = radius + 0.75;
        Box box = new Box(cx + 0.5 - reach, minY, cz + 0.5 - reach,
                          cx + 0.5 + reach, maxY + 1.0, cz + 0.5 + reach);

        for (Entity entity : world.getOtherEntities(null, box, Entity::isAlive)) {
            if (!(entity instanceof LivingEntity living)) continue;

            double dist = Math.max(Math.abs(living.getX() - (cx + 0.5)),
                                   Math.abs(living.getZ() - (cz + 0.5)));
            if (dist > reach) continue;

            float baseDmg = (float)(StabConfig.explosionPower * 2.0 * mult
                    * (1.0 - (dist / (reach + 1.0)) * 0.55));

            if (living.isBlocking()) {
                // Shield block — launch upward, break shield, no HP damage.
                // addVelocity delta (not setVelocity) because addVelocity internally
                // triggers the velocity sync packet to the player client.
                double curX = living.getVelocity().x;
                double curY = living.getVelocity().y;
                double curZ = living.getVelocity().z;
                living.addVelocity(curX * 0.2 - curX, 1.3 - curY, curZ * 0.2 - curZ);

                Hand activeHand = living.getActiveHand();
                if (activeHand != null) {
                    ItemStack shield = living.getActiveItem();
                    if (!shield.isEmpty()) {
                        EquipmentSlot shieldSlot = activeHand == Hand.MAIN_HAND
                                ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND;
                        shield.damage(shield.getMaxDamage() + 1, living, shieldSlot);
                    }
                }
                continue;
            }

            if (baseDmg <= 0) continue;

            living.damage(world.getDamageSources().explosion(null, null), baseDmg);

            // takeKnockback(strength, dx, dz) — dx/dz = direction FROM explosion TO entity
            // = pushes entity away from shaft center. Scales with proximity.
            double kbStrength = 0.55 * (1.0 - dist / (reach + 1.0));
            living.takeKnockback(
                    kbStrength,
                    living.getX() - (cx + 0.5),
                    living.getZ() - (cz + 0.5));
            for (EquipmentSlot slot : new EquipmentSlot[]{
                    EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                    EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
                ItemStack armor = living.getEquippedStack(slot);
                if (!armor.isEmpty()) {
                    double r = living.getRandom().nextDouble();
                    int armorDmg = 53 + (int)((90 - 53) * Math.pow(r, 1.5));
                    armor.damage(armorDmg, living, slot);
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Sounds
    // -------------------------------------------------------------------------

    private static void playSounds(ServerWorld world, int x, int y, int z) {
        playCustomSound(world, x, y + 15, z, "stabshot:explosion2", 6.0f, 0.75f);
        playCustomSound(world, x, y,      z, "stabshot:explosion1", 6.0f, 0.55f);
        float[] pitches = { 0.50f, 0.60f, 0.68f, 0.76f, 0.85f, 0.95f };
        for (float p : pitches)
            world.playSound(null, x + 0.5, y, z + 0.5,
                    SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.MASTER, 6.0f, p);
    }

    private static void playCustomSound(ServerWorld world, int x, int y, int z,
                                         String id, float vol, float pitch) {
        try {
            SoundEvent ev = Registries.SOUND_EVENT.get(Identifier.of(id));
            if (ev != null)
                world.playSound(null, x + 0.5, y, z + 0.5, ev, SoundCategory.MASTER, vol, pitch);
        } catch (Exception ignored) {}
    }

    // -------------------------------------------------------------------------
    // Surface finding
    // -------------------------------------------------------------------------

    private static int findHighestSurfaceInFootprint(ServerWorld world,
                                                      int cx, int cz, int radius) {
        int highest = world.getBottomY();
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int y = world.getTopY() - 1; y >= world.getBottomY(); y--) {
                    pos.set(cx + dx, y, cz + dz);
                    if (!world.getBlockState(pos).isAir()) {
                        highest = Math.max(highest, y);
                        break;
                    }
                }
            }
        }
        return highest;
    }

    private static int findColumnSurface(ServerWorld world, int x, int targetY, int z) {
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int y = targetY + 8; y >= targetY - 16; y--) {
            pos.set(x, y, z);
            if (!world.getBlockState(pos).isAir()) return y;
        }
        return targetY;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static boolean stableChance(int x, int y, int z, double chance) {
        if (chance <= 0) return false;
        if (chance >= 1) return true;
        long seed = x * 3129871L ^ z * 116129781L ^ y * 42317861L;
        seed = seed * seed * 42317861L + seed * 11L;
        return (((seed >> 16) & 1023L) / 1023.0) < chance;
    }

    private static boolean canAffect(BlockState state) {
        return !state.isAir()
                && state.getBlock().getBlastResistance() < UNBREAKABLE_RESISTANCE;
    }
                 }
