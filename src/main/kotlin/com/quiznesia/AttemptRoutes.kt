package com.quiznesia

import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import kotlin.math.roundToInt

private fun buildResult(attemptId: Long, uid: Long): ResultResponse {
    val at = Attempts.selectAll().where { Attempts.id eq attemptId }.singleOrNull() ?: notFound("Hasil tidak ditemukan")
    if (at[Attempts.userId] != uid || !at[Attempts.isCompleted]) notFound("Hasil tidak ditemukan")
    val quizId = at[Attempts.quizId]
    val quiz = Quizzes.selectAll().where { Quizzes.id eq quizId }.single()
    val score = at[Attempts.score]

    val picked = Answers.selectAll().where { Answers.attemptId eq attemptId }
        .associate { it[Answers.questionId] to it[Answers.selectedChoiceId] }

    val items = questionDtos(quizId, reveal = true).map { q ->
        val correct = q.choices.firstOrNull { it.isCorrect == true }
        val selected = picked[q.id]
        ResultItem(
            questionId = q.id,
            text = q.text,
            explanation = q.explanation,
            points = q.points,
            selectedChoiceId = selected,
            correctChoiceId = correct?.id,
            isCorrect = selected != null && selected == correct?.id,
            choices = q.choices,
        )
    }
    val rank = Attempts.selectAll().where {
        (Attempts.quizId eq quizId) and (Attempts.isCompleted eq true) and (Attempts.score greater score)
    }.count().toInt() + 1

    return ResultResponse(
        attemptId = attemptId,
        quizId = quizId,
        quizTitle = quiz[Quizzes.title],
        score = score,
        earnedPoints = at[Attempts.earnedPoints],
        totalPoints = at[Attempts.totalPoints],
        passScore = quiz[Quizzes.passScore],
        passed = score >= quiz[Quizzes.passScore],
        rank = rank,
        items = items,
    )
}

fun Route.attemptRoutes() {
    post("/quizzes/{id}/start") {
        val uid = call.uid()
        val quizId = call.pathId()
        val res = db {
            val q = Quizzes.selectAll().where { Quizzes.id eq quizId }.singleOrNull() ?: notFound("Quiz tidak ditemukan")
            if (!q[Quizzes.isPublished] && q[Quizzes.creatorId] != uid) notFound("Quiz tidak ditemukan")
            val questions = questionDtos(quizId, reveal = false)
            if (questions.isEmpty()) bad("Quiz ini belum memiliki soal.")
            val attemptId = Attempts.insert {
                it[Attempts.quizId] = quizId
                it[Attempts.userId] = uid
                it[Attempts.startedAt] = now()
            }[Attempts.id]
            StartResponse(attemptId, summarize(q, uid), questions)
        }
        call.respond(res)
    }

    post("/attempts/{id}/submit") {
        val uid = call.uid()
        val attemptId = call.pathId()
        val req = call.receive<SubmitRequest>()
        val res = db {
            val at = Attempts.selectAll().where { Attempts.id eq attemptId }.singleOrNull() ?: notFound("Percobaan tidak ditemukan")
            if (at[Attempts.userId] != uid) notFound("Percobaan tidak ditemukan")
            if (at[Attempts.isCompleted]) bad("Kuis ini sudah diselesaikan")

            val quizId = at[Attempts.quizId]
            val chosen = req.answers.associate { it.questionId to it.choiceId }
            var earned = 0
            var total = 0
            for (qr in Questions.selectAll().where { Questions.quizId eq quizId }.toList()) {
                val qid = qr[Questions.id]
                val pts = qr[Questions.points]
                total += pts
                val choiceId = chosen[qid]
                var valid: Long? = null
                if (choiceId != null) {
                    val ch = Choices.selectAll().where { (Choices.id eq choiceId) and (Choices.questionId eq qid) }.singleOrNull()
                    if (ch != null) {
                        valid = choiceId
                        if (ch[Choices.isCorrect]) earned += pts
                    }
                }
                Answers.insert {
                    it[Answers.attemptId] = attemptId
                    it[Answers.questionId] = qid
                    it[Answers.selectedChoiceId] = valid
                }
            }
            val score = if (total > 0) (earned * 1000.0 / total).roundToInt() / 10.0 else 0.0
            Attempts.update({ Attempts.id eq attemptId }) {
                it[Attempts.completedAt] = now()
                it[Attempts.isCompleted] = true
                it[Attempts.totalPoints] = total
                it[Attempts.earnedPoints] = earned
                it[Attempts.score] = score
            }
            buildResult(attemptId, uid)
        }
        call.respond(res)
    }

    get("/attempts/{id}/result") {
        val uid = call.uid()
        val attemptId = call.pathId()
        call.respond(db { buildResult(attemptId, uid) })
    }
}
