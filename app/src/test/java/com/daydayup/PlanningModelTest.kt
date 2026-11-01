package com.daydayup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PlanningModelTest {
    @Test fun weeklyPlanRepeatsAndKeepsOrder() {
        val a = CourseTemplate("a", "跑步", "run", 180)
        val b = CourseTemplate("b", "力量", "strength")
        val plan = WeeklyPlan(listOf(a, b), mapOf(1 to listOf("b", "a")))
        assertEquals(listOf(b, a), plan.coursesFor(1))
        assertEquals(emptyList<CourseTemplate>(), plan.coursesFor(2))
    }

    @Test fun weightAcceptsHalfKilograms() {
        assertEquals(7.5, parseKg("7.5")!!, 0.001)
        assertFalse(parseKg("-1") != null)
    }
}
