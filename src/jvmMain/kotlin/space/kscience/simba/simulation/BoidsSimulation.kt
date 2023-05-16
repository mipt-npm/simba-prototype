package space.kscience.simba.simulation

import io.ktor.application.*
import io.ktor.routing.*
import space.kscience.simba.EngineFactory
import space.kscience.simba.engine.Engine
import space.kscience.simba.state.ActorBoidsCell
import space.kscience.simba.state.ActorBoidsState
import space.kscience.simba.state.EnvironmentState
import space.kscience.simba.systems.PrintSystem
import space.kscience.simba.utils.*
import kotlin.random.Random

// TODO extract to environment
private object BoidsSettings {
    const val minSpeed = 100.0
    const val maxSpeed = 300.0

    const val perceptionRadius = 200.0
    const val avoidanceRadius = 100.0
    const val maxSteerForce = 300.0 // how fast boid can turn

    const val avoidanceWeight = 1.0
    const val alignWeight = 1.0
    const val cohesionWeight = 1.0

    const val bound = 1000.0
    const val applyAllRules = true
}

class BoidsSimulation: Simulation<ActorBoidsCell, ActorBoidsState, EnvironmentState>("boids") {
    private val random = Random(0)
    private val n = 100

    private val neighbours = (1 until n).map { intArrayOf(it) }.toSet()
    private var withAllRules = false

    override val engine: Engine<EnvironmentState> = EngineFactory.createEngine(intArrayOf(n), neighbours) {
        ActorBoidsCell(it[0], random.randomBoidsState())
    }
    override val printSystem: PrintSystem<ActorBoidsState> = PrintSystem(n)

    init {
        engine.addNewSystem(printSystem)
        engine.init()
        engine.iterate()
    }

    override fun Routing.addAdditionalRouting() {
        put("/$name") {
            // TODO apply to BoidsSettings
            withAllRules = call.request.queryParameters["withAllRules"] == "true"
        }
    }

    companion object {
        // original document http://www.cs.toronto.edu/~dt/siggraph97-course/cwr87/
        // C# implementation https://github.com/SebLague/Boids
        suspend fun nextStep(old: ActorBoidsState, neighbours: List<ActorBoidsState>): ActorBoidsState {
            val deltaTime = 1.0 / 60
            val visibleNeighbours =
                neighbours.filter { (it.position - old.position).length() <= BoidsSettings.perceptionRadius }
            val avoidNeighbours =
                neighbours.filter { (it.position - old.position).length() <= BoidsSettings.avoidanceRadius }

            fun applyFirstRule(boid: ActorBoidsState): Vector2 {
                val avgAvoidanceHeading = avoidNeighbours
                    .map { it.position }
                    .fold(zero) { acc, otherPosition ->
                        val distance = otherPosition - boid.position
                        acc - distance / distance.sqrLength()
                    }
                // separationForce
                return steer(boid.velocity, avgAvoidanceHeading) * BoidsSettings.avoidanceWeight
            }

            fun applySecondRule(boid: ActorBoidsState): Vector2 {
                val avgFlockHeading = visibleNeighbours.fold(zero) { acc, other -> acc + other.direction }
                // alignmentForce
                return steer(boid.velocity, avgFlockHeading) * BoidsSettings.alignWeight
            }

            fun applyThirdRule(boid: ActorBoidsState): Vector2 {
                val avgFlockPosition = visibleNeighbours.fold(zero) { acc, other -> acc + other.position }
                val centreOfFlockmates = avgFlockPosition / visibleNeighbours.size.toDouble()
                val offsetToFlockmatesCentre = (centreOfFlockmates - boid.position)
                // cohesionForce
                return steer(boid.velocity, offsetToFlockmatesCentre) * BoidsSettings.cohesionWeight
            }

            var acceleration = zero
            if (visibleNeighbours.isNotEmpty() && BoidsSettings.applyAllRules) {
                acceleration += applyFirstRule(old)
                acceleration += applySecondRule(old)
                acceleration += applyThirdRule(old)
            }

            var newVelocity = old.velocity + acceleration * deltaTime
            val newDirection = newVelocity.normalized()
            val speed = newVelocity.length().clamp(BoidsSettings.minSpeed, BoidsSettings.maxSpeed)
            newVelocity = newDirection * speed

            val newPosition = old.position + newVelocity * deltaTime
            return ActorBoidsState(newPosition.clampAndSwap(0.0, BoidsSettings.bound), newDirection, newVelocity)
        }

        private fun Vector2.clampAndSwap(min: Double, max: Double): Vector2 = Vector2(first.clampAndSwap(min, max), second.clampAndSwap(min, max))

        private fun Random.randomBoidsState(): ActorBoidsState {
            val position = this.randomVector() * BoidsSettings.bound
            val direction = this.randomVector()
            val velocity = direction * (BoidsSettings.minSpeed + BoidsSettings.maxSpeed) / 2.0
            return ActorBoidsState(position, direction, velocity)
        }

        private fun steer(from: Vector2, towards: Vector2): Vector2 {
            val v = towards.normalized() * BoidsSettings.maxSpeed - from
            return v.clampMagnitude(BoidsSettings.maxSteerForce)
        }
    }
}