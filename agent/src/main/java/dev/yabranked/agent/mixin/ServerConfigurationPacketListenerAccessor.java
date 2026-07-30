package dev.yabranked.agent.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reaches the profile of the player being configured — i.e. <em>who</em> this
 * connection belongs to, which is what decides which replay stream its packets
 * are written to.
 *
 * <p>This exists because {@code Connection.getIntendedProfileId()} is not an
 * answer on a real server. It is only set by
 * {@code ServerConnectionListener.acceptChannel(Channel, UUID)} — the memory and
 * transfer path — and never by the initializer that
 * {@code startTcpServerListener} installs, so on a match server it is always
 * null. Reading it and giving up produced exactly one symptom: every match
 * recorded nothing, and said nothing about it.
 *
 * <p>{@code playerProfile()} would do just as well but is protected, and
 * {@code @Accessor} only reaches fields.
 */
@Mixin(ServerConfigurationPacketListenerImpl.class)
public interface ServerConfigurationPacketListenerAccessor {
    @Accessor("gameProfile")
    GameProfile yabrankedGameProfile();
}
