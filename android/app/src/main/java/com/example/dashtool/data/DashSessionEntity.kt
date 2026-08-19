package com.example.dashtool.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/*
 * One row represents one period between pressing
 * Start DashTool and Stop DashTool.
 */
@Entity(
    tableName = "dash_sessions"
)
data class DashSessionEntity(

    @PrimaryKey
    val sessionId: String,

    /*
     * Wall time preserves the real date and time.
     */
    val startedAtWallTime: Long,

    /*
     * Elapsed time provides reliable duration
     * calculations even if the phone clock changes.
     */
    val startedAtElapsedTime: Long,

    /*
     * These remain null while the session is active.
     */
    val endedAtWallTime: Long? = null,

    val endedAtElapsedTime: Long? = null
)