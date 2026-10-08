package com.aleksalfi.curvegen;

import com.aleksalfi.curvegen.compat.CreateCompat;
import com.aleksalfi.curvegen.item.CurvePlannerItem;
import com.aleksalfi.curvegen.network.Networking;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.living.LivingEquipmentChangeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.slf4j.Logger;

@Mod(CurveGen.MOD_ID)
public class CurveGen {
    public static final String MOD_ID = "curvegen";
    public static final Logger LOGGER = LogUtils.getLogger();

    public CurveGen(IEventBus modBus, ModContainer container) {
        ModRegistry.register(modBus);
        modBus.addListener(Networking::register);
        modBus.addListener(CurveGen::addCreative);
        NeoForge.EVENT_BUS.addListener(CurveGen::onLeftClickBlock);
        NeoForge.EVENT_BUS.addListener(CurveGen::onEquipmentChange);
        NeoForge.EVENT_BUS.addListener(CurveGenCommands::register);
        NeoForge.EVENT_BUS.addListener(RoadCommands::register);
        NeoForge.EVENT_BUS.addListener(CurveGen::onPlayerTick);
        NeoForge.EVENT_BUS.addListener(com.aleksalfi.curvegen.build.WorldPlacer::onEntityJoin);
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStoppingEvent e) -> com.aleksalfi.curvegen.build.WorldPlacer.clearAll());
    }

    private static void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(ModRegistry.CURVE_PLANNER);
            event.accept(ModRegistry.ROAD_PLANNER);
        }
    }

    /** Left-clicking with the planner undoes the last point instead of breaking the block. */
    private static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (event.getEntity().getMainHandItem().getItem() instanceof com.aleksalfi.curvegen.item.RoadPlannerItem) {
            event.setCanceled(true); // the client handles road planner left-clicks itself
            return;
        }
        if (!(event.getEntity().getMainHandItem().getItem() instanceof CurvePlannerItem)) return;
        if (event.getAction() != PlayerInteractEvent.LeftClickBlock.Action.START
                && event.getAction() != PlayerInteractEvent.LeftClickBlock.Action.ABORT
                && event.getAction() != PlayerInteractEvent.LeftClickBlock.Action.STOP) {
            event.setCanceled(true);
            return;
        }
        event.setCanceled(true);
        if (event.getAction() == PlayerInteractEvent.LeftClickBlock.Action.START && event.getLevel().isClientSide()) {
            com.aleksalfi.curvegen.client.ClientHooks.sendUndoPoint();
        }
    }

    private static void onPlayerTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player && player.tickCount % 10 == 0) CreateCompat.onPlayerTick(player);
    }

    private static void onEquipmentChange(LivingEquipmentChangeEvent event) {
        if (event.getSlot() != EquipmentSlot.MAINHAND && event.getSlot() != EquipmentSlot.OFFHAND) return;
        if (event.getEntity() instanceof ServerPlayer player) {
            CreateCompat.onHandItemChanged(player, event.getTo());
            if (event.getTo().getItem() instanceof com.aleksalfi.curvegen.item.RoadPlannerItem) com.aleksalfi.curvegen.road.RoadService.sync(player);
        }
    }
}
