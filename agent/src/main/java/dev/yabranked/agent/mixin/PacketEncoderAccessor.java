package dev.yabranked.agent.mixin;

import net.minecraft.network.PacketEncoder;
import net.minecraft.network.ProtocolInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reaches {@link PacketEncoder}'s protocol, which the replay tap needs in order
 * to label a captured frame.
 *
 * <p>A packet stream is only decodable by the protocol that wrote it, and a
 * connection changes protocol twice in its life (login &rarr; configuration
 * &rarr; play, and back again on a transfer). The tap therefore stores the
 * protocol <em>with every frame</em> rather than inferring it on playback from
 * where it thinks the phase boundary was — the boundary is exactly the place a
 * guess goes wrong, and a mislabelled frame is a malformed packet in the middle
 * of a world the viewer has already drawn.
 *
 * <p>The encoder is the authority on this because the pipeline swaps it on every
 * protocol change: whatever sits under the {@code "encoder"} handler name at the
 * moment of a write is by definition the protocol that write was encoded with.
 */
@Mixin(PacketEncoder.class)
public interface PacketEncoderAccessor {
    @Accessor("protocolInfo")
    ProtocolInfo<?> yabrankedProtocolInfo();
}
