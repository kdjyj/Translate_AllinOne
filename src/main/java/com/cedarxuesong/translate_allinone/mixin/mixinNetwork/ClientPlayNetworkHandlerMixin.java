package com.cedarxuesong.translate_allinone.mixin.mixinNetwork;

import com.cedarxuesong.translate_allinone.utils.text.WynnDialogueExtractor;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public class ClientPlayNetworkHandlerMixin {

    @Inject(method = "onGameMessage", at = @At("HEAD"))
    private void onGameMessageIntercept(GameMessageS2CPacket packet, CallbackInfo ci) {
        // NPC 对话通常带有 overlay=true (ActionBar)
        // 使用 try-catch 保护主网络线程，防止因为处理逻辑异常导致连接挂起
        try {
            if (packet != null && packet.overlay()) {
                WynnDialogueExtractor.extract(packet.content());
            }
        } catch (Exception e) {
            // 仅记录错误，不干扰正常的包处理流程
        }
    }
}
