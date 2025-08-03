package com.myapp.privateroom.model

import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.PropertyName

data class UserProfile(
    @DocumentId
    val id: String = "",

    @PropertyName("user_id")
    val userId: String = "",

    @PropertyName("username")
    val username: String = "",

    @PropertyName("display_name")
    val displayName: String = "",

    @PropertyName("profile_image_url")
    val profileImageUrl: String? = null,

    @PropertyName("created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @PropertyName("email")
    val email: String? = null,

    @PropertyName("fcm_token")
    val fcmToken: String? = null,
    @PropertyName("unique_id")
    val uniqueId: String = userId // Default to userId if not explicitly set
) {
    companion object {
        fun generateUsername(email: String): String {
            // Extract username from email, removing special characters
            return email.split("@")[0]
                .replace("[^A-Za-z0-9]".toRegex(), "")
                .lowercase()
        }
    }
}
