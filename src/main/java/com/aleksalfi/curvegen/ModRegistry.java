package com.aleksalfi.curvegen;

import com.aleksalfi.curvegen.item.CurvePlannerItem;
import com.aleksalfi.curvegen.plan.CurvePlan;
import com.aleksalfi.curvegen.plan.PlanCodecs;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public final class ModRegistry {
    private ModRegistry() {}

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(CurveGen.MOD_ID);
    public static final DeferredRegister.DataComponents COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, CurveGen.MOD_ID);

    public static final Supplier<DataComponentType<CurvePlan>> PLAN = COMPONENTS.registerComponentType("plan",
            b -> b.persistent(PlanCodecs.PLAN).networkSynchronized(PlanCodecs.PLAN_STREAM));

    /** Marker put on Create schematic items this mod has auto-deployed, so each one is deployed exactly once. */
    public static final Supplier<DataComponentType<Boolean>> AUTO_DEPLOYED = COMPONENTS.registerComponentType("auto_deployed",
            b -> b.persistent(com.mojang.serialization.Codec.BOOL).networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.BOOL));

    public static final DeferredItem<Item> CURVE_PLANNER = ITEMS.registerItem("curve_planner",
            CurvePlannerItem::new, new Item.Properties().stacksTo(1));

    public static void register(IEventBus bus) {
        COMPONENTS.register(bus);
        ITEMS.register(bus);
    }
}
