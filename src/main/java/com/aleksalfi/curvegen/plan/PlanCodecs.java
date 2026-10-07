package com.aleksalfi.curvegen.plan;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.Locale;

/** DFU codecs for the plan model (kept separate so the model itself stays Minecraft-free). */
public final class PlanCodecs {
    private PlanCodecs() {}

    /** Lower-case name codec; an unknown name decodes to {@code fallback} (the same value a missing field gets). */
    private static <E extends Enum<E>> Codec<E> simpleEnum(E[] values, E fallback) {
        return Codec.STRING.xmap(
                s -> {
                    for (E e : values) if (e.name().equalsIgnoreCase(s)) return e;
                    return fallback;
                },
                e -> e.name().toLowerCase(Locale.ROOT));
    }

    public static final Codec<SegmentType> SEGMENT_TYPE = simpleEnum(SegmentType.values(), SegmentType.SPLINE);
    public static final Codec<ArcMode> ARC_MODE = simpleEnum(ArcMode.values(), ArcMode.TANGENT);
    public static final Codec<BezierKind> BEZIER_KIND = simpleEnum(BezierKind.values(), BezierKind.CUBIC);
    public static final Codec<SBendStyle> SBEND_STYLE = simpleEnum(SBendStyle.values(), SBendStyle.SMOOTH);
    public static final Codec<Heading> HEADING = simpleEnum(Heading.values(), Heading.AUTO);
    public static final Codec<ElevationMode> ELEVATION = simpleEnum(ElevationMode.values(), ElevationMode.LINEAR);

    public static final Codec<PlanPoint> POINT = RecordCodecBuilder.create(i -> i.group(
            Codec.DOUBLE.optionalFieldOf("x", 0.0).forGetter(PlanPoint::x),
            Codec.DOUBLE.optionalFieldOf("y", 0.0).forGetter(PlanPoint::y),
            Codec.DOUBLE.optionalFieldOf("z", 0.0).forGetter(PlanPoint::z)
    ).apply(i, PlanPoint::new));

    public static final Codec<SegmentSpec> SEGMENT = RecordCodecBuilder.create(i -> i.group(
            SEGMENT_TYPE.optionalFieldOf("type", SegmentType.SPLINE).forGetter(SegmentSpec::type),
            POINT.listOf().optionalFieldOf("points", java.util.List.of()).forGetter(SegmentSpec::points),
            ARC_MODE.optionalFieldOf("arc_mode", ArcMode.TANGENT).forGetter(SegmentSpec::arcMode),
            Codec.DOUBLE.optionalFieldOf("radius", 12.0).forGetter(SegmentSpec::radius),
            Codec.BOOL.optionalFieldOf("turn_left", true).forGetter(SegmentSpec::turnLeft),
            BEZIER_KIND.optionalFieldOf("bezier", BezierKind.CUBIC).forGetter(SegmentSpec::bezierKind),
            SBEND_STYLE.optionalFieldOf("s_bend", SBendStyle.SMOOTH).forGetter(SegmentSpec::sBendStyle),
            HEADING.optionalFieldOf("heading", Heading.AUTO).forGetter(SegmentSpec::heading),
            Codec.BOOL.optionalFieldOf("smooth_join", true).forGetter(SegmentSpec::smoothJoin),
            Codec.BOOL.optionalFieldOf("align_start", false).forGetter(SegmentSpec::alignStart),
            Codec.BOOL.optionalFieldOf("align_end", false).forGetter(SegmentSpec::alignEnd)
    ).apply(i, SegmentSpec::new));

    public static final Codec<LaneSpec> LANE = RecordCodecBuilder.create(i -> i.group(
            Codec.DOUBLE.optionalFieldOf("width", 1.0).forGetter(LaneSpec::width),
            Codec.STRING.optionalFieldOf("block", "minecraft:stone").forGetter(LaneSpec::block),
            Codec.STRING.optionalFieldOf("material", "").forGetter(LaneSpec::material)
    ).apply(i, LaneSpec::new));

    public static final Codec<ProfileSpec> PROFILE = RecordCodecBuilder.create(i -> i.group(
            LANE.listOf().optionalFieldOf("lanes", ProfileSpec.defaults().lanes()).forGetter(ProfileSpec::lanes),
            Codec.INT.optionalFieldOf("thickness", 1).forGetter(ProfileSpec::thickness),
            Codec.STRING.optionalFieldOf("base_block", "").forGetter(ProfileSpec::baseBlock),
            Codec.DOUBLE.optionalFieldOf("y_offset", 0.0).forGetter(ProfileSpec::yOffset),
            Codec.BOOL.optionalFieldOf("edge_smoothing", true).forGetter(ProfileSpec::edgeSmoothing),
            Codec.BOOL.optionalFieldOf("slope_smoothing", true).forGetter(ProfileSpec::slopeSmoothing),
            ELEVATION.optionalFieldOf("elevation", ElevationMode.LINEAR).forGetter(ProfileSpec::elevationMode),
            Codec.INT.optionalFieldOf("quality", 1).forGetter(ProfileSpec::quality)
    ).apply(i, ProfileSpec::new));

    public static final Codec<CurvePlan> PLAN = RecordCodecBuilder.create(i -> i.group(
            SEGMENT.listOf().optionalFieldOf("segments", java.util.List.of()).forGetter(CurvePlan::segments),
            SEGMENT.optionalFieldOf("draft", SegmentSpec.defaults()).forGetter(CurvePlan::draft),
            PROFILE.optionalFieldOf("profile", ProfileSpec.defaults()).forGetter(CurvePlan::profile),
            Codec.STRING.optionalFieldOf("schematic_name", "curve").forGetter(CurvePlan::schematicName)
    ).apply(i, CurvePlan::new));

    /** Network budget for a plan: {@link PlanLimits#MAX_TOTAL_POINTS} points fit comfortably in 1 MB of NBT. */
    public static final StreamCodec<ByteBuf, CurvePlan> PLAN_STREAM =
            ByteBufCodecs.fromCodec(PLAN, () -> net.minecraft.nbt.NbtAccounter.create(1_048_576L));
}
