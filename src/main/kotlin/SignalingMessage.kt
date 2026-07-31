package dev.anewbhav.paircanvas.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire protocol between client and signaling server.
 *
 * This file is duplicated verbatim in the server repo (SignallingService2). The two copies
 * must stay identical - every type here carries an explicit [SerialName] so the wire format
 * is decoupled from Kotlin package and class names. Renaming a class no longer silently
 * breaks the protocol; only changing a [SerialName] does.
 *
 * Design notes that are easy to get wrong later:
 *
 * - Membership is keyed by [JoinRoom.deviceId], never by the WebSocket session. A peer that
 *   reconnects keeps its slot, so a returning member can never be told the room is full.
 * - [RoomState] is a full snapshot, not an event. It is broadcast on every membership change,
 *   so a dropped message is repaired by the next one instead of leaving the two peers with
 *   permanently divergent views.
 * - [epoch] is owned by the server and bumped on every membership change. It guards against
 *   SDP/ICE left over from a previous pairing generation. Collisions *within* one epoch are
 *   the job of perfect negotiation, not of this counter.
 * - [Welcome.resumeToken] is sent only to its owner. It must never be included in [RoomState],
 *   which both members receive - that would hand each peer the means to evict the other.
 */
@Serializable
sealed class SignalingMessage {

    // ---------------- client -> server ----------------

    /**
     * First message on every connection, including reconnects. Presenting a [resumeToken]
     * that matches an existing member reclaims that member's slot rather than consuming a
     * new one.
     */
    @Serializable
    @SerialName("join")
    data class JoinRoom(
        val roomId: String,
        val deviceId: String,
        val resumeToken: String?,
        /**
         * Deliberately has no default. kotlinx omits any value equal to its default unless
         * `encodeDefaults` is set, and the client's Json does not set it - a defaulted field
         * would therefore never reach the wire, the server would fall back to its own default,
         * and the version check would pass vacuously. Requiring it makes that impossible to
         * reintroduce by editing a Json config.
         */
        val protocolVersion: Int
    ) : SignalingMessage()

    /**
     * Deliberate departure, as opposed to losing the transport. Releases the slot
     * immediately with no grace period, so the peer sees the member vanish from
     * [RoomState] rather than turn [Presence.AWAY].
     */
    @Serializable
    @SerialName("leave")
    data object Leave : SignalingMessage()

    // ---------------- server -> client ----------------

    /** Private acknowledgement of a successful join. Not broadcast. */
    @Serializable
    @SerialName("welcome")
    data class Welcome(
        val you: String,
        val resumeToken: String
    ) : SignalingMessage()

    /**
     * Full membership snapshot. Broadcast to every member on any change.
     *
     * [members] order carries no meaning. The server sorts by `deviceId` purely so the
     * value is deterministic - never derive a role or a recipient from position. Doing
     * exactly that (`room.sessions[0]`) is what made the previous protocol fragile.
     */
    @Serializable
    @SerialName("roomState")
    data class RoomState(
        val roomId: String,
        val members: List<Member>,
        val epoch: Int
    ) : SignalingMessage()

    /**
     * Terminal: retrying cannot change the outcome, so the client must stop and surface it.
     *
     * The client must also close the socket itself on receipt rather than waiting to be
     * closed. The server does close immediately afterwards, but that close frame is not
     * reliably delivered - when it went missing the client sat blocked for the server's full
     * 20s handshake timeout on every attempt (bug #9). Do not reintroduce a wait here.
     */
    @Serializable
    @SerialName("rejected")
    data class Rejected(val reason: RejectReason) : SignalingMessage()

    // ---------------- relayed peer <-> peer ----------------
    // No roomId field: the server already knows which room a session belongs to, and a
    // client-supplied room could disagree with it. No sender field either - in a two-member
    // room the recipient is by definition the other member.
    //
    // The server must drop any of these whose [epoch] is not the room's current epoch,
    // rather than relaying blindly. Receivers check too, but filtering at the relay keeps
    // one stale peer from being able to disturb a healthy negotiation at all.

    @Serializable
    @SerialName("offer")
    data class Offer(val sdp: String, val epoch: Int) : SignalingMessage()

    @Serializable
    @SerialName("answer")
    data class Answer(val sdp: String, val epoch: Int) : SignalingMessage()

    @Serializable
    @SerialName("ice")
    data class IceCandidate(
        val candidate: String,
        val sdpMid: String,
        val sdpMLineIndex: Int,
        val epoch: Int
    ) : SignalingMessage()

    companion object {
        /**
         * Bumped on any breaking change to this file. The server rejects mismatches with
         * [RejectReason.PROTOCOL_MISMATCH] so an out-of-date client fails loudly instead of
         * hanging on messages it cannot deserialize.
         */
        const val PROTOCOL_VERSION = 2
    }
}

@Serializable
data class Member(
    val deviceId: String,
    val presence: Presence
)

@Serializable
enum class Presence {
    /** Holding a live WebSocket right now. */
    @SerialName("present") PRESENT,

    /** Transport lost, slot held until the grace period expires. Not the same as gone. */
    @SerialName("away") AWAY
}

@Serializable
enum class RejectReason {
    /** Two *other* devices already hold the room. Genuinely terminal - retrying cannot help. */
    @SerialName("roomFull") ROOM_FULL,

    /** Resume token did not match the member it claimed. */
    @SerialName("badResumeToken") BAD_RESUME_TOKEN,

    /** Client and server disagree on [SignalingMessage.PROTOCOL_VERSION]. */
    @SerialName("protocolMismatch") PROTOCOL_MISMATCH
}
