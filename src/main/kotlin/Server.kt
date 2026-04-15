package dev.anewbhav.paircanvas.network

import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

// 1. Signaling Protocol - MUST match the Android App exactly
@Serializable
sealed class SignalingMessage {
    @Serializable data class JoinRoom(val roomId: String) : SignalingMessage()
    @Serializable data class Joined(val isOfferer: Boolean) : SignalingMessage()
    @Serializable object PeerJoined : SignalingMessage()
    @Serializable data class Offer(val sdp: String, val roomId: String) : SignalingMessage()
    @Serializable data class Answer(val sdp: String, val roomId: String) : SignalingMessage()
    @Serializable data class IceCandidate(
        val candidate: String, val sdpMid: String, val sdpMLineIndex: Int, val roomId: String
    ) : SignalingMessage()
}

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

        // Stores roomId -> Set of active WebSocket sessions
        val rooms = ConcurrentHashMap<String, MutableList<DefaultWebSocketServerSession>>()

        routing {
            get("/health") {
                call.respondText("OK", ContentType.Text.Plain)
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
                                    userRoomId = message.roomId
                                    val room = rooms.getOrPut(message.roomId) { mutableListOf() }
                                    room.add(this)

                                    val isOfferer = room.size == 1
                                    sendSerialized<SignalingMessage>(SignalingMessage.Joined(isOfferer))
                                    println("SERVER: User joined room ${message.roomId}. Offerer: $isOfferer")

                                    // If this is the second person, notify the first person (the offerer)
                                    if (room.size == 2) {
                                        println("SERVER: Room ${message.roomId} is full. Notifying offerer to start handshake...")
                                        room[0].sendSerialized<SignalingMessage>(SignalingMessage.PeerJoined)
                                    }
                                }

                                is SignalingMessage.Offer,
                                is SignalingMessage.Answer,
                                is SignalingMessage.IceCandidate -> {
                                    // Forward SDP and ICE messages to the other peer in the room
                                    userRoomId?.let { roomId ->
                                        rooms[roomId]?.forEach { session ->
                                            if (session != this) {
                                                session.send(Frame.Text(text))
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
                    // Cleanup on disconnect
                    userRoomId?.let { roomId ->
                        rooms[roomId]?.remove(this)
                        if (rooms[roomId]?.isEmpty() == true) rooms.remove(roomId)
                        println("SERVER: User disconnected from room $roomId")
                    }
                }
            }
        }
    }.start(wait = true)
}