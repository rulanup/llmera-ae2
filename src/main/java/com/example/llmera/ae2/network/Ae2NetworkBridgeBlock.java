package com.example.llmera.ae2.network;

import com.example.llmera.network.CodeNetworkMember;
import com.example.llmera.network.CodeNetworkScanner;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;

public final class Ae2NetworkBridgeBlock extends Block implements EntityBlock, CodeNetworkMember {
    public Ae2NetworkBridgeBlock() {
        super(Properties.of().mapColor(MapColor.COLOR_PURPLE).strength(2.5f));
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new Ae2NetworkBridgeBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hitResult) {
        if (!level.isClientSide && level instanceof ServerLevel serverLevel) {
            CodeNetworkScanner.NetworkView view = CodeNetworkScanner.scan(serverLevel, pos);
            Ae2NetworkBridgeBlockEntity bridge = serverLevel.getBlockEntity(pos)
                    instanceof Ae2NetworkBridgeBlockEntity blockEntity ? blockEntity : null;
            boolean coreOnline = view.memberHasChannel(pos);
            int ae2Used = bridge == null ? 0 : bridge.getAe2ChannelUsed();
            int ae2Capacity = bridge == null ? Ae2NetworkBridgeBlockEntity.CHANNEL_DEMAND
                    : bridge.getAe2ChannelCapacity();
            player.displayClientMessage(Component.literal("AE2 Network Bridge: "
                    + "core code " + (coreOnline ? "online" : "offline")
                    + " (" + (coreOnline ? Ae2NetworkBridgeBlockEntity.CHANNEL_DEMAND : 0) + "/"
                    + Ae2NetworkBridgeBlockEntity.CHANNEL_DEMAND + " channels), AE2 "
                    + (bridge != null && bridge.isAe2Online() ? "online" : "offline")
                    + " (" + ae2Used + "/" + ae2Capacity + " channels)"), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
