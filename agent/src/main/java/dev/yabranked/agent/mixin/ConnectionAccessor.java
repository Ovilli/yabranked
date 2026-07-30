package dev.yabranked.agent.mixin;

import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reaches a {@link Connection}'s Netty channel, which it does not expose.
 *
 * <p>The pipeline is the whole point of the replay tap. Capturing at the
 * {@code Connection.send} level would give packet <em>objects</em>, which are
 * only meaningful in the process that made them; capturing in the pipeline gives
 * the bytes the client was actually sent, which is a thing that can be written
 * to a file and fed back in a year.
 */
@Mixin(Connection.class)
public interface ConnectionAccessor {
    @Accessor("channel")
    Channel yabrankedChannel();
}
