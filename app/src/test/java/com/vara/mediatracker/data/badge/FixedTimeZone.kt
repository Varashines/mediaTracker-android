package com.vara.mediatracker.data.badge

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.util.Locale
import java.util.TimeZone

/**
 * Pins the JVM timezone for a test.
 *
 * A bare air date parses in the device's local zone, so any assertion about a window
 * measured in hours is meaningless unless the zone is fixed. These tests were passing
 * only because of class ordering — with the machine on IST they silently compared
 * values rendered in different zones.
 */
class FixedTimeZone(private val zone: String = "UTC") : TestRule {
    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                val original = TimeZone.getDefault()
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                try {
                    base.evaluate()
                } finally {
                    TimeZone.setDefault(original)
                }
            }
        }
}
