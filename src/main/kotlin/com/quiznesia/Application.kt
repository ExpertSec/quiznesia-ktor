package com.quiznesia

import io.ktor.http.*
import io.ktor.serialization.ContentConvertException
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Dipanggil oleh EngineMain lewat application.yaml (ktor.application.modules). */
@OptIn(ExperimentalSerializationApi::class)
fun Application.module() {
    initDb()

    install(CallLogging)

    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        })
    }

    install(StatusPages) {
        exception<ApiException> { call, e -> call.respond(e.status, ErrorResponse(e.message ?: "Error")) }
        exception<BadRequestException> { call, _ -> call.respond(HttpStatusCode.BadRequest, ErrorResponse("Format request tidak valid")) }
        exception<ContentConvertException> { call, _ -> call.respond(HttpStatusCode.BadRequest, ErrorResponse("Format request tidak valid")) }
        exception<SerializationException> { call, _ -> call.respond(HttpStatusCode.BadRequest, ErrorResponse("Format request tidak valid")) }
        exception<Throwable> { call, e ->
            call.application.environment.log.error("Unhandled error", e)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Terjadi kesalahan pada server"))
        }
    }

    install(Authentication) {
        jwt("auth-jwt") {
            realm = "quiznesia"
            verifier(JwtConfig.verifier)
            validate { cred -> if (cred.payload.getClaim("uid").asLong() != null) JWTPrincipal(cred.payload) else null }
            challenge { _, _ -> call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Silakan login kembali")) }
        }
    }

    routing {
        get("/") { call.respondText("QuizNesia API is running") }
        route("/api") {
            authRoutes()
            authenticate("auth-jwt") {
                meRoute()
                quizRoutes()
                questionRoutes()
                attemptRoutes()
            }
        }
    }
}
