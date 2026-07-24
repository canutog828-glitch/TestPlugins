dependencies {
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}

// Use an integer for version numbers
version = 1

cloudstream {
    description = "Plugin para assistir filmes, séries, animes e doramas"
    authors = listOf("lospobreflix")

    status = 1 // 0=Down, 1=Ok, 2=Slow, 3=Beta

    tvTypes = listOf("Movie", "TvSeries", "Anime")

    requiresResources = true
    language = "pt"

    iconUrl = "https://lospobreflix.lat/favicon.ico"
}

android {
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}
