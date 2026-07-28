package com.example.llmera.ae2.jade;

import com.example.llmera.ae2.network.Ae2NetworkBridgeBlock;
import com.example.llmera.ae2.network.Ae2NetworkBridgeBlockEntity;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

@WailaPlugin("ae2")
public final class LLMEraAe2JadePlugin implements IWailaPlugin {
    @Override
    public void register(IWailaCommonRegistration registration) {
        registration.registerBlockDataProvider(Ae2NetworkStatusProvider.INSTANCE, Ae2NetworkBridgeBlockEntity.class);
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerBlockComponent(Ae2NetworkStatusProvider.INSTANCE, Ae2NetworkBridgeBlock.class);
    }
}
