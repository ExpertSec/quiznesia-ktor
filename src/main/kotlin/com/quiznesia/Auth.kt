package com.quiznesia

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import java.util.Date

class ApiException(val status: HttpStatusCode, message: String) : RuntimeException(message)

fun bad(message: String): Nothing = throw ApiException(HttpStatusCode.BadRequest, message)
fun notFound(message: String): Nothing = throw ApiException(HttpStatusCode.NotFound, message)
fun forbidden(message: String): Nothing = throw ApiException(HttpStatusCode.Forbidden, message)

object JwtConfig {
    // WAJIB diganti lewat environment variable JWT_SECRET di production.
    private val secret = System.getenv("JWT_SECRET") ?: "dev-only-secret-change-me"
    const val ISSUER = "quiznesia"
    const val AUDIENCE = "quiznesia-app"
    private val algorithm = Algorithm.HMAC256(secret)

    val verifier = JWT.require(algorithm).withIssuer(ISSUER).withAudience(AUDIENCE).build()

    fun create(userId: Long, username: String): String = JWT.create()
        .withIssuer(ISSUER)
        .withAudience(AUDIENCE)
        .withClaim("uid", userId)
        .withClaim("username", username)
        .withExpiresAt(Date(now() + 30L * 24 * 3600 * 1000))
        .sign(algorithm)
}

fun ApplicationCall.uid(): Long =
    principal<JWTPrincipal>()?.payload?.getClaim("uid")?.asLong()
        ?: throw ApiException(HttpStatusCode.Unauthorized, "Sesi tidak valid")

fun ApplicationCall.pathId(name: String = "id"): Long =
    parameters[name]?.toLongOrNull() ?: bad("ID tidak valid")
