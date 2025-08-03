package com.myapp.privateroom.viewmodel

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.ktx.Firebase
import com.google.firebase.functions.ktx.functions
import com.myapp.privateroom.RoomActivity
import com.myapp.privateroom.data.repository.UserRepository
import com.myapp.privateroom.data.repository.RoomRepository
import com.myapp.privateroom.model.Room
import com.myapp.privateroom.model.Room.RoomMember
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.UUID

data class ErrorState(val message: String)

sealed class NavigationEvent {
    object NavigateBack : NavigationEvent()
}

@HiltViewModel
class RoomViewModel @Inject constructor(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth,
    private val userRepository: UserRepository,
    private val roomRepository: RoomRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val functions: FirebaseFunctions = Firebase.functions

    private val _createRoomState = MutableStateFlow<RoomState>(RoomState.Initial)
    val createRoomState: StateFlow<RoomState> = _createRoomState

    private val _joinRoomState = MutableStateFlow<RoomState>(RoomState.Initial)
    val joinRoomState: StateFlow<RoomState> = _joinRoomState

    private val _roomState = MutableStateFlow<RoomState>(RoomState.Initial)
    val roomState: StateFlow<RoomState> = _roomState
    private val _currentRoom = MutableStateFlow<Room?>(null)
    val currentRoom: StateFlow<Room?> = _currentRoom
    private val _userRooms = MutableStateFlow<List<Room>>(emptyList())
    val userRooms: StateFlow<List<Room>> = _userRooms.asStateFlow()
    private val _roomName = MutableStateFlow<String?>(null)
    val roomName: StateFlow<String?> = _roomName.asStateFlow()
    private val _roomMessages = MutableStateFlow<List<Room.Message>>(emptyList())
    val roomMessages: StateFlow<List<Room.Message>> = _roomMessages.asStateFlow()
    
    private val _unreadMessageCount = MutableStateFlow<Map<String, Int>>(emptyMap())
    val unreadMessageCount: StateFlow<Map<String, Int>> = _unreadMessageCount.asStateFlow()
    
    // Track unread messages per room
    private fun updateUnreadCount(roomId: String) {
        viewModelScope.launch {
            try {
                val currentUser = auth.currentUser ?: return@launch
                val roomRef = db.collection("rooms").document(roomId)
                val messages = roomRef.collection("messages").get().await()
                
                val unreadCount = messages.documents.count { doc ->
                    val message = doc.toObject(Room.Message::class.java)
                    message != null && !message.readBy.contains(currentUser.uid)
                }
                
                _unreadMessageCount.value = _unreadMessageCount.value + (roomId to unreadCount)
            } catch (e: Exception) {
                Log.e("RoomViewModel", "Error updating unread count", e)
            }
        }
    }
    private val _navigationEvent = MutableStateFlow<NavigationEvent?>(null)
    val navigationEvent: StateFlow<NavigationEvent?> = _navigationEvent.asStateFlow()

    private val _errorState = MutableStateFlow<ErrorState?>(null)
    val errorState: StateFlow<ErrorState?> = _errorState.asStateFlow()

    private var roomListener: ListenerRegistration? = null
    private var messagesListener: ListenerRegistration? = null

    val currentUser get() = auth.currentUser

    fun getRoom(roomId: String) {
        viewModelScope.launch {
            try {
                val currentUser = auth.currentUser
                if (currentUser == null) {
                    _roomState.value = RoomState.Error("User not authenticated")
                    return@launch
                }
                
                val userId = currentUser.uid
                Log.d("RoomViewModel", "Attempting to fetch room with ID: $roomId for user: $userId")
                _roomState.value = RoomState.Loading
                
                // First, check if the user is a member of the room
                val roomDoc = db.collection("rooms").document(roomId).get().await()
                if (!roomDoc.exists()) {
                    Log.e("RoomViewModel", "Room document does not exist for ID: $roomId")
                    _roomState.value = RoomState.Error("Room not found")
                    return@launch
                }

                val room = roomDoc.toObject(Room::class.java)
                if (room == null) {
                    Log.e("RoomViewModel", "Failed to convert room document")
                    _roomState.value = RoomState.Error("Failed to convert room document")
                    return@launch
                }
                
                // Check if the user is the creator or a member of the room
                if (room.creatorId != userId && !room.memberIds.contains(userId)) {
                    Log.e("RoomViewModel", "User $userId is not a member of room $roomId")
                    
                    // Add the user to the room's memberIds list
                    db.collection("rooms").document(roomId)
                        .update("memberIds", FieldValue.arrayUnion(userId))
                        .await()
                    
                    Log.d("RoomViewModel", "Added user $userId to room $roomId")
                }
                
                // Now set up the listener for room updates
                roomListener?.remove()
                roomListener = db.collection("rooms")
                    .document(roomId)
                    .addSnapshotListener { snapshot, error ->
                        if (error != null) {
                            Log.e("RoomViewModel", "Error listening to room updates", error)
                            _roomState.value = RoomState.Error(error.message ?: "Failed to listen to room updates")
                            return@addSnapshotListener
                        }
                        if (snapshot == null || !snapshot.exists()) {
                            Log.e("RoomViewModel", "Room document does not exist for ID: $roomId")
                            _roomState.value = RoomState.Error("Room not found")
                            return@addSnapshotListener
                        }
                        val updatedRoom = snapshot.toObject(Room::class.java)?.copy(id = roomId)
                        if (updatedRoom != null) {
                            Log.d("RoomViewModel", "Successfully converted document to Room object: ${updatedRoom.name}")
                            _currentRoom.value = updatedRoom
                            _roomState.value = RoomState.Success(listOf(updatedRoom))
                            listenToRoomMessages(roomId)
                        } else {
                            Log.e("RoomViewModel", "Failed to convert room document")
                            _roomState.value = RoomState.Error("Failed to convert room document")
                        }
                    }
            } catch (e: Exception) {
                Log.e("RoomViewModel", "Error fetching room", e)
                _roomState.value = RoomState.Error(e.message ?: "Failed to fetch room")
            }
        }
    }

    fun getRoomMessages(roomId: String) {
        viewModelScope.launch {
            val messages = db.collection("rooms").document(roomId).collection("messages").get().await()
            val nonNullMessages = messages.documents.mapNotNull { it.toObject(Room.Message::class.java) }
            _roomMessages.value = nonNullMessages
            markMessagesAsRead(roomId, nonNullMessages)
        }
    }

    private fun markMessagesAsRead(roomId: String, messages: List<Room.Message>) {
        viewModelScope.launch {
            try {
                val currentUser = auth.currentUser ?: return@launch
                val roomRef = db.collection("rooms").document(roomId)
                
                val batch = db.batch()
                
                // Update each unread message
                messages.forEach { message ->
                    if (!message.readBy.contains(currentUser.uid)) {
                        val messageRef = roomRef.collection("messages").document(message.id)
                        batch.update(messageRef, "readBy", FieldValue.arrayUnion(currentUser.uid))
                    }
                }
                
                // Reset unread count for current user
                val unreadCountsDoc = roomRef.get().await()
                // Handle both Integer and Long types for unread counts
                val rawUnreadCounts = unreadCountsDoc.get("unreadCounts") as? Map<String, Any> ?: mapOf()
                val currentUnreadCounts = rawUnreadCounts.mapValues { (_, value) -> 
                    when (value) {
                        is Long -> value.toInt()
                        is Int -> value
                        else -> 0
                    }
                }
                val updatedUnreadCounts = currentUnreadCounts.toMutableMap()
                updatedUnreadCounts[currentUser.uid] = 0
                
                batch.update(roomRef, "unreadCounts", updatedUnreadCounts)
                batch.commit().await()
                
                // Update local state
                _unreadMessageCount.value = _unreadMessageCount.value + (roomId to 0)
                
                // Refresh messages to reflect updated read status
                val updatedMessages = roomRef.collection("messages").get().await()
                val nonNullMessages = updatedMessages.documents.mapNotNull { it.toObject(Room.Message::class.java) }
                _roomMessages.value = nonNullMessages
                
                Log.d("RoomViewModel", "Messages marked as read for room: $roomId")
            } catch (e: Exception) {
                Log.e("RoomViewModel", "Error marking messages as read", e)
            }
        }
    }

    fun sendMessage(roomId: String, message: Room.Message) {
        viewModelScope.launch {
            try {
                val currentUser = auth.currentUser ?: throw SecurityException("User not authenticated")
                val roomRef = db.collection("rooms").document(roomId)
                
                // First check if room exists
                val roomDoc = roomRef.get().await()
                if (!roomDoc.exists()) {
                    Log.e("RoomViewModel", "Room not found when sending message: $roomId")
                    return@launch
                }
                
                val room = roomDoc.toObject(Room::class.java)
                if (room == null) {
                    Log.e("RoomViewModel", "Failed to convert room document to Room object")
                    return@launch
                }
                
                // Get the member code for the current user
                val memberCode = room.members.find { it.userId == currentUser.uid }?.memberCode ?: ""
                
                // Add a retry mechanism for transaction
                var retryCount = 0
                val maxRetries = 3
                var success = false
                
                while (!success && retryCount < maxRetries) {
                    try {
                        // Use a transaction to ensure atomicity
                        db.runTransaction { transaction ->
                            // Verify room still exists in transaction
                            val roomSnapshot = transaction.get(roomRef)
                            if (!roomSnapshot.exists()) {
                                throw Exception("Room no longer exists")
                            }
                            
                            val messageRef = roomRef.collection("messages").document()
                            val messageData = hashMapOf(
                                "id" to messageRef.id,
                                "senderId" to message.senderId,
                                "senderName" to message.senderName,
                                "content" to message.content,
                                "timestamp" to message.timestamp,
                                "type" to message.type,
                                "isAnonymous" to false,  // Force messages to not be anonymous
                                "senderMemberCode" to memberCode,  // Changed from sender_member_code to match rules
                                "readBy" to listOf(currentUser.uid) // Mark as read by sender
                            )
                            
                            // Set the message
                            transaction.set(messageRef, messageData)
                            
                            // Get current unread counts - handle both Integer and Long types
                            val rawUnreadCounts = roomSnapshot.get("unreadCounts") as? Map<String, Any> ?: mapOf()
                            val currentUnreadCounts = rawUnreadCounts.mapValues { (_, value) -> 
                                when (value) {
                                    is Long -> value.toInt()
                                    is Int -> value
                                    else -> 0
                                }
                            }
                            
                            // Update unread counts for all members except sender
                            val updatedUnreadCounts = room.members.associate { member ->
                                if (member.userId != currentUser.uid) {
                                    member.userId to (currentUnreadCounts[member.userId] ?: 0) + 1
                                } else {
                                    member.userId to (currentUnreadCounts[member.userId] ?: 0)
                                }
                            }
                            
                            // Update room with unread counts and last message info
                            transaction.update(roomRef, mapOf(
                                "unreadCounts" to updatedUnreadCounts,
                                "last_message" to message.content,
                                "last_message_timestamp" to message.timestamp
                            ))
                        }.await()
                        
                        success = true
                        Log.d("RoomViewModel", "Message sent successfully with updated unread counts")
                    } catch (e: Exception) {
                        retryCount++
                        Log.e("RoomViewModel", "Failed to send message (attempt $retryCount/$maxRetries)", e)
                        if (retryCount >= maxRetries) {
                            Log.e("RoomViewModel", "Max retries reached, giving up on sending message")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("RoomViewModel", "Failed to send message", e)
                // Don't throw the exception, just log it to prevent app crashes
            }
        }
    }

    fun createRoom(name: String, description: String, restrictMembers: Boolean = false, maxMembers: Int = 500) {
        viewModelScope.launch {
            _createRoomState.value = RoomState.Loading
            try {
                val currentUser = auth.currentUser
                if (currentUser == null) {
                    _createRoomState.value = RoomState.Error("User not authenticated")
                    return@launch
                }
                
                val userId = currentUser.uid
                val roomRef = db.collection("rooms").document()
                val initialMember = RoomMember(
                    userId = userId,
                    displayName = currentUser.displayName ?: "Anonymous",
                    memberCode = UUID.randomUUID().toString().take(4).uppercase()
                )

                val roomData = Room(
                    id = roomRef.id,
                    name = name,
                    description = description,
                    restrictMembers = restrictMembers,
                    maxMembers = maxMembers,
                    creatorId = userId,
                    memberIds = listOf(userId),
                    members = listOf(initialMember)
                )
                roomRef.set(roomData).await()
                _createRoomState.value = RoomState.Success()
            } catch (e: Exception) {
                _createRoomState.value = RoomState.Error(e.message ?: "Failed to create room")
            }
        }
    }

    fun listenToRoomMessages(roomId: String) {
        viewModelScope.launch {
            try {
                messagesListener?.remove()
                val roomRef = db.collection("rooms").document(roomId)
                val messagesRef = roomRef.collection("messages")
                messagesListener = messagesRef
                    .orderBy("timestamp")
                    .addSnapshotListener { snapshot, error ->
                        if (error != null) {
                            Log.e("RoomViewModel", "Error listening to room messages", error)
                            return@addSnapshotListener
                        }
                        if (snapshot == null) {
                            Log.e("RoomViewModel", "Messages snapshot is null")
                            return@addSnapshotListener
                        }

                        val currentUser = auth.currentUser ?: return@addSnapshotListener
                        val messages = snapshot.documents.mapNotNull { it.toObject(Room.Message::class.java) }
                        
                        // Update messages in UI
                        _roomMessages.value = messages
                        
                        // Update unread count when messages change
                        updateUnreadCount(roomId)
                        
                        // Send notifications only for new messages
                        snapshot.documentChanges.forEach { change ->
                            if (change.type == com.google.firebase.firestore.DocumentChange.Type.ADDED) {
                                val message = change.document.toObject(Room.Message::class.java)
                                val currentTime = System.currentTimeMillis()
                                // Only notify if:
                                // 1. Message is from someone else
                                // 2. Message is recent (less than 5 seconds old)
                                // 3. Message hasn't been read by current user
                                if (message.senderId != currentUser.uid && 
                                    currentTime - message.timestamp <= 5000 && 
                                    !message.readBy.contains(currentUser.uid)) {
                                    // Only send local notification if the message is from someone else
                                    if (message.senderId != currentUser.uid) {
                                        sendNotification(roomId, message)
                                    }
                                    // Send FCM notifications to other room members when receiving a message from someone else
                                    if (message.senderId != currentUser.uid) {
                                        sendNotificationToRoomMembers(message, roomId)
                                    }
                                }
                            }
                        }
                    }
            } catch (e: Exception) {
                Log.e("RoomViewModel", "Failed to listen to room messages", e)
            }
        }
    }

    private val _notificationPermissionRequired = MutableStateFlow(false)
    val notificationPermissionRequired: StateFlow<Boolean> = _notificationPermissionRequired.asStateFlow()

    fun setNotificationPermissionRequired(required: Boolean) {
        _notificationPermissionRequired.value = required
    }

    fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permissionGranted = NotificationManagerCompat.from(context)
                .areNotificationsEnabled() && context.checkSelfPermission(
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            
            _notificationPermissionRequired.value = !permissionGranted
        }
    }

    private fun sendNotification(roomId: String, message: Room.Message) {
        viewModelScope.launch {
            try {
                // Check notification permission for Android 13 and above
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val permissionGranted = NotificationManagerCompat.from(context)
                        .areNotificationsEnabled() && context.checkSelfPermission(
                            android.Manifest.permission.POST_NOTIFICATIONS
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    
                    if (!permissionGranted) {
                        _notificationPermissionRequired.value = true
                        Log.w("RoomViewModel", "Notification permission not granted")
                        return@launch
                    }
                }

                // Create notification channel for Android 8.0 and above
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val channelId = "message_notifications"
                    val channelName = "Message Notifications"
                    val importance = android.app.NotificationManager.IMPORTANCE_HIGH
                    val channel = android.app.NotificationChannel(channelId, channelName, importance).apply {
                        description = "Notifications for new messages in rooms"
                        enableLights(true)
                        enableVibration(true)
                        setShowBadge(true)
                        lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                        setBypassDnd(true)
                    }
                    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                    notificationManager.createNotificationChannel(channel)
                }

                // Get room name for notification
                val room = db.collection("rooms").document(roomId).get().await().toObject(Room::class.java)
                val roomName = room?.name ?: "Chat Room"

                // Create intent to open the room when notification is tapped
                val intent = Intent(context, RoomActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("ROOM_ID", roomId)
                }
                
                val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                
                val pendingIntent = PendingIntent.getActivity(
                    context,
                    roomId.hashCode(),
                    intent,
                    pendingIntentFlags
                )

                // Build and show notification with high priority and all defaults
                val notification = NotificationCompat.Builder(context, "message_notifications")
                    .setContentTitle(roomName)
                    .setContentText("${message.senderName}: ${message.content}")
                    .setSmallIcon(android.R.drawable.ic_dialog_email)
                    .setAutoCancel(true)
                    .setDefaults(NotificationCompat.DEFAULT_ALL)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setContentIntent(pendingIntent)
                    .setOnlyAlertOnce(false)
                    .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_ALL)
                    .build()

                try {
                    NotificationManagerCompat.from(context).notify(message.id.hashCode(), notification)
                } catch (e: SecurityException) {
                    _notificationPermissionRequired.value = true
                    Log.e("RoomViewModel", "Failed to post notification due to permission denial", e)
                } catch (e: Exception) {
                    Log.e("RoomViewModel", "Failed to post notification", e)
                }
            } catch (e: Exception) {
                Log.e("RoomViewModel", "Error in sendNotification", e)
            }
        }
    }

    fun fetchRoomMessages(roomId: String) {
        viewModelScope.launch {
            try {
                val messages = db.collection("rooms").document(roomId).collection("messages").get().await()
                val nonNullMessages = messages.documents.mapNotNull { it.toObject(Room.Message::class.java) }
                _roomMessages.value = nonNullMessages
            } catch (e: Exception) {
                Log.e("RoomViewModel", "Error fetching room messages", e)
            }
        }
    }

    fun addMember(roomId: String, userId: String) {
        viewModelScope.launch {
            try {
                val roomRef = db.collection("rooms").document(roomId)
                roomRef.update("members", FieldValue.arrayUnion(userId)).await()
                Log.d("RoomViewModel", "User added to room successfully")
            } catch (e: Exception) {
                Log.e("RoomViewModel", "Failed to add member", e)
            }
        }
    }

    fun joinRoomByCode(code: String) {
        viewModelScope.launch {
            try {
                Log.d("RoomViewModel", "Attempting to join room with code: $code")
                _joinRoomState.value = RoomState.Loading
                val currentUser = auth.currentUser
                if (currentUser == null) {
                    _joinRoomState.value = RoomState.Error("User not authenticated")
                    return@launch
                }

                // Find the room by code
                val roomRef = db.collection("rooms").whereEqualTo("roomCode", code).get().await()
                
                if (roomRef.documents.isEmpty()) {
                    Log.e("RoomViewModel", "Room not found with code: $code")
                    _joinRoomState.value = RoomState.Error("Room not found")
                    return@launch
                }
                
                val roomId = roomRef.documents[0].id
                
                // Use the repository method to join the room
                val result = roomRepository.joinRoom(roomId, code)
                if (result.isSuccess) {
                    // Refresh user's room list
                    fetchUserRooms(currentUser.uid)
                    val room = result.getOrNull()!!
                    _joinRoomState.value = RoomState.Success(listOf(room))
                    Log.d("RoomViewModel", "Successfully joined room: $roomId")
                    Log.d("RoomViewModel", "User ${currentUser.uid} joined room $roomId successfully")
                } else {
                    val error = result.exceptionOrNull()
                    Log.e("RoomViewModel", "Failed to join room", error)
                    _joinRoomState.value = RoomState.Error(error?.message ?: "Failed to join room")
                }
            } catch (e: Exception) {
                Log.e("RoomViewModel", "Failed to join room by code", e)
                _joinRoomState.value = RoomState.Error(e.message ?: "Failed to join room by code")
            }
        }
    }

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    fun fetchUserRooms(userId: String) {
        viewModelScope.launch {
            try {
                _roomState.value = RoomState.Loading
                _isRefreshing.value = true
                
                // Use the repository method to get user rooms instead of direct Firestore query
                val rooms = roomRepository.getUserRooms(userId)
                
                _userRooms.value = rooms
                _roomState.value = RoomState.Success(rooms)
                
                Log.d("RoomViewModel", "Fetched ${rooms.size} rooms for user $userId")
            } catch (e: Exception) {
                Log.e("RoomViewModel", "Error fetching user rooms", e)
                _roomState.value = RoomState.Error(e.message ?: "Failed to fetch rooms")
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun stopListeningToRoomMessages() {
        messagesListener?. remove()
    }

    fun resetCreateRoomState() {
        _createRoomState.value = RoomState.Initial
    }

    fun resetJoinRoomState() {
        _joinRoomState.value = RoomState.Initial
    }

    fun resetRoomState() {
        _roomState.value = RoomState.Initial
    }

    fun resetState() {
        _roomState.value = RoomState.Initial
        _currentRoom.value = null
        _errorState.value = null
    }

    fun getRoomName(roomId: String) {
        viewModelScope.launch {
            try {
                val roomRef = db.collection("rooms").document(roomId)
                val roomSnapshot = roomRef.get().await()
                if (roomSnapshot.exists()) {
                    val room = roomSnapshot.toObject(Room::class.java)
                    _roomName.value = room?.name
                } else {
                    Log.e("RoomViewModel", "Room not found: $roomId")
                    _roomName.value = null
                }
            } catch (e: Exception) {
                Log.e("RoomViewModel", "Error fetching room name", e)
                _roomName.value = null
            }
        }
    }

    private var roomListListener: ListenerRegistration? = null

    init {
        setupRoomListListener()
    }

    private fun setupRoomListListener() {
        val currentUser = auth.currentUser ?: return
        
        // Remove existing listener if any
        roomListListener?.remove()
        
        // Set up new listener
        roomListListener = db.collection("rooms")
            .whereArrayContains("memberIds", currentUser.uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e("RoomViewModel", "Error in room list listener", error)
                    return@addSnapshotListener
                }
                viewModelScope.launch {
                    try {
                        val rooms = roomRepository.getUserRooms(currentUser.uid)
                        _userRooms.value = rooms
                        _roomState.value = RoomState.Success(rooms)
                    } catch (e: Exception) {
                        Log.e("RoomViewModel", "Error fetching user rooms in listener", e)
                    }
                }
            }
    }

    fun leaveRoom(roomId: String) {
        viewModelScope.launch {
            try {
                val currentUser = auth.currentUser
                if (currentUser == null) {
                    _errorState.value = ErrorState("User not authenticated")
                    return@launch
                }

                // Stop listening to room messages before leaving
                messagesListener?.remove()
                messagesListener = null
                
                // Stop listening to room changes
                roomListener?.remove()
                roomListener = null
                
                _roomState.value = RoomState.Loading
                val result = roomRepository.leaveRoom(roomId)
                if (result.isSuccess) {
                    Log.d("RoomViewModel", "Successfully left room $roomId")
                    
                    // Clear current room data
                    _currentRoom.value = null
                    _roomMessages.value = emptyList()
                    _roomName.value = null
                    _roomState.value = RoomState.Success()
                    
                    // Reset all states to ensure clean state for next room access
                    resetState()
                    resetJoinRoomState()
                    resetCreateRoomState()
                    
                    // Refresh room list immediately
                    val rooms = roomRepository.getUserRooms(currentUser.uid)
                    _userRooms.value = rooms
                    
                    // Navigate back
                    _navigationEvent.value = NavigationEvent.NavigateBack
                } else {
                    val exception = result.exceptionOrNull()
                    Log.e("RoomViewModel", "Failed to leave room", exception)
                    _errorState.value = ErrorState("Failed to leave room: ${exception?.message ?: "Unknown error"}")
                    _roomState.value = RoomState.Error("Failed to leave room")
                }
            } catch (e: Exception) {
                Log.e("RoomViewModel", "Error leaving room", e)
                _errorState.value = ErrorState("Error leaving room: ${e.message}")
                _roomState.value = RoomState.Error("Error leaving room")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        roomListListener?.remove()
        roomListener?.remove()
        messagesListener?.remove()
    }

    fun deleteRoom(roomId: String, permanentDelete: Boolean = false) {
        viewModelScope.launch {
            try {
                val currentUser = auth.currentUser
                if (currentUser == null) {
                    _roomState.value = RoomState.Error("User not authenticated")
                    return@launch
                }

                // Stop listening to room messages before deletion
                messagesListener?.remove()
                messagesListener = null
                
                // Stop listening to room changes
                roomListener?.remove()
                roomListener = null
                
                _roomState.value = RoomState.Loading
                val result = roomRepository.deleteRoom(roomId, permanentDelete)
                
                if (result.isSuccess) {
                    // Clear local data for this room
                    _roomMessages.value = emptyList()
                    _currentRoom.value = null
                    
                    // Refresh the user's rooms list
                    fetchUserRooms(currentUser.uid)
                    val actionType = if (permanentDelete) "permanently deleted" else "removed from your list"
                    Log.d("RoomViewModel", "Room $actionType successfully: $roomId")
                    _roomState.value = RoomState.Success()
                    
                    // If room was permanently deleted or removed from list, navigate back
                    _navigationEvent.value = NavigationEvent.NavigateBack
                } else {
                    val error = result.exceptionOrNull()?.message ?: "Failed to delete room"
                    Log.e("RoomViewModel", "Failed to delete room: $error")
                    _roomState.value = RoomState.Error(error)
                }
            } catch (e: Exception) {
                Log.e("RoomViewModel", "Failed to delete room", e)
                _roomState.value = RoomState.Error(e.message ?: "Failed to delete room")
            }
        }
    }
    private fun sendNotificationToRoomMembers(message: Room.Message, roomId: String) {
        viewModelScope.launch {
            try {
                val currentUserId = auth.currentUser?.uid ?: run {
                    Log.e("RoomViewModel", "Current user is null")
                    return@launch
                }
                
                Log.d("RoomViewModel", "[sendNotificationToRoomMembers] Starting for room: $roomId")
                Log.d("RoomViewModel", "[sendNotificationToRoomMembers] Current user: $currentUserId")
                
                val roomRef = db.collection("rooms").document(roomId)
                val roomSnapshot = roomRef.get().await()
                
                if (!roomSnapshot.exists()) {
                    Log.e("RoomViewModel", "[sendNotificationToRoomMembers] Room not found: $roomId")
                    return@launch
                }
                
                val room = roomSnapshot.toObject(Room::class.java) ?: run {
                    Log.e("RoomViewModel", "[sendNotificationToRoomMembers] Failed to parse room data")
                    return@launch
                }
                
                Log.d("RoomViewModel", "[sendNotificationToRoomMembers] Room name: ${room.name}")
                Log.d("RoomViewModel", "[sendNotificationToRoomMembers] Member IDs: ${room.memberIds}")
                
                val otherMemberIds = room.memberIds.filter { it != currentUserId }
                
                if (otherMemberIds.isEmpty()) {
                    Log.d("RoomViewModel", "[sendNotificationToRoomMembers] No other members to notify")
                    return@launch
                }
                
                Log.d("RoomViewModel", "[sendNotificationToRoomMembers] Sending to member IDs: $otherMemberIds")

                userRepository.getFcmTokensForUsers(otherMemberIds).onSuccess { tokens ->
                    Log.d("RoomViewModel", "[sendNotificationToRoomMembers] Retrieved ${tokens.size} FCM tokens")
                    
                    if (tokens.isEmpty()) {
                        Log.d("RoomViewModel", "[sendNotificationToRoomMembers] No FCM tokens found for room members")
                        return@onSuccess
                    }
                    
                    // Log first few tokens for debugging (don't log all to avoid log spam)
                    val tokensToLog = if (tokens.size > 3) tokens.take(3) + "... (${tokens.size - 3} more)" else tokens
                    Log.d("RoomViewModel", "[sendNotificationToRoomMembers] Tokens: $tokensToLog")

                    // Prepare the data for the Firebase Cloud Function
                    val data = hashMapOf(
                        "tokens" to tokens,
                        "title" to room.name,
                        "body" to "${message.senderName}: ${message.content}",
                        "room_id" to roomId,
                        "message_id" to message.id,
                        "sender_id" to currentUserId,
                        "timestamp" to System.currentTimeMillis().toString() // Ensure timestamp is a string
                    )

                    Log.d("RoomViewModel", "[sendNotificationToRoomMembers] Calling Cloud Function with data: $data")

                    // Call the Firebase Cloud Function with retry mechanism
                    callCloudFunctionWithRetry("sendNotification", data)
                }.onFailure { e ->
                    Log.e("RoomViewModel", "[sendNotificationToRoomMembers] Error getting FCM tokens", e)
                }
            } catch (e: Exception) {
                Log.e("RoomViewModel", "[sendNotificationToRoomMembers] Unexpected error", e)
                e.printStackTrace()
            }
        }
    }
    
    /**
     * Call a Firebase Cloud Function with exponential backoff retry mechanism
     * @param functionName The name of the Cloud Function to call
     * @param data The data to pass to the Cloud Function
     * @param maxRetries Maximum number of retries (default: 3)
     * @param initialDelayMs Initial delay in milliseconds before first retry (default: 1000)
     */
    private fun callCloudFunctionWithRetry(
        functionName: String,
        data: Map<String, Any>,
        maxRetries: Int = 3,
        initialDelayMs: Long = 1000
    ) {
        var retryCount = 0
        var delayMs = initialDelayMs

        // Define the executeCall function first
        fun executeCall() {
            functions.getHttpsCallable(functionName)
                .call(data)
                .addOnSuccessListener { result ->
                    val resultData = result?.data as? Map<*, *> ?: emptyMap<Any, Any>()
                    Log.d("RoomViewModel", "[$functionName] Cloud function success: $resultData")

                    // Log detailed results if available
                    if (resultData.containsKey("responses")) {
                        val responses = resultData["responses"] as? List<*>
                        if (responses != null) {
                            responses.forEachIndexed { index, response ->
                                val success = (response as? Map<*, *>)?.get("success") ?: false
                                val messageId = (response as? Map<*, *>)?.get("messageId") ?: "none"
                                Log.d("RoomViewModel", "[$functionName] Response $index: success=$success, messageId=$messageId")
                            }
                        }
                    }

                    // Log the success with success count if available
                    val successCount = resultData["successCount"] as? Int ?: 0
                    Log.d("RoomViewModel", "[$functionName] Function call successful with $successCount successes")

                    // Log any failures
                    val failureCount = resultData["failureCount"] as? Int ?: 0
                    if (failureCount > 0) {
                        Log.e("RoomViewModel", "[$functionName] Failed to process $failureCount items")
                    }
                }
                .addOnFailureListener { e ->
                    Log.e("RoomViewModel", "[$functionName] Error calling Cloud Function", e)

                    // Log more details about the error
                    if (e is com.google.firebase.functions.FirebaseFunctionsException) {
                        Log.e("RoomViewModel", "[$functionName] Error calling Cloud Function (Ask Gemini)", e)
                        Log.e("RoomViewModel", "[$functionName] Error details: ${e.message}")
                        Log.e("RoomViewModel", "[$functionName] Error code: ${e.code}")
                        Log.e("RoomViewModel", "[$functionName] Error cause: ${e.cause?.message}")

                        // Extract and log the full error details if available
                        try {
                            val errorDetails = e.details as? Map<*, *>
                            if (errorDetails != null) {
                                Log.e("RoomViewModel", "[$functionName] Error details from server: $errorDetails")
                                Log.e("RoomViewModel", "[$functionName] Stack trace from server: ${errorDetails["stack"]}")
                            }
                        } catch (ex: Exception) {
                            Log.e("RoomViewModel", "[$functionName] Error parsing error details", ex)
                        }

                        // Only retry for certain error codes
                        if (retryCount < maxRetries &&
                            (e.code == com.google.firebase.functions.FirebaseFunctionsException.Code.INTERNAL ||
                             e.code == com.google.firebase.functions.FirebaseFunctionsException.Code.UNAVAILABLE ||
                             e.code == com.google.firebase.functions.FirebaseFunctionsException.Code.DEADLINE_EXCEEDED)) {

                            Log.d("RoomViewModel", "[$functionName] Retrying call (${retryCount + 1}/$maxRetries) after $delayMs ms")

                            // Increase retry count
                            retryCount++

                            // Use exponential backoff for retries
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                executeCall()
                            }, delayMs)

                            // Increase delay for next retry (exponential backoff)
                            delayMs *= 2
                        } else {
                            Log.e("RoomViewModel", "[$functionName] Not retrying after error: ${e.code}")

                            // Show a user-friendly error message
                            _errorState.value = ErrorState(
                                "Failed to send notification: ${e.message ?: "Unknown error"}"
                            )
                        }
                    } else {
                        Log.e("RoomViewModel", "[$functionName] Non-Firebase exception", e)
                    }
                }
        }

        // Start the initial call
        executeCall()
    }

    private suspend fun sendFcmNotification(notification: Map<String, Any>) {
        // Implement your FCM notification sending logic here
        // This could be a call to your backend server or Firebase Admin SDK
    }
}
