# Curve Gen (NeoForge 1.21.1)

Point-and-click generator for curved, multi-lane roads (or any curved strip of blocks).
Draw a path from straights, arcs, Bézier curves and S-bends, give it a lane profile, preview
it in the world, then either export a **Create schematic** (survival) or place it directly
(creative / operators).

Smooth edges and ramps use **Create: Copycats+** *Copycat Layers* (1/8-block steps) so curves
look anti-aliased instead of blocky, and ramps are walkable. Any block from any loaded mod can be
used for lanes and as copycat material.

## Requirements

| Mod | Version | Needed for |
|-----|---------|------------|
| NeoForge | 21.1.219+ | – |
| Create | 6.0.8+ | schematic export / auto-deploy, copycat materials |
| Create: Copycats+ | 3.0.x | smooth edges and ramps (optional, features turn off without it) |

## How to use

1. Craft the **Curve Planner**: paper and a compass in the top row, a stick and paper in the middle
   row, a stick bottom-left (the recipe shows up in JEI and in the recipe book once you have paper or a
   compass). Creative players find it in the Tools tab.
2. **Right-click** a block to add a point. The road's top block goes where a block would be placed
   on the face you clicked. Right-clicking into the distance works too (up to 256 blocks).
3. **Left-click** undoes the last point. **Sneak + right-click** (or `N`) opens the options screen.
4. The in-world preview shows every block as a translucent box (copycat layers are drawn with their
   real shape), the yellow line is the centre line and the small boxes are your points.
5. In the options screen:
   * **Export schematic** writes `schematics/<name>.nbt` in your game folder (where Create's
     Schematic Table looks). Write it onto an empty schematic at a Schematic Table; as soon as you
     hold the written schematic it is **deployed at the correct world position automatically**, so you
     can put it straight into a Schematicannon. (`Deploy held schematic` does the same manually.)
   * **Place** places everything directly (creative or permission level 2). **Undo place** restores what
     was there before; containers that were paved over come back with their contents, and the materials of
     the road's own copycat layers go to a survival player's inventory (discarded in creative).

### Segments

Every segment starts where the previous one ended, so a whole road is one continuous path.

| Type | Clicks after the end point | Options |
|------|----------------------------|---------|
| Straight | – | – |
| Arc – Tangent | – when it continues a previous segment, a cardinal heading is set, or *Align start* is on; otherwise one point on the arc | heading for the first segment |
| Arc – Through point | one point on the arc | – |
| Arc – Fixed radius | – | radius, turn left/right |
| Bezier | 1 (quadratic) or 2 (cubic) control points; with *smooth join* the first control is automatic | kind |
| S-bend | – | smooth (one Bézier) or two mirrored arcs; heading |
| Spline | as many points as you like; press **Finish spline** (or `/curvegen finish`) when done | – |

*Heading* is the direction of travel used when no previous segment exists (Auto snaps the
start→end direction to the nearest cardinal axis).

**Align start / end to axis** snaps the direction of travel at that end of the segment to the nearest of
north, east, south or west, so a straight you build by hand lines up with it. The axis is picked
automatically from the curve's natural direction. For arcs the result stays a true circular arc: the
arc turns onto the axis and a straight run completes the segment. The spline runs smoothly through
every point (tangent-continuous), with heights interpolated point to point.

### Profile

* **Lanes** run left → right across the road in the direction you draw it. Each lane has a width
  (fractions allowed, e.g. `0.5`), a block (any block state, e.g. `minecraft:oak_stairs[facing=north]`)
  and optionally a different copycat **material** for its smoothing layers.
* **Thickness** and **base block** fill blocks below the surface.
* **Smooth edges**: partially covered edge columns become sideways copycat layers.
* **Smooth ramps**: fractional surface heights become upward copycat layers (ramps rise in 1/8 steps).
* **Elevation**: flat, linear ramp or smooth ramp between the heights of your points. **Y offset**
  shifts the whole road.
* **Quality** controls the supersampling used to measure coverage.

Blocks that Create would refuse as copycat material (stairs, block entities, non-full blocks) fall
back to full blocks for that lane; a warning shows on the HUD and in the status line of the screen.

Limits: 256 segments, 4096 points in total, 32 lanes up to 64 wide (256 in total), thickness 32,
paths up to 100 000 blocks long, 1 000 000 blocks per plan (direct placement stops at 250 000).

### Commands

All commands act on the Curve Planner you are holding, exactly like clicking with it:

```
/curvegen point <x> <y> <z>   add a point whose road surface block is that block
/curvegen undo                remove the last point
/curvegen removesegment       remove the last segment
/curvegen clear               clear the path
/curvegen place               place the blocks directly (creative / op)
/curvegen undoplace           restore what the last placement replaced
/curvegen finish              finish an open spline
/curvegen deploy              deploy the Create schematic you are holding at its stored position

/curvegen type <straight|arc|bezier|s_bend|spline>
/curvegen arcmode <tangent|through_point|radius>   /curvegen radius <r>   /curvegen turnleft <bool>
/curvegen bezier <quadratic|cubic>                 /curvegen sbend <smooth|two_arcs>
/curvegen heading <auto|previous|north|east|south|west>
/curvegen smoothjoin <bool>    /curvegen align start <bool>    /curvegen align end <bool>
```

The option commands change the same settings as the options screen, so a whole road can be scripted.

### Survival workflow with Create

1. Draw the road, check the preview, press **Export schematic**.
2. Put an empty schematic in a Schematic Table and write `<name>.nbt` onto it (Create uploads the file
   to the server as usual).
3. The moment the written schematic is in your inventory it is deployed at the road's position (chat
   confirms the anchor). Put it in a Schematicannon and supply the blocks: the cannon needs the lane
   blocks, Copycat Layers and the layer materials (Create's item requirements handle this).

You can still move or rotate it with Create's own tools before printing; the mod deploys each
schematic only once.

## Road Planner (networks of roads)

The **Road Planner** (paper + compass, stick + paper, black concrete bottom-left) builds whole road
networks from nodes instead of single curves. Networks are saved in the world, by name, and belong to
the player who created them.

1. Sneak + right-click (or `N`) opens the network screen; create a network or pick one shared with you.
   Right-clicking without a network opens it too.
2. **Right-click** does one thing depending on what the crosshair points at (shown on the HUD and in the world):
   * ground: place a node there. With a node selected the new node is joined to it and becomes the
     selected one, so you draw a road like a polyline. **Axis snap** (network screen, on by default) puts
     the node exactly north/south or east/west of the one it joins when it is within a block of that line.
   * a node: select it; with another node selected, connect the two and continue from the clicked one;
     clicking the selected node again finishes the chain. **Hold** right-click on a node to pick it up:
     drag it with the crosshair and release to drop it (the ghost shows the roads following it).
   * a road: insert a node into it (joined to the selected node, if any), e.g. to start a side road.
     **Hold** on a road to insert the node and drag it straight away, which is the quickest way to bend a road.
   The ghost line shows the centre line the road would get, green for placing, orange for connecting,
   cyan while moving a node, red when you may not edit the network.
3. **Left-click** a node or a road to open its settings; left-click the ground to finish the chain.
   **Sneak + left-click** and hold a quarter second (a square fills around the crosshair) deletes the node or
   road you aim at; sneak + left-click on the ground **undoes** the last change. The last 50 versions of every
   network are kept in the world save, so undo survives restarts.
   * **Kind**: auto (plain pass-through, dead end, or junction when 3+ links meet) or **roundabout**
     (island radius, 1 or 2 ring lanes, give-way lines at every entry).
   * **Corner** for pass-through nodes: circular **fillet** with a radius, or **smooth** (spline-like).
   * **Zebra crossing** at a plain node, or per junction arm (between the stop line and the junction).
   * Per arm (node screen) or per end (road screen): **priority** (priority road: no line; give way: dashed
     line; stop: solid line across the entry lanes) and a zebra; the road's **class** from either screen.
   * **Move…** in the node screen does the same without holding: the next right-click drops the node (left-click cancels).
4. **Direction.** A road is two-way by default. The road screen (left-click a road) or `/roadgen linkdir <road>
   <twoway|forward|reverse>` makes it one-way; forward runs from the road's first node to its second. A one-way
   road has all its lanes in one direction with dashed lines between them and no centre line; the preview draws
   chevrons along it, and a class can paint direction arrows in the lanes (*Arrows*, off by default).
   * **Split**: where a two-way road meets two one-way roads, one leaving and one arriving, both within 60° of
     its direction, the lanes run straight into their roads around a hatched nose. A node kind can force
     (*Split*) or prevent (*Junction*) it.
   * **Ramp merge**: a road joining a road with 2+ lanes per direction at under 35° (or any one-way road there)
     becomes an entry or exit ramp: the ramp meets the carriageway at a nose beside the through lanes, with an
     acceleration lane after an entry or a deceleration lane before an exit (*Merge lane* length per class,
     default 60), long-dash lane line, hatched gore. An entry followed by an exit within twice that length
     share one weaving lane. Entry or exit follows the ramp's direction, or its angle for a two-way ramp; the
     node kind can force either. Right-hand traffic: the ramp must lie to the right of the carriageway it serves.
   * **Class change** at a node: the wider road tapers into the narrower one over 10 blocks per block of width
     difference (at least 20), entirely on the wider road's side; curbs and sidewalks switch at the node.
   * **Per road** (road screen, or `/roadgen linkset <road> sidewalk|edgelines|shoulder <value|class>`): the
     sidewalk width, edge lines and shoulder can override the class for one road, e.g. a street without
     pavement where it serves as a ramp. Roads of one class with different overrides taper into each other.
5. **Road classes** (per network, editable, four by default: Street, Main road, Highway and Ramp, the last a
   single 8-wide lane with edge lines and a 1-block shoulder and no pavement): lane width (6 / 7 / 8 blocks, sized for
   Create Aeronautics vehicles), lanes per direction, sidewalk width, curb height (copycat layers),
   solid edge lines, smooth edges (sideways copycat layers on the outer edge, off by default; ramps and
   curbs use upward layers regardless), hard shoulder width (asphalt outside the edge line, 2 on the highway
   class), merge lane length, direction arrows, and the blocks for asphalt, lines, curb and sidewalk. New links use the class marked
   ★; change a link's class from either end node.
6. Heights: a road follows a smooth vertical curve between its nodes, so grades change gradually. A junction or
   roundabout sits on a plane that follows the through road's grade up to a 10 % tilt; side roads are level
   through the box and banked at most 10 % to meet it. Grades along a road (ramps) are not limited.
7. Export, Place, Undo and Deploy work exactly like the curve planner; the preview shows the whole network.

Markings follow simplified EU practice for right-hand traffic: dashed centre line that becomes solid
in tight bends and on the approach to junctions and roundabouts, dashed lane lines between lanes of the
same direction (solid centre on multi-lane roads), solid edge lines, stop / give-way lines across the
entry lanes, zebra stripes 1 block wide, rounded curb corners at every junction.

**Sharing.** The creator owns the network. From the network screen the owner can share it with a player
(*view* or *edit*), remove a share, or open it to everyone (*Everyone: view / edit*). Operators can see
and edit every network. Commands: `/roadgen share <player> <view|edit|none>`, `/roadgen public <none|view|edit>`.

`/roadgen` mirrors the whole tool: `create`, `select`, `list`, `info`, `delete`, `undo`, `node add <pos>`,
`node move <id> <pos>`, `node insert <link> <pos>`, `node select|delete|kind|corner|radius|roundabout|zebra`,
`arm <link> priority|zebra`, `link <a> <b>`, `linkclass <link> <class>`, `linkdir <link> <twoway|forward|reverse>`,
`class add|remove|default|set …` (incl. `shoulder`, `mergelength`, `arrows`, `smoothedges`), `place`, `undoplace`, `deploy`.
Node positions given to commands are not range-limited; clicks must be within 320 blocks.

## Building

```
./gradlew build              # jar in build/libs (also runs the unit tests)
./gradlew runClient          # dev client; downloads Create + Copycats+ into run/mods first
./gradlew runGameTestServer  # in-game tests with Create and Copycats+ loaded
```

The build uses Java 21 (set `JAVA_HOME` or let the Gradle toolchain download it).
`./gradlew downloadRuntimeMods` fetches the Create and Copycats+ jars used by the dev runs.

## How it works

`geom` is a Minecraft-free geometry core: curves (`Arc2`, `CubicBezier2`, `Line2`) are sampled into
a polyline, and `Rasterizer` supersamples every column of the bounding box, measuring how much of it
the road covers, which lane it belongs to and the surface height. `BlockAssembler` turns that into
block states: full blocks where coverage is high, sideways copycat layers on partially covered edges
(`facing` = the exposed face, pointing away from the road), upward layers for fractional heights.
`SchematicWriter` writes the vanilla structure format Create reads and stores the world anchor in a
`curvegen` tag; `CreateSchematicHooks` reads that anchor back from the uploaded copy and sets Create's
schematic item components so the schematic is deployed in place.
