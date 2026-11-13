package com.quiznesia

import at.favre.lib.crypto.bcrypt.BCrypt
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

private fun toUserDto(row: ResultRow) = UserDto(row[Users.id], row[Users.username], row[Users.firstName], row[Users.email])

fun Route.authRoutes() {
    post("/auth/register") {
        val r = call.receive<RegisterRequest>()
        val name = r.username.trim()
        val mail = r.email.trim()
        if (!Regex("^[A-Za-z0-9_.]{3,30}$").matches(name)) bad("Username 3-30 karakter (huruf, angka, _ atau .)")
        if (!mail.contains("@") || mail.length > 120) bad("Email tidak valid")
        if (r.password.length < 8) bad("Password minimal 8 karakter")

        val res = db {
            if (Users.selectAll().where { Users.username eq name }.any()) {
                throw ApiException(HttpStatusCode.Conflict, "Username sudah dipakai")
            }
            val hash = BCrypt.withDefaults().hashToString(12, r.password.toCharArray())
            val newId = Users.insert {
                it[Users.username] = name
                it[Users.email] = mail
                it[Users.firstName] = r.firstName.trim()
                it[Users.passwordHash] = hash
                it[Users.createdAt] = now()
            }[Users.id]
            val row = Users.selectAll().where { Users.id eq newId }.single()
            AuthResponse(JwtConfig.create(newId, name), toUserDto(row))
        }
        call.respond(HttpStatusCode.Created, res)
    }

    post("/auth/login") {
        val r = call.receive<LoginRequest>()
        val name = r.username.trim()
        val res = db {
            val row = Users.selectAll().where { Users.username eq name }.singleOrNull()
            val ok = row != null && BCrypt.verifyer().verify(r.password.toCharArray(), row[Users.passwordHash]).verified
            if (row == null || !ok) throw ApiException(HttpStatusCode.Unauthorized, "Username atau password salah.")
            AuthResponse(JwtConfig.create(row[Users.id], row[Users.username]), toUserDto(row))
        }
        call.respond(res)
    }
}

fun Route.meRoute() {
    get("/me") {
        val uid = call.uid()
        val res = db {
            val row = Users.selectAll().where { Users.id eq uid }.singleOrNull()
                ?: throw ApiException(HttpStatusCode.Unauthorized, "Akun tidak ditemukan")
            toUserDto(row)
        }
        call.respond(res)
    }
}
