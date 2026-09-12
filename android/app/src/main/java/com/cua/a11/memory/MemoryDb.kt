package com.cua.a11.memory

import android.content.Context
import androidx.room.*

// ── 0단계: 구조화 로깅 ────────────────────────────────────────────────────
//  run_history.txt 는 사람이 읽는 평문이라 "턴 1의 액션이 주로 뭐냐" 같은 질문에 답할 수
//  없다. 같은 사건을 질의 가능한 형태로 한 벌 더 남긴다. 평문 로그는 그대로 둔다.
//
//  이 단계에서 memory 테이블은 만들지 않는다 — 아직 아무도 안 쓰고, 컬럼이 확정되지
//  않았다(설계 문서 부록 F의 N1~N6). Unit 3 에서 스키마 버전을 올리며 추가한다.
//  설계 근거 전체는 docs/reference/android_run-memory-2026-09-12.md 참조.

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

@Database(
    entities = [RunEntity::class, EpisodeEntity::class],
    version = 1,
    // Unit 3 에서 memory 테이블을 추가하며 버전을 올린다. 그때까지 스키마가 흔들리므로
    // 스키마 JSON 을 커밋하지 않는다(지금 켜면 churn 만 쌓인다). 안정되면 켤 것.
    exportSchema = false,
)
abstract class MemoryDb : RoomDatabase() {
    abstract fun dao(): MemoryDao

    companion object {
        @Volatile private var instance: MemoryDb? = null

        fun get(ctx: Context): MemoryDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                ctx.applicationContext, MemoryDb::class.java, "memory.db",
            ).build().also { instance = it }
        }
    }
}
