package com.quiznesia

import kotlinx.serialization.Serializable

// ───── Auth ─────
@Serializable data class RegisterRequest(val username: String, val email: String, val firstName: String = "", val password: String)
@Serializable data class LoginRequest(val username: String, val password: String)
@Serializable data class UserDto(val id: Long, val username: String, val firstName: String, val email: String)
@Serializable data class AuthResponse(val token: String, val user: UserDto)
@Serializable data class ErrorResponse(val message: String)

// ───── Quiz ─────
@Serializable
data class QuizSummary(
    val id: Long,
    val title: String,
    val description: String,
    val creator: String,
    val isPublished: Boolean,
    val timeLimit: Int,
    val passScore: Int,
    val totalQuestions: Int,
    val totalAttempts: Int,
    val avgScore: Double,
    val myBestScore: Double? = null,
    val createdAt: Long,
)

@Serializable data class DashboardStats(val totalQuizzes: Int, val myQuizzes: Int, val attemptsDone: Int, val avgScore: Double)
@Serializable data class DashboardResponse(val stats: DashboardStats, val quizzes: List<QuizSummary>)
@Serializable data class QuizRequest(val title: String, val description: String = "", val timeLimit: Int = 0, val passScore: Int = 60, val isPublished: Boolean = true)

@Serializable data class LeaderboardEntry(val rank: Int, val username: String, val score: Double, val completedAt: Long? = null)
@Serializable data class CommentDto(val id: Long, val author: String, val text: String, val createdAt: Long, val canDelete: Boolean)
@Serializable data class CommentRequest(val text: String)
@Serializable data class QuizDetail(val quiz: QuizSummary, val isOwner: Boolean, val leaderboard: List<LeaderboardEntry>, val comments: List<CommentDto>)

// ───── Questions ─────
/** isCorrect hanya terisi untuk pemilik kuis / halaman hasil, null saat mengerjakan kuis. */
@Serializable data class ChoiceDto(val id: Long, val text: String, val isCorrect: Boolean? = null)
@Serializable data class QuestionDto(val id: Long, val text: String, val type: String, val points: Int, val order: Int, val explanation: String? = null, val choices: List<ChoiceDto>)
@Serializable data class ChoiceInput(val text: String, val isCorrect: Boolean)
@Serializable data class QuestionRequest(val text: String, val type: String = "multiple", val points: Int = 10, val explanation: String = "", val choices: List<ChoiceInput>)

// ───── Attempt ─────
@Serializable data class StartResponse(val attemptId: Long, val quiz: QuizSummary, val questions: List<QuestionDto>)
@Serializable data class AnswerInput(val questionId: Long, val choiceId: Long? = null)
@Serializable data class SubmitRequest(val answers: List<AnswerInput>)

@Serializable
data class ResultItem(
    val questionId: Long,
    val text: String,
    val explanation: String? = null,
    val points: Int,
    val selectedChoiceId: Long? = null,
    val correctChoiceId: Long? = null,
    val isCorrect: Boolean,
    val choices: List<ChoiceDto>,
)

@Serializable
data class ResultResponse(
    val attemptId: Long,
    val quizId: Long,
    val quizTitle: String,
    val score: Double,
    val earnedPoints: Int,
    val totalPoints: Int,
    val passScore: Int,
    val passed: Boolean,
    val rank: Int,
    val items: List<ResultItem>,
)
