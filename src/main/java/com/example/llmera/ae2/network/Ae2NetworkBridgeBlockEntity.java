package com.example.llmera.ae2.network;

import appeng.api.networking.GridFlags;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.networking.IManagedGridNode;
import com.example.llmera.ae2.LLMEraAe2;
import com.example.llmera.network.CodeNetworkMember;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.EnumSet;

public final class Ae2NetworkBridgeBlockEntity extends BlockEntity
        implements CodeNetworkMember, IInWorldGridNodeHost {
    public static final int CHANNEL_DEMAND = 8;
    private static final int EXPOSED_NODE = 0;
    private static final IGridNodeListener<Ae2NetworkBridgeBlockEntity> NODE_LISTENER =
            (bridge, node) -> bridge.setChanged();

    private final IManagedGridNode[] ae2Nodes = new IManagedGridNode[CHANNEL_DEMAND];

    public Ae2NetworkBridgeBlockEntity(BlockPos pos, BlockState state) {
        super(LLMEraAe2.NETWORK_BRIDGE.get(), pos, state);
        for (int i = 0; i < ae2Nodes.length; i++) {
            IManagedGridNode node = GridHelper.createManagedNode(this, NODE_LISTENER)
                    .setFlags(GridFlags.REQUIRE_CHANNEL)
                    .setTagName("ae2BridgeNode" + i);
            if (i == EXPOSED_NODE) {
                node.setInWorldNode(true).setExposedOnSides(EnumSet.allOf(Direction.class));
            } else {
                node.setInWorldNode(false);
            }
            ae2Nodes[i] = node;
        }
        GridHelper.onFirstTick(this, Ae2NetworkBridgeBlockEntity::createAe2Nodes);
    }

    public Component getDisplayName() {
        return Component.translatable("block.llmera.ae2_network_bridge");
    }

    @Override
    public IGridNode getGridNode(Direction direction) {
        // Every side exposes the same node. Multiple adjacent AE2 grids therefore connect as one grid.
        return ae2Nodes[EXPOSED_NODE].getNode();
    }

    public boolean isAe2Online() {
        IGridNode exposedNode = ae2Nodes[EXPOSED_NODE].getNode();
        return exposedNode != null
                && hasPhysicalAe2Connection()
                && getAe2ChannelUsed() == CHANNEL_DEMAND;
    }

    public boolean hasPhysicalAe2Connection() {
        IGridNode exposedNode = ae2Nodes[EXPOSED_NODE].getNode();
        return exposedNode != null && !exposedNode.getInWorldConnections().isEmpty();
    }

    public int getAe2ChannelUsed() {
        int used = 0;
        for (IManagedGridNode managedNode : ae2Nodes) {
            IGridNode node = managedNode.getNode();
            if (managedNode.isReady() && node != null
                    && node.meetsChannelRequirements() && node.getUsedChannels() > 0) {
                used++;
            }
        }
        return used;
    }

    public int getAe2ChannelCapacity() {
        return CHANNEL_DEMAND;
    }

    private void createAe2Nodes() {
        if (level == null || level.isClientSide || ae2Nodes[EXPOSED_NODE].isReady()) {
            return;
        }
        for (IManagedGridNode node : ae2Nodes) {
            node.create(level, worldPosition);
        }
        IGridNode exposedNode = ae2Nodes[EXPOSED_NODE].getNode();
        for (int i = 1; i < ae2Nodes.length; i++) {
            GridHelper.createConnection(exposedNode, ae2Nodes[i].getNode());
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.loadAdditional(tag, provider);
        for (IManagedGridNode node : ae2Nodes) {
            node.loadFromNBT(tag);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.saveAdditional(tag, provider);
        for (IManagedGridNode node : ae2Nodes) {
            node.saveToNBT(tag);
        }
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        destroyAe2Nodes();
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        destroyAe2Nodes();
    }

    @Override
    public void clearRemoved() {
        super.clearRemoved();
        GridHelper.onFirstTick(this, Ae2NetworkBridgeBlockEntity::createAe2Nodes);
    }

    private void destroyAe2Nodes() {
        for (IManagedGridNode node : ae2Nodes) {
            node.destroy();
        }
    }

    @Override
    public int channelDemand() {
        return CHANNEL_DEMAND;
    }
}
