package com.example.janus.client

/** A participant's role in a Janus room - a HOST may create the room when it doesn't exist. */
enum class UserRole {
    HOST,
    GUEST
}
