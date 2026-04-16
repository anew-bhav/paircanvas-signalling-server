package dev.anewbhav.paircanvas.network

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

@Serializable
sealed class SignalingMessage {
    @Serializable data class JoinRoom(val roomId: String) : SignalingMessage()
    @Serializable data class Joined(val isOfferer: Boolean) : SignalingMessage()
    @Serializable object PeerJoined : SignalingMessage()
    @Serializable object PeerLeft : SignalingMessage()
    @Serializable object RoomFull : SignalingMessage()
    @Serializable data class Offer(val sdp: String, val roomId: String) : SignalingMessage()
    @Serializable data class Answer(val sdp: String, val roomId: String) : SignalingMessage()
    @Serializable data class IceCandidate(
        val candidate: String, val sdpMid: String, val sdpMLineIndex: Int, val roomId: String
    ) : SignalingMessage()
}

@Serializable
data class RoomStatus(val exists: Boolean, val peerCount: Int)

data class Room(
    val sessions: CopyOnWriteArrayList<DefaultWebSocketServerSession> = CopyOnWriteArrayList(),
    val createdAt: Long = System.currentTimeMillis(),
    @Volatile var lastActivityAt: Long = System.currentTimeMillis()
)

fun main() {
    val port = System.getenv("PORT")?.toInt() ?: 8080
    println("Starting Signaling Server on port $port...")

    embeddedServer(Netty, port = port) {
        install(WebSockets) {
            contentConverter = KotlinxWebsocketSerializationConverter(Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            })
        }

        val rooms = ConcurrentHashMap<String, Room>()

        // Expire empty rooms after 10 min, single-peer rooms after 30 min
        launch {
            while (true) {
                delay(60_000)
                val now = System.currentTimeMillis()
                val expired = rooms.entries.filter { (_, room) ->
                    (room.sessions.isEmpty() && now - room.createdAt > 10 * 60_000) ||
                    (room.sessions.size == 1 && now - room.lastActivityAt > 30 * 60_000)
                }
                expired.forEach { (roomId, _) ->
                    rooms.remove(roomId)
                    println("SERVER: Room $roomId expired and removed")
                }
            }
        }

        routing {
            get("/health") {
                call.respondText("OK", ContentType.Text.Plain)
            }

            get("/rooms/{roomId}") {
                val roomId = call.parameters["roomId"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing roomId")
                val room = rooms[roomId]
                call.respond(RoomStatus(exists = room != null, peerCount = room?.sessions?.size ?: 0))
            }

            webSocket("/ws") {
                var userRoomId: String? = null

                try {
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            val text = frame.readText()
                            val message = try {
                                Json.decodeFromString<SignalingMessage>(text)
                            } catch (e: Exception) {
                                println("SERVER: Error decoding message: ${e.message}")
                                null
                            } ?: continue

                            when (message) {
                                is SignalingMessage.JoinRoom -> {
                                    val room = rooms.getOrPut(message.roomId) { Room() }

                                    if (room.sessions.size >= 2) {
                                        println("SERVER: Room ${message.roomId} full, rejecting peer")
                                        sendSerialized<SignalingMessage>(SignalingMessage.RoomFull)
                                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Room full"))
                                        return@webSocket
                                    }

                                    userRoomId = message.roomId
                                    room.sessions.add(this)
                                    room.lastActivityAt = System.currentTimeMillis()

                                    val isOfferer = room.sessions.size == 2
                                    sendSerialized<SignalingMessage>(SignalingMessage.Joined(isOfferer))
                                    println("SERVER: User joined room ${message.roomId}. isOfferer=$isOfferer")

                                    if (room.sessions.size == 2) {
                                        println("SERVER: Room ${message.roomId} full. Notifying first peer to start handshake...")
                                        room.sessions[0].sendSerialized<SignalingMessage>(SignalingMessage.PeerJoined)
                                    }
                                }

                                is SignalingMessage.Offer,
                                is SignalingMessage.Answer,
                                is SignalingMessage.IceCandidate -> {
                                    userRoomId?.let { roomId ->
                                        rooms[roomId]?.let { room ->
                                            room.lastActivityAt = System.currentTimeMillis()
                                            room.sessions.forEach { session ->
                                                if (session != this) session.send(Frame.Text(text))
                                            }
                                        }
                                    }
                                }

                                else -> {}
                            }
                        }
                    }
                } catch (e: Exception) {
                    println("SERVER: Connection error: ${e.message}")
                } finally {
                    userRoomId?.let { roomId ->
                        rooms[roomId]?.let { room ->
                            room.sessions.remove(this)
                            println("SERVER: User disconnected from room $roomId. Peers remaining: ${room.sessions.size}")
                            if (room.sessions.isEmpty()) {
                                rooms.remove(roomId)
                                println("SERVER: Room $roomId removed (empty)")
                            } else {
                                room.sessions.forEach { session ->
                                    session.sendSerialized<SignalingMessage>(SignalingMessage.PeerLeft)
                                }
                            }
                        }
                    }
                }
            }
        }
    }.start(wait = true)
}
