// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Bas van den Dikkenberg

package net.dikkenberg.activiteitenweger.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PairingCodeTest {
    @Test
    fun hexRoundTripAndGrouping() {
        val bytes = byteArrayOf(
            0x00,
            0x11,
            0x22,
            0x33,
            0x44,
            0x55,
            0x66,
            0x77,
            0x7f,
            0x10,
            0x20,
            0x30,
            0x40,
            0x50,
            0x60,
            0x70,
        )

        val hex = bytes.hexUpper()
        assertEquals("00112233445566777F10203040506070", hex)
        assertEquals(
            "0011-2233-4455-6677-7F10-2030-4050-6070",
            hex.groupedPairingCode(),
        )
        assertContentEquals(bytes, hex.groupedPairingCode().fromHexFlexible())
    }

    @Test
    fun malformedHexFails() {
        assertFailsWith<IllegalArgumentException> {
            "ABC".fromHexFlexible()
        }
    }
}
