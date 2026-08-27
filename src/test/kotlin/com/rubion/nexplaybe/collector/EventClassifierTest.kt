package com.rubion.nexplaybe.collector

import com.rubion.nexplaybe.event.GameEventType
import kotlin.test.Test
import kotlin.test.assertEquals

class EventClassifierTest {
    private val classifier = EventClassifier()

    @Test
    fun `classifies common Steam news titles deterministically`() {
        assertEquals(GameEventType.PATCH, classifier.classify("Patch 9.1.2 is now live"))
        assertEquals(GameEventType.TRAILER, classifier.classify("Watch the launch trailer"))
        assertEquals(GameEventType.MAJOR_UPDATE, classifier.classify("Major Update: Worlds Part II"))
        assertEquals(GameEventType.RELEASE_DATE, classifier.classify("The game launches on October 10"))
    }

    @Test
    fun `normalizes punctuation for duplicate comparison`() {
        assertEquals("patch 9 1 is live", classifier.normalizedTitle("Patch 9.1 — Is Live!"))
    }
}
