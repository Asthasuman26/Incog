package com.myapp.privateroom.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.FirebaseFirestore
import com.myapp.privateroom.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

sealed class UsernameUpdateState {
    object Initial : UsernameUpdateState()
    object Loading : UsernameUpdateState()
    object Success : UsernameUpdateState()
    data class Error(val message: String) : UsernameUpdateState()
}

sealed class AuthState {
    object Initial : AuthState()
    object Loading : AuthState()
    object Success : AuthState()
    data class Error(val message: String) : AuthState()
}

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val userRepository: UserRepository
) : ViewModel() {
    private val auth = FirebaseAuth.getInstance()
    
    private val _authState = MutableStateFlow<AuthState>(AuthState.Initial)
    val authState: StateFlow<AuthState> = _authState

    private val _usernameUpdateState = MutableStateFlow<UsernameUpdateState>(UsernameUpdateState.Initial)
    val usernameUpdateState: StateFlow<UsernameUpdateState> = _usernameUpdateState

    fun login(email: String, password: String) {
        viewModelScope.launch {
            try {
                _authState.value = AuthState.Loading
                Log.d("AuthViewModel", "Attempting login for email: $email")
                
                auth.signInWithEmailAndPassword(email, password)
                    .addOnSuccessListener {
                        Log.d("AuthViewModel", "Login successful")
                        viewModelScope.launch {
                            _authState.value = AuthState.Success
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e("AuthViewModel", "Login failed", e)
                        viewModelScope.launch {
                            _authState.value = AuthState.Error(e.message ?: "Login failed")
                        }
                    }
            } catch (e: Exception) {
                Log.e("AuthViewModel", "Exception during login", e)
                _authState.value = AuthState.Error(e.message ?: "Login failed")
            }
        }
    }

    fun reAuthenticate(email: String, password: String) {
        viewModelScope.launch {
            try {
                _authState.value = AuthState.Loading
                Log.d("AuthViewModel", "Attempting re-authentication for email: $email")
                
                val credential = com.google.firebase.auth.EmailAuthProvider.getCredential(email, password)
                val user = auth.currentUser
                
                if (user == null) {
                    _authState.value = AuthState.Error("User not authenticated")
                    return@launch
                }
                
                user.reauthenticate(credential).await()
                Log.d("AuthViewModel", "Re-authentication successful")
                _authState.value = AuthState.Success
            } catch (e: Exception) {
                Log.e("AuthViewModel", "Re-authentication failed", e)
                _authState.value = AuthState.Error(e.message ?: "Re-authentication failed")
            }
        }
    }

    fun register(email: String, password: String) {
        viewModelScope.launch {
            try {
                _authState.value = AuthState.Loading
                // Create the Firebase Auth user
                auth.createUserWithEmailAndPassword(email, password).await()

                // Create a user profile in Firestore
                val username = email.split("@")[0].lowercase()
                val result = userRepository.createOrUpdateUserProfile(username = username)

                if (result.isSuccess) {
                    Log.d("AuthViewModel", "User profile created successfully")
                    _authState.value = AuthState.Success
                } else {
                    Log.e("AuthViewModel", "Failed to create user profile", result.exceptionOrNull())
                    _authState.value = AuthState.Error(result.exceptionOrNull()?.message ?: "Failed to create user profile")
                }
            } catch (e: Exception) {
                Log.e("AuthViewModel", "Registration failed", e)
                _authState.value = AuthState.Error(e.message ?: "Registration failed")
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            try {
                auth.signOut()
                _authState.value = AuthState.Initial
            } catch (e: Exception) {
                _authState.value = AuthState.Error(e.message ?: "Sign out failed")
            }
        }
    }

    fun deleteAccount() {
        viewModelScope.launch {
            try {
                _authState.value = AuthState.Loading
                val currentUser = auth.currentUser
                if (currentUser == null) {
                    _authState.value = AuthState.Error("User not authenticated")
                    return@launch
                }

                // Check if email is verified
                if (!currentUser.isEmailVerified) {
                    currentUser.sendEmailVerification().await()
                    _authState.value = AuthState.Error("Please verify your email before deleting your account. A verification email has been sent.")
                    return@launch
                }

                // Check if user has recently authenticated
                val metadata = currentUser.metadata
                if (metadata != null && System.currentTimeMillis() - metadata.lastSignInTimestamp > 300000) { // 5 minutes
                    _authState.value = AuthState.Error("Please re-authenticate before deleting your account")
                    return@launch
                }

                // First delete user profile and data
                val result = userRepository.deleteUserProfile()
                if (result.isSuccess) {
                    Log.d("AuthViewModel", "User profile deleted successfully")
                    
                    // Then delete the Firebase Auth user account
                    currentUser.delete().await()
                    Log.d("AuthViewModel", "Firebase user account deleted successfully")
                    
                    _authState.value = AuthState.Success
                } else {
                    Log.e("AuthViewModel", "Failed to delete user profile", result.exceptionOrNull())
                    _authState.value = AuthState.Error(result.exceptionOrNull()?.message ?: "Failed to delete account")
                }
            } catch (e: Exception) {
                Log.e("AuthViewModel", "Failed to delete account", e)
                _authState.value = AuthState.Error(e.message ?: "Failed to delete account")
            }
        }
    }

    fun updateUsername(newUsername: String) {
        viewModelScope.launch {
            try {
                _usernameUpdateState.value = UsernameUpdateState.Loading
                val user = auth.currentUser
                if (user != null) {
                    val profileUpdates = UserProfileChangeRequest.Builder()
                        .setDisplayName(newUsername)
                        .build()

                    user.updateProfile(profileUpdates).await()
                    _usernameUpdateState.value = UsernameUpdateState.Success
                } else {
                    _usernameUpdateState.value = UsernameUpdateState.Error("User not logged in")
                }
            } catch (e: Exception) {
                Log.e("AuthViewModel", "Failed to update username", e)
                _usernameUpdateState.value = UsernameUpdateState.Error(e.message ?: "Failed to update username")
            }
        }
    }
}