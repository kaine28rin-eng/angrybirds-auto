package com.stevestudy.angrybirdsauto.automation

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Physics-based trajectory calculator for Angry Birds.
 * Ported from the Python prototype's brute-force search approach.
 *
 * The model uses a parabolic arc with drag coefficient, searching over
 * launch angles (10°-80°) and power levels (0%-100%) to find the shot
 * that passes closest to the target pig.
 */
class TrajectoryCalculator {

    // Physics constants
    private val g: Double = 9.8       // gravity (m/s²) — scaled
    private val drag: Double = 0.02    // drag coefficient
    private val dt: Double = 0.05      // time step for simulation

    /**
     * Represents a single candidate shot evaluated during the brute-force search.
     */
    data class ShotResult(
        val angleDegrees: Double,
        val power: Double,
        val targetX: Double,
        val targetY: Double,
        val impactX: Double,
        val impactY: Double,
        val missDistance: Double,
        val passThroughSlingshot: Boolean
    )

    /**
     * Represents the best found shot and whether it's a direct hit.
     */
    data class BestShot(
        val shot: ShotResult?,
        val isHit: Boolean,
        val hitThresholdPx: Double,
        val hitCount: Int
    )

    /**
     * Find the optimal shot from slingshot to target pig.
     *
     * @param slingshotX X-coordinate of the slingshot (launch point).
     * @param slingshotY Y-coordinate of the slingshot (launch point).
     * @param targetX X-coordinate of the target pig.
     * @param targetY Y-coordinate of the target pig.
     * @param screenWidth Width of the screen (for bounds checking).
     * @param screenHeight Height of the screen (for bounds checking).
     * @return BestShot containing the optimal angle/power and hit analysis.
     */
    fun findBestShot(
        slingshotX: Double,
        slingshotY: Double,
        targetX: Double,
        targetY: Double,
        screenWidth: Int,
        screenHeight: Int
    ): BestShot {
        val hitThresholdPx = 40.0  // Pixel tolerance for a "hit"
        val results = mutableListOf<ShotResult>()

        // Brute-force search over angles and power levels
        for (angleDeg in 10..80 step 2) {
            for (powerLevel in 1..100 step 5) {
                val power = powerLevel / 100.0
                val result = simulateShot(
                    slingshotX, slingshotY, targetX, targetY,
                    angleDeg.toDouble(), power, screenWidth, screenHeight
                )

                if (result != null) {
                    results.add(result)
                }
            }
        }

        if (results.isEmpty()) {
            return BestShot(null, false, hitThresholdPx, 0)
        }

        // Sort by miss distance (ascending)
        results.sortBy { it.missDistance }

        val best = results.first()
        val hits = results.count { it.missDistance <= hitThresholdPx }

        return BestShot(
            shot = best,
            isHit = best.missDistance <= hitThresholdPx,
            hitThresholdPx = hitThresholdPx,
            hitCount = hits
        )
    }

    /**
     * Simulate a single shot using the physics model.
     * The bird flies from the slingshot position at a given angle and power,
     * and we check if the trajectory passes near the target.
     */
    private fun simulateShot(
        slingX: Double,
        slingY: Double,
        targetX: Double,
        targetY: Double,
        angleDeg: Double,
        power: Double,
        screenW: Int,
        screenH: Int
    ): ShotResult? {
        val angleRad = angleDeg * PI / 180.0

        // Initial velocity components
        // Max velocity ~15 m/s scaled to pixels
        val maxVelocity = 600.0
        val vx = cos(angleRad) * maxVelocity * power
        val vy = -sin(angleRad) * maxVelocity * power  // Negative = upward (screen Y grows downward)

        var x = slingX
        var y = slingY
        var vxCurrent = vx
        var vyCurrent = vy
        var t = 0.0

        // Track closest approach to target
        var minDistance = Double.MAX_VALUE
        var closestX = slingX
        var closestY = slingY

        // Simulate the trajectory
        // Run up to 2000 steps (max time ~10 seconds)
        for (step in 0..2000) {
            t += dt

            // Apply drag
            val speed = sqrt(vxCurrent * vxCurrent + vyCurrent * vyCurrent)
            if (speed > 0.1) {
                val dragForce = drag * speed * speed
                vxCurrent -= (vxCurrent / speed) * dragForce * dt
                vyCurrent += (g * dt) - (vyCurrent / speed) * dragForce * dt * 0.5
            }

            x += vxCurrent * dt
            y += vyCurrent * dt

            // Check bounds
            if (x < 0 || x > screenW || y > screenH + 200) {
                break
            }

            // Track closest point to target
            val distToTarget = sqrt((x - targetX).pow(2) + (y - targetY).pow(2))
            if (distToTarget < minDistance) {
                minDistance = distToTarget
                closestX = x
                closestY = y
            }

            vyCurrent += g * dt
        }

        if (minDistance.isInfinite() || minDistance.isNaN()) return null

        // Check if trajectory passes through the slingshot area (avoid shooting backwards)
        val passThroughSlingshot = false  // Simplified — real check would verify the trajectory doesn't start inside obstacles

        return ShotResult(
            angleDegrees = angleDeg,
            power = power,
            targetX = targetX,
            targetY = targetY,
            impactX = closestX,
            impactY = closestY,
            missDistance = minDistance,
            passThroughSlingshot = passThroughSlingshot
        )
    }

    /**
     * Calculate the drag vector needed for the swipe gesture.
     * The swipe starts from the slingshot and drags in the direction
     * opposite to the desired launch angle.
     *
     * @param slingshotX X-coordinate of the slingshot.
     * @param slingshotY Y-coordinate of the slingshot.
     * @param angleDegrees Launch angle in degrees.
     * @param power Power level (0.0 to 1.0).
     * @return Pair of (startX, startY, endX, endY) for the swipe gesture.
     */
    fun calculateSwipeVector(
        slingshotX: Double,
        slingshotY: Double,
        angleDegrees: Double,
        power: Double
    ): SwipeVector {
        val angleRad = angleDegrees * PI / 180.0

        // The bird is pulled back from the slingshot, then released.
        // Drag direction = opposite to the launch direction.
        val maxDragDistance = 120.0
        val dragDistance = maxDragDistance * power

        // Start point is slightly above the slingshot (where the bird sits)
        val startX = slingshotX
        val startY = slingshotY

        // End point is in the opposite direction of the launch angle
        // For a forward launch, the drag goes backward
        val endX = startX - cos(angleRad) * dragDistance
        val endY = startY + sin(angleRad) * dragDistance

        return SwipeVector(startX, startY, endX, endY, angleDegrees, power)
    }

    /**
     * Calculate the trajectory points for visualization on the overlay.
     *
     * @param slingX Launch X.
     * @param slingY Launch Y.
     * @param angleDeg Launch angle in degrees.
     * @param power Power level (0.0 to 1.0).
     * @param screenW Screen width for bounds.
     * @param screenH Screen height for bounds.
     * @return List of (x, y) points representing the trajectory arc.
     */
    fun calculateTrajectoryPoints(
        slingX: Double,
        slingY: Double,
        angleDeg: Double,
        power: Double,
        screenW: Int,
        screenH: Int
    ): List<Pair<Double, Double>> {
        val points = mutableListOf<Pair<Double, Double>>()
        val angleRad = angleDeg * PI / 180.0

        val maxVelocity = 600.0
        var vx = cos(angleRad) * maxVelocity * power
        var vy = -sin(angleRad) * maxVelocity * power

        var x = slingX
        var y = slingY
        var t = 0.0

        points.add(Pair(x, y))

        for (step in 0..500) {
            t += dt

            val speed = sqrt(vx * vx + vy * vy)
            if (speed > 0.1) {
                val dragForce = drag * speed * speed
                vx -= (vx / speed) * dragForce * dt
                vy += (g * dt) - (vy / speed) * dragForce * dt * 0.5
            }

            x += vx * dt
            y += vy * dt

            if (x < 0 || x > screenW || y > screenH + 200) break

            points.add(Pair(x, y))
            vy += g * dt
        }

        return points
    }

    data class SwipeVector(
        val startX: Double,
        val startY: Double,
        val endX: Double,
        val endY: Double,
        val angleDegrees: Double,
        val power: Double
    )
}
