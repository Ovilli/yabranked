package dev.yabranked.client.replay

import dev.yabranked.proto.PlayerRef
import dev.yabranked.proto.ReplayPose
import dev.yabranked.proto.ReplayTrack
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How a body's pose is read out of a track sampled five times a second.
 *
 * The samples are coarse for a look direction — where a player is pointing is the
 * fastest-changing thing about them — so the curve between them is what a follow
 * camera actually rides. Joining them with straight lines put a corner at every
 * sample, which is the jerk every fifth of a second that this replaced.
 */
class ReplayPoseInterpolationTest {

    private fun track(vararg poses: ReplayPose) =
        ReplayTrack(player = PlayerRef(uuid = "u", name = "Player"), poses = poses.toList())

    /** A straight walk east, sampled on the recorder's own fixed schedule. */
    private fun line(count: Int, step: Int = 200) = (0 until count).map {
        ReplayPose(atMillis = it * step, x = it * 1.0, y = 64.0, z = 0.0)
    }

    private val ghosts get() = ReplayGhosts(emptyList())

    @Test
    fun `the curve passes through the samples it was built from`() {
        val t = track(*line(6).toTypedArray())

        // Catmull-Rom interpolates rather than approximates: at a sample's own time
        // the answer is that sample, or the curve is not describing the recording.
        for (i in 1 until 5) {
            val pose = assertNotNull(ghosts.poseAt(t, i * 200))
            assertEquals(i * 1.0, pose.x, 1e-6, "the curve missed sample $i")
        }
    }

    @Test
    fun `a straight line stays straight`() {
        val t = track(*line(6).toTypedArray())

        // A spline overshoots at a sharp turn, which is the price of having no
        // corners. It must not overshoot when there is no turn at all — a player
        // walking a corridor would weave through the walls of it.
        for (millis in 200..800 step 25) {
            val pose = assertNotNull(ghosts.poseAt(t, millis))
            assertEquals(millis / 200.0, pose.x, 1e-6, "the body left the line at $millis")
        }
    }

    @Test
    fun `yaw takes the short way round zero`() {
        val t = track(
            ReplayPose(atMillis = 0, x = 0.0, y = 0.0, z = 0.0, yaw = 340f),
            ReplayPose(atMillis = 200, x = 0.0, y = 0.0, z = 0.0, yaw = 350f),
            ReplayPose(atMillis = 400, x = 0.0, y = 0.0, z = 0.0, yaw = 10f),
            ReplayPose(atMillis = 600, x = 0.0, y = 0.0, z = 0.0, yaw = 20f),
        )

        // 350° and 10° are twenty degrees apart. Interpolating the raw numbers
        // sweeps the body 340° the other way — a spin, once per sample, forever.
        val pose = assertNotNull(ghosts.poseAt(t, 300))
        val fromEnd = abs(angle(pose.yaw - 0f))
        assertTrue(fromEnd <= 10f, "yaw went the long way round: ${pose.yaw}")
    }

    @Test
    fun `pitch cannot bend past straight down`() {
        val t = track(
            ReplayPose(atMillis = 0, x = 0.0, y = 0.0, z = 0.0, pitch = 0f),
            ReplayPose(atMillis = 200, x = 0.0, y = 0.0, z = 0.0, pitch = 40f),
            ReplayPose(atMillis = 400, x = 0.0, y = 0.0, z = 0.0, pitch = 90f),
            ReplayPose(atMillis = 600, x = 0.0, y = 0.0, z = 0.0, pitch = 90f),
        )

        for (millis in 200..500 step 10) {
            val pose = assertNotNull(ghosts.poseAt(t, millis))
            assertTrue(pose.pitch in -90f..90f, "pitch overshot to ${pose.pitch} at $millis")
        }
    }

    @Test
    fun `a gap in the samples falls back to the straight line`() {
        // A player who dropped and came back leaves an unevenly spaced pair, and a
        // uniform spline through one of those overshoots hard enough to put a body
        // inside the terrain. The straight line's worst case is the corner.
        val t = track(
            ReplayPose(atMillis = 0, x = 0.0, y = 0.0, z = 0.0),
            ReplayPose(atMillis = 200, x = 1.0, y = 0.0, z = 0.0),
            ReplayPose(atMillis = 400, x = 2.0, y = 0.0, z = 0.0),
            ReplayPose(atMillis = 9_000, x = 100.0, y = 0.0, z = 0.0),
        )

        val pose = assertNotNull(ghosts.poseAt(t, 300))
        assertEquals(1.5, pose.x, 1e-6, "an uneven neighbourhood was splined anyway")
    }

    @Test
    fun `a track that has run out stops having a pose`() {
        val t = track(*line(3).toTypedArray())

        // How a player who disconnected mid-match stops having a body, rather than
        // standing frozen where they left it for the rest of the recording.
        assertNull(ghosts.poseAt(t, 30_000))
        assertNull(ghosts.poseAt(t, -1))
    }

    /** Shortest signed distance from zero, so a wrapped yaw can be compared. */
    private fun angle(degrees: Float): Float {
        var d = degrees % 360f
        if (d >= 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }
}
