package org.wirl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelHolderTest {
    private class Fake : SpeechTranscriber {
        var closed = false
        override fun transcribe(pcm: FloatArray, language: String, prompt: String?) = ""
        override fun close() {
            closed = true
        }
    }

    private var clock = 0L
    private val scheduled = mutableListOf<Runnable>()
    private var loads = 0
    private var failLoad = false

    private fun holder(idleMs: Long = 1_000L) = ModelHolder(
        load = {
            loads++
            if (failLoad) null else Fake()
        },
        now = { clock },
        schedule = { _, task -> scheduled += task },
        idleMs = idleMs,
    )

    @Test fun theModelLoadsOnFirstUseAndIsReusedWhileActive() {
        val holder = holder()
        val first = holder.get()
        clock = 500
        val second = holder.get()
        assertSame(first, second)
        assertEquals(1, loads)
    }

    @Test fun theModelIsFreedAfterTheIdleTime() {
        val holder = holder(idleMs = 1_000L)
        val model = holder.get() as Fake
        clock = 999
        holder.checkIdle()
        assertTrue(holder.isLoaded)
        clock = 1_000
        holder.checkIdle()
        assertFalse(holder.isLoaded)
        assertTrue("freed model is closed", model.closed)
    }

    @Test fun aFreedModelLoadsAgainOnTheNextUse() {
        val holder = holder(idleMs = 1_000L)
        holder.get()
        clock = 2_000
        holder.checkIdle()
        assertFalse(holder.isLoaded)
        holder.get()
        assertEquals(2, loads)
    }

    @Test fun useKeepsTheModelFromBeingFreed() {
        val holder = holder(idleMs = 1_000L)
        holder.get()
        clock = 900
        holder.get()
        clock = 1_500
        holder.checkIdle()
        assertTrue("used at 900 ms, so still loaded at 1500 ms", holder.isLoaded)
    }

    @Test fun aModelThatCannotBeLoadedGivesNull() {
        failLoad = true
        assertNull(holder().get())
    }

    @Test fun everyUseSchedulesAnIdleCheck() {
        val holder = holder()
        holder.get()
        holder.get()
        assertEquals(2, scheduled.size)
    }
}
