package com.quiznesia

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import kotlin.math.roundToInt

// ───── Helper (dipanggil di dalam transaksi) ─────

fun usernameOf(userId: Long): String =
    Users.selectAll().where { Users.id eq userId }.singleOrNull()?.get(Users.username) ?: "?"

private fun round1(v: Double): Double = (v * 10).roundToInt() / 10.0

fun summarize(q: ResultRow, uid: Long): QuizSummary {
    val quizId = q[Quizzes.id]
    val total = Questions.selectAll().where { Questions.quizId eq quizId }.count().toInt()
    val done = Attempts.selectAll().where { (Attempts.quizId eq quizId) and (Attempts.isCompleted eq true) }.toList()
    val mine = done.filter { it[Attempts.userId] == uid }.maxOfOrNull { it[Attempts.score] }
    return QuizSummary(
        id = quizId,
        title = q[Quizzes.title],
        description = q[Quizzes.description],
        creator = usernameOf(q[Quizzes.creatorId]),
        isPublished = q[Quizzes.isPublished],
        timeLimit = q[Quizzes.timeLimit],
        passScore = q[Quizzes.passScore],
        totalQuestions = total,
        totalAttempts = done.size,
        avgScore = if (done.isEmpty()) 0.0 else round1(done.sumOf { it[Attempts.score] } / done.size),
        myBestScore = mine,
        createdAt = q[Quizzes.createdAt],
    )
}

fun requireOwnedQuiz(quizId: Long, uid: Long): ResultRow {
    val row = Quizzes.selectAll().where { Quizzes.id eq quizId }.singleOrNull() ?: notFound("Quiz tidak ditemukan")
    if (row[Quizzes.creatorId] != uid) forbidden("Ini bukan kuis milikmu")
    return row
}

fun questionDtos(quizId: Long, reveal: Boolean): List<QuestionDto> {
    val qs = Questions.selectAll().where { Questions.quizId eq quizId }
        .orderBy(Questions.sortOrder to SortOrder.ASC, Questions.id to SortOrder.ASC).toList()
    if (qs.isEmpty()) return emptyList()
    val ids = qs.map { it[Questions.id] }
    val byQuestion = Choices.selectAll().where { Choices.questionId inList ids }
        .orderBy(Choices.sortOrder to SortOrder.ASC, Choices.id to SortOrder.ASC)
        .toList().groupBy { it[Choices.questionId] }
    return qs.map { r ->
        QuestionDto(
            id = r[Questions.id],
            text = r[Questions.text],
            type = r[Questions.type],
            points = r[Questions.points],
            order = r[Questions.sortOrder],
            explanation = if (reveal) r[Questions.explanation].ifBlank { null } else null,
            choices = (byQuestion[r[Questions.id]] ?: emptyList()).map { c ->
                ChoiceDto(c[Choices.id], c[Choices.text], if (reveal) c[Choices.isCorrect] else null)
            },
        )
    }
}

private fun validate(r: QuizRequest) {
    if (r.title.isBlank() || r.title.length > 200) bad("Judul wajib diisi (maks. 200 karakter)")
    if (r.description.length > 2000) bad("Deskripsi terlalu panjang")
    if (r.timeLimit < 0) bad("Batas waktu tidak boleh negatif")
    if (r.passScore !in 0..100) bad("Nilai kelulusan harus 0-100")
}

// ───── Routes ─────

fun Route.quizRoutes() {
    get("/dashboard") {
        val uid = call.uid()
        val search = call.request.queryParameters["q"]?.trim().orEmpty()
        val res = db {
            var query = Quizzes.selectAll().where { Quizzes.isPublished eq true }
            if (search.isNotEmpty()) {
                val pat = "%${search.lowercase()}%"
                query = query.andWhere {
                    (Quizzes.title.lowerCase() like pat) or (Quizzes.description.lowerCase() like pat)
                }
            }
            val quizzes = query.orderBy(Quizzes.createdAt, SortOrder.DESC).map { summarize(it, uid) }
            val myDone = Attempts.selectAll().where { (Attempts.userId eq uid) and (Attempts.isCompleted eq true) }.toList()
            DashboardResponse(
                stats = DashboardStats(
                    totalQuizzes = Quizzes.selectAll().where { Quizzes.isPublished eq true }.count().toInt(),
                    myQuizzes = Quizzes.selectAll().where { Quizzes.creatorId eq uid }.count().toInt(),
                    attemptsDone = myDone.size,
                    avgScore = if (myDone.isEmpty()) 0.0 else round1(myDone.sumOf { it[Attempts.score] } / myDone.size),
                ),
                quizzes = quizzes,
            )
        }
        call.respond(res)
    }

    get("/quizzes/mine") {
        val uid = call.uid()
        val res = db {
            Quizzes.selectAll().where { Quizzes.creatorId eq uid }
                .orderBy(Quizzes.createdAt, SortOrder.DESC).map { summarize(it, uid) }
        }
        call.respond(res)
    }

    post("/quizzes") {
        val uid = call.uid()
        val r = call.receive<QuizRequest>().also { validate(it) }
        val res = db {
            val t = now()
            val newId = Quizzes.insert {
                it[Quizzes.creatorId] = uid
                it[Quizzes.title] = r.title.trim()
                it[Quizzes.description] = r.description.trim()
                it[Quizzes.isPublished] = r.isPublished
                it[Quizzes.timeLimit] = r.timeLimit
                it[Quizzes.passScore] = r.passScore
                it[Quizzes.createdAt] = t
                it[Quizzes.updatedAt] = t
            }[Quizzes.id]
            summarize(Quizzes.selectAll().where { Quizzes.id eq newId }.single(), uid)
        }
        call.respond(HttpStatusCode.Created, res)
    }

    get("/quizzes/{id}") {
        val uid = call.uid()
        val quizId = call.pathId()
        val res = db {
            val q = Quizzes.selectAll().where { Quizzes.id eq quizId }.singleOrNull() ?: notFound("Quiz tidak ditemukan")
            val owner = q[Quizzes.creatorId] == uid
            if (!q[Quizzes.isPublished] && !owner) notFound("Quiz tidak ditemukan")

            val order = compareByDescending<ResultRow> { it[Attempts.score] }
                .thenBy { it[Attempts.completedAt] ?: Long.MAX_VALUE }
            val done = Attempts.selectAll().where { (Attempts.quizId eq quizId) and (Attempts.isCompleted eq true) }.toList()
            // Leaderboard: nilai terbaik per user
            val bestPerUser = done.groupBy { it[Attempts.userId] }.values.map { list -> list.sortedWith(order).first() }
            val leaderboard = bestPerUser.sortedWith(order).take(20).mapIndexed { i, r ->
                LeaderboardEntry(i + 1, usernameOf(r[Attempts.userId]), r[Attempts.score], r[Attempts.completedAt])
            }
            val comments = Comments.selectAll().where { Comments.quizId eq quizId }
                .orderBy(Comments.createdAt, SortOrder.DESC).map { c ->
                    CommentDto(
                        id = c[Comments.id],
                        author = usernameOf(c[Comments.authorId]),
                        text = c[Comments.text],
                        createdAt = c[Comments.createdAt],
                        canDelete = owner || c[Comments.authorId] == uid,
                    )
                }
            QuizDetail(summarize(q, uid), owner, leaderboard, comments)
        }
        call.respond(res)
    }

    put("/quizzes/{id}") {
        val uid = call.uid()
        val quizId = call.pathId()
        val r = call.receive<QuizRequest>().also { validate(it) }
        val res = db {
            requireOwnedQuiz(quizId, uid)
            Quizzes.update({ Quizzes.id eq quizId }) {
                it[Quizzes.title] = r.title.trim()
                it[Quizzes.description] = r.description.trim()
                it[Quizzes.isPublished] = r.isPublished
                it[Quizzes.timeLimit] = r.timeLimit
                it[Quizzes.passScore] = r.passScore
                it[Quizzes.updatedAt] = now()
            }
            summarize(Quizzes.selectAll().where { Quizzes.id eq quizId }.single(), uid)
        }
        call.respond(res)
    }

    delete("/quizzes/{id}") {
        val uid = call.uid()
        val quizId = call.pathId()
        db {
            requireOwnedQuiz(quizId, uid)
            Quizzes.deleteWhere { Quizzes.id eq quizId }
        }
        call.respond(HttpStatusCode.NoContent)
    }

    // ── Komentar ──
    post("/quizzes/{id}/comments") {
        val uid = call.uid()
        val quizId = call.pathId()
        val body = call.receive<CommentRequest>().text.trim()
        if (body.isEmpty() || body.length > 1000) bad("Komentar wajib diisi (maks. 1000 karakter)")
        db {
            val q = Quizzes.selectAll().where { Quizzes.id eq quizId }.singleOrNull() ?: notFound("Quiz tidak ditemukan")
            if (!q[Quizzes.isPublished] && q[Quizzes.creatorId] != uid) notFound("Quiz tidak ditemukan")
            Comments.insert {
                it[Comments.quizId] = quizId
                it[Comments.authorId] = uid
                it[Comments.text] = body
                it[Comments.createdAt] = now()
            }
        }
        call.respond(HttpStatusCode.Created)
    }

    delete("/comments/{id}") {
        val uid = call.uid()
        val commentId = call.pathId()
        db {
            val c = Comments.selectAll().where { Comments.id eq commentId }.singleOrNull() ?: notFound("Komentar tidak ditemukan")
            val quizOwner = Quizzes.selectAll().where { Quizzes.id eq c[Comments.quizId] }.single()[Quizzes.creatorId]
            if (c[Comments.authorId] != uid && quizOwner != uid) forbidden("Tidak boleh menghapus komentar ini")
            Comments.deleteWhere { Comments.id eq commentId }
        }
        call.respond(HttpStatusCode.NoContent)
    }
}
