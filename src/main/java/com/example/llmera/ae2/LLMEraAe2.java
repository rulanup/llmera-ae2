package com.example.llmera.ae2;

import appeng.api.AECapabilities;
import com.example.llmera.ae2.llm.Ae2ToolExtension;
import com.example.llmera.ae2.network.Ae2NetworkBridgeBlock;
import com.example.llmera.ae2.network.Ae2NetworkBridgeBlockEntity;
import com.example.llmera.llm.ToolExtensionRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod(LLMEraAe2.MODID)
public final class LLMEraAe2 {
    public static final String MODID = "llmera_ae2";

    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks("llmera");
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems("llmera");
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(BuiltInRegistries.BLOCK_ENTITY_TYPE, "llmera");
    private static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    public static final DeferredBlock<Ae2NetworkBridgeBlock> NETWORK_BRIDGE_BLOCK =
            BLOCKS.register("ae2_network_bridge", Ae2NetworkBridgeBlock::new);
    public static final DeferredItem<BlockItem> NETWORK_BRIDGE_ITEM =
            ITEMS.registerSimpleBlockItem("ae2_network_bridge", NETWORK_BRIDGE_BLOCK);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<Ae2NetworkBridgeBlockEntity>> NETWORK_BRIDGE =
            BLOCK_ENTITIES.register("ae2_network_bridge", () -> BlockEntityType.Builder.of(
                    Ae2NetworkBridgeBlockEntity::new, NETWORK_BRIDGE_BLOCK.get()).build(null));
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN_TAB =
            CREATIVE_TABS.register("main", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.llmera_ae2"))
                    .icon(() -> new ItemStack(NETWORK_BRIDGE_ITEM.get()))
                    .displayItems((params, output) -> output.accept(NETWORK_BRIDGE_ITEM.get()))
                    .build());

    public LLMEraAe2(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITIES.register(modEventBus);
        CREATIVE_TABS.register(modEventBus);
        modEventBus.addListener(LLMEraAe2::registerCapabilities);
        ToolExtensionRegistry.register(Ae2ToolExtension.extension());
    }

    private static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(AECapabilities.IN_WORLD_GRID_NODE_HOST, NETWORK_BRIDGE.get(),
                (bridge, context) -> bridge);
    }
}
