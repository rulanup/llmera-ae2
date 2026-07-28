package com.example.llmera.ae2.jade;

import com.example.llmera.ae2.network.Ae2NetworkBridgeBlockEntity;
import com.example.llmera.network.CodeNetworkScanner;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;
import snownee.jade.api.theme.IThemeHelper;

public enum Ae2NetworkStatusProvider implements IBlockComponentProvider, IServerDataProvider<BlockAccessor> {
    INSTANCE;

    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("llmera_ae2", "network_status");
    private static final String DATA_KEY = ID.toString();

    @Override
    public void appendServerData(CompoundTag data, BlockAccessor accessor) {
        if (!(accessor.getLevel() instanceof ServerLevel level)
                || !(accessor.getBlockEntity() instanceof Ae2NetworkBridgeBlockEntity bridge)) {
            return;
        }

        CodeNetworkScanner.NetworkView view = CodeNetworkScanner.scan(level, accessor.getPosition());
        CompoundTag status = new CompoundTag();
        boolean codeAssigned = view.memberHasChannel(accessor.getPosition());
        boolean ae2Online = bridge.isAe2Online();

        status.putBoolean("CodeControllerPresent", view.controller() != null);
        status.putBoolean("CodePowered", view.powered());
        status.putInt("CodeChannelAssigned", codeAssigned
                ? Ae2NetworkBridgeBlockEntity.CHANNEL_DEMAND : 0);
        status.putInt("CodeChannelTotal", Ae2NetworkBridgeBlockEntity.CHANNEL_DEMAND);
        status.putBoolean("Ae2Online", ae2Online);
        status.putBoolean("Operational", view.powered() && codeAssigned && ae2Online);
        status.putInt("Ae2ChannelUsed", bridge.getAe2ChannelUsed());
        status.putInt("Ae2ChannelCapacity", bridge.getAe2ChannelCapacity());
        status.putBoolean("Ae2PhysicalConnected", bridge.hasPhysicalAe2Connection());
        status.putBoolean("ScanTruncated", view.truncated());
        data.put(DATA_KEY, status);
    }

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        CompoundTag data = accessor.getServerData();
        if (!data.contains(DATA_KEY)) {
            return;
        }
        CompoundTag status = data.getCompound(DATA_KEY);

        IThemeHelper theme = IThemeHelper.get();
        Component codeState;
        if (!status.getBoolean("CodeControllerPresent")) {
            codeState = theme.danger(Component.translatable("tooltip.llmera_ae2.jade.no_controller"));
        } else if (!status.getBoolean("CodePowered")) {
            codeState = theme.danger(Component.translatable("tooltip.llmera_ae2.jade.no_power"));
        } else if (status.getInt("CodeChannelAssigned") == 0) {
            codeState = theme.danger(Component.translatable("tooltip.llmera_ae2.jade.no_channel"));
        } else {
            codeState = theme.success(Component.translatable("tooltip.llmera_ae2.jade.online"));
        }
        tooltip.add(Component.translatable("tooltip.llmera_ae2.jade.code_network", codeState));
        tooltip.add(Component.translatable("tooltip.llmera_ae2.jade.code_bridge_channels",
                status.getInt("CodeChannelAssigned") > 0
                        ? theme.success(status.getInt("CodeChannelAssigned"))
                        : theme.danger(0),
                theme.info(status.getInt("CodeChannelTotal"))));

        Component ae2State = status.getBoolean("Ae2Online")
                ? theme.success(Component.translatable("tooltip.llmera_ae2.jade.online"))
                : theme.danger(Component.translatable("tooltip.llmera_ae2.jade.offline"));
        tooltip.add(Component.translatable("tooltip.llmera_ae2.jade.ae2_network", ae2State));
        tooltip.add(Component.translatable("tooltip.llmera_ae2.jade.ae2_channels",
                theme.info(status.getInt("Ae2ChannelUsed")), theme.info(status.getInt("Ae2ChannelCapacity"))));

        Component physical = status.getBoolean("Ae2PhysicalConnected")
                ? theme.success(Component.translatable("tooltip.llmera_ae2.jade.connected"))
                : theme.danger(Component.translatable("tooltip.llmera_ae2.jade.disconnected"));
        tooltip.add(Component.translatable("tooltip.llmera_ae2.jade.physical_cable", physical));

        Component operational = status.getBoolean("Operational")
                ? theme.success(Component.translatable("tooltip.llmera_ae2.jade.ready"))
                : theme.danger(Component.translatable("tooltip.llmera_ae2.jade.not_ready"));
        tooltip.add(Component.translatable("tooltip.llmera_ae2.jade.bridge", operational));
        if (status.getBoolean("ScanTruncated")) {
            tooltip.add(theme.warning(Component.translatable("tooltip.llmera_ae2.jade.scan_truncated")));
        }
    }

    @Override
    public ResourceLocation getUid() {
        return ID;
    }
}
