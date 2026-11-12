package io.embodit.lubanlock.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SaveCodecTest {
    @Test fun roundTripAndRejectIllegalHistories() {
        val g = SavedGame("nudge", false, listOf(Move(2, Dir.NY, 1, false), Move(6, Dir.NY, 1, false)), 0, View(2, true), true, 3)
        assertEquals(g, SaveCodec.decode(SaveCodec.encode(g)))
        // an illegal first move (③ cannot move at the start of 钥匙在哪)
        assertNull(SaveCodec.decode("lb1|key|A|3,+x,1,0|0|0|0|0"))
        // the put-back phase needs every stick out
        assertNull(SaveCodec.decode("lb1|key|B|5,+z,1,1|0|0|0|0"))
        assertNull(SaveCodec.decode("garbage"))
        val full = listOf(Move(5, Dir.PZ, 1, true), Move(4, Dir.NX, 1, true), Move(1, Dir.PY, 1, true), Move(2, Dir.NY, 1, true), Move(3, Dir.NX, 1, true), Move(6, Dir.PX, 1, true))
        val back = SavedGame("key", true, full, 2, View(), false, 0)
        assertEquals(back, SaveCodec.decode(SaveCodec.encode(back)))
    }
}
