package com.lospobreflix

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

class ExampleProvider : MainAPI() {
    override var mainUrl = "https://lospobreflix.lat"
    override var name = "LosPobreFlixx"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    override var lang = "pt"
    override val hasMainPage = true

    private val interceptor = CloudflareKiller()

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Home"
    )

    private fun cleanImageUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val secondHttpIndex = url.indexOf("http", 1)
        return if (secondHttpIndex > 0) url.substring(secondHttpIndex) else url
    }

    private fun detectType(url: String): TvType {
        return when {
            url.contains("/filme/") -> TvType.Movie
            url.contains("/serie/") || url.contains("/series/") -> TvType.TvSeries
            url.contains("/anime/") -> TvType.Anime
            url.contains("/dorama/") -> TvType.TvSeries
            else -> TvType.Movie
        }
    }

    private fun parseCardList(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()

        for (h3 in document.select("h3")) {
            val a = h3.selectFirst("a") ?: continue
            val title = a.text().takeIf { it.isNotBlank() } ?: h3.attr("title")
            val url = a.attr("href")
            if (url.isBlank()) continue

            val parent = h3.parents().firstOrNull { p -> p.selectFirst("img") != null }
            val img = parent?.selectFirst("img")
            val posterRaw = img?.attr("data-src")?.takeIf { it.isNotBlank() } ?: img?.attr("src")
            val poster = cleanImageUrl(posterRaw)

            val type = detectType(url)

            if (type == TvType.TvSeries || type == TvType.Anime) {
                results.add(newTvSeriesSearchResponse(title, url, type) {
                    this.posterUrl = poster
                })
            } else {
                results.add(newMovieSearchResponse(title, url, type) {
                    this.posterUrl = poster
                })
            }
        }

        return results
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val response = app.get(request.data, interceptor = interceptor)
        val homeItems = parseCardList(response.document)
        return newHomePageResponse(request.name, homeItems)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val response = app.get(
            "$mainUrl/pesquisar?s=$encodedQuery&type=&genre=&year=",
            interceptor = interceptor
        )
        return parseCardList(response.document)
    }

    override suspend fun load(url: String): LoadResponse? {
        val response = app.get(url, interceptor = interceptor)
        val document = response.document

        val title = document.selectFirst("h1.text-lead")?.text() ?: return null

        val bannerRaw = document.selectFirst("img[alt*='backdrop']")?.attr("data-src")
            ?: document.selectFirst("img[alt*='backdrop']")?.attr("src")
        val banner = cleanImageUrl(bannerRaw)

        val posterRaw = document.selectFirst("img[alt*='poster']")?.attr("data-src")
            ?: document.selectFirst("img[alt*='poster']")?.attr("src")
            ?: bannerRaw
        val poster = cleanImageUrl(posterRaw)

        val plot = document.selectFirst("div.text-slate-700 p, div.text-slate-200 p")?.text()
            ?: document.selectFirst(".sinopse-text, p")?.text()
            ?: "Sem sinopse disponível."

        val type = detectType(url)
        val isSeries = type == TvType.TvSeries || type == TvType.Anime ||
            document.select("a.seasonLoaderBtn").isNotEmpty()

        if (isSeries) {
            val episodes = mutableListOf<Episode>()
            val seasonBtns = document.select("a.seasonLoaderBtn")

            if (seasonBtns.isNotEmpty()) {
                for (btn in seasonBtns) {
                    val seasonNum = btn.attr("data-season").toIntOrNull() ?: 1
                    val seasonUrl = btn.attr("href")
                    if (seasonUrl.isBlank()) continue

                    val seasonResponse = app.get(seasonUrl, interceptor = interceptor)
                    val seasonDoc = seasonResponse.document

                    val epLinks = seasonDoc.select("a[href*='/episodio/'], a[href*='/episodios/'], .episode-card a, .episodios a")
                    for (epLink in epLinks) {
                        val epUrl = epLink.attr("href")
                        if (epUrl.isBlank()) continue

                        val epName = epLink.text().trim()
                        val epNum = epName.filter { it.isDigit() }.toIntOrNull() ?: 1

                        episodes.add(newEpisode(epUrl) {
                            this.name = epName
                            this.season = seasonNum
                            this.episode = epNum
                            this.posterUrl = poster
                        })
                    }
                }
            } else {
                val epLinks = document.select("a[href*='/episodio/'], a[href*='/episodios/'], .episode-card a, .episodios a")
                epLinks.forEachIndexed { index, epLink ->
                    val epUrl = epLink.attr("href")
                    if (epUrl.isBlank()) return@forEachIndexed

                    val epName = epLink.text().trim()
                    val epNum = epName.filter { it.isDigit() }.toIntOrNull() ?: (index + 1)

                    episodes.add(newEpisode(epUrl) {
                        this.name = epName
                        this.season = 1
                        this.episode = epNum
                        this.posterUrl = poster
                    })
                }
            }

            return newTvSeriesLoadResponse(title, url, type, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = banner
                this.plot = plot
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = banner
                this.plot = plot
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCdn: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data, interceptor = interceptor)
        val document = response.document

        val playerDiv = document.selectFirst("[data-apicontentid]") ?: return false
        val apiContentId = playerDiv.attr("data-apicontentid")
        if (apiContentId.isBlank()) return false

        val playerType = playerDiv.attr("data-playertype")
        val season = playerDiv.attr("data-season").toIntOrNull() ?: 1
        val episode = playerDiv.attr("data-episode").toIntOrNull() ?: 1

        val scriptText = document.select("script")
            .firstOrNull { it.data().contains("__PLAYER_APIS__") }
            ?.data()

        val apis = scriptText
            ?.substringAfter("__PLAYER_APIS__")
            ?.substringAfter("[")
            ?.substringBefore("]")
            ?.let { block ->
                // Ajustado o posicionamento do hífen dentro do colchete
                Regex("\"([a-zA-Z0-9.-]+)\"")
                    .findAll(block)
                    .map { match -> match.groupValues[1] }
                    .toList()
            }
            ?: listOf("warezcdn.lat", "superflixapi.pro")

        var foundAny = false

        for (api in apis) {
            val embedUrl = if (playerType == "episodio") {
                "https://$api/serie/$apiContentId/$season/$episode"
            } else {
                "https://$api/filme/$apiContentId"
            }

            Log.d("ExampleProvider", "Tentando: $embedUrl")
            val loaded = loadExtractor(embedUrl, data, subtitleCallback, callback)
            foundAny = foundAny || loaded
        }

        return foundAny
    }
}
