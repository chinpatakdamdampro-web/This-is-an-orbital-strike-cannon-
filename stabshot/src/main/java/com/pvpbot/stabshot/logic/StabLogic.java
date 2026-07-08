// 
// Decompiled by Procyon v0.6.0
// 

package com.pvpbot.stabshot.logic;

import java.util.ArrayList;
import net.minecraft.class_2960;
import net.minecraft.class_7923;
import net.minecraft.class_3414;
import net.minecraft.class_6880;
import net.minecraft.class_1657;
import net.minecraft.class_3419;
import net.minecraft.class_3417;
import net.minecraft.class_1799;
import net.minecraft.class_1304;
import net.minecraft.class_1268;
import net.minecraft.class_1309;
import net.minecraft.class_1297;
import net.minecraft.class_238;
import net.minecraft.class_2680;
import net.minecraft.class_2338;
import java.util.Iterator;
import net.minecraft.class_2394;
import net.minecraft.class_2398;
import net.minecraft.class_3222;
import com.pvpbot.stabshot.config.StabConfig;
import net.minecraft.class_3218;
import net.minecraft.server.MinecraftServer;
import java.util.List;

public class StabLogic
{
    private static final int WEMMBU_STOP_ABOVE_BOTTOM = 6;
    private static final float UNBREAKABLE_RESISTANCE = 1000.0f;
    private static final List<PendingStrike> PENDING;
    private static final List<PendingParticles> PARTICLE_PHASES;
    
    public static void onServerTick(final MinecraftServer server) {
        if (StabLogic.PENDING.isEmpty() && StabLogic.PARTICLE_PHASES.isEmpty()) {
            return;
        }
        final long now = server.method_3780();
        StabLogic.PENDING.removeIf(s -> {
            if (now >= s.fireAtTick()) {
                executeStrike(s.world(), s.x(), s.y(), s.z());
                return true;
            }
            else {
                return false;
            }
        });
        StabLogic.PARTICLE_PHASES.removeIf(pp -> {
            if (now >= pp.fireAtTick()) {
                spawnColumnPhase(pp.world(), pp.cx(), pp.topY(), pp.bottomY(), pp.cz(), pp.radius(), pp.phase());
                return true;
            }
            else {
                return false;
            }
        });
    }
    
    public static void summonStab(final class_3218 world, final int x, final int y, final int z) {
        final int delay = Math.max(0, StabConfig.fireDelayTicks);
        if (delay <= 0) {
            executeStrike(world, x, y, z);
        }
        else {
            StabLogic.PENDING.add(new PendingStrike(world, x, y, z, world.method_8503().method_3780() + delay));
        }
    }
    
    private static void executeStrike(final class_3218 world, final int x, final int y, final int z) {
        if (StabConfig.isWemmbuMode()) {
            summonWemmbu(world, x, z);
        }
        else {
            summonLegacy(world, x, y, z);
        }
    }
    
    private static void summonWemmbu(final class_3218 world, final int cx, final int cz) {
        final int radius = Math.max(0, (int)StabConfig.wemmbuRadius);
        final int bottomY = world.method_31607() + 6;
        final int topY = Math.max(findHighestSurfaceInFootprint(world, cx, cz, radius), bottomY);
        playSounds(world, cx, topY, cz);
        spawnColumnPhase(world, cx, topY, bottomY, cz, radius, 0);
        if (StabConfig.destroyTerrain) {
            carveShaft(world, cx, cz, radius, topY, bottomY);
        }
        damageEntities(world, cx, bottomY, topY, cz, radius, 1.0f);
    }
    
    private static void summonLegacy(final class_3218 world, final int x, final int y, final int z) {
        final int radius = Math.max(0, (int)StabConfig.strikeRadius);
        final int strikeY = y + StabConfig.columnStartAbove;
        final int bottomY = y - Math.max(1, StabConfig.blastDepth);
        playSounds(world, x, strikeY, z);
        for (int dx = -radius; dx <= radius; ++dx) {
            for (int dz = -radius; dz <= radius; ++dz) {
                final int colX = x + dx;
                final int colZ = z + dz;
                final int surfaceY = findColumnSurface(world, colX, y, colZ);
                if (StabConfig.destroyTerrain) {
                    carveLegacyColumn(world, colX, surfaceY, colZ);
                }
            }
        }
        spawnColumnPhase(world, x, strikeY, bottomY, z, radius, 0);
        damageEntities(world, x, bottomY, strikeY + 2, z, radius, 1.0f);
    }
    
    private static void spawnColumnPhase(final class_3218 world, final int cx, final int topY, final int bottomY, final int cz, final int radius, final int phase) {
        if (topY < bottomY) {
            return;
        }
        final double xzSpread = Math.max(0.4, radius * 0.5);
        int yStep = 0;
        switch (phase) {
            case 0: {
                yStep = 3;
                break;
            }
            case 1: {
                yStep = 5;
                break;
            }
            default: {
                yStep = 8;
                break;
            }
        }
        final List<class_3222> players = world.method_18456();
        for (int y = topY; y >= bottomY; y -= yStep) {
            for (final class_3222 player : players) {
                world.method_14166(player, (class_2394)class_2398.field_11221, true, cx + 0.5, y + 0.5, cz + 0.5, 1, xzSpread, 0.3, xzSpread, 0.0);
            }
        }
    }
    
    private static void carveShaft(final class_3218 world, final int cx, final int cz, final int radius, final int topY, final int bottomY) {
        for (int y = topY; y >= bottomY; --y) {
            for (int dx = -radius; dx <= radius; ++dx) {
                for (int dz = -radius; dz <= radius; ++dz) {
                    final int cheb = Math.max(Math.abs(dx), Math.abs(dz));
                    if (cheb != radius || !stableChance(cx + dx, y, cz + dz, StabConfig.ledgeBlockChance)) {
                        breakIfPossible(world, cx + dx, y, cz + dz);
                    }
                }
            }
        }
    }
    
    private static void breakIfPossible(final class_3218 world, final int x, final int y, final int z) {
        final class_2338 pos = new class_2338(x, y, z);
        final class_2680 state = world.method_8320(pos);
        if (canAffect(state)) {
            world.method_22352(pos, false);
        }
    }
    
    private static void carveLegacyColumn(final class_3218 world, final int x, final int surfaceY, final int z) {
        final int impactY = surfaceY + StabConfig.columnStartAbove;
        final int maxDepth = Math.max(1, StabConfig.blastDepth);
        final class_2338.class_2339 pos = new class_2338.class_2339();
        for (int y = impactY; y >= surfaceY - maxDepth; --y) {
            pos.method_10103(x, y, z);
            final class_2680 state = world.method_8320((class_2338)pos);
            if (canAffect(state)) {
                final double progress = (impactY - y) / (double)(maxDepth + StabConfig.columnStartAbove + 1);
                if (state.method_26204().method_9520() <= 60.0 * (1.0 - progress * 0.6)) {
                    world.method_22352((class_2338)pos, false);
                }
            }
        }
    }
    
    private static void damageEntities(final class_3218 world, final int cx, final int minY, final int maxY, final int cz, final int radius, final float mult) {
        final double reach = radius + 0.75;
        final class_238 box = new class_238(cx + 0.5 - reach, (double)minY, cz + 0.5 - reach, cx + 0.5 + reach, maxY + 1.0, cz + 0.5 + reach);
        for (final class_1297 entity : world.method_8333((class_1297)null, box, class_1297::method_5805)) {
            if (entity instanceof final class_1309 living) {
                final double dist = Math.max(Math.abs(living.method_23317() - (cx + 0.5)), Math.abs(living.method_23321() - (cz + 0.5)));
                if (dist > reach) {
                    continue;
                }
                final float baseDmg = (float)(StabConfig.explosionPower * 2.0 * mult * (1.0 - dist / (reach + 1.0) * 0.55));
                if (living.method_6039()) {
                    final double curX = living.method_18798().field_1352;
                    final double curY = living.method_18798().field_1351;
                    final double curZ = living.method_18798().field_1350;
                    living.method_5762(curX * 0.2 - curX, 1.3 - curY, curZ * 0.2 - curZ);
                    final class_1268 activeHand = living.method_6058();
                    if (activeHand == null) {
                        continue;
                    }
                    final class_1799 shield = living.method_6030();
                    if (shield.method_7960()) {
                        continue;
                    }
                    final class_1304 shieldSlot = (activeHand == class_1268.field_5808) ? class_1304.field_6173 : class_1304.field_6171;
                    shield.method_7970(shield.method_7936() + 1, living, shieldSlot);
                }
                else {
                    if (baseDmg <= 0.0f) {
                        continue;
                    }
                    living.method_5643(world.method_48963().method_48819((class_1297)null, (class_1297)null), baseDmg);
                    final double kbStrength = 0.55 * (1.0 - dist / (reach + 1.0));
                    living.method_6005(kbStrength, living.method_23317() - (cx + 0.5), living.method_23321() - (cz + 0.5));
                    for (final class_1304 slot : new class_1304[] { class_1304.field_6169, class_1304.field_6174, class_1304.field_6172, class_1304.field_6166 }) {
                        final class_1799 armor = living.method_6118(slot);
                        if (!armor.method_7960()) {
                            final double r = living.method_59922().method_43058();
                            final int armorDmg = 53 + (int)(37.0 * Math.pow(r, 1.5));
                            armor.method_7970(armorDmg, living, slot);
                        }
                    }
                }
            }
        }
    }
    
    private static void playSounds(final class_3218 world, final int x, final int y, final int z) {
        playCustomSound(world, x, y + 15, z, "stabshot:explosion2", 20.0f, 0.75f);
        playCustomSound(world, x, y, z, "stabshot:explosion1", 20.0f, 0.55f);
        final float[] array;
        final float[] pitches = array = new float[] { 0.5f, 0.6f, 0.68f, 0.76f, 0.85f, 0.95f };
        for (final float p : array) {
            world.method_60511((class_1657)null, x + 0.5, (double)y, z + 0.5, (class_6880)class_3417.field_15152, class_3419.field_15250, 20.0f, p);
        }
    }
    
    private static void playCustomSound(final class_3218 world, final int x, final int y, final int z, final String id, final float vol, final float pitch) {
        try {
            final class_3414 ev = (class_3414)class_7923.field_41172.method_10223(class_2960.method_60654(id));
            if (ev != null) {
                world.method_43128((class_1657)null, x + 0.5, (double)y, z + 0.5, ev, class_3419.field_15250, vol, pitch);
            }
        }
        catch (final Exception ex) {}
    }
    
    private static int findHighestSurfaceInFootprint(final class_3218 world, final int cx, final int cz, final int radius) {
        int highest = world.method_31607();
        final class_2338.class_2339 pos = new class_2338.class_2339();
        for (int dx = -radius; dx <= radius; ++dx) {
            for (int dz = -radius; dz <= radius; ++dz) {
                for (int y = world.method_31600() - 1; y >= world.method_31607(); --y) {
                    pos.method_10103(cx + dx, y, cz + dz);
                    if (!world.method_8320((class_2338)pos).method_26215()) {
                        highest = Math.max(highest, y);
                        break;
                    }
                }
            }
        }
        return highest;
    }
    
    private static int findColumnSurface(final class_3218 world, final int x, final int targetY, final int z) {
        final class_2338.class_2339 pos = new class_2338.class_2339();
        for (int y = targetY + 8; y >= targetY - 16; --y) {
            pos.method_10103(x, y, z);
            if (!world.method_8320((class_2338)pos).method_26215()) {
                return y;
            }
        }
        return targetY;
    }
    
    private static boolean stableChance(final int x, final int y, final int z, final double chance) {
        if (chance <= 0.0) {
            return false;
        }
        if (chance >= 1.0) {
            return true;
        }
        long seed = x * 3129871L ^ z * 116129781L ^ y * 42317861L;
        seed = seed * seed * 42317861L + seed * 11L;
        return (seed >> 16 & 0x3FFL) / 1023.0 < chance;
    }
    
    private static boolean canAffect(final class_2680 state) {
        return !state.method_26215() && state.method_26204().method_9520() < 1000.0f;
    }
    
    static {
        PENDING = new ArrayList<PendingStrike>();
        PARTICLE_PHASES = new ArrayList<PendingParticles>();
    }
    
    record PendingStrike(class_3218 world, int x, int y, int z, long fireAtTick) {}
    
    record PendingParticles(class_3218 world, int cx, int topY, int bottomY, int cz, int radius, int phase, long fireAtTick) {}
}
