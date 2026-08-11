import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.cua.a11"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }
    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.cua.a11"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        val props = Properties()
        val f = rootProject.file("local.properties")
        if (f.exists()) props.load(FileInputStream(f))
        val key = props.getProperty("GEMINI_API_KEY") ?: ""
        buildConfigField("String", "GEMINI_API_KEY", "\"$key\"")

        // 모델·사고수준은 앱 드롭다운에서 매 실행 고른다. 여기 값은 드롭다운의 '첫 기본 선택'일 뿐이고,
        // 한 번 실행하면 그때 고른 값이 SharedPreferences 에 저장돼 그쪽이 이긴다.
        // ★ 둘 다 없으면 빈 문자열로 넘긴다 — 기본값은 CuClient(DEFAULT_MODEL / DEFAULT_THINKING)가
        //   유일한 출처다. 여기서 `?: "gemini-3.5-flash"` 로 채우면 기본값이 두 군데 살게 되고,
        //   BuildConfig 가 늘 값을 주므로 CuClient 쪽 상수가 죽은 값이 된다.
        val model = (props.getProperty("GEMINI_MODEL") ?: "").trim()
        val thinking = (props.getProperty("GEMINI_THINKING") ?: "").trim().lowercase()

        // 사고수준만 여기서 검증한다. 값이 4종으로 고정이라 목록을 적어도 안 낡기 때문이다.
        // 모델은 검증하지 않는다 — 후보 목록(CuClient.MODELS)을 여기 복사하면 새 모델이 나올 때마다
        // 두 곳을 고쳐야 하고, 그게 바로 위에서 피하려던 '기본값 두 군데' 문제와 같은 함정이다.
        // 모델 오타는 조용히 DEFAULT_MODEL 로 떨어진다(드롭다운이 실제 값을 정하므로 영향이 작다).
        require(thinking.isEmpty() || thinking in listOf("minimal", "low", "medium", "high")) {
            "local.properties: GEMINI_THINKING='$thinking' — minimal/low/medium/high 중 하나이거나 비어 있어야 합니다."
        }

        buildConfigField("String", "GEMINI_MODEL", "\"$model\"")
        buildConfigField("String", "GEMINI_THINKING", "\"$thinking\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}