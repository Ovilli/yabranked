package dev.yabranked.agent.mixin;

import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reaches the {@link Connection} behind a packet listener.
 *
 * <p>Fabric's connection events hand out the listener, not the connection, and
 * {@code ServerCommonPacketListenerImpl.connection} is protected. The replay tap
 * needs the channel: it installs itself into the Netty pipeline, which is the
 * only place a fully encoded clientbound packet exists as bytes.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public interface ServerCommonPacketListenerAccessor {
    @Accessor("connection")
    Connection yabrankedConnection();
}
