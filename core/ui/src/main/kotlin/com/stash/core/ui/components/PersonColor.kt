package com.stash.core.ui.components

import androidx.compose.ui.graphics.Color

/**
 * One colour per person in a Listen Together room or a station's session, stable from their member id: their face,
 * reactions and picks all wear it, in the room and on Community's cards alike.
 */
val PERSON_COLORS = listOf(
    Color(0xFFA78BFA), Color(0xFFF472B6), Color(0xFF22D3EE), Color(0xFFFBBF24),
    Color(0xFF34D399), Color(0xFFFB923C), Color(0xFF60A5FA), Color(0xFFA3E635),
)

fun personColor(memberId: String): Color = PERSON_COLORS[Math.floorMod(memberId.hashCode(), PERSON_COLORS.size)]
