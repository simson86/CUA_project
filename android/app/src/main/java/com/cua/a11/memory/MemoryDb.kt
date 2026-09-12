package com.cua.a11.memory

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// ── 0단계: 구조화 로깅 ────────────────────────────────────────────────────
//  run_history.txt 는 사람이 읽는 평문이라 "턴 1의 액션이 주로 뭐냐" 같은 질문에 답할 수
//  없다. 같은 사건을 질의 가능한 형태로 한 벌 더 남긴다. 평문 로그는 그대로 둔다.
//
//  Unit 2 에서 memory 테이블(의미·절차 기억)을 추가하며 버전을 2 로 올렸다.
//  설계 근거 전체는 docs/reference/android_run-memory-2026-09-12.html 참조.

/** 실행 1건. 목표·설정·결과. 턴 상세는 episode 로 나간다. */
@Entity(tableName = "run")
data class RunEntity(
    @PrimaryKey val id: String,          // UUID
    val goal: String,
    val model: String,
    val thinking: String,
    val maxTurns: Int,
    val outcome: String?,                // success|fail|aborted|error — 종료 시 채움
    val turnsUsed: Int?,
    val startedAt: Long,
    val endedAt: Long?,
    // 아래 둘은 Unit 9(생애주기)의 압축용. 지금은 항상 null 이다.
    val summary: String? = null,
    val compactedAt: Long? = null,
)

/**
 * 액션 1건 = 일화(EPISODE). 한 턴에 function_call 이 여러 개면 행도 여러 개다.
 *
 * **좌표는 담지 않는다**(설계 원칙 1). action 이름과 의도만 남긴다 — 좌표를 남기면
 * 앱 업데이트에 조용히 무효화되는 데이터가 쌓이고, 그걸 재생하고 싶은 유혹이 생긴다.
 */
@Entity(
    tableName = "episode",
    foreignKeys = [ForeignKey(
        entity = RunEntity::class,
        parentColumns = ["id"],
        childColumns = ["runId"],
        onDelete = ForeignKey.CASCADE,   // run 을 지우면 episode 도 함께 (Unit 9 보존 정리)
    )],
    indices = [Index(value = ["runId", "turn"])],
)
data class EpisodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runId: String,
    val turn: Int,
    val pkg: String?,                    // 액션 '실행 후'의 포그라운드 패키지
    val pkgVersion: Long?,               // 그 앱의 longVersionCode — 무효화의 근거가 된다
    val action: String?,                 // click|type|open_app…
    val intent: String?,                 // 모델이 말한 의도
    val result: String?,                 // ok|error
    val hadSafety: Boolean,              // require_confirmation 이 붙었나 (설계 §3 규칙 A)
    val note: String?,                   // 그 턴에 주입된 메모 (지금은 항상 null)
    val createdAt: Long,
)


/**
 * 의미·절차 기억. **모델에 실제로 주입되는 것은 이 표뿐이다.**
 *
 * 칸이 네 덩어리다 — 내용(주입되는 것) · 검색 키 · 신뢰 · 수명. 수명 칸이 가장 많은 것이
 * 이 설계의 비율을 그대로 보여준다: 모델에 가는 건 text 한 줄이고 나머지는 전부
 * "그 한 줄을 언제 꺼내고 언제 버릴지"를 정하기 위한 것이다.
 *
 * SQL DEFAULT 를 쓰지 않고 코틀린 기본값만 쓴다 — @ColumnInfo(defaultValue=) 를 섞으면
 * 손으로 쓴 마이그레이션 DDL 과 Room 이 기대하는 스키마가 어긋나기 쉽다.
 */
@Entity(
    tableName = "memory",
    indices = [
        Index(value = ["pkg", "state", "kind"]),      // appNote 조회
        Index(value = ["state", "lastAccessed"]),     // 생애주기 정리(Unit 8)
    ],
)
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    // ── 내용: 모델에 가는 것 ──────────────────────────────
    val kind: String,                      // APP_FACT | PITFALL | RECIPE
    val text: String,                      // 주입 문장 (영문 권장)
    val steps: String? = null,             // RECIPE: JSON 배열
    val precondition: String? = null,      // RECIPE
    val observedTurns: Int? = null,        // RECIPE: 턴 절감을 재는 기준값

    // ── 검색 키 ──────────────────────────────────────────
    val pkg: String? = null,               // APP_FACT·RECIPE
    val keywords: String? = null,          // PITFALL. 쉼표 구분, 동의어 포함
    val scope: String = "pkg",             // pkg | vendor | system

    // ── 신뢰 ─────────────────────────────────────────────
    val state: String = "PENDING",         // PENDING | ACTIVE | RETIRED
    val score: Int = 1,
    val pinned: Boolean = false,           // 사람이 손댐 → 자동 강등 면제
    val source: String,                    // reflector | user_ui | user_feedback
    val sourceRunId: String? = null,       // 근거가 된 실행 — 목록에서 보여준다
    val sensitivity: String = "normal",    // normal | restricted (설계 §3 규칙 B)

    // ── 수명 ─────────────────────────────────────────────
    val pkgVersion: Long? = null,          // 배울 당시의 앱 버전
    val timeAdded: Long,
    val lastAccessed: Long? = null,
    val numRecalled: Int = 0,
    val invalidAt: Long? = null,           // 무효화는 삭제가 아니라 기록이다
    val expiresAt: Long? = null,
    val supersededBy: Long? = null,        // 무엇이 이걸 대체했나 (버전 체인)
)

/**
 * v1 → v2: memory 테이블 추가. 기존 run·episode 는 건드리지 않는다.
 *
 * DDL 은 Room 이 기대하는 것과 **정확히** 같아야 한다(app/schemas/2.json 이 정답지).
 * 어긋나면 앱을 열자마자 IllegalStateException 으로 죽는다.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `memory` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`kind` TEXT NOT NULL, `text` TEXT NOT NULL, `steps` TEXT, " +
                "`precondition` TEXT, `observedTurns` INTEGER, " +
                "`pkg` TEXT, `keywords` TEXT, `scope` TEXT NOT NULL, " +
                "`state` TEXT NOT NULL, `score` INTEGER NOT NULL, `pinned` INTEGER NOT NULL, " +
                "`source` TEXT NOT NULL, `sourceRunId` TEXT, `sensitivity` TEXT NOT NULL, " +
                "`pkgVersion` INTEGER, `timeAdded` INTEGER NOT NULL, `lastAccessed` INTEGER, " +
                "`numRecalled` INTEGER NOT NULL, `invalidAt` INTEGER, `expiresAt` INTEGER, " +
                "`supersededBy` INTEGER)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_pkg_state_kind` ON `memory` (`pkg`, `state`, `kind`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_state_lastAccessed` ON `memory` (`state`, `lastAccessed`)")
    }
}

@Database(
    entities = [RunEntity::class, EpisodeEntity::class, MemoryEntity::class],
    version = 2,
    exportSchema = true,   // app/schemas/ — 마이그레이션 DDL 의 정답지
)
abstract class MemoryDb : RoomDatabase() {
    abstract fun dao(): MemoryDao

    companion object {
        @Volatile private var instance: MemoryDb? = null

        fun get(ctx: Context): MemoryDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                ctx.applicationContext, MemoryDb::class.java, "memory.db",
            ).addMigrations(MIGRATION_1_2).build().also { instance = it }
        }
    }
}
