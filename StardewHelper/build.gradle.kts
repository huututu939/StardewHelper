plugins {
    alias(libs.plugins.android.application) apply false
    // ⚠ com.android.test 与 com.android.application 同在 AGP 一个 jar 里。
    //   模块侧若带版本号去请求，会报「已在 classpath 上但版本未知」——
    //   所以必须和 application 一样在根脚本先 apply false，模块侧只写别名不写版本。
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.androidx.baselineprofile) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
