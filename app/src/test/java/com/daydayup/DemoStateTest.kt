package com.daydayup

import org.junit.Assert.*
import org.junit.Test

class DemoStateTest {
    @Test fun demoTrainingDoesNotSkipPreparationAndPreservesPausePoint() {
        val state = DemoState()
        state.start()
        assertEquals("prepare", state.phase)
        assertEquals(5, state.seconds)
        state.tick()
        assertEquals(4, state.seconds)
        state.pause("phone")
        assertEquals("phone", state.phase)
        state.resume()
        assertEquals("prepare", state.phase)
        assertEquals(4, state.seconds)
    }

    @Test fun coachRecommendationIsNotAppliedBeforeConfirmation() {
        val state = DemoState()
        val before = state.exercises.first().sets.first().kg
        state.coachSuggestion = true
        assertEquals(before, state.exercises.first().sets.first().kg, 0.001)
        state.updateSet(0, 0, 10, before - 2.0)
        assertEquals(before - 2.0, state.exercises.first().sets.first().kg, 0.001)
    }

    @Test fun copyingTemplateKeepsOriginalIndependent() {
        val state = DemoState()
        state.copyTemplate()
        state.templateName = "改过的副本"
        state.scheduledDay = "周三"
        assertEquals(2, state.templates.size)
        assertEquals("上肢力量 A", state.templates[0].name)
        assertEquals("周一", state.templates[0].day)
        assertEquals("改过的副本", state.templates[1].name)
    }

    @Test fun restLeadsToPreparationBeforeNextSet() {
        val state = DemoState()
        state.start()
        state.result("跳过")
        assertEquals("rest", state.phase)
        state.seconds = 1
        state.tick()
        assertEquals("prepare", state.phase)
        assertEquals(5, state.seconds)
    }
}
