package com.ai.assistance.operit.core.awareness

/** Foreground time is measured monotonically; merely selecting an assistant is not a message. */
class SoftwarePresence {
    data class View(val chat: String, val role: String, val name: String, val group: String)
    data class Event(val kind: String, val at: Long, val view: View? = null, val entered: Long? = null, val duration: Long? = null)
    private data class Span(val wall: Long, val tick: Long)
    private var recording = false
    private var foreground = false
    private var app: Span? = null
    private val views = linkedMapOf<String, View>()
    private val visits = linkedMapOf<String, Span>()

    fun recording(value: Boolean, wall: Long, tick: Long): List<Event> {
        if (recording == value) return emptyList()
        recording = value
        app = null; visits.clear()
        return if (value && foreground) enter(wall, tick) else emptyList()
    }

    fun foreground(value: Boolean, wall: Long, tick: Long): List<Event> {
        if (foreground == value) return emptyList()
        foreground = value
        if (!recording) return emptyList()
        return if (value) enter(wall, tick) else buildList {
            views.forEach { (id, view) -> finish(id, view, wall, tick)?.let(::add) }
            app?.let { add(Event("app_background", wall, entered = it.wall, duration = (tick - it.tick).coerceAtLeast(0))) }
            app = null
        }
    }

    private fun enter(wall: Long, tick: Long): List<Event> {
        app = Span(wall, tick)
        return listOf(Event("app_foreground", wall)) + views.map { (id, view) ->
            visits[id] = Span(wall, tick)
            Event("chat_enter", wall, view)
        }
    }

    fun view(id: String, value: View?, wall: Long, tick: Long): List<Event> {
        if (views[id] == value) return emptyList()
        return buildList {
            views.remove(id)?.let { old -> finish(id, old, wall, tick)?.let(::add) }
            if (value != null) {
                views[id] = value
                if (recording && foreground) {
                    visits[id] = Span(wall, tick)
                    add(Event("chat_enter", wall, value))
                }
            }
        }
    }

    private fun finish(id: String, view: View, wall: Long, tick: Long): Event? =
        visits.remove(id)?.let { Event("chat_leave", wall, view, it.wall, (tick - it.tick).coerceAtLeast(0)) }

    fun active(wall: Long, tick: Long): List<Event> = visits.mapNotNull { (id, span) ->
        views[id]?.let { Event("chat_visible", wall, it, span.wall, (tick - span.tick).coerceAtLeast(0)) }
    }
}
