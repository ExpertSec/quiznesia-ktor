package com.quiznesia

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

object Users : Table("users") {
    val id = long("id").autoIncrement()
    val username = varchar("username", 50).uniqueIndex()
    val email = varchar("email", 120)
    val firstName = varchar("first_name", 60).default("")
    val passwordHash = varchar("password_hash", 100)
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

object Quizzes : Table("quizzes") {
    val id = long("id").autoIncrement()
    val creatorId = reference("creator_id", Users.id, onDelete = ReferenceOption.CASCADE)
    val title = varchar("title", 200)
    val description = varchar("description", 2000).default("")
    val isPublished = bool("is_published").default(true)
    val timeLimit = integer("time_limit").default(0)
    val passScore = integer("pass_score").default(60)
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object Questions : Table("questions") {
    val id = long("id").autoIncrement()
    val quizId = reference("quiz_id", Quizzes.id, onDelete = ReferenceOption.CASCADE)
    val text = varchar("content", 2000)
    val type = varchar("question_type", 20)
    val points = integer("points").default(10)
    val sortOrder = integer("sort_order").default(0)
    val explanation = varchar("explanation", 2000).default("")
    override val primaryKey = PrimaryKey(id)
}

object Choices : Table("choices") {
    val id = long("id").autoIncrement()
    val questionId = reference("question_id", Questions.id, onDelete = ReferenceOption.CASCADE)
    val text = varchar("label", 500)
    val isCorrect = bool("is_correct").default(false)
    val sortOrder = integer("sort_order").default(0)
    override val primaryKey = PrimaryKey(id)
}

object Attempts : Table("attempts") {
    val id = long("id").autoIncrement()
    val quizId = reference("quiz_id", Quizzes.id, onDelete = ReferenceOption.CASCADE)
    val userId = reference("user_id", Users.id, onDelete = ReferenceOption.CASCADE)
    val startedAt = long("started_at")
    val completedAt = long("completed_at").nullable()
    val score = double("score").default(0.0)
    val totalPoints = integer("total_points").default(0)
    val earnedPoints = integer("earned_points").default(0)
    val isCompleted = bool("is_completed").default(false)
    override val primaryKey = PrimaryKey(id)
}

object Answers : Table("answers") {
    val id = long("id").autoIncrement()
    val attemptId = reference("attempt_id", Attempts.id, onDelete = ReferenceOption.CASCADE)
    val questionId = reference("question_id", Questions.id, onDelete = ReferenceOption.CASCADE)
    val selectedChoiceId = optReference("selected_choice_id", Choices.id, onDelete = ReferenceOption.SET_NULL)
    override val primaryKey = PrimaryKey(id)
}

object Comments : Table("comments") {
    val id = long("id").autoIncrement()
    val quizId = reference("quiz_id", Quizzes.id, onDelete = ReferenceOption.CASCADE)
    val authorId = reference("author_id", Users.id, onDelete = ReferenceOption.CASCADE)
    val text = varchar("content", 1000)
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

fun initDb() {
    val url = System.getenv("DB_URL") ?: "jdbc:h2:file:./data/quiznesia;DB_CLOSE_DELAY=-1"
    val driver = System.getenv("DB_DRIVER") ?: "org.h2.Driver"
    Database.connect(
        url = url,
        driver = driver,
        user = System.getenv("DB_USER") ?: "sa",
        password = System.getenv("DB_PASSWORD") ?: "",
    )
    transaction {
        SchemaUtils.create(Users, Quizzes, Questions, Choices, Attempts, Answers, Comments)
    }
}

/** Jalankan blok Exposed di thread IO. Exception di dalam blok membatalkan transaksi. */
suspend fun <T> db(block: () -> T): T = withContext(Dispatchers.IO) { transaction { block() } }

fun now(): Long = System.currentTimeMillis()
