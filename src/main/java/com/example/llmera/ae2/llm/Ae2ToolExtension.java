package com.example.llmera.ae2.llm;

import com.example.llmera.LLMEra;
import com.example.llmera.ae2.network.Ae2NetworkBridgeBlockEntity;
import com.example.llmera.config.LLMEraConfig;
import com.example.llmera.llm.ToolDefinition;
import com.example.llmera.llm.ToolExtension;
import com.example.llmera.llm.ToolInvocation;
import com.example.llmera.network.CodeNetworkScanner;
import com.example.llmera.network.IntelligentTransmitterBlockEntity;
import com.example.llmera.network.LlmToolMode;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Ae2ToolExtension {
    private static final Gson GSON = new Gson();
    private static final ToolDefinition AE2_PATTERN_LOOKUP_TOOL = new ToolDefinition(
            "ae2_pattern_lookup",
            "Query encoded patterns currently visible to an AE2 pattern access terminal. "
                    + "By default, uses the ae2_network_bridge blocks connected to this executor's code network. "
                    + "Optional x/y/z can point at an AE2 network node, cable, controller, terminal, or pattern provider "
                    + "for manual debugging. "
                    + "The tool reflects AE2's IGrid -> PatternContainer flow, so the mod has no hard dependency on AE2. "
                    + "It is read-only and returns pattern provider groups, slots, encoded pattern items, inputs, and outputs. "
                    + "If no AE2 grid can be resolved, it falls back to reading a single pattern provider at the coordinates.",
            Map.of(
                    "x", Map.of("type", "integer", "description", "optional x coordinate of an AE2 network block"),
                    "y", Map.of("type", "integer", "description", "optional y coordinate of an AE2 network block"),
                    "z", Map.of("type", "integer", "description", "optional z coordinate of an AE2 network block"),
                    "include_details", Map.of("type", "boolean",
                            "description", "optional. Decode pattern inputs and outputs. Default true."),
                    "include_hidden", Map.of("type", "boolean",
                            "description", "optional. Include pattern containers hidden from the pattern access terminal. Default false.")
            )
    );

    private static final ToolDefinition AE2_CHANNEL_DIAGNOSTICS_TOOL = new ToolDefinition(
            "ae2_channel_diagnostics",
            "Diagnose AE2 channel and network health. By default, inspects the AE2 networks next to every "
                    + "ae2_network_bridge connected to this executor's code network; optional x/y/z selects a manual "
                    + "anchor. This read-only tool reports power, booting state, channel mode, controller conflicts, "
                    + "devices missing channels, saturated channel-carrying nodes, and their world coordinates. "
                    + "Use this when devices are offline, channels appear exhausted, or an AE2 network is malfunctioning.",
            Map.of(
                    "x", Map.of("type", "integer", "description", "optional x coordinate of an AE2 network block"),
                    "y", Map.of("type", "integer", "description", "optional y coordinate of an AE2 network block"),
                    "z", Map.of("type", "integer", "description", "optional z coordinate of an AE2 network block"),
                    "include_healthy_nodes", Map.of("type", "boolean",
                            "description", "optional. Include healthy nodes as well as problem nodes. Default false."),
                    "max_nodes", Map.of("type", "integer",
                            "description", "optional maximum node records per network, 1..512. Default 128.")
            )
    );

    private static final ToolDefinition AE2_PATTERN_WRITE_TOOL = new ToolDefinition(
            "ae2_pattern_write",
            "Encode and install an AE2 encoded pattern through the ae2_network_bridge connected to this code network. "
                    + "This is a write tool for build executors only. For crafting patterns, pass recipe_id from JEI "
                    + "or pass crafting_grid; if only output_item_id is given and multiple recipes produce it, the tool "
                    + "returns candidate recipe ids instead of guessing. The tool uses the same server recipe layout JEI "
                    + "shows for normal crafting recipes, encodes the AE2 pattern reflectively, then installs it into a "
                    + "matching Pattern Access Terminal container: crafting patterns go to molecular-assembler targets, "
                    + "processing patterns go to non-molecular pattern providers. If a compatible pattern with the same "
                    + "primary output already exists, it is replaced by default; otherwise the first compatible empty slot "
                    + "is used.",
            Map.ofEntries(
                    Map.entry("pattern_type", Map.of("type", "string",
                            "description", "crafting or processing. Default crafting.")),
                    Map.entry("recipe_id", Map.of("type", "string",
                            "description", "optional JEI/server recipe id for crafting, e.g. minecraft:oak_planks")),
                    Map.entry("output_item_id", Map.of("type", "string",
                            "description", "crafting output item id, or processing primary output item id")),
                    Map.entry("crafting_grid", Map.of("type", "array",
                            "description", "optional 9-slot JEI crafting grid. Each entry is an item id, empty/null, "
                                    + "or {item_id, amount, data_value}. Used when recipe_id is missing or a JEI "
                                    + "transient layout must be preserved.")),
                    Map.entry("inputs", Map.of("type", "array",
                            "description", "processing inputs as [{item_id, amount, data_value}], only for pattern_type=processing")),
                    Map.entry("outputs", Map.of("type", "array",
                            "description", "processing outputs as [{item_id, amount, data_value}], first output is primary")),
                    Map.entry("allow_substitutes", Map.of("type", "boolean",
                            "description", "crafting pattern item substitution flag. Default false.")),
                    Map.entry("allow_fluid_substitutes", Map.of("type", "boolean",
                            "description", "crafting pattern fluid substitution flag. Default false.")),
                    Map.entry("replace_existing", Map.of("type", "boolean",
                            "description", "replace an existing compatible pattern with the same primary output. Default true.")),
                    Map.entry("target_machine_item_id", Map.of("type", "string",
                            "description", "optional machine/block item id learned from JEI, e.g. minecraft:furnace "
                                    + "or create:mechanical_press. The tool prefers pattern providers whose terminal "
                                    + "group icon matches this machine.")),
                    Map.entry("target_group_name", Map.of("type", "string",
                            "description", "optional lowercase/partial terminal group name to match the target machine "
                                    + "when JEI gives a machine name instead of an item id.")),
                    Map.entry("container_index", Map.of("type", "integer",
                            "description", "optional target container index from ae2_pattern_lookup")),
                    Map.entry("slot", Map.of("type", "integer",
                            "description", "optional exact slot in target container; refuses to overwrite a different pattern")),
                    Map.entry("x", Map.of("type", "integer", "description", "optional manual AE2 anchor x")),
                    Map.entry("y", Map.of("type", "integer", "description", "optional manual AE2 anchor y")),
                    Map.entry("z", Map.of("type", "integer", "description", "optional manual AE2 anchor z"))
            )
    );

    /**
     * JSON Schema fragment for one entry in ae2_pattern_write_many's "patterns"
     * array. Mirrors ae2_pattern_write's argument shape so the LLM can build
     * each entry by copy-pasting the single-call argument map. Declared
     * before {@link #AE2_PATTERN_WRITE_MANY_TOOL} to avoid an illegal forward
     * reference when the tool definition references it.
     */
    private static final Map<String, Object> AE2_PATTERN_WRITE_ITEM_SCHEMA = Map.ofEntries(
            Map.entry("type", "object"),
            Map.entry("description", "one AE2 pattern spec; shape matches ae2_pattern_write's arguments"),
            Map.entry("properties", Map.ofEntries(
                    Map.entry("pattern_type", Map.of("type", "string",
                            "description", "crafting or processing. Default crafting.")),
                    Map.entry("recipe_id", Map.of("type", "string",
                            "description", "optional JEI recipe id for crafting")),
                    Map.entry("output_item_id", Map.of("type", "string",
                            "description", "crafting output item id, or processing primary output")),
                    Map.entry("crafting_grid", Map.of("type", "array",
                            "description", "optional 9-slot JEI crafting grid for crafting patterns")),
                    Map.entry("inputs", Map.of("type", "array",
                            "description", "processing inputs (only for pattern_type=processing)")),
                    Map.entry("outputs", Map.of("type", "array",
                            "description", "processing outputs (only for pattern_type=processing, first = primary)")),
                    Map.entry("allow_substitutes", Map.of("type", "boolean",
                            "description", "per-spec override of the global allow_substitutes flag")),
                    Map.entry("allow_fluid_substitutes", Map.of("type", "boolean",
                            "description", "per-spec override of the global allow_fluid_substitutes flag")),
                    Map.entry("replace_existing", Map.of("type", "boolean",
                            "description", "per-spec override of the global replace_existing policy")),
                    Map.entry("container_index", Map.of("type", "integer",
                            "description", "per-spec override of the target container index")),
                    Map.entry("slot", Map.of("type", "integer",
                            "description", "per-spec override of the target slot; refuses to overwrite a different pattern"))
            ))
    );

    /**
     * Batch counterpart of {@link #AE2_PATTERN_WRITE_TOOL}. Encodes and installs
     * 1..16 patterns in a single round-trip using a shared network anchor
     * and pre-scanned PatternContainer list. Per-spec overrides let the LLM
     * mix crafting + processing, target different containers, and override the
     * global replace_existing policy per pattern.
     *
     * <p>Two transactional modes:
     * <ul>
     *   <li>{@code atomic: false} (default) — best-effort: successful patterns
     *       stay written, failures are reported per index. Useful for
     *       incremental "fill what you can" work.</li>
     *   <li>{@code atomic: true} — full-or-nothing: any failure rolls back
     *       every successful write via a pre-write slot snapshot. Useful when
     *       the LLM has a complete plan and wants to commit or abort as a unit.</li>
     * </ul>
     *
     * <p>{@code dry_run: true} runs the full encode + target-choose path but
     * skips the actual slot write; response is a "would-write" preview.
     */
    private static final ToolDefinition AE2_PATTERN_WRITE_MANY_TOOL = new ToolDefinition(
            "ae2_pattern_write_many",
            "Encode and install up to 16 AE2 encoded patterns in a single call. Mirrors "
                    + "ae2_pattern_write but takes a 'patterns' array; each entry is the same shape as "
                    + "ae2_pattern_write's arguments (pattern_type, recipe_id, output_item_id, crafting_grid, "
                    + "inputs, outputs, allow_substitutes, allow_fluid_substitutes, replace_existing, "
                    + "container_index, slot). The x/y/z anchor, target_machine_item_id, target_group_name, "
                    + "and the default replace_existing / allow_substitutes / allow_fluid_substitutes are "
                    + "shared across the batch (top-level). Per-spec 'replace_existing' overrides the top "
                    + "level. PREFER THIS over ae2_pattern_write when installing more than one pattern at "
                    + "once (e.g. an entire Create machine chain). 'atomic: true' makes the batch "
                    + "all-or-nothing — any failure rolls back every successful write. 'dry_run: true' "
                    + "previews what would be written without actually writing.",
            Map.ofEntries(
                    Map.entry("patterns", Map.of(
                            "type", "array",
                            "description", "list of pattern specs. Each entry is a self-contained ae2_pattern_write "
                                    + "argument map (pattern_type, recipe_id or output_item_id, crafting_grid or "
                                    + "inputs+outputs, optional replace_existing / container_index / slot).",
                            "minItems", 1,
                            "maxItems", 16,
                            "items", AE2_PATTERN_WRITE_ITEM_SCHEMA
                    )),
                    Map.entry("atomic", Map.of("type", "boolean",
                            "description", "if true, any failure rolls back every successful write in this call. "
                                    + "Default false (best-effort).")),
                    Map.entry("dry_run", Map.of("type", "boolean",
                            "description", "if true, run the full encode + target-choose path but skip the actual "
                                    + "slot write. Returns the would-be targets. Default false.")),
                    Map.entry("replace_existing", Map.of("type", "boolean",
                            "description", "default replace_existing policy for specs that don't override it. Default true.")),
                    Map.entry("allow_substitutes", Map.of("type", "boolean",
                            "description", "default crafting item-substitution flag. Default false.")),
                    Map.entry("allow_fluid_substitutes", Map.of("type", "boolean",
                            "description", "default crafting fluid-substitution flag. Default false.")),
                    Map.entry("target_machine_item_id", Map.of("type", "string",
                            "description", "shared machine item id hint for target container selection (e.g. "
                                    + "create:mechanical_press). Per-spec container_index still wins.")),
                    Map.entry("target_group_name", Map.of("type", "string",
                            "description", "shared terminal group name hint for target container selection.")),
                    Map.entry("x", Map.of("type", "integer", "description", "shared AE2 network anchor x")),
                    Map.entry("y", Map.of("type", "integer", "description", "shared AE2 network anchor y")),
                    Map.entry("z", Map.of("type", "integer", "description", "shared AE2 network anchor z"))
            )
    );

    private static final String SYSTEM_PROMPT_GUIDANCE = """
            Applied Energistics 2 integration tools:
              - For devices that are offline, missing channels, unexpectedly disconnected, or have controller/channel-capacity problems, call ae2_channel_diagnostics. Report finding types and affected coordinates; do not guess from recipes or web pages.
              - To inspect installed encoded patterns, call ae2_pattern_lookup.
              - To write one encoded pattern, call ae2_pattern_write. To write multiple patterns, prefer ae2_pattern_write_many.
              - Before writing a pattern, use emi_lookup or jei_lookup for the local recipe. Do not use web search for per-recipe pattern data.
              - Use pattern_type=crafting only for workbench 3x3 recipes. Use pattern_type=processing for attached machines such as inscribers, chargers, pulverizers, sawmills, and smelters.
              - For processing patterns, provide inputs and outputs. For crafting patterns, provide recipe_id or a 9-slot crafting_grid.
            """;

    private Ae2ToolExtension() {
    }

    public static ToolExtension extension() {
        return new ToolExtension(List.of(
                new ToolExtension.Tool(AE2_CHANNEL_DIAGNOSTICS_TOOL,
                        Set.of(LlmToolMode.BUILD, LlmToolMode.PLAN), Ae2ToolExtension::execute),
                new ToolExtension.Tool(AE2_PATTERN_LOOKUP_TOOL,
                        Set.of(LlmToolMode.BUILD, LlmToolMode.PLAN), Ae2ToolExtension::execute),
                new ToolExtension.Tool(AE2_PATTERN_WRITE_TOOL,
                        Set.of(LlmToolMode.BUILD), Ae2ToolExtension::execute),
                new ToolExtension.Tool(AE2_PATTERN_WRITE_MANY_TOOL,
                        Set.of(LlmToolMode.BUILD), Ae2ToolExtension::execute)
        ), SYSTEM_PROMPT_GUIDANCE);
    }

    private static String execute(IntelligentTransmitterBlockEntity transmitter, ToolInvocation invocation) {
        return switch (invocation.name()) {
            case "ae2_channel_diagnostics" -> runAe2ChannelDiagnostics(transmitter, invocation);
            case "ae2_pattern_lookup" -> runAe2PatternLookup(transmitter, invocation);
            case "ae2_pattern_write" -> runAe2PatternWrite(transmitter, invocation);
            case "ae2_pattern_write_many" -> runAe2PatternWriteMany(transmitter, invocation);
            default -> errorJson("unknown AE2 tool: " + invocation.name());
        };
    }
    private static java.util.List<java.util.Map<String, Object>> coerceBlocksArg(Object raw) {
        java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
        if (raw == null) return out;
        if (raw instanceof java.util.List<?> list) {
            for (Object e : list) {
                if (e instanceof java.util.Map<?, ?> m) {
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> casted = (java.util.Map<String, Object>) m;
                    out.add(casted);
                }
            }
            return out;
        }
        String s = raw.toString();
        try {
            com.google.gson.JsonElement el = com.google.gson.JsonParser.parseString(s);
            if (el.isJsonArray()) {
                for (com.google.gson.JsonElement child : el.getAsJsonArray()) {
                    if (child.isJsonObject()) {
                        out.add(new com.google.gson.Gson().fromJson(child, java.util.Map.class));
                    }
                }
            }
        } catch (Exception e) {
            // fall through, returns empty list
        }
        return out;
    }

    private static boolean addOnlineBridgeAnchors(ServerLevel level, CodeNetworkScanner.NetworkView codeView,
                                                   Set<BlockPos> anchors) {
        boolean found = false;
        for (BlockPos memberPos : codeView.memberPositions()) {
            if (codeView.memberHasChannel(memberPos)
                    && level.getBlockEntity(memberPos) instanceof Ae2NetworkBridgeBlockEntity bridge
                    && bridge.isAe2Online()) {
                anchors.add(memberPos);
                found = true;
            }
        }
        return found;
    }

    private static String runAe2ChannelDiagnostics(IntelligentTransmitterBlockEntity t, ToolInvocation inv) {
        if (!(t.getLevel() instanceof ServerLevel level)) {
            return errorJson("ae2_channel_diagnostics: transmitter level is not a ServerLevel");
        }

        boolean includeHealthyNodes = asBool(inv.arguments().get("include_healthy_nodes"));
        Integer requestedMaxNodes = asInt(inv.arguments().get("max_nodes"));
        int maxNodes = Math.max(1, Math.min(requestedMaxNodes == null ? 128 : requestedMaxNodes, 512));
        java.util.LinkedHashSet<BlockPos> anchors = new java.util.LinkedHashSet<>();
        Integer x = asInt(inv.arguments().get("x"));
        Integer y = asInt(inv.arguments().get("y"));
        Integer z = asInt(inv.arguments().get("z"));
        if (x != null && y != null && z != null) {
            anchors.add(new BlockPos(x, y, z));
        }
        CodeNetworkScanner.NetworkView codeView = CodeNetworkScanner.scan(level, t.getBlockPos());
        if (!addOnlineBridgeAnchors(level, codeView, anchors)) {
            return errorJson("ae2_channel_diagnostics: no AE2 network bridge found on the code network; "
                    + "the bridge requires 8 core-code and 8 AE2 channels");
        }

        try {
            Class.forName("appeng.api.networking.IGrid");
            JsonObject out = new JsonObject();
            out.addProperty("ok", true);
            out.addProperty("tool", "ae2_channel_diagnostics");
            out.addProperty("read_only", true);
            out.addProperty("anchor_count", anchors.size());
            JsonArray networks = new JsonArray();
            JsonArray unresolved = new JsonArray();
            java.util.IdentityHashMap<Object, Boolean> seenGrids = new java.util.IdentityHashMap<>();

            for (BlockPos anchor : anchors) {
                Object grid = resolveAe2GridNear(level, anchor);
                if (grid == null) {
                    unresolved.add(blockPosJson(anchor, "no AE2 grid found at/adjacent to anchor"));
                } else if (seenGrids.put(grid, Boolean.TRUE) == null) {
                    networks.add(describeAe2ChannelHealth(anchor, grid, includeHealthyNodes, maxNodes));
                }
            }
            out.add("networks", networks);
            out.add("unresolved_anchors", unresolved);
            out.addProperty("network_count", networks.size());
            if (networks.isEmpty()) {
                out.addProperty("ok", false);
                out.addProperty("error", "no AE2 grid could be resolved from the supplied anchors");
            }
            return GSON.toJson(out);
        } catch (ClassNotFoundException e) {
            return errorJson("ae2_channel_diagnostics: AE2 is not installed or is not loaded (missing "
                    + e.getMessage() + ")");
        } catch (Exception e) {
            return errorJson("ae2_channel_diagnostics: AE2 reflection failed: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static JsonObject describeAe2ChannelHealth(BlockPos anchor, Object grid, boolean includeHealthyNodes,
                                                        int maxNodes) throws Exception {
        Class<?> gridClass = Class.forName("appeng.api.networking.IGrid");
        Class<?> nodeClass = Class.forName("appeng.api.networking.IGridNode");
        Class<?> pathingClass = Class.forName("appeng.api.networking.pathing.IPathingService");
        Class<?> flagsClass = Class.forName("appeng.api.networking.GridFlags");
        Object pathing = gridClass.getMethod("getService", Class.class).invoke(grid, pathingClass);

        JsonObject out = new JsonObject();
        out.add("anchor", blockPosJson(anchor, null));
        out.addProperty("grid_class", grid.getClass().getName());
        out.addProperty("node_count", ((Number) gridClass.getMethod("size").invoke(grid)).intValue());
        boolean booting = (Boolean) pathingClass.getMethod("isNetworkBooting").invoke(pathing);
        String controllerState = String.valueOf(pathingClass.getMethod("getControllerState").invoke(pathing));
        String channelMode = String.valueOf(pathingClass.getMethod("getChannelMode").invoke(pathing));
        int networkUsed = ((Number) pathingClass.getMethod("getUsedChannels").invoke(pathing)).intValue();
        out.addProperty("booting", booting);
        out.addProperty("controller_state", controllerState);
        out.addProperty("channel_mode", channelMode);
        out.addProperty("used_channels", networkUsed);

        Object adHocError = invokeNoArgQuiet(pathing, "getAdHocNetworkError");
        if (adHocError != null) {
            out.addProperty("ad_hoc_error", String.valueOf(adHocError));
        }

        java.lang.reflect.Method hasFlag = nodeClass.getMethod("hasFlag", flagsClass);
        Object requireChannelFlag = enumConstant(flagsClass, "REQUIRE_CHANNEL");
        Iterable<?> iterable = (Iterable<?>) gridClass.getMethod("getNodes").invoke(grid);
        java.util.List<Object> nodes = new java.util.ArrayList<>();
        java.util.IdentityHashMap<Object, Integer> nodeIds = new java.util.IdentityHashMap<>();
        for (Object node : iterable) {
            nodeIds.put(node, nodes.size());
            nodes.add(node);
        }

        JsonArray findings = new JsonArray();
        JsonArray reportedNodes = new JsonArray();
        int poweredCount = 0;
        int missingChannels = 0;
        int saturatedNodes = 0;
        int omittedNodes = 0;
        for (int i = 0; i < nodes.size(); i++) {
            Object node = nodes.get(i);
            boolean powered = (Boolean) nodeClass.getMethod("isPowered").invoke(node);
            boolean meetsChannels = (Boolean) nodeClass.getMethod("meetsChannelRequirements").invoke(node);
            boolean active = (Boolean) nodeClass.getMethod("isActive").invoke(node);
            boolean requiresChannel = (Boolean) hasFlag.invoke(node, requireChannelFlag);
            int used = ((Number) nodeClass.getMethod("getUsedChannels").invoke(node)).intValue();
            int max = ((Number) nodeClass.getMethod("getMaxChannels").invoke(node)).intValue();
            boolean missing = requiresChannel && !meetsChannels;
            boolean saturated = max > 0 && used >= max;
            if (powered) poweredCount++;
            if (missing) missingChannels++;
            if (saturated) saturatedNodes++;

            boolean problematic = !powered || missing || saturated;
            if ((includeHealthyNodes || problematic) && reportedNodes.size() < maxNodes) {
                reportedNodes.add(describeAe2ChannelNode(nodeClass, node, i, powered, active, requiresChannel,
                        meetsChannels, used, max, missing, saturated));
            } else if (includeHealthyNodes || problematic) {
                omittedNodes++;
            }
        }

        if (poweredCount == 0 && !nodes.isEmpty()) findings.add("NETWORK_UNPOWERED");
        if (booting) findings.add("NETWORK_BOOTING_CHANNEL_VALUES_MAY_BE_STALE");
        if (controllerState.contains("CONFLICT")) findings.add("CONTROLLER_CONFLICT");
        if (adHocError != null && !"NONE".equals(String.valueOf(adHocError))) {
            findings.add("AD_HOC_ERROR:" + adHocError);
        }
        if (missingChannels > 0) findings.add("DEVICES_MISSING_CHANNELS:" + missingChannels);
        if (saturatedNodes > 0) findings.add("SATURATED_CHANNEL_NODES:" + saturatedNodes);
        if (findings.isEmpty()) findings.add("NO_CHANNEL_PROBLEMS_DETECTED");

        JsonArray saturatedConnections = describeSaturatedAe2Connections(nodeClass, nodes, nodeIds, maxNodes);
        if (!saturatedConnections.isEmpty()) {
            findings.add("SATURATED_CONNECTIONS:" + saturatedConnections.size());
        }
        out.add("findings", findings);
        out.add("nodes", reportedNodes);
        out.add("saturated_connections", saturatedConnections);
        out.addProperty("powered_nodes", poweredCount);
        out.addProperty("missing_channel_nodes", missingChannels);
        out.addProperty("saturated_nodes", saturatedNodes);
        out.addProperty("reported_node_count", reportedNodes.size());
        out.addProperty("omitted_node_count", omittedNodes);
        out.addProperty("healthy", findings.size() == 1
                && "NO_CHANNEL_PROBLEMS_DETECTED".equals(findings.get(0).getAsString()));
        out.addProperty("capacity_note", "controller-network capacity is topology-dependent; saturated nodes and "
                + "connections identify bottlenecks. Ad-hoc networks use a grid-wide channel limit.");
        return out;
    }

    private static JsonObject describeAe2ChannelNode(Class<?> nodeClass, Object node, int id, boolean powered,
                                                       boolean active, boolean requiresChannel, boolean meetsChannels,
                                                       int used, int max, boolean missing, boolean saturated)
            throws Exception {
        JsonObject out = new JsonObject();
        out.addProperty("node_id", id);
        Object owner = nodeClass.getMethod("getOwner").invoke(node);
        out.addProperty("owner_class", owner == null ? "null" : owner.getClass().getName());
        BlockEntity ownerBlockEntity = ae2OwnerBlockEntity(owner);
        if (ownerBlockEntity != null) {
            out.add("position", blockPosJson(ownerBlockEntity.getBlockPos(), null));
            out.addProperty("block", ownerBlockEntity.getBlockState().getBlock().builtInRegistryHolder()
                    .key().location().toString());
        }
        Object visual = invokeNoArgQuiet(node, "getVisualRepresentation");
        Object visualId = invokeNoArgQuiet(visual, "getId");
        if (visualId != null) out.addProperty("visual_id", visualId.toString());
        out.addProperty("powered", powered);
        out.addProperty("active", active);
        out.addProperty("requires_channel", requiresChannel);
        out.addProperty("meets_channel_requirements", meetsChannels);
        out.addProperty("used_channels", used);
        out.addProperty("max_channels", max);
        JsonArray issues = new JsonArray();
        if (!powered) issues.add("UNPOWERED");
        if (missing) issues.add("MISSING_CHANNEL");
        if (saturated) issues.add("SATURATED");
        out.add("issues", issues);
        return out;
    }

    private static JsonArray describeSaturatedAe2Connections(Class<?> nodeClass, java.util.List<Object> nodes,
                                                               java.util.IdentityHashMap<Object, Integer> nodeIds,
                                                               int limit) throws Exception {
        Class<?> connectionClass = Class.forName("appeng.api.networking.IGridConnection");
        java.util.IdentityHashMap<Object, Boolean> seen = new java.util.IdentityHashMap<>();
        JsonArray out = new JsonArray();
        for (Object node : nodes) {
            Iterable<?> connections = (Iterable<?>) nodeClass.getMethod("getConnections").invoke(node);
            for (Object connection : connections) {
                if (seen.put(connection, Boolean.TRUE) != null) continue;
                Object a = connectionClass.getMethod("a").invoke(connection);
                Object b = connectionClass.getMethod("b").invoke(connection);
                int used = ((Number) connectionClass.getMethod("getUsedChannels").invoke(connection)).intValue();
                int aMax = ((Number) nodeClass.getMethod("getMaxChannels").invoke(a)).intValue();
                int bMax = ((Number) nodeClass.getMethod("getMaxChannels").invoke(b)).intValue();
                int capacity = Math.min(aMax > 0 ? aMax : Integer.MAX_VALUE,
                        bMax > 0 ? bMax : Integer.MAX_VALUE);
                if (capacity == Integer.MAX_VALUE || used < capacity || out.size() >= limit) continue;
                JsonObject item = new JsonObject();
                item.addProperty("node_a", nodeIds.getOrDefault(a, -1));
                item.addProperty("node_b", nodeIds.getOrDefault(b, -1));
                item.addProperty("used_channels", used);
                item.addProperty("estimated_capacity", capacity);
                item.addProperty("in_world", connectionClass.getMethod("isInWorld").invoke(connection).equals(true));
                item.add("endpoint_a", describeAe2NodeLocation(nodeClass, a));
                item.add("endpoint_b", describeAe2NodeLocation(nodeClass, b));
                out.add(item);
            }
        }
        return out;
    }

    private static JsonObject describeAe2NodeLocation(Class<?> nodeClass, Object node) throws Exception {
        Object owner = nodeClass.getMethod("getOwner").invoke(node);
        BlockEntity be = ae2OwnerBlockEntity(owner);
        JsonObject out = new JsonObject();
        out.addProperty("owner_class", owner == null ? "null" : owner.getClass().getName());
        if (be != null) out.add("position", blockPosJson(be.getBlockPos(), null));
        return out;
    }

    private static BlockEntity ae2OwnerBlockEntity(Object owner) {
        if (owner instanceof BlockEntity be) return be;
        Object be = invokeNoArgQuiet(owner, "getBlockEntity");
        return be instanceof BlockEntity blockEntity ? blockEntity : null;
    }

    private static Object enumConstant(Class<?> enumClass, String name) {
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object value = Enum.valueOf((Class<? extends Enum>) enumClass.asSubclass(Enum.class), name);
        return value;
    }

    private static JsonObject blockPosJson(BlockPos pos, String error) {
        JsonObject out = new JsonObject();
        out.addProperty("x", pos.getX());
        out.addProperty("y", pos.getY());
        out.addProperty("z", pos.getZ());
        if (error != null) out.addProperty("error", error);
        return out;
    }

    private static String runAe2PatternLookup(IntelligentTransmitterBlockEntity t, ToolInvocation inv) {
        if (!(t.getLevel() instanceof ServerLevel level)) {
            return errorJson("ae2_pattern_lookup: transmitter level is not a ServerLevel");
        }

        boolean includeDetails = !inv.arguments().containsKey("include_details")
                || asBool(inv.arguments().get("include_details"));
        boolean includeHidden = asBool(inv.arguments().get("include_hidden"));
        java.util.LinkedHashSet<BlockPos> anchors = new java.util.LinkedHashSet<>();

        Integer x = asInt(inv.arguments().get("x"));
        Integer y = asInt(inv.arguments().get("y"));
        Integer z = asInt(inv.arguments().get("z"));
        if (x != null && y != null && z != null) {
            anchors.add(new BlockPos(x, y, z));
        }

        CodeNetworkScanner.NetworkView codeView = CodeNetworkScanner.scan(level, t.getBlockPos());
        if (!addOnlineBridgeAnchors(level, codeView, anchors)) {
            return errorJson("ae2_pattern_lookup: no AE2 network bridge found on the code network; "
                    + "the bridge requires 8 core-code and 8 AE2 channels");
        }

        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("tool", "ae2_pattern_lookup");
        out.addProperty("source", "ae2_pattern_access_terminal_reflection");
        out.addProperty("include_hidden", includeHidden);
        out.addProperty("include_details", includeDetails);
        out.addProperty("anchor_count", anchors.size());

        try {
            Class.forName("appeng.api.networking.IGrid");
            Class.forName("appeng.helpers.patternprovider.PatternContainer");
            Class.forName("appeng.api.crafting.PatternDetailsHelper");

            JsonArray networks = new JsonArray();
            JsonArray unresolved = new JsonArray();
            int[] remainingPatterns = new int[]{128};

            for (BlockPos anchor : anchors) {
                Object grid = resolveAe2GridNear(level, anchor);
                if (grid != null) {
                    networks.add(describeAe2GridPatterns(level, anchor, grid, includeDetails, includeHidden,
                            remainingPatterns));
                    continue;
                }

                JsonObject single = describeSingleAe2PatternContainer(level, anchor, includeDetails, includeHidden,
                        remainingPatterns);
                if (single != null) {
                    networks.add(single);
                } else {
                    JsonObject miss = new JsonObject();
                    miss.addProperty("x", anchor.getX());
                    miss.addProperty("y", anchor.getY());
                    miss.addProperty("z", anchor.getZ());
                    miss.addProperty("error", "no AE2 grid or PatternContainer found at/adjacent to anchor");
                    unresolved.add(miss);
                }
            }

            out.add("networks", networks);
            out.add("unresolved_anchors", unresolved);
            out.addProperty("network_count", networks.size());
            out.addProperty("truncated", remainingPatterns[0] <= 0);
            return GSON.toJson(out);
        } catch (ClassNotFoundException e) {
            return errorJson("ae2_pattern_lookup: AE2 is not installed or is not loaded (missing "
                    + e.getMessage() + ")");
        } catch (Exception e) {
            return errorJson("ae2_pattern_lookup: AE2 reflection failed: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private record EncodedAe2Pattern(ItemStack stack, String patternType, String recipeId, ItemStack primaryOutput,
                                     JsonObject recipeInfo) {
    }

    private record Ae2PatternContainerRef(BlockPos anchor, int index, Object container, Object inventory) {
    }

    private record Ae2PatternWriteTarget(Ae2PatternContainerRef ref, int slot, boolean replacing,
                                         String selectionReason) {
    }

    private static String runAe2PatternWrite(IntelligentTransmitterBlockEntity t, ToolInvocation inv) {
        long startNs = System.nanoTime();
        if (!(t.getLevel() instanceof ServerLevel level)) {
            agentDebug("ae2_pattern_write: abort — transmitter level is not a ServerLevel");
            return errorJson("ae2_pattern_write: transmitter level is not a ServerLevel");
        }
        agentDebug("ae2_pattern_write: step 1/8 resolve anchors (transmitter pos={})", t.getBlockPos());

        java.util.LinkedHashSet<BlockPos> anchors = new java.util.LinkedHashSet<>();
        Integer x = asInt(inv.arguments().get("x"));
        Integer y = asInt(inv.arguments().get("y"));
        Integer z = asInt(inv.arguments().get("z"));
        if (x != null && y != null && z != null) {
            anchors.add(new BlockPos(x, y, z));
        }

        CodeNetworkScanner.NetworkView codeView = CodeNetworkScanner.scan(level, t.getBlockPos());
        if (!addOnlineBridgeAnchors(level, codeView, anchors)) {
            agentDebug("ae2_pattern_write: abort — no AE2 bridge on code network");
            return errorJson("ae2_pattern_write: no AE2 network bridge found on the code network; "
                    + "the bridge requires 8 core-code and 8 AE2 channels");
        }
        agentDebug("ae2_pattern_write: step 2/8 found {} bridge anchor(s)", anchors.size());

        try {
            agentDebug("ae2_pattern_write: step 3/8 checking AE2 classes on classpath");
            Class.forName("appeng.api.networking.IGrid");
            Class.forName("appeng.helpers.patternprovider.PatternContainer");
            Class.forName("appeng.api.crafting.PatternDetailsHelper");

            agentDebug("ae2_pattern_write: step 4/8 scanning pattern containers (anchors={}, includeEmpty=false)",
                    anchors.size());
            java.util.List<Ae2PatternContainerRef> containers = collectAe2PatternContainers(level, anchors, false);
            if (containers.isEmpty()) {
                agentDebug("ae2_pattern_write: abort — no visible PatternContainer found from anchors");
                return errorJson("ae2_pattern_write: no visible AE2 PatternContainer found from bridge anchors");
            }
            agentDebug("ae2_pattern_write: found {} candidate container(s)", containers.size());

            String targetMachineItemId = normalizeId(asString(inv.arguments().get("target_machine_item_id")));
            String targetGroupName = asString(inv.arguments().get("target_group_name"));
            boolean replaceExisting = !inv.arguments().containsKey("replace_existing")
                    || asBool(inv.arguments().get("replace_existing"));
            agentDebug("ae2_pattern_write: selection hints machineId='{}' group='{}' replaceExisting={}",
                    targetMachineItemId, targetGroupName, replaceExisting);

            agentDebug("ae2_pattern_write: step 5/8 prepare (encode + choose target) — shared with ae2_pattern_write_many");
            PreparedAe2Pattern prepared = prepareAe2PatternWrite(level, inv, containers,
                    targetMachineItemId, targetGroupName, replaceExisting);
            EncodedAe2Pattern encoded = prepared.encoded();
            agentDebug("ae2_pattern_write: encoded pattern OK recipe='{}' primary={} stackSize={}",
                    encoded.recipeId(), itemId(encoded.primaryOutput()), encoded.stack().getCount());

            if (prepared.target() == null) {
                agentDebug("ae2_pattern_write: abort — prepareAe2PatternWrite returned null target "
                        + "(no empty slot and no existing match)");
                JsonObject out = new JsonObject();
                out.addProperty("ok", false);
                out.addProperty("error", "ae2_pattern_write: no compatible target slot found");
                out.addProperty("pattern_type", encoded.patternType());
                out.addProperty("primary_output", itemId(encoded.primaryOutput()));
                out.add("containers", summarizeAe2PatternWriteContainers(level, containers, targetMachineItemId,
                        targetGroupName, encoded.stack()));
                return GSON.toJson(out);
            }
            Ae2PatternWriteTarget target = prepared.target();
            agentDebug("ae2_pattern_write: target chosen containerIdx={} slot={} replacing={} reason='{}'",
                    target.ref().index(), target.slot(), target.replacing(), target.selectionReason());

            agentDebug("ae2_pattern_write: step 6/8 invoking setItemDirect via reflection "
                    + "on inventory class '{}'", target.ref().inventory().getClass().getName());
            writeAe2PatternSlot(level, target, encoded.stack());
            agentDebug("ae2_pattern_write: write complete, building success response");

            JsonObject out = new JsonObject();
            out.addProperty("ok", true);
            out.addProperty("tool", "ae2_pattern_write");
            out.addProperty("pattern_type", encoded.patternType());
            out.addProperty("recipe_id", encoded.recipeId() == null ? "" : encoded.recipeId());
            out.addProperty("primary_output", itemId(encoded.primaryOutput()));
            out.addProperty("target_container_index", target.ref().index());
            out.addProperty("target_slot", target.slot());
            out.addProperty("replaced_existing", target.replacing());
            out.addProperty("selection_reason", target.selectionReason());
            out.add("target_group", describeAe2PatternGroup(
                    Class.forName("appeng.helpers.patternprovider.PatternContainer")
                            .getMethod("getTerminalGroup")
                            .invoke(target.ref().container())));
            out.add("encoded_pattern", describeItemStack(encoded.stack()));
            out.add("recipe", encoded.recipeInfo());
            long durMs = (System.nanoTime() - startNs) / 1_000_000L;
            agentDebug("ae2_pattern_write: SUCCESS totalDurMs={}", durMs);
            return GSON.toJson(out);
        } catch (ClassNotFoundException e) {
            agentWarn("ae2_pattern_write: ClassNotFoundException — {}", e.getMessage());
            return errorJson("ae2_pattern_write: AE2 is not installed or is not loaded (missing "
                    + e.getMessage() + ")");
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            agentWarn("ae2_pattern_write: AE2 reflection InvocationTargetException cause={}: {}",
                    cause == null ? "<none>" : cause.getClass().getName(),
                    cause == null ? e.getMessage() : cause.getMessage(),
                    cause == null ? e : cause);
            return errorJson("ae2_pattern_write: AE2 reflection failed: "
                    + (cause == null ? e.getClass().getSimpleName() : cause.getClass().getSimpleName())
                    + ": " + (cause == null ? e.getMessage() : cause.getMessage()));
        } catch (Exception e) {
            agentWarn("ae2_pattern_write: caught {}: {}", e.getClass().getName(), e.getMessage(), e);
            return errorJson("ae2_pattern_write: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * Batch counterpart of {@link #runAe2PatternWrite}. Encodes and installs
     * 1..{@value #MAX_AE2_PATTERNS_PER_BATCH} patterns in one call, sharing
     * the network anchor resolution + container scan, but iterating the
     * encode + choose-target + write loop per spec. Per-spec overrides
     * ({@code pattern_type}, {@code replace_existing}, {@code container_index},
     * {@code slot}, etc.) take precedence over the top-level defaults.
     *
     * <p>Transaction modes: {@code atomic: false} (default) keeps every
     * successful write even if later specs fail; {@code atomic: true} rolls
     * back every successful write on the first failure. {@code dry_run: true}
     * runs the full encode + target-choose path but skips setItemDirect.
     */
    private static String runAe2PatternWriteMany(IntelligentTransmitterBlockEntity t, ToolInvocation inv) {
        long startNs = System.nanoTime();
        @SuppressWarnings("unchecked")
        java.util.List<java.util.Map<String, Object>> patterns =
                (java.util.List<java.util.Map<String, Object>>) inv.arguments().get("patterns");
        if (patterns == null || patterns.isEmpty()) {
            return errorJson("ae2_pattern_write_many: 'patterns' array is required and must contain at least 1 entry");
        }
        if (patterns.size() > MAX_AE2_PATTERNS_PER_BATCH) {
            return errorJson("ae2_pattern_write_many: too many patterns: " + patterns.size()
                    + " (max " + MAX_AE2_PATTERNS_PER_BATCH + " per call)");
        }

        boolean atomic = asBool(inv.arguments().get("atomic"));
        boolean dryRun = asBool(inv.arguments().get("dry_run"));
        boolean replaceDefault = !inv.arguments().containsKey("replace_existing")
                || asBool(inv.arguments().get("replace_existing"));
        String targetMachineItemId = normalizeId(asString(inv.arguments().get("target_machine_item_id")));
        String targetGroupName = asString(inv.arguments().get("target_group_name"));
        agentDebug("ae2_pattern_write_many: dry_run={} atomic={} size={} replaceDefault={}",
                dryRun, atomic, patterns.size(), replaceDefault);

        if (!(t.getLevel() instanceof ServerLevel level)) {
            return errorJson("ae2_pattern_write_many: transmitter level is not a ServerLevel");
        }

        java.util.LinkedHashSet<BlockPos> anchors = new java.util.LinkedHashSet<>();
        Integer ax = asInt(inv.arguments().get("x"));
        Integer ay = asInt(inv.arguments().get("y"));
        Integer az = asInt(inv.arguments().get("z"));
        if (ax != null && ay != null && az != null) {
            anchors.add(new BlockPos(ax, ay, az));
        }
        CodeNetworkScanner.NetworkView codeView = CodeNetworkScanner.scan(level, t.getBlockPos());
        if (!addOnlineBridgeAnchors(level, codeView, anchors)) {
            return errorJson("ae2_pattern_write_many: no AE2 network bridge found on the code network; "
                    + "the bridge requires 8 core-code and 8 AE2 channels");
        }

        try {
            Class.forName("appeng.api.networking.IGrid");
            Class.forName("appeng.helpers.patternprovider.PatternContainer");
            Class.forName("appeng.api.crafting.PatternDetailsHelper");

            java.util.List<Ae2PatternContainerRef> containers =
                    collectAe2PatternContainers(level, anchors, false);
            if (containers.isEmpty()) {
                return errorJson("ae2_pattern_write_many: no visible AE2 PatternContainer found from bridge anchors");
            }
            agentDebug("ae2_pattern_write_many: {} candidate container(s) for the batch", containers.size());

            java.util.List<JsonObject> results = new java.util.ArrayList<>();
            java.util.List<Ae2SlotSnapshot> snapshots = new java.util.ArrayList<>();
            int succeeded = 0;
            int failed = 0;
            int skipped = 0;
            boolean atomicRolledBack = false;
            String firstError = null;

            for (int i = 0; i < patterns.size(); i++) {
                java.util.Map<String, Object> spec = patterns.get(i);
                if (spec == null) spec = java.util.Map.of();
                JsonObject result = new JsonObject();
                result.addProperty("index", i);
                String pType = asString(spec.get("pattern_type"));
                if (pType == null || pType.isBlank()) pType = "crafting";
                result.addProperty("pattern_type", pType.toLowerCase(java.util.Locale.ROOT));
                String recipeId = asString(spec.get("recipe_id"));
                if (recipeId != null) result.addProperty("recipe_id", recipeId);
                String outId = asString(spec.get("output_item_id"));
                if (outId != null) result.addProperty("output_item_id", outId);

                try {
                    ToolInvocation specInv = new ToolInvocation(
                            "spec_" + i, "ae2_pattern_write", spec);
                    boolean specReplace = spec.containsKey("replace_existing")
                            ? asBool(spec.get("replace_existing")) : replaceDefault;
                    PreparedAe2Pattern prepared = prepareAe2PatternWrite(level, specInv, containers,
                            targetMachineItemId, targetGroupName, specReplace);

                    if (prepared.target() == null) {
                        result.addProperty("ok", false);
                        result.addProperty("error", "no compatible target slot found");
                        failed++;
                        if (firstError == null) firstError = "spec[" + i + "]: no compatible target slot found";
                        results.add(result);
                        if (atomic) {
                            rollbackAe2PatternSlots(level, snapshots, results, succeeded);
                            atomicRolledBack = true;
                            for (int j = i + 1; j < patterns.size(); j++) {
                                JsonObject skip = new JsonObject();
                                skip.addProperty("index", j);
                                skip.addProperty("ok", false);
                                skip.addProperty("skipped", true);
                                skip.addProperty("error", "atomic batch rolled back at spec[" + i + "]");
                                results.add(skip);
                                skipped++;
                            }
                            break;
                        }
                        continue;
                    }

                    result.addProperty("ok", true);
                    result.addProperty("container_index", prepared.target().ref().index());
                    result.addProperty("slot", prepared.target().slot());
                    result.addProperty("replaced_existing", prepared.target().replacing());
                    result.addProperty("selection_reason", prepared.target().selectionReason());
                    result.addProperty("primary_output", itemId(prepared.encoded().primaryOutput()));
                    result.addProperty("dry_run", dryRun);

                    if (!dryRun) {
                        Ae2SlotSnapshot snap = snapshotAe2PatternSlot(prepared.target());
                        writeAe2PatternSlot(level, prepared.target(), prepared.encoded().stack());
                        snapshots.add(snap);
                        result.addProperty("written", true);
                    } else {
                        result.addProperty("written", false);
                    }
                    succeeded++;
                    results.add(result);
                } catch (Exception e) {
                    agentWarn("ae2_pattern_write_many: spec[{}] threw: {}", i, e.toString());
                    result.addProperty("ok", false);
                    result.addProperty("error", e.getClass().getSimpleName() + ": " + e.getMessage());
                    results.add(result);
                    failed++;
                    if (firstError == null) firstError = "spec[" + i + "]: " + e.getMessage();
                    if (atomic) {
                        rollbackAe2PatternSlots(level, snapshots, results, succeeded);
                        atomicRolledBack = true;
                        for (int j = i + 1; j < patterns.size(); j++) {
                            JsonObject skip = new JsonObject();
                            skip.addProperty("index", j);
                            skip.addProperty("ok", false);
                            skip.addProperty("skipped", true);
                            skip.addProperty("error", "atomic batch rolled back at spec[" + i + "]");
                            results.add(skip);
                            skipped++;
                        }
                        break;
                    }
                }
            }

            JsonObject out = new JsonObject();
            out.addProperty("ok", failed == 0 && !atomicRolledBack);
            out.addProperty("tool", "ae2_pattern_write_many");
            out.addProperty("dry_run", dryRun);
            out.addProperty("atomic", atomic);
            out.addProperty("atomic_rolled_back", atomicRolledBack);
            JsonArray arr = new JsonArray();
            for (JsonObject r : results) arr.add(r);
            out.add("results", arr);
            JsonObject summary = new JsonObject();
            summary.addProperty("total", patterns.size());
            summary.addProperty("succeeded", succeeded);
            summary.addProperty("failed", failed);
            summary.addProperty("skipped", skipped);
            out.add("summary", summary);
            if (firstError != null) out.addProperty("first_error", firstError);
            long durMs = (System.nanoTime() - startNs) / 1_000_000L;
            agentDebug("ae2_pattern_write_many: done total={} ok={} fail={} skip={} durMs={}",
                    patterns.size(), succeeded, failed, skipped, durMs);
            return GSON.toJson(out);
        } catch (ClassNotFoundException e) {
            agentWarn("ae2_pattern_write_many: ClassNotFoundException — {}", e.getMessage());
            return errorJson("ae2_pattern_write_many: AE2 is not installed or is not loaded (missing "
                    + e.getMessage() + ")");
        } catch (Exception e) {
            agentWarn("ae2_pattern_write_many: caught {}: {}", e.getClass().getName(), e.getMessage(), e);
            return errorJson("ae2_pattern_write_many: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** Cap on patterns per ae2_pattern_write_many call. Big enough to fill a
     *  large pattern provider in one round-trip, small enough to keep a
     *  worst-case reflection failure (atomic rollback) bounded. */
    private static final int MAX_AE2_PATTERNS_PER_BATCH = 16;

    /** Pre-write snapshot of one slot in one AE2 PatternContainer inventory.
     *  Captured in atomic mode so the slot can be restored on rollback. The
     *  container reference is kept so the change-marker can be fired after
     *  restore. */
    private record Ae2SlotSnapshot(Object container, Object inventory, int slot, ItemStack original) {}

    /** Result of the "prepare" step: encoded pattern + chosen target slot.
     *  {@code target} may be null when no compatible slot exists; callers
     *  surface this as a per-spec failure in the batch result array. */
    private record PreparedAe2Pattern(EncodedAe2Pattern encoded, Ae2PatternWriteTarget target) {}

    /**
     * Encode a single spec and choose a target slot. Shared between
     * {@link #runAe2PatternWrite} and {@link #runAe2PatternWriteMany} so
     * both paths apply the same recipe resolution + slot-choice rules.
     *
     * @param specInv     synthetic {@link ToolInvocation} carrying the per-spec
     *                    arguments ({@code pattern_type}, {@code recipe_id},
     *                    {@code output_item_id}, {@code crafting_grid} /
     *                    {@code inputs} / {@code outputs}, {@code container_index},
     *                    {@code slot}, etc.)
     * @param containers  pre-scanned list of AE2 PatternContainer refs
     * @param replaceExisting  resolved replace_existing policy (top-level
     *                          default OR per-spec override)
     * @return encoded pattern + target. {@code target} may be null.
     * @throws Exception on bad spec (illegal pattern_type, missing item,
     *                   recipe_id not found, encode reflection failure).
     */
    private static PreparedAe2Pattern prepareAe2PatternWrite(
            ServerLevel level, ToolInvocation specInv,
            java.util.List<Ae2PatternContainerRef> containers,
            String targetMachineItemId, String targetGroupName, boolean replaceExisting) throws Exception {
        String patternType = asString(specInv.arguments().get("pattern_type"));
        if (patternType == null || patternType.isBlank()) {
            patternType = "crafting";
        }
        patternType = patternType.trim().toLowerCase(java.util.Locale.ROOT);
        EncodedAe2Pattern encoded = switch (patternType) {
            case "crafting" -> encodeAe2CraftingPattern(level, specInv);
            case "processing" -> encodeAe2ProcessingPattern(specInv);
            default -> throw new IllegalArgumentException("pattern_type must be crafting or processing, got: " + patternType);
        };
        Integer requestedContainer = asInt(specInv.arguments().get("container_index"));
        Integer requestedSlot = asInt(specInv.arguments().get("slot"));
        Ae2PatternWriteTarget target = chooseAe2PatternTarget(level, containers, encoded,
                targetMachineItemId, targetGroupName, replaceExisting, requestedContainer, requestedSlot);
        return new PreparedAe2Pattern(encoded, target);
    }

    /**
     * Write the encoded pattern into the chosen target slot via reflective
     * {@code setItemDirect}, then mark the owning container changed. Throws
     * on reflection failure (caller decides whether to surface as per-spec
     * error or abort the batch).
     */
    private static void writeAe2PatternSlot(ServerLevel level, Ae2PatternWriteTarget target, ItemStack pattern)
            throws Exception {
        java.lang.reflect.Method setItemDirect = target.ref().inventory().getClass()
                .getMethod("setItemDirect", int.class, ItemStack.class);
        setItemDirect.invoke(target.ref().inventory(), target.slot(), pattern.copy());
        markAe2PatternContainerChanged(level, target.ref().container());
    }

    /** Capture the current contents of a target slot for later rollback. */
    private static Ae2SlotSnapshot snapshotAe2PatternSlot(Ae2PatternWriteTarget target) throws Exception {
        ItemStack original = inventoryStack(target.ref().inventory(), target.slot());
        return new Ae2SlotSnapshot(target.ref().container(), target.ref().inventory(), target.slot(),
                original.copy());
    }

    /**
     * Roll back a batch's successful writes. Restores each snapshot's
     * original stack to the same slot via setItemDirect, then marks the
     * affected containers changed. Also annotates the per-spec results
     * array so the LLM can see which writes were reverted. Failures
     * during restore are logged at warn but do not throw — the partial
     * restore is still better than nothing, and a follow-up ae2_pattern_lookup
     * can confirm the state.
     */
    private static void rollbackAe2PatternSlots(ServerLevel level,
                                                java.util.List<Ae2SlotSnapshot> snapshots,
                                                java.util.List<JsonObject> results,
                                                int previouslySucceeded) {
        if (snapshots.isEmpty()) return;
        agentDebug("rollbackAe2PatternSlots: rolling back {} snapshot(s)", snapshots.size());
        java.util.LinkedHashSet<Object> containers = new java.util.LinkedHashSet<>();
        for (Ae2SlotSnapshot snap : snapshots) {
            try {
                java.lang.reflect.Method setItemDirect = snap.inventory().getClass()
                        .getMethod("setItemDirect", int.class, ItemStack.class);
                setItemDirect.invoke(snap.inventory(), snap.slot(), snap.original().copy());
                containers.add(snap.container());
            } catch (Exception e) {
                agentWarn("rollbackAe2PatternSlots: failed to restore slot {}: {}",
                        snap.slot(), e.toString());
            }
        }
        for (Object container : containers) {
            try {
                markAe2PatternContainerChanged(level, container);
            } catch (Exception e) {
                agentWarn("rollbackAe2PatternSlots: failed to mark container changed: {}", e.toString());
            }
        }
        // Annotate previously-succeeded results as rolled-back so the LLM
        // can tell the difference between "I never wrote this" and "I
        // wrote this and then reverted it".
        int marked = 0;
        for (JsonObject r : results) {
            if (marked >= previouslySucceeded) break;
            if (r.has("ok") && r.get("ok").getAsBoolean()
                    && r.has("written") && r.get("written").getAsBoolean()) {
                r.addProperty("rolled_back", true);
                marked++;
            }
        }
    }

    private static EncodedAe2Pattern encodeAe2ProcessingPattern(ToolInvocation inv) throws Exception {
        java.util.List<java.util.Map<String, Object>> inputArgs = coerceBlocksArg(inv.arguments().get("inputs"));
        java.util.List<java.util.Map<String, Object>> outputArgs = coerceBlocksArg(inv.arguments().get("outputs"));
        if (inputArgs.isEmpty()) {
            throw new IllegalArgumentException("processing inputs must be [{item_id, amount, data_value}]");
        }
        if (outputArgs.isEmpty()) {
            String outputItemId = asString(inv.arguments().get("output_item_id"));
            if (outputItemId != null && !outputItemId.isBlank()) {
                outputArgs = java.util.List.of(java.util.Map.of("item_id", outputItemId, "amount", 1));
            } else {
                throw new IllegalArgumentException("processing outputs or output_item_id is required");
            }
        }

        java.util.List<Object> inputs = new java.util.ArrayList<>();
        for (java.util.Map<String, Object> entry : inputArgs) {
            inputs.add(ae2GenericStackFromItemEntry(entry, "inputs"));
        }
        java.util.List<Object> outputs = new java.util.ArrayList<>();
        for (java.util.Map<String, Object> entry : outputArgs) {
            outputs.add(ae2GenericStackFromItemEntry(entry, "outputs"));
        }

        Class<?> helper = Class.forName("appeng.api.crafting.PatternDetailsHelper");
        @SuppressWarnings("unchecked")
        ItemStack encoded = (ItemStack) helper.getMethod("encodeProcessingPattern", java.util.List.class,
                java.util.List.class).invoke(null, inputs, outputs);

        ItemStack primaryOutput = itemStackFromObject(outputArgs.get(0), "outputs[0]", 1);
        JsonObject recipe = new JsonObject();
        recipe.addProperty("source", "explicit_processing_io");
        recipe.add("inputs", describePatternItemEntries(inputArgs));
        recipe.add("outputs", describePatternItemEntries(outputArgs));
        return new EncodedAe2Pattern(encoded, "processing", null, primaryOutput, recipe);
    }

    private static EncodedAe2Pattern encodeAe2CraftingPattern(ServerLevel level, ToolInvocation inv) throws Exception {
        String recipeIdArg = asString(inv.arguments().get("recipe_id"));
        String outputItemId = normalizeId(asString(inv.arguments().get("output_item_id")));
        ItemStack[] grid = parseCraftingGrid(inv.arguments().get("crafting_grid"));
        RecipeHolder<CraftingRecipe> recipe = null;

        if (recipeIdArg != null && !recipeIdArg.isBlank()) {
            var holder = level.getRecipeManager().byKey(ResourceLocation.parse(recipeIdArg.trim())).orElse(null);
            if (holder == null) {
                throw new IllegalArgumentException("recipe_id not found: " + recipeIdArg);
            }
            if (!(holder.value() instanceof CraftingRecipe)) {
                throw new IllegalArgumentException("recipe_id is not a crafting recipe: " + recipeIdArg);
            }
            @SuppressWarnings("unchecked")
            RecipeHolder<CraftingRecipe> casted = (RecipeHolder<CraftingRecipe>) (RecipeHolder<?>) holder;
            recipe = casted;
            if (isEmptyGrid(grid)) {
                grid = craftingGridFromRecipe(recipe.value());
            }
        } else if (!isEmptyGrid(grid)) {
            CraftingInput input = CraftingInput.of(3, 3, nonNullCraftingGrid(grid));
            recipe = level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, level).orElse(null);
            if (recipe == null) {
                throw new IllegalArgumentException("no crafting recipe matches crafting_grid");
            }
        } else if (outputItemId != null && !outputItemId.isBlank()) {
            java.util.List<RecipeHolder<CraftingRecipe>> candidates = findCraftingRecipesForOutput(level, outputItemId);
            if (candidates.isEmpty()) {
                throw new IllegalArgumentException("no crafting recipe produces " + outputItemId);
            }
            if (candidates.size() > 1) {
                JsonObject err = new JsonObject();
                err.addProperty("output_item_id", outputItemId);
                err.addProperty("candidate_count", candidates.size());
                JsonArray candidateIds = new JsonArray();
                for (RecipeHolder<CraftingRecipe> candidate : candidates) {
                    candidateIds.add(candidate.id().toString());
                }
                err.add("candidate_recipe_ids", candidateIds);
                throw new IllegalArgumentException("multiple recipes produce " + outputItemId
                        + "; call jei_lookup/include_recipes and retry with recipe_id. Candidates: "
                        + GSON.toJson(err));
            }
            recipe = candidates.get(0);
            grid = craftingGridFromRecipe(recipe.value());
        } else {
            throw new IllegalArgumentException("crafting requires recipe_id, crafting_grid, or output_item_id");
        }

        if (isEmptyGrid(grid)) {
            throw new IllegalArgumentException("crafting grid resolved to empty");
        }

        CraftingInput input = CraftingInput.of(3, 3, nonNullCraftingGrid(grid));
        if (!recipe.value().matches(input, level)) {
            throw new IllegalArgumentException("resolved crafting_grid does not match recipe " + recipe.id());
        }
        ItemStack output = recipe.value().assemble(input, level.registryAccess());
        if (output.isEmpty()) {
            output = recipe.value().getResultItem(level.registryAccess()).copy();
        }
        if (outputItemId != null && !outputItemId.isBlank() && !outputItemId.equals(itemId(output))) {
            throw new IllegalArgumentException("recipe " + recipe.id() + " produces " + itemId(output)
                    + ", not requested output " + outputItemId);
        }

        boolean allowSubstitutes = asBool(inv.arguments().get("allow_substitutes"));
        boolean allowFluidSubstitutes = asBool(inv.arguments().get("allow_fluid_substitutes"));
        Class<?> helper = Class.forName("appeng.api.crafting.PatternDetailsHelper");
        ItemStack encoded = (ItemStack) helper.getMethod("encodeCraftingPattern", RecipeHolder.class,
                ItemStack[].class, ItemStack.class, boolean.class, boolean.class)
                .invoke(null, recipe, grid, output, allowSubstitutes, allowFluidSubstitutes);

        JsonObject recipeInfo = new JsonObject();
        recipeInfo.addProperty("source", recipeIdArg == null || recipeIdArg.isBlank()
                ? "server_recipe_lookup_matching_jei_layout" : "recipe_id");
        recipeInfo.addProperty("id", recipe.id().toString());
        recipeInfo.addProperty("output", itemId(output));
        recipeInfo.add("crafting_grid", describeItemStackArray(grid));
        recipeInfo.addProperty("allow_substitutes", allowSubstitutes);
        recipeInfo.addProperty("allow_fluid_substitutes", allowFluidSubstitutes);
        return new EncodedAe2Pattern(encoded, "crafting", recipe.id().toString(), output, recipeInfo);
    }

    private static java.util.List<Ae2PatternContainerRef> collectAe2PatternContainers(ServerLevel level,
                                                                                       java.util.Set<BlockPos> anchors,
                                                                                       boolean includeHidden)
            throws Exception {
        Class<?> gridClass = Class.forName("appeng.api.networking.IGrid");
        Class<?> patternContainerClass = Class.forName("appeng.helpers.patternprovider.PatternContainer");
        java.lang.reflect.Method getMachineClasses = gridClass.getMethod("getMachineClasses");
        java.lang.reflect.Method getActiveMachines = gridClass.getMethod("getActiveMachines", Class.class);
        java.util.List<Ae2PatternContainerRef> out = new java.util.ArrayList<>();
        java.util.Set<Object> seenContainers = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

        for (BlockPos anchor : anchors) {
            Object grid = resolveAe2GridNear(level, anchor);
            if (grid != null) {
                Iterable<?> machineClasses = (Iterable<?>) getMachineClasses.invoke(grid);
                for (Object rawMachineClass : machineClasses) {
                    if (!(rawMachineClass instanceof Class<?> machineClass)
                            || !patternContainerClass.isAssignableFrom(machineClass)) {
                        continue;
                    }
                    Iterable<?> activeMachines = (Iterable<?>) getActiveMachines.invoke(grid, machineClass);
                    for (Object container : activeMachines) {
                        if (!seenContainers.add(container)) {
                            continue;
                        }
                        boolean visible = reflectBoolean(patternContainerClass, container, "isVisibleInTerminal",
                                true);
                        if (!visible && !includeHidden) {
                            continue;
                        }
                        Object inventory = patternContainerClass.getMethod("getTerminalPatternInventory")
                                .invoke(container);
                        out.add(new Ae2PatternContainerRef(anchor, out.size(), container, inventory));
                    }
                }
                continue;
            }

            for (BlockPos pos : positionsAtAndAdjacent(anchor)) {
                BlockEntity be = level.getBlockEntity(pos);
                if (be == null || !patternContainerClass.isInstance(be) || !seenContainers.add(be)) {
                    continue;
                }
                boolean visible = reflectBoolean(patternContainerClass, be, "isVisibleInTerminal", true);
                if (!visible && !includeHidden) {
                    continue;
                }
                Object inventory = patternContainerClass.getMethod("getTerminalPatternInventory").invoke(be);
                out.add(new Ae2PatternContainerRef(pos, out.size(), be, inventory));
            }
        }
        return out;
    }

    private static java.util.List<BlockPos> positionsAtAndAdjacent(BlockPos pos) {
        java.util.List<BlockPos> out = new java.util.ArrayList<>();
        out.add(pos);
        for (Direction direction : Direction.values()) {
            out.add(pos.relative(direction));
        }
        return out;
    }

    private static Ae2PatternWriteTarget chooseAe2PatternTarget(Level level,
                                                                java.util.List<Ae2PatternContainerRef> containers,
                                                                EncodedAe2Pattern encoded,
                                                                String targetMachineItemId,
                                                                String targetGroupName,
                                                                boolean replaceExisting,
                                                                Integer requestedContainer,
                                                                Integer requestedSlot) throws Exception {
        long startNs = System.nanoTime();
        boolean molecularPattern = isAe2MolecularAssemblerPattern(level, encoded.stack());
        String primaryOutputId = itemId(encoded.primaryOutput());
        agentDebug("chooseAe2PatternTarget: molecularPattern={} primaryOutputId='{}' containerCount={}",
                molecularPattern, primaryOutputId, containers.size());
        java.util.List<Ae2PatternContainerRef> candidates = new java.util.ArrayList<>();
        for (Ae2PatternContainerRef ref : containers) {
            if (requestedContainer != null && ref.index() != requestedContainer) {
                continue;
            }
            boolean molecularContainer = isAe2MolecularAssemblerContainer(ref.container());
            if (molecularPattern != molecularContainer) {
                continue;
            }
            if ((targetMachineItemId != null || targetGroupName != null)
                    && !ae2ContainerMatchesRequestedTarget(ref.container(), targetMachineItemId, targetGroupName)) {
                continue;
            }
            candidates.add(ref);
        }
        candidates.sort((a, b) -> Integer.compare(
                ae2ContainerMatchScore(b.container(), targetMachineItemId, targetGroupName, molecularPattern),
                ae2ContainerMatchScore(a.container(), targetMachineItemId, targetGroupName, molecularPattern)));
        agentDebug("chooseAe2PatternTarget: filtered to {} candidate container(s) after filter+sort", candidates.size());

        if (requestedSlot != null) {
            agentDebug("chooseAe2PatternTarget: requestedSlot={} — scanning candidates for that slot", requestedSlot);
            for (Ae2PatternContainerRef ref : candidates) {
                int size = inventorySize(ref.inventory());
                if (requestedSlot < 0 || requestedSlot >= size) {
                    continue;
                }
                ItemStack existing = inventoryStack(ref.inventory(), requestedSlot);
                if (existing.isEmpty()) {
                    return new Ae2PatternWriteTarget(ref, requestedSlot, false, "requested_empty_slot");
                }
                String existingOutput = ae2PatternPrimaryOutputId(level, existing);
                if (primaryOutputId.equals(existingOutput) && replaceExisting) {
                    return new Ae2PatternWriteTarget(ref, requestedSlot, true, "requested_matching_output_slot");
                }
                return null;
            }
            return null;
        }

        if (replaceExisting) {
            agentDebug("chooseAe2PatternTarget: replaceExisting=true — searching for existing slot matching primary output");
            for (Ae2PatternContainerRef ref : candidates) {
                int size = inventorySize(ref.inventory());
                for (int slot = 0; slot < size; slot++) {
                    ItemStack existing = inventoryStack(ref.inventory(), slot);
                    if (!existing.isEmpty() && primaryOutputId.equals(ae2PatternPrimaryOutputId(level, existing))) {
                        return new Ae2PatternWriteTarget(ref, slot, true, "existing_matching_primary_output");
                    }
                }
            }
            agentDebug("chooseAe2PatternTarget: no existing-matching slot found, falling back to empty-slot search");
        }

        for (Ae2PatternContainerRef ref : candidates) {
            int size = inventorySize(ref.inventory());
            for (int slot = 0; slot < size; slot++) {
                if (inventoryStack(ref.inventory(), slot).isEmpty()) {
                    String reason = targetMachineItemId != null || targetGroupName != null
                            ? "target_machine_matched_empty_slot"
                            : "ae2_pattern_type_matched_empty_slot";
                    long durMs = (System.nanoTime() - startNs) / 1_000_000L;
                    agentDebug("chooseAe2PatternTarget: empty slot found container={} slot={} durMs={}",
                            ref.index(), slot, durMs);
                    return new Ae2PatternWriteTarget(ref, slot, false, reason);
                }
            }
        }
        agentDebug("chooseAe2PatternTarget: no slot available in any candidate container");
        return null;
    }

    private static boolean ae2ContainerMatchesRequestedTarget(Object container, String targetMachineItemId,
                                                              String targetGroupName) {
        Object group = invokeNoArgQuiet(container, "getTerminalGroup");
        if (group == null) {
            return false;
        }
        String iconId = ae2GroupIconId(group);
        if (targetMachineItemId != null && targetMachineItemId.equals(iconId)) {
            return true;
        }
        String name = ae2GroupName(group);
        return targetGroupName != null && name.toLowerCase(java.util.Locale.ROOT)
                .contains(targetGroupName.toLowerCase(java.util.Locale.ROOT));
    }

    private static int ae2ContainerMatchScore(Object container, String targetMachineItemId, String targetGroupName,
                                              boolean molecularPattern) {
        int score = 0;
        if (isAe2MolecularAssemblerContainer(container) == molecularPattern) {
            score += 100;
        }
        Object group = invokeNoArgQuiet(container, "getTerminalGroup");
        if (group == null) {
            return score;
        }
        String iconId = ae2GroupIconId(group);
        if (targetMachineItemId != null && targetMachineItemId.equals(iconId)) {
            score += 1000;
        }
        String name = ae2GroupName(group);
        if (targetGroupName != null && name.toLowerCase(java.util.Locale.ROOT)
                .contains(targetGroupName.toLowerCase(java.util.Locale.ROOT))) {
            score += 500;
        }
        return score;
    }

    private static JsonArray summarizeAe2PatternWriteContainers(Level level,
                                                                 java.util.List<Ae2PatternContainerRef> containers,
                                                                 String targetMachineItemId,
                                                                 String targetGroupName,
                                                                 ItemStack encodedPattern) throws Exception {
        boolean molecularPattern = isAe2MolecularAssemblerPattern(level, encodedPattern);
        JsonArray out = new JsonArray();
        for (Ae2PatternContainerRef ref : containers) {
            JsonObject entry = new JsonObject();
            entry.addProperty("container_index", ref.index());
            entry.addProperty("inventory_size", inventorySize(ref.inventory()));
            entry.addProperty("empty_slots", countEmptySlots(ref.inventory()));
            entry.addProperty("molecular_assembler_target", isAe2MolecularAssemblerContainer(ref.container()));
            entry.addProperty("match_score", ae2ContainerMatchScore(ref.container(), targetMachineItemId,
                    targetGroupName, molecularPattern));
            Object group = invokeNoArgQuiet(ref.container(), "getTerminalGroup");
            if (group != null) {
                entry.add("group", describeAe2PatternGroup(group));
            }
            out.add(entry);
        }
        return out;
    }

    private static Object ae2GenericStackFromItemEntry(java.util.Map<String, Object> entry, String context)
            throws Exception {
        ItemStack stack = itemStackFromObject(entry, context, 1);
        long amount = asLong(entry.get("amount"), stack.getCount());
        if (amount <= 0) {
            throw new IllegalArgumentException(context + ": amount must be positive");
        }
        Class<?> aeItemKeyClass = Class.forName("appeng.api.stacks.AEItemKey");
        Object key = aeItemKeyClass.getMethod("of", ItemStack.class).invoke(null, stack);
        if (key == null) {
            throw new IllegalArgumentException(context + ": cannot create AE item key for " + itemId(stack));
        }
        Class<?> aeKeyClass = Class.forName("appeng.api.stacks.AEKey");
        Class<?> genericStackClass = Class.forName("appeng.api.stacks.GenericStack");
        return genericStackClass.getConstructor(aeKeyClass, long.class).newInstance(key, amount);
    }

    private static ItemStack itemStackFromObject(Object raw, String context, int defaultCount) {
        if (raw == null) {
            return ItemStack.EMPTY;
        }
        if (raw instanceof String s) {
            if (s.isBlank() || "minecraft:air".equals(s.trim())) {
                return ItemStack.EMPTY;
            }
            return itemStackFromId(s.trim(), defaultCount, null, context);
        }
        if (raw instanceof java.util.Map<?, ?> rawMap) {
            Object idRaw = rawMap.get("item_id");
            if (idRaw == null) {
                idRaw = rawMap.get("id");
            }
            if (idRaw == null) {
                throw new IllegalArgumentException(context + ": item_id is required");
            }
            int count = asInt(rawMap.get("count")) == null
                    ? (asInt(rawMap.get("amount")) == null ? defaultCount : asInt(rawMap.get("amount")))
                    : asInt(rawMap.get("count"));
            Integer dataValue = firstPresentInt(rawMap, "data_value", "data", "metadata", "damage");
            return itemStackFromId(idRaw.toString(), Math.max(1, count), dataValue, context);
        }
        throw new IllegalArgumentException(context + ": expected item id or {item_id, amount, data_value}");
    }

    private static ItemStack itemStackFromId(String id, int count, Integer dataValue, String context) {
        ResourceLocation location = ResourceLocation.parse(id);
        Item item = BuiltInRegistries.ITEM.getOptional(location).orElse(null);
        if (item == null || location.equals(BuiltInRegistries.ITEM.getDefaultKey())) {
            throw new IllegalArgumentException(context + ": unknown item id " + id);
        }
        ItemStack stack = new ItemStack(item, Math.max(1, count));
        if (dataValue != null) {
            if (dataValue < 0) {
                throw new IllegalArgumentException(context + ": data_value must be >= 0");
            }
            stack.setDamageValue(dataValue);
        }
        return stack;
    }

    private static ItemStack[] parseCraftingGrid(Object raw) {
        ItemStack[] grid = emptyCraftingGrid();
        java.util.List<Object> list = coerceListArg(raw);
        if (list.isEmpty()) {
            return grid;
        }
        if (list.size() != 9) {
            throw new IllegalArgumentException("crafting_grid must contain exactly 9 slots");
        }
        for (int i = 0; i < 9; i++) {
            grid[i] = itemStackFromObject(list.get(i), "crafting_grid[" + i + "]", 1);
        }
        return grid;
    }

    private static ItemStack[] emptyCraftingGrid() {
        ItemStack[] grid = new ItemStack[9];
        java.util.Arrays.fill(grid, ItemStack.EMPTY);
        return grid;
    }

    private static boolean isEmptyGrid(ItemStack[] grid) {
        for (ItemStack stack : grid) {
            if (stack != null && !stack.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Build a 9-slot {@link NonNullList} from a 3x3 crafting grid. {@link
     * NonNullList#of(Object, Object...)} treats the second argument as a single
     * varargs element, so {@code NonNullList.of(ItemStack.EMPTY, grid)} would
     * create a 2-entry list with the whole array as the second element and blow
     * up {@link CraftingInput#of} with a {@link ClassCastException}. This
     * helper materialises the grid into a fixed-size list that the recipe
     * matcher actually expects.
     */
    private static NonNullList<ItemStack> nonNullCraftingGrid(ItemStack[] grid) {
        NonNullList<ItemStack> out = NonNullList.withSize(9, ItemStack.EMPTY);
        if (grid != null) {
            for (int i = 0; i < Math.min(9, grid.length); i++) {
                ItemStack stack = grid[i];
                out.set(i, stack == null ? ItemStack.EMPTY : stack);
            }
        }
        return out;
    }

    private static ItemStack[] craftingGridFromRecipe(Recipe<?> recipe) {
        NonNullList<Ingredient> ingredients = ensure3By3CraftingMatrix(recipe);
        ItemStack[] grid = emptyCraftingGrid();
        for (int i = 0; i < ingredients.size() && i < 9; i++) {
            Ingredient ingredient = ingredients.get(i);
            if (!ingredient.isEmpty() && ingredient.getItems().length > 0) {
                grid[i] = ingredient.getItems()[0].copy();
                grid[i].setCount(1);
            }
        }
        return grid;
    }

    private static NonNullList<Ingredient> ensure3By3CraftingMatrix(Recipe<?> recipe) {
        NonNullList<Ingredient> ingredients = recipe.getIngredients();
        NonNullList<Ingredient> expanded = NonNullList.withSize(9, Ingredient.EMPTY);
        if (ingredients.size() > 9) {
            throw new IllegalArgumentException("recipe has more than 9 ingredients");
        }
        if (recipe instanceof ShapedRecipe shapedRecipe) {
            int width = shapedRecipe.getWidth();
            int height = shapedRecipe.getHeight();
            if (width > 3 || height > 3) {
                throw new IllegalArgumentException("recipe is larger than 3x3");
            }
            for (int h = 0; h < height; h++) {
                for (int w = 0; w < width; w++) {
                    expanded.set(w + h * 3, ingredients.get(w + h * width));
                }
            }
        } else {
            for (int i = 0; i < ingredients.size(); i++) {
                expanded.set(i, ingredients.get(i));
            }
        }
        return expanded;
    }

    private static java.util.List<RecipeHolder<CraftingRecipe>> findCraftingRecipesForOutput(ServerLevel level,
                                                                                              String outputItemId) {
        java.util.List<RecipeHolder<CraftingRecipe>> out = new java.util.ArrayList<>();
        for (RecipeHolder<CraftingRecipe> holder : level.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
            ItemStack result = holder.value().getResultItem(level.registryAccess());
            if (!result.isEmpty() && outputItemId.equals(itemId(result))) {
                out.add(holder);
            }
        }
        return out;
    }

    private static java.util.List<Object> coerceListArg(Object raw) {
        java.util.List<Object> out = new java.util.ArrayList<>();
        if (raw == null) {
            return out;
        }
        if (raw instanceof java.util.List<?> list) {
            out.addAll(list);
            return out;
        }
        try {
            com.google.gson.JsonElement el = JsonParser.parseString(raw.toString());
            if (el.isJsonArray()) {
                for (com.google.gson.JsonElement child : el.getAsJsonArray()) {
                    if (child.isJsonObject()) {
                        out.add(GSON.fromJson(child, java.util.Map.class));
                    } else if (child.isJsonNull()) {
                        out.add(null);
                    } else if (child.isJsonPrimitive()) {
                        out.add(child.getAsString());
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static JsonArray describePatternItemEntries(java.util.List<java.util.Map<String, Object>> entries) {
        JsonArray out = new JsonArray();
        for (java.util.Map<String, Object> entry : entries) {
            JsonObject item = new JsonObject();
            item.addProperty("item_id", normalizeId(asString(entry.get("item_id"))));
            item.addProperty("amount", asLong(entry.get("amount"), 1));
            Integer dataValue = firstPresentInt(entry, "data_value", "data", "metadata", "damage");
            if (dataValue != null) {
                item.addProperty("data_value", dataValue);
            }
            out.add(item);
        }
        return out;
    }

    private static JsonArray describeItemStackArray(ItemStack[] stacks) throws Exception {
        JsonArray out = new JsonArray();
        for (int i = 0; i < stacks.length; i++) {
            JsonObject entry = describeItemStack(stacks[i] == null ? ItemStack.EMPTY : stacks[i]);
            entry.addProperty("slot", i);
            out.add(entry);
        }
        return out;
    }

    private static ItemStack inventoryStack(Object inventory, int slot) throws Exception {
        Object rawStack = inventory.getClass().getMethod("getStackInSlot", int.class).invoke(inventory, slot);
        return rawStack instanceof ItemStack stack ? stack : ItemStack.EMPTY;
    }

    private static int countEmptySlots(Object inventory) throws Exception {
        int size = inventorySize(inventory);
        int count = 0;
        for (int slot = 0; slot < size; slot++) {
            if (inventoryStack(inventory, slot).isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private static boolean isAe2MolecularAssemblerPattern(Level level, ItemStack stack) throws Exception {
        Class<?> helper = Class.forName("appeng.api.crafting.PatternDetailsHelper");
        Object details = helper.getMethod("decodePattern", ItemStack.class, Level.class).invoke(null, stack, level);
        return details != null
                && Class.forName("appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern")
                .isInstance(details);
    }

    private static boolean isAe2MolecularAssemblerContainer(Object container) {
        Object group = invokeNoArgQuiet(container, "getTerminalGroup");
        String iconId = ae2GroupIconId(group);
        return "ae2:molecular_assembler".equals(iconId);
    }

    private static String ae2GroupIconId(Object group) {
        if (group == null) {
            return "";
        }
        Object icon = invokeNoArgQuiet(group, "icon");
        if (icon == null) {
            return "";
        }
        Object id = invokeNoArgQuiet(icon, "getId");
        return id == null ? "" : id.toString();
    }

    private static String ae2GroupName(Object group) {
        if (group == null) {
            return "";
        }
        Object name = invokeNoArgQuiet(group, "name");
        return name == null ? "" : componentText(name);
    }

    private static String ae2PatternPrimaryOutputId(Level level, ItemStack stack) throws Exception {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        Class<?> helper = Class.forName("appeng.api.crafting.PatternDetailsHelper");
        Object details = helper.getMethod("decodePattern", ItemStack.class, Level.class).invoke(null, stack, level);
        if (details == null) {
            return "";
        }
        Class<?> detailsClass = Class.forName("appeng.api.crafting.IPatternDetails");
        Object primaryOutput = detailsClass.getMethod("getPrimaryOutput").invoke(details);
        if (primaryOutput == null) {
            return "";
        }
        Object what = invokeNoArgQuiet(primaryOutput, "what");
        if (what == null) {
            return "";
        }
        Object id = invokeNoArgQuiet(what, "getId");
        return id == null ? "" : id.toString();
    }

    private static void markAe2PatternContainerChanged(Level level, Object container) {
        long startNs = System.nanoTime();
        agentDebug("markAe2PatternContainerChanged: container class='{}'",
                container == null ? "<null>" : container.getClass().getName());
        invokeNoArgQuiet(container, "saveChanges");
        invokeNoArgQuiet(container, "setChanged");
        if (container instanceof BlockEntity be) {
            be.setChanged();
            agentDebug("markAe2PatternContainerChanged: BlockEntity.setChanged() at pos={}", be.getBlockPos());
            level.sendBlockUpdated(be.getBlockPos(), be.getBlockState(), be.getBlockState(), 3);
        }
        long durMs = (System.nanoTime() - startNs) / 1_000_000L;
        agentDebug("markAe2PatternContainerChanged: done durMs={}", durMs);
    }

    private static String normalizeId(String id) {
        if (id == null) {
            return null;
        }
        String trimmed = id.trim();
        return trimmed.isEmpty() ? null : ResourceLocation.parse(trimmed).toString();
    }

    private static String itemId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "minecraft:air";
        }
        return stack.getItem().builtInRegistryHolder().key().location().toString();
    }

    private static Object resolveAe2GridNear(ServerLevel level, BlockPos anchor) throws Exception {
        Object grid = resolveAe2GridFromBlockEntity(level.getBlockEntity(anchor));
        if (grid != null) {
            return grid;
        }
        for (Direction direction : Direction.values()) {
            grid = resolveAe2GridFromBlockEntity(level.getBlockEntity(anchor.relative(direction)));
            if (grid != null) {
                return grid;
            }
        }
        return null;
    }

    private static Object resolveAe2GridFromBlockEntity(BlockEntity be) throws Exception {
        if (be == null) {
            return null;
        }

        Object directGrid = invokeNoArg(be, "getGrid");
        if (isAe2Grid(directGrid)) {
            return directGrid;
        }

        Object mainNode = invokeNoArg(be, "getMainNode");
        Object grid = gridFromAe2Node(mainNode);
        if (grid != null) {
            return grid;
        }

        Object gridNode = invokeNoArg(be, "getGridNode");
        grid = gridFromAe2Node(gridNode);
        if (grid != null) {
            return grid;
        }

        for (Direction direction : Direction.values()) {
            Object sideNode = invokeOneArg(be, "getGridNode", Direction.class, direction);
            grid = gridFromAe2Node(sideNode);
            if (grid != null) {
                return grid;
            }
        }

        Object cableBus = invokeNoArg(be, "getCableBus");
        if (cableBus != null) {
            for (Direction direction : Direction.values()) {
                Object sideNode = invokeOneArg(cableBus, "getGridNode", Direction.class, direction);
                grid = gridFromAe2Node(sideNode);
                if (grid != null) {
                    return grid;
                }
            }
        }

        return null;
    }

    private static Object gridFromAe2Node(Object nodeLike) throws Exception {
        if (nodeLike == null) {
            return null;
        }
        Object grid = invokeNoArg(nodeLike, "getGrid");
        if (isAe2Grid(grid)) {
            return grid;
        }
        Object node = invokeNoArg(nodeLike, "getNode");
        if (node != null && node != nodeLike) {
            grid = invokeNoArg(node, "getGrid");
            if (isAe2Grid(grid)) {
                return grid;
            }
        }
        return null;
    }

    private static boolean isAe2Grid(Object grid) throws ClassNotFoundException {
        return grid != null && Class.forName("appeng.api.networking.IGrid").isInstance(grid);
    }

    private static JsonObject describeAe2GridPatterns(Level level, BlockPos anchor, Object grid,
                                                       boolean includeDetails, boolean includeHidden,
                                                       int[] remainingPatterns) throws Exception {
        Class<?> gridClass = Class.forName("appeng.api.networking.IGrid");
        Class<?> patternContainerClass = Class.forName("appeng.helpers.patternprovider.PatternContainer");
        java.lang.reflect.Method getMachineClasses = gridClass.getMethod("getMachineClasses");
        java.lang.reflect.Method getActiveMachines = gridClass.getMethod("getActiveMachines", Class.class);

        JsonObject network = new JsonObject();
        network.addProperty("anchor_x", anchor.getX());
        network.addProperty("anchor_y", anchor.getY());
        network.addProperty("anchor_z", anchor.getZ());
        network.addProperty("grid_class", grid.getClass().getName());
        Object size = invokeNoArg(grid, "size");
        if (size instanceof Number n) {
            network.addProperty("grid_nodes", n.intValue());
        }

        JsonArray containers = new JsonArray();
        int containerCount = 0;
        int visibleSkipped = 0;
        int patternCount = 0;

        Iterable<?> machineClasses = (Iterable<?>) getMachineClasses.invoke(grid);
        for (Object rawMachineClass : machineClasses) {
            if (!(rawMachineClass instanceof Class<?> machineClass)
                    || !patternContainerClass.isAssignableFrom(machineClass)) {
                continue;
            }

            Iterable<?> activeMachines = (Iterable<?>) getActiveMachines.invoke(grid, machineClass);
            for (Object container : activeMachines) {
                containerCount++;
                boolean visible = reflectBoolean(patternContainerClass, container, "isVisibleInTerminal", true);
                if (!visible && !includeHidden) {
                    visibleSkipped++;
                    continue;
                }
                JsonObject containerJson = describeAe2PatternContainer(level, patternContainerClass, container,
                        includeDetails, remainingPatterns);
                containerJson.addProperty("visible_in_pattern_access_terminal", visible);
                patternCount += containerJson.get("pattern_count").getAsInt();
                containers.add(containerJson);
            }
        }

        network.add("containers", containers);
        network.addProperty("container_count", containerCount);
        network.addProperty("visible_container_count", containers.size());
        network.addProperty("hidden_container_skipped", visibleSkipped);
        network.addProperty("pattern_count", patternCount);
        return network;
    }

    private static JsonObject describeSingleAe2PatternContainer(Level level, BlockPos anchor, boolean includeDetails,
                                                                boolean includeHidden, int[] remainingPatterns)
            throws Exception {
        Class<?> patternContainerClass = Class.forName("appeng.helpers.patternprovider.PatternContainer");
        BlockEntity be = level.getBlockEntity(anchor);
        if (be == null || !patternContainerClass.isInstance(be)) {
            return null;
        }
        boolean visible = reflectBoolean(patternContainerClass, be, "isVisibleInTerminal", true);
        if (!visible && !includeHidden) {
            return null;
        }

        JsonObject network = new JsonObject();
        network.addProperty("anchor_x", anchor.getX());
        network.addProperty("anchor_y", anchor.getY());
        network.addProperty("anchor_z", anchor.getZ());
        network.addProperty("source", "single_pattern_container_fallback");

        JsonObject container = describeAe2PatternContainer(level, patternContainerClass, be, includeDetails,
                remainingPatterns);
        container.addProperty("visible_in_pattern_access_terminal", visible);

        JsonArray containers = new JsonArray();
        containers.add(container);
        network.add("containers", containers);
        network.addProperty("container_count", 1);
        network.addProperty("visible_container_count", 1);
        network.addProperty("hidden_container_skipped", 0);
        network.addProperty("pattern_count", container.get("pattern_count").getAsInt());
        return network;
    }

    private static JsonObject describeAe2PatternContainer(Level level, Class<?> patternContainerClass,
                                                          Object container, boolean includeDetails,
                                                          int[] remainingPatterns) throws Exception {
        JsonObject out = new JsonObject();
        out.addProperty("container_class", container.getClass().getName());
        Object sortOrder = patternContainerClass.getMethod("getTerminalSortOrder").invoke(container);
        if (sortOrder instanceof Number n) {
            out.addProperty("sort_order", n.longValue());
        }
        Object group = patternContainerClass.getMethod("getTerminalGroup").invoke(container);
        if (group != null) {
            out.add("group", describeAe2PatternGroup(group));
        }

        Object inventory = patternContainerClass.getMethod("getTerminalPatternInventory").invoke(container);
        JsonArray patterns = new JsonArray();
        int patternCount = addAe2InventoryPatterns(level, inventory, includeDetails, remainingPatterns, patterns);
        out.addProperty("inventory_class", inventory == null ? "" : inventory.getClass().getName());
        out.addProperty("inventory_size", inventorySize(inventory));
        out.addProperty("pattern_count", patternCount);
        out.add("patterns", patterns);
        return out;
    }

    private static JsonObject describeAe2PatternGroup(Object group) {
        JsonObject out = new JsonObject();
        out.addProperty("group_class", group.getClass().getName());
        Object name = invokeNoArgQuiet(group, "name");
        if (name != null) {
            out.addProperty("name", componentText(name));
        }
        Object icon = invokeNoArgQuiet(group, "icon");
        if (icon != null) {
            out.add("icon", describeAe2Key(icon));
        }
        Object tooltip = invokeNoArgQuiet(group, "tooltip");
        if (tooltip instanceof Iterable<?> iterable) {
            JsonArray lines = new JsonArray();
            for (Object line : iterable) {
                lines.add(componentText(line));
            }
            out.add("tooltip", lines);
        }
        return out;
    }

    private static int addAe2InventoryPatterns(Level level, Object inventory, boolean includeDetails,
                                               int[] remainingPatterns, JsonArray out) throws Exception {
        if (inventory == null) {
            return 0;
        }
        java.lang.reflect.Method sizeMethod = inventory.getClass().getMethod("size");
        java.lang.reflect.Method getStack = inventory.getClass().getMethod("getStackInSlot", int.class);
        int size = ((Number) sizeMethod.invoke(inventory)).intValue();
        int patternCount = 0;
        for (int slot = 0; slot < size; slot++) {
            Object rawStack = getStack.invoke(inventory, slot);
            if (!(rawStack instanceof ItemStack stack) || stack.isEmpty()) {
                continue;
            }
            patternCount++;
            if (remainingPatterns[0] <= 0) {
                continue;
            }
            remainingPatterns[0]--;

            JsonObject pattern = describeItemStack(stack);
            pattern.addProperty("slot", slot);
            if (includeDetails) {
                JsonObject details = decodeAe2PatternDetails(level, stack);
                if (details != null) {
                    pattern.add("details", details);
                }
            }
            out.add(pattern);
        }
        return patternCount;
    }

    private static int inventorySize(Object inventory) {
        if (inventory == null) {
            return 0;
        }
        try {
            Object size = inventory.getClass().getMethod("size").invoke(inventory);
            return size instanceof Number n ? n.intValue() : 0;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static JsonObject describeItemStack(ItemStack stack) throws Exception {
        JsonObject out = new JsonObject();
        out.addProperty("item_id", stack.getItem().builtInRegistryHolder().key().location().toString());
        out.addProperty("display_name", stack.getHoverName().getString());
        out.addProperty("count", stack.getCount());
        out.addProperty("data_value", stack.getDamageValue());
        out.addProperty("has_custom_components", !stack.isComponentsPatchEmpty());
        out.addProperty("raw", shortString(stack.toString(), 240));
        try {
            Class<?> helper = Class.forName("appeng.api.crafting.PatternDetailsHelper");
            out.addProperty("encoded_pattern",
                    (Boolean) helper.getMethod("isEncodedPattern", ItemStack.class).invoke(null, stack));
        } catch (Exception e) {
            out.addProperty("encoded_pattern_error", e.getClass().getSimpleName());
        }
        return out;
    }

    private static JsonObject decodeAe2PatternDetails(Level level, ItemStack stack) throws Exception {
        Class<?> helper = Class.forName("appeng.api.crafting.PatternDetailsHelper");
        Object details = helper.getMethod("decodePattern", ItemStack.class, Level.class).invoke(null, stack, level);
        if (details == null) {
            return null;
        }

        Class<?> detailsClass = Class.forName("appeng.api.crafting.IPatternDetails");
        JsonObject out = new JsonObject();
        out.addProperty("details_class", details.getClass().getName());
        Object definition = detailsClass.getMethod("getDefinition").invoke(details);
        if (definition != null) {
            out.add("definition", describeAe2Key(definition));
        }
        Object primaryOutput = detailsClass.getMethod("getPrimaryOutput").invoke(details);
        if (primaryOutput != null) {
            out.add("primary_output", describeGenericStack(primaryOutput));
        }
        Object outputs = detailsClass.getMethod("getOutputs").invoke(details);
        out.add("outputs", describeGenericStackIterable(outputs, 16));
        Object inputs = detailsClass.getMethod("getInputs").invoke(details);
        out.add("inputs", describeAe2PatternInputs(inputs));
        Object supportsExternal = detailsClass.getMethod("supportsPushInputsToExternalInventory").invoke(details);
        if (supportsExternal instanceof Boolean b) {
            out.addProperty("supports_external_inventory", b);
        }
        return out;
    }

    private static JsonArray describeAe2PatternInputs(Object inputs) throws Exception {
        JsonArray out = new JsonArray();
        if (inputs == null || !inputs.getClass().isArray()) {
            return out;
        }
        Class<?> inputClass = Class.forName("appeng.api.crafting.IPatternDetails$IInput");
        java.lang.reflect.Method getMultiplier = inputClass.getMethod("getMultiplier");
        java.lang.reflect.Method getPossibleInputs = inputClass.getMethod("getPossibleInputs");
        int size = Math.min(java.lang.reflect.Array.getLength(inputs), 16);
        for (int i = 0; i < size; i++) {
            Object input = java.lang.reflect.Array.get(inputs, i);
            JsonObject inputJson = new JsonObject();
            inputJson.addProperty("index", i);
            Object multiplier = getMultiplier.invoke(input);
            if (multiplier instanceof Number n) {
                inputJson.addProperty("multiplier", n.longValue());
            }
            Object possibleInputs = getPossibleInputs.invoke(input);
            inputJson.add("possible", describeGenericStackArray(possibleInputs, 16));
            out.add(inputJson);
        }
        return out;
    }

    private static JsonArray describeGenericStackIterable(Object stacks, int limit) {
        JsonArray out = new JsonArray();
        if (stacks instanceof Iterable<?> iterable) {
            int count = 0;
            for (Object stack : iterable) {
                if (count++ >= limit) {
                    break;
                }
                out.add(describeGenericStack(stack));
            }
        }
        return out;
    }

    private static JsonArray describeGenericStackArray(Object stacks, int limit) {
        JsonArray out = new JsonArray();
        if (stacks == null || !stacks.getClass().isArray()) {
            return out;
        }
        int size = Math.min(java.lang.reflect.Array.getLength(stacks), limit);
        for (int i = 0; i < size; i++) {
            out.add(describeGenericStack(java.lang.reflect.Array.get(stacks, i)));
        }
        return out;
    }

    private static JsonObject describeGenericStack(Object stack) {
        JsonObject out = new JsonObject();
        if (stack == null) {
            out.addProperty("raw", "null");
            return out;
        }
        Object amount = invokeNoArgQuiet(stack, "amount");
        if (amount instanceof Number n) {
            out.addProperty("amount", n.longValue());
        }
        Object what = invokeNoArgQuiet(stack, "what");
        if (what != null) {
            out.add("what", describeAe2Key(what));
        }
        out.addProperty("raw", shortString(stack.toString(), 240));
        return out;
    }

    private static JsonObject describeAe2Key(Object key) {
        JsonObject out = new JsonObject();
        out.addProperty("class", key.getClass().getName());
        Object id = invokeNoArgQuiet(key, "getId");
        if (id != null) {
            out.addProperty("id", id.toString());
        }
        Object displayName = invokeNoArgQuiet(key, "getDisplayName");
        if (displayName != null) {
            out.addProperty("display_name", componentText(displayName));
        }
        out.addProperty("raw", shortString(key.toString(), 240));
        return out;
    }

    private static Object invokeNoArg(Object target, String method) throws Exception {
        if (target == null) {
            return null;
        }
        try {
            return target.getClass().getMethod(method).invoke(target);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static Object invokeOneArg(Object target, String method, Class<?> argType, Object arg) throws Exception {
        if (target == null) {
            return null;
        }
        try {
            return target.getClass().getMethod(method, argType).invoke(target, arg);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static Object invokeNoArgQuiet(Object target, String method) {
        try {
            return invokeNoArg(target, method);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean reflectBoolean(Class<?> declaringClass, Object target, String method, boolean fallback) {
        try {
            Object value = declaringClass.getMethod(method).invoke(target);
            return value instanceof Boolean b ? b : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String componentText(Object component) {
        if (component instanceof net.minecraft.network.chat.Component c) {
            return c.getString();
        }
        return shortString(String.valueOf(component), 240);
    }

    private static String shortString(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + "...";
    }

    private static Integer asInt(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    private static Integer firstPresentInt(java.util.Map<?, ?> values, String... keys) {
        for (String key : keys) {
            if (values.containsKey(key)) {
                Integer value = asInt(values.get(key));
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    private static long asLong(Object o, long fallback) {
        if (o == null) return fallback;
        if (o instanceof Number n) return n.longValue();
        if (o instanceof String s) {
            try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return fallback; }
        }
        return fallback;
    }

    private static boolean asBool(Object o) {
        if (o == null) return false;
        if (o instanceof Boolean b) return b;
        if (o instanceof String s) return Boolean.parseBoolean(s.trim());
        return false;
    }

    /** Boolean arg with a default when the key is missing. A present-but-
     *  empty / unparseable value still falls back to {@code defaultValue}
     *  via {@link #asBool(Object)}'s lenient parsing. */
    private static boolean asBoolWithDefault(Map<String, Object> args, String key, boolean defaultValue) {
        if (args == null || !args.containsKey(key)) return defaultValue;
        return asBool(args.get(key));
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }

    private static String errorJson(String message) {
        JsonObject out = new JsonObject();
        out.addProperty("error", message);
        return GSON.toJson(out);
    }

    private static boolean agentDebug() {
        try {
            return LLMEraConfig.DEBUG_AGENT_TOOLS.get();
        } catch (Exception | NoSuchFieldError ignored) {
            return true;
        }
    }

    private static void agentDebug(String format, Object... args) {
        if (agentDebug()) LLMEra.LOGGER.info("[llmera_ae2:agent] " + format, args);
    }

    private static void agentWarn(String format, Object... args) {
        LLMEra.LOGGER.warn("[llmera_ae2:agent] " + format, args);
    }
}
