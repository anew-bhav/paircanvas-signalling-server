package dev.anewbhav.paircanvas.network

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * How long a member keeps its slot after losing the transport.
 *
 * This is deliberately unrelated to the ping/timeout values below. Detection is cheap to get
 * wrong (a false positive only marks someone AWAY) so it is aggressive; eviction is expensive
 * to get wrong (the peer has to re-pair) so it is slow. Coupling the two is what made the old
 * design unfixable: any timeout short enough to detect death promptly also evicted live peers,
 * and any timeout long enough to be safe left a zombie session occupying the room.
 */
private const val GRACE_MS = 2 * 60_000L
private const val REAPER_PERIOD_MS = 5_000L
private const val EMPTY_ROOM_TTL_MS = 10 * 60_000L

@Serializable
data class RoomStatus(val exists: Boolean, val peerCount: Int)

/** Mutable server-side view of one member. All fields guarded by [Room.lock]. */
private class MemberState(
    val deviceId: String,
    val resumeToken: String,
    var session: DefaultWebSocketServerSession?,
    var presence: Presence,
    var awaySince: Long
)

private class Room {
    val lock = Any()
    val members = LinkedHashMap<String, MemberState>()
    var epoch: Int = 0
    val createdAt = System.currentTimeMillis()
    @Volatile var lastActivityAt = System.currentTimeMillis()

    /** Must be called under [lock]. */
    fun snapshot(roomId: String) = SignalingMessage.RoomState(
        roomId = roomId,
        // Sorted only so the value is deterministic. Order is not meaningful and nothing
        // downstream may derive a role or a recipient from it.
        members = members.values
            .sortedBy { it.deviceId }
            .map { Member(it.deviceId, it.presence) },
        epoch = epoch
    )
}

private sealed interface Admit {
    /** [displaced] is a stale socket for the same member that must be closed outside the lock. */
    data class Ok(val token: String, val displaced: DefaultWebSocketServerSession?) : Admit
    data class Denied(val reason: RejectReason) : Admit
}

/**
 * Membership is keyed by deviceId, never by socket. A returning member reclaims its slot,
 * so it can never be told the room is full - the race the old size-based check could not win.
 *
 * Must be called under [Room.lock].
 */
private fun Room.admit(msg: SignalingMessage.JoinRoom, session: DefaultWebSocketServerSession): Admit {
    val existing = members[msg.deviceId]
    if (existing != null) {
        // deviceId says which slot; resumeToken proves ownership of it. The token is needed
        // because deviceId is not secret - both peers learn each other's via RoomState, so
        // deviceId alone would let either peer evict the other.
        if (msg.resumeToken == null || msg.resumeToken != existing.resumeToken) {
            return Admit.Denied(RejectReason.BAD_RESUME_TOKEN)
        }
        val displaced = existing.session?.takeIf { it !== session }
        existing.session = session
        existing.presence = Presence.PRESENT
        existing.awaySince = 0L
        epoch++
        return Admit.Ok(existing.resumeToken, displaced)
    }
    if (members.size >= 2) return Admit.Denied(RejectReason.ROOM_FULL)
    val token = UUID.randomUUID().toString()
    members[msg.deviceId] = MemberState(msg.deviceId, token, session, Presence.PRESENT, 0L)
    epoch++
    return Admit.Ok(token, null)
}

private fun SignalingMessage.relayEpoch(): Int? = when (this) {
    is SignalingMessage.Offer -> epoch
    is SignalingMessage.Answer -> epoch
    is SignalingMessage.IceCandidate -> epoch
    else -> null
}

private suspend fun broadcastState(room: Room, roomId: String) {
    // Snapshot under the lock, send outside it - sendSerialized suspends and suspending
    // inside a synchronized block is not allowed.
    val (msg, targets) = synchronized(room.lock) {
        room.snapshot(roomId) to room.members.values.mapNotNull { it.session }
    }
    targets.forEach { session ->
        runCatching { session.sendSerialized<SignalingMessage>(msg) }
            .onFailure { println("SERVER: broadcast to a member in $roomId failed: ${it.message}") }
    }
}

fun main() {
    val port = System.getenv("PORT")?.toInt() ?: 8080
    println("Starting Signaling Server on port $port (protocol v${SignalingMessage.PROTOCOL_VERSION})...")

    embeddedServer(Netty, port = port) {
        install(WebSockets) {
            contentConverter = KotlinxWebsocketSerializationConverter(json)
            // Aggressive on purpose - see GRACE_MS. Losing the socket no longer loses the slot,
            // so detecting death quickly costs nothing.
            pingPeriod = java.time.Duration.ofSeconds(5)
            timeout = java.time.Duration.ofSeconds(10)
        }
        install(ContentNegotiation) { json(json) }

        val rooms = ConcurrentHashMap<String, Room>()

        // Releases slots whose grace has expired, and drops rooms nobody is coming back to.
        launch {
            while (true) {
                delay(REAPER_PERIOD_MS)
                val now = System.currentTimeMillis()
                for ((roomId, room) in rooms) {
                    val evicted = synchronized(room.lock) {
                        val gone = mutableListOf<String>()
                        val it = room.members.entries.iterator()
                        while (it.hasNext()) {
                            val m = it.next().value
                            if (m.presence == Presence.AWAY && now - m.awaySince > GRACE_MS) {
                                it.remove()
                                gone += m.deviceId
                            }
                        }
                        if (gone.isNotEmpty()) room.epoch++
                        gone
                    }
                    if (evicted.isNotEmpty()) {
                        println("SERVER: room $roomId released ${evicted.size} expired slot(s)")
                        broadcastState(room, roomId)
                    }
                    val empty = synchronized(room.lock) { room.members.isEmpty() }
                    if (empty && now - room.lastActivityAt > EMPTY_ROOM_TTL_MS) {
                        rooms.remove(roomId)
                        println("SERVER: room $roomId expired and removed")
                    }
                }
            }
        }

        routing {
            get("/health") { call.respondText("OK", ContentType.Text.Plain) }

            get("/join/{roomId}") {
                val roomId = call.parameters["roomId"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing roomId")
                call.respondText(
                    contentType = ContentType.Text.Html,
                    text = """
                        <!DOCTYPE html>
                        <html>
                        <head>
                            <meta http-equiv="refresh" content="0;url=paircanvas://join/$roomId">
                            <title>Opening PairCanvas...</title>
                            <style>
                                body { font-family: sans-serif; display: flex; flex-direction: column;
                                       align-items: center; justify-content: center; height: 100vh; margin: 0; }
                                a { color: #6366F1; font-size: 1.2rem; text-decoration: none; padding: 12px 24px;
                                    border: 2px solid #6366F1; border-radius: 8px; }
                            </style>
                        </head>
                        <body>
                            <p>Opening PairCanvas...</p>
                            <a href="paircanvas://join/$roomId">Tap here if it doesn't open</a>
                        </body>
                        </html>
                    """.trimIndent()
                )
            }

            get("/rooms/{roomId}") {
                val roomId = call.parameters["roomId"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing roomId")
                val room = rooms[roomId]
                val count = room?.let { synchronized(it.lock) { it.members.size } } ?: 0
                call.respond(RoomStatus(exists = room != null, peerCount = count))
            }

            webSocket("/ws") {
                var joinedRoomId: String? = null
                var joinedDeviceId: String? = null

                suspend fun reject(reason: RejectReason) {
                    runCatching { sendSerialized<SignalingMessage>(SignalingMessage.Rejected(reason)) }
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, reason.name))
                }

                try {
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        val text = frame.readText()
                        val message = runCatching { json.decodeFromString<SignalingMessage>(text) }
                            .onFailure { println("SERVER: undecodable frame: ${it.message}") }
                            .getOrNull() ?: continue

                        when (message) {
                            is SignalingMessage.JoinRoom -> {
                                if (message.protocolVersion != SignalingMessage.PROTOCOL_VERSION) {
                                    println("SERVER: rejecting protocol v${message.protocolVersion}")
                                    reject(RejectReason.PROTOCOL_MISMATCH)
                                    return@webSocket
                                }
                                val room = rooms.getOrPut(message.roomId) { Room() }
                                room.lastActivityAt = System.currentTimeMillis()
                                when (val outcome = synchronized(room.lock) { room.admit(message, this) }) {
                                    is Admit.Denied -> {
                                        println("SERVER: ${message.deviceId} denied from ${message.roomId}: ${outcome.reason}")
                                        reject(outcome.reason)
                                        return@webSocket
                                    }
                                    is Admit.Ok -> {
                                        joinedRoomId = message.roomId
                                        joinedDeviceId = message.deviceId
                                        // A second live socket for the same member means the old
                                        // one is stale. Close it here; its finally block will see
                                        // it is no longer the member's current session and leave
                                        // the membership alone.
                                        outcome.displaced?.let { stale ->
                                            runCatching {
                                                stale.close(CloseReason(CloseReason.Codes.NORMAL, "superseded"))
                                            }
                                        }
                                        sendSerialized<SignalingMessage>(
                                            SignalingMessage.Welcome(message.deviceId, outcome.token)
                                        )
                                        println("SERVER: ${message.deviceId} joined ${message.roomId}")
                                        broadcastState(room, message.roomId)
                                    }
                                }
                            }

                            is SignalingMessage.Leave -> {
                                val rid = joinedRoomId ?: continue
                                val did = joinedDeviceId ?: continue
                                rooms[rid]?.let { room ->
                                    synchronized(room.lock) {
                                        room.members.remove(did)
                                        room.epoch++
                                    }
                                    println("SERVER: $did left $rid deliberately")
                                    broadcastState(room, rid)
                                }
                                return@webSocket
                            }

                            else -> {
                                val epoch = message.relayEpoch() ?: continue
                                val rid = joinedRoomId ?: continue
                                val did = joinedDeviceId ?: continue
                                val room = rooms[rid] ?: continue
                                room.lastActivityAt = System.currentTimeMillis()
                                val target = synchronized(room.lock) {
                                    // Drop anything from a previous pairing generation rather
                                    // than relaying it. Receivers check too, but filtering here
                                    // stops one stale peer disturbing a healthy negotiation.
                                    if (epoch != room.epoch) null
                                    else room.members.values.firstOrNull { it.deviceId != did }?.session
                                }
                                if (target == null) {
                                    println("SERVER: dropped ${message::class.simpleName} in $rid (epoch=$epoch)")
                                } else {
                                    runCatching { target.send(Frame.Text(text)) }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    println("SERVER: connection error: ${e.message}")
                } finally {
                    // NonCancellable: this runs on the network-death path, where the coroutine is
                    // already cancelled and every suspend call would otherwise throw immediately -
                    // which is how the old code could silently fail to tell the peer anything.
                    withContext(NonCancellable) {
                        val rid = joinedRoomId
                        val did = joinedDeviceId
                        if (rid != null && did != null) {
                            rooms[rid]?.let { room ->
                                val changed = synchronized(room.lock) {
                                    val member = room.members[did]
                                    // Only stand down if this socket is still the member's current
                                    // one. A reconnect that already replaced it must not be undone
                                    // by the old socket's teardown arriving late.
                                    if (member != null && member.session === this@webSocket) {
                                        member.session = null
                                        member.presence = Presence.AWAY
                                        member.awaySince = System.currentTimeMillis()
                                        room.epoch++
                                        true
                                    } else false
                                }
                                if (changed) {
                                    println("SERVER: $did went AWAY in $rid (slot held ${GRACE_MS / 1000}s)")
                                    broadcastState(room, rid)
                                }
                            }
                        }
                    }
                }
            }
        }
    }.start(wait = true)
}
