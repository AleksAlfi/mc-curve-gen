package com.aleksalfi.curvegen.client;

import com.aleksalfi.curvegen.CurveGen;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

@Mod(value = CurveGen.MOD_ID, dist = Dist.CLIENT)
public class CurveGenClient {
    public static final KeyMapping OPEN_PLANNER = new KeyMapping("key.curvegen.open_planner", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_N, "key.categories.curvegen");
    public static final KeyMapping TOGGLE_PREVIEW = new KeyMapping("key.curvegen.toggle_preview", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, "key.categories.curvegen");

    public CurveGenClient(IEventBus modBus, ModContainer container) {
        modBus.addListener(CurveGenClient::registerKeys);
        container.registerConfig(net.neoforged.fml.config.ModConfig.Type.CLIENT, ClientConfig.SPEC);
        if (net.neoforged.fml.ModList.get().isLoaded("create")) {
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(com.aleksalfi.curvegen.compat.CreateTablePanel::onInit);
        }
    }

    private static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(OPEN_PLANNER);
        event.register(TOGGLE_PREVIEW);
    }
}
