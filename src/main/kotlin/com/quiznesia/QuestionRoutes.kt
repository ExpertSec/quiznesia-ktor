package com.quiznesia

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

private fun validate(r: QuestionRequest) {
    if (r.text.isBlank() || r.text.length > 2000) bad("Pertanyaan wajib diisi (maks. 2000 karakter)")
    if (r.type != "multiple" && r.type != "true_false") bad("Tipe soal tidak valid")
    if (r.points < 1) bad("Poin minimal 1")
    if (r.explanation.length > 2000) bad("Penjelasan terlalu panjang")
    if (r.choices.size < 2) bad("Minimal 2 pilihan jawaban")
    if (r.choices.any { it.text.isBlank() || it.text.length > 500 }) bad("Teks pilihan wajib diisi (maks. 500 karakter)")
    if (r.choices.count { it.isCorrect } != 1) bad("Harus ada tepat satu jawaban benar")
}

private fun insertChoices(qid: Long, choices: List<ChoiceInput>) {
    choices.forEachIndexed { i, c ->
        Choices.insert {
            it[Choices.questionId] = qid
            it[Choices.text] = c.text.trim()
            it[Choices.isCorrect] = c.isCorrect
            it[Choices.sortOrder] = i + 1
        }
    }
}

private fun ownedQuestion(qid: Long, uid: Long): ResultRow {
    val q = Questions.selectAll().where { Questions.id eq qid }.singleOrNull() ?: notFound("Soal tidak ditemukan")
    requireOwnedQuiz(q[Questions.quizId], uid)
    return q
}

fun Route.questionRoutes() {
    // Daftar soal lengkap dengan kunci jawaban (khusus pemilik kuis)
    get("/quizzes/{id}/questions") {
        val uid = call.uid()
        val quizId = call.pathId()
        val res = db {
            requireOwnedQuiz(quizId, uid)
            questionDtos(quizId, reveal = true)
        }
        call.respond(res)
    }

    post("/quizzes/{id}/questions") {
        val uid = call.uid()
        val quizId = call.pathId()
        val r = call.receive<QuestionRequest>().also { validate(it) }
        db {
            requireOwnedQuiz(quizId, uid)
            val next = Questions.selectAll().where { Questions.quizId eq quizId }.count().toInt() + 1
            val qid = Questions.insert {
                it[Questions.quizId] = quizId
                it[Questions.text] = r.text.trim()
                it[Questions.type] = r.type
                it[Questions.points] = r.points
                it[Questions.sortOrder] = next
                it[Questions.explanation] = r.explanation.trim()
            }[Questions.id]
            insertChoices(qid, r.choices)
        }
        call.respond(HttpStatusCode.Created)
    }

    // Catatan: pilihan lama dihapus lalu dibuat ulang. Jawaban lama di riwayat pengerjaan
    // yang menunjuk ke pilihan tersebut jadi "tidak terjawab" pada halaman review.
    put("/questions/{id}") {
        val uid = call.uid()
        val qid = call.pathId()
        val r = call.receive<QuestionRequest>().also { validate(it) }
        db {
            ownedQuestion(qid, uid)
            Questions.update({ Questions.id eq qid }) {
                it[Questions.text] = r.text.trim()
                it[Questions.type] = r.type
                it[Questions.points] = r.points
                it[Questions.explanation] = r.explanation.trim()
            }
            Choices.deleteWhere { Choices.questionId eq qid }
            insertChoices(qid, r.choices)
        }
        call.respond(HttpStatusCode.NoContent)
    }

    delete("/questions/{id}") {
        val uid = call.uid()
        val qid = call.pathId()
        db {
            ownedQuestion(qid, uid)
            Questions.deleteWhere { Questions.id eq qid }
        }
        call.respond(HttpStatusCode.NoContent)
    }
}
