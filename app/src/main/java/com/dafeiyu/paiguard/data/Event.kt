package com.dafeiyu.paiguard.data

/** 一条防护事件。kind 见 Reporter 里的常量。 */
data class Event(
    val id: Long = 0L,
    val kind: String,
    val title: String,
    val detail: String,
    val count: Int,
    val time: Long
) {
    val isSevere: Boolean
        get() = kind == KIND_FATAL

    companion object {
        const val KIND_TRIP = "trip"
        const val KIND_BLOCK = "block"
        const val KIND_FATAL = "fatal"
        const val KIND_INFO = "info"
    }
}
